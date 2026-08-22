package app.mtx.toolbox.ui;

import android.os.Bundle;
import android.view.View;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.core.HashEngine;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.fs.FileItem;
import app.mtx.toolbox.ops.Operation;
import app.mtx.toolbox.ops.OperationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage analyzer: volume usage, biggest files and folders, type breakdown and
 * true duplicate detection (same size first, then SHA-256 to confirm).
 */
public class StorageAnalyzerActivity extends BaseActivity {

    private static final int TOP_N = 40;
    private static final long DUP_MIN_SIZE = 512 * 1024;
    private static final int DUP_HASH_BUDGET = 400;

    private String root;
    private Operation running;

    private static final class Node {
        final String path;
        final long size;
        Node(String path, long size) { this.path = path; this.size = size; }
    }

    private static final class Result {
        long totalBytes;
        long fileCount;
        long dirCount;
        final List<Node> files = new ArrayList<>();
        final List<Node> folders = new ArrayList<>();
        final Map<String, long[]> types = new HashMap<>();
        final Map<String, List<String>> duplicates = new LinkedHashMap<>();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_storage));
        root = getIntent().getStringExtra(ToolRouter.EXTRA_PATH);
        if (root == null) root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();

        addToolbarAction(getString(R.string.analyze), new View.OnClickListener() {
            @Override
            public void onClick(View v) { analyze(); }
        });
        addToolbarAction(getString(R.string.stop), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running != null) running.cancel();
            }
        });

        showVolume();
        analyze();
    }

    private void showVolume() {
        List<SimpleAdapter.Row> rows = new ArrayList<>();
        long[] usage = Native.isAvailable() ? Native.diskUsage(root) : null;
        if (usage != null && usage.length >= 3) {
            long used = usage[0] - usage[1];
            rows.add(new SimpleAdapter.Row(root,
                    getString(R.string.storage_total) + " " + Fmt.bytes(usage[0])
                            + "\n" + getString(R.string.storage_used) + " " + Fmt.bytes(used)
                            + "   " + Fmt.percent(used, usage[0])
                            + "\n" + getString(R.string.storage_free) + " " + Fmt.bytes(usage[2])
                            + "\n" + Fmt.bar(used, usage[0], 24)));
        } else {
            rows.add(new SimpleAdapter.Row(root, null));
        }
        adapter.setRows(rows);
    }

    private void analyze() {
        setStatus(getString(R.string.op_scanning) + ": " + root);
        running = OperationManager.get(this).submit("analyze",
                getString(R.string.title_storage) + " " + root,
                new Operation.Body() {
                    @Override
                    public int execute(Operation op) {
                        Result result = new Result();
                        walk(op, root, result, 0);
                        if (op.isCancelRequested()) return OpResult.E_CANCELLED;
                        findDuplicates(op, result);
                        op.setOutput(result);
                        return OpResult.OK;
                    }
                },
                new OperationManager.Completion() {
                    @Override
                    public void onFinished(Operation op) {
                        running = null;
                        if (op.output() instanceof Result) render((Result) op.output(), op);
                        else setStatus(OpResult.message(StorageAnalyzerActivity.this, op.resultCode()));
                    }
                });
    }

    /** Directory walk driven by the native lister: bounded depth, cancellable, never fatal. */
    private long walk(Operation op, String dir, Result result, int depth) {
        if (op.isCancelRequested() || depth > 48) return 0;
        String[] rows = Native.listDir(dir);
        if (rows == null) return 0;

        long subtotal = 0;
        result.dirCount++;
        for (String raw : rows) {
            if (op.isCancelRequested()) break;
            FileItem item = FileItem.parse(dir, raw);
            if (item == null) continue;
            if (item.isDir) {
                subtotal += walk(op, item.path, result, depth + 1);
                continue;
            }
            long size = Math.max(0, item.size);
            subtotal += size;
            result.fileCount++;
            result.totalBytes += size;
            result.files.add(new Node(item.path, size));

            String ext = item.extension().isEmpty() ? "(none)" : item.extension();
            long[] stat = result.types.get(ext);
            if (stat == null) {
                stat = new long[2];
                result.types.put(ext, stat);
            }
            stat[0]++;
            stat[1] += size;

            if (result.files.size() > 20000) trim(result.files);
            op.onProgress(item.path, result.totalBytes, -1, 0, result.fileCount, -1);
        }
        result.folders.add(new Node(dir, subtotal));
        if (result.folders.size() > 20000) trim(result.folders);
        return subtotal;
    }

    private void trim(List<Node> list) {
        Collections.sort(list, new Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) { return Long.compare(b.size, a.size); }
        });
        while (list.size() > 2000) list.remove(list.size() - 1);
    }

    /** Same size is only a hint: SHA-256 decides whether files are really identical. */
    private void findDuplicates(Operation op, Result result) {
        Map<Long, List<String>> bySize = new HashMap<>();
        for (Node n : result.files) {
            if (n.size < DUP_MIN_SIZE) continue;
            List<String> list = bySize.get(n.size);
            if (list == null) {
                list = new ArrayList<>();
                bySize.put(n.size, list);
            }
            list.add(n.path);
        }
        int hashed = 0;
        for (Map.Entry<Long, List<String>> entry : bySize.entrySet()) {
            if (op.isCancelRequested()) return;
            if (entry.getValue().size() < 2) continue;
            for (String path : entry.getValue()) {
                if (op.isCancelRequested() || hashed++ > DUP_HASH_BUDGET) return;
                String digest;
                try {
                    digest = HashEngine.hash(op.jobId(), path, "SHA-256", null);
                } catch (Exception ex) {
                    continue;   // unreadable file: skip it, never fail the whole scan
                }
                List<String> group = result.duplicates.get(digest);
                if (group == null) {
                    group = new ArrayList<>();
                    result.duplicates.put(digest, group);
                }
                group.add(path);
            }
        }
    }

    private void render(Result result, Operation op) {
        List<SimpleAdapter.Row> rows = new ArrayList<>();
        long[] usage = Native.isAvailable() ? Native.diskUsage(root) : null;
        if (usage != null && usage.length >= 3) {
            long used = usage[0] - usage[1];
            rows.add(new SimpleAdapter.Row(root,
                    getString(R.string.storage_total) + " " + Fmt.bytes(usage[0])
                            + "   " + getString(R.string.storage_free) + " " + Fmt.bytes(usage[2])
                            + "\n" + Fmt.bar(used, usage[0], 24) + " " + Fmt.percent(used, usage[0])));
        }
        rows.add(new SimpleAdapter.Row(getString(R.string.analyze),
                Fmt.bytes(result.totalBytes) + "   " + result.fileCount + " files   "
                        + result.dirCount + " folders   " + Fmt.duration(op.elapsedMs())));

        trim(result.files);
        trim(result.folders);

        rows.add(new SimpleAdapter.Row(getString(R.string.biggest_files), null));
        for (int i = 0; i < Math.min(TOP_N, result.files.size()); i++) {
            final Node n = result.files.get(i);
            rows.add(new SimpleAdapter.Row(Fmt.bytes(n.size), n.path, null, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FileItem item = FileItem.of(n.path);
                    if (item != null) ToolRouter.open(StorageAnalyzerActivity.this, item);
                }
            }));
        }

        rows.add(new SimpleAdapter.Row(getString(R.string.biggest_folders), null));
        for (int i = 0; i < Math.min(TOP_N, result.folders.size()); i++) {
            Node n = result.folders.get(i);
            rows.add(new SimpleAdapter.Row(Fmt.bytes(n.size), n.path));
        }

        rows.add(new SimpleAdapter.Row(getString(R.string.file_types), null));
        List<Map.Entry<String, long[]>> types = new ArrayList<>(result.types.entrySet());
        Collections.sort(types, new Comparator<Map.Entry<String, long[]>>() {
            @Override
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[1], a.getValue()[1]);
            }
        });
        for (int i = 0; i < Math.min(25, types.size()); i++) {
            Map.Entry<String, long[]> e = types.get(i);
            rows.add(new SimpleAdapter.Row(e.getKey(),
                    Fmt.bytes(e.getValue()[1]) + "   " + e.getValue()[0] + " files"));
        }

        int groups = 0;
        List<SimpleAdapter.Row> dupRows = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : result.duplicates.entrySet()) {
            if (e.getValue().size() < 2) continue;
            groups++;
            StringBuilder sb = new StringBuilder();
            for (String p : e.getValue()) sb.append(p).append('\n');
            String shortDigest = e.getKey().length() > 16 ? e.getKey().substring(0, 16) : e.getKey();
            dupRows.add(new SimpleAdapter.Row(shortDigest + "...  x" + e.getValue().size(),
                    sb.toString().trim()));
        }
        rows.add(new SimpleAdapter.Row(getString(R.string.duplicates),
                groups == 0 ? "-" : groups + " groups"));
        rows.addAll(dupRows);

        adapter.setRows(rows);
        setStatus(Fmt.bytes(result.totalBytes) + "   " + result.fileCount + " files");
    }

    @Override
    protected void onDestroy() {
        if (running != null) running.cancel();
        super.onDestroy();
    }
}
