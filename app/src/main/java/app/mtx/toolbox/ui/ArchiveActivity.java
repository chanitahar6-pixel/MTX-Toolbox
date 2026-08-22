package app.mtx.toolbox.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.core.Kv;
import app.mtx.toolbox.core.MtxLog;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.core.Workspace;
import app.mtx.toolbox.fs.FileOps;
import app.mtx.toolbox.ops.Operation;
import app.mtx.toolbox.ops.OperationManager;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browses an archive as if it were a folder: only the central directory is read,
 * and entries are inflated on demand. Extraction is traversal-safe in the engine.
 */
public class ArchiveActivity extends BaseActivity {

    private static final int PREVIEW_LIMIT = 4 * 1024 * 1024;

    private static final class Entry {
        String name;
        boolean isDir;
        long size;
        long compressed;
        long mtime;
        int method;
        boolean encrypted;
    }

    private String archive;
    private final List<Entry> entries = new ArrayList<>();
    private String prefix = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_archive));
        archive = getIntent().getStringExtra(ToolRouter.EXTRA_PATH);
        if (archive == null) { finish(); return; }
        titleView.setText(new File(archive).getName());

        addToolbarAction(getString(R.string.extract), new View.OnClickListener() {
            @Override
            public void onClick(View v) { extractAll(); }
        });
        addToolbarAction(getString(R.string.op_testing_archive), new View.OnClickListener() {
            @Override
            public void onClick(View v) { testArchive(); }
        });

        load();
    }

    private void load() {
        setStatus(getString(R.string.op_scanning));
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String[] rows = Native.isAvailable() ? Native.zipList(archive) : null;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (rows == null) {
                            setStatus(getString(R.string.err_corrupt));
                            showError(getString(R.string.err_corrupt), OpResult.safeLastError());
                            return;
                        }
                        entries.clear();
                        for (String row : rows) {
                            String[] f = Kv.fields(row);
                            if (f.length < 8) continue;
                            Entry e = new Entry();
                            e.name = f[0];
                            e.isDir = "1".equals(f[1]);
                            e.size = parse(f[2]);
                            e.compressed = parse(f[3]);
                            e.mtime = parse(f[4]);
                            e.method = (int) parse(f[5]);
                            e.encrypted = "1".equals(f[6]);
                            entries.add(e);
                        }
                        render();
                    }
                });
            }
        }, "mtx-zip").start();
    }

    private long parse(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }

    /** Groups the flat entry list into the virtual folder currently being viewed. */
    private void render() {
        List<SimpleAdapter.Row> rows = new ArrayList<>();
        Map<String, long[]> folders = new LinkedHashMap<>();   // name -> {count, bytes}
        List<Entry> files = new ArrayList<>();

        for (Entry e : entries) {
            if (!e.name.startsWith(prefix)) continue;
            String rest = e.name.substring(prefix.length());
            if (rest.isEmpty()) continue;
            int slash = rest.indexOf('/');
            if (slash >= 0) {
                String folder = rest.substring(0, slash);
                long[] stat = folders.get(folder);
                if (stat == null) folders.put(folder, stat = new long[2]);
                if (!e.isDir) { stat[0]++; stat[1] += Math.max(0, e.size); }
            } else if (!e.isDir) {
                files.add(e);
            }
        }

        if (!prefix.isEmpty()) {
            rows.add(new SimpleAdapter.Row("..", prefix, null, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String p = prefix.substring(0, prefix.length() - 1);
                    int slash = p.lastIndexOf('/');
                    prefix = slash < 0 ? "" : p.substring(0, slash + 1);
                    render();
                }
            }));
        }

        for (Map.Entry<String, long[]> f : folders.entrySet()) {
            final String folder = f.getKey();
            rows.add(new SimpleAdapter.Row(folder + "/",
                    f.getValue()[0] + " files   " + Fmt.bytes(f.getValue()[1]),
                    null, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefix = prefix + folder + "/";
                    render();
                }
            }));
        }

        for (final Entry e : files) {
            String sub = Fmt.bytes(e.size) + "   \u2192 " + Fmt.bytes(e.compressed)
                    + "   " + (e.method == 0 ? "stored" : e.method == 8 ? "deflate" : "method " + e.method)
                    + "   " + Fmt.date(e.mtime)
                    + (e.encrypted ? "   \uD83D\uDD12" : "");
            rows.add(new SimpleAdapter.Row(e.name.substring(prefix.length()), sub, null,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) { entryActions(e); }
                    }));
        }

        adapter.setRows(rows);
        setStatus(entries.size() + " entries   \u2022   " + (prefix.isEmpty() ? "/" : prefix));
    }

    private void entryActions(final Entry e) {
        final String[] labels = {
                getString(R.string.extract),
                getString(R.string.open_as_text),
                getString(R.string.copy)};
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) extractEntry(e);
                        else if (which == 1) previewEntry(e);
                        else extractEntry(e);
                    }
                })
                .show();
    }

    private File outputDir() {
        return Workspace.uniqueFile(Workspace.dir(this, Workspace.EXTRACTED),
                new File(archive).getName() + "-contents");
    }

    private void extractAll() {
        final File out = outputDir();
        FileOps.extractArchive(this, archive, out.getAbsolutePath(), completion());
        toast(getString(R.string.operation_started, getString(R.string.op_extracting)));
    }

    private void extractEntry(Entry e) {
        File out = new File(Workspace.dir(this, Workspace.EXTRACTED),
                new File(archive).getName() + "-contents");
        FileOps.extractEntry(this, archive, e.name, out.getAbsolutePath(), completion());
    }

    private OperationManager.Completion completion() {
        return new OperationManager.Completion() {
            @Override
            public void onFinished(Operation op) {
                if (op.state() == Operation.State.DONE) {
                    toast(getString(R.string.extracted_to, String.valueOf(op.output())));
                } else if (op.state() == Operation.State.FAILED) {
                    showError(getString(R.string.operation_failed, op.title),
                            OpResult.message(ArchiveActivity.this, op.resultCode())
                                    + "\n\n" + String.valueOf(op.error()));
                }
            }
        };
    }

    /** Inflates a single entry to MTX/Temp and opens it read-only in the text editor. */
    private void previewEntry(final Entry e) {
        if (e.size > PREVIEW_LIMIT) {
            toast(getString(R.string.err_range));
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                File temp = null;
                try {
                    byte[] data = Native.zipRead(archive, e.name, PREVIEW_LIMIT);
                    if (data == null) {
                        error = OpResult.safeLastError();
                    } else {
                        temp = Workspace.uniqueFile(Workspace.dir(ArchiveActivity.this, Workspace.TEMP),
                                new File(e.name).getName());
                        FileOutputStream out = new FileOutputStream(temp);
                        try {
                            out.write(data);
                        } finally {
                            out.close();
                        }
                    }
                } catch (Throwable t) {
                    error = String.valueOf(t.getMessage());
                    MtxLog.e(ArchiveActivity.this, "zip", "preview failed for " + e.name, t);
                }
                final String err = error;
                final File file = temp;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (err != null) {
                            showError(getString(R.string.err_corrupt), err);
                            return;
                        }
                        Intent i = new Intent(ArchiveActivity.this, TextEditorActivity.class);
                        i.putExtra(ToolRouter.EXTRA_PATH, file.getAbsolutePath());
                        i.putExtra(ToolRouter.EXTRA_READONLY, true);
                        startActivity(i);
                    }
                });
            }
        }, "mtx-zip-read").start();
    }

    private void testArchive() {
        FileOps.testArchive(this, archive, new OperationManager.Completion() {
            @Override
            public void onFinished(Operation op) {
                if (op.state() != Operation.State.DONE) {
                    showError(getString(R.string.operation_failed, op.title),
                            OpResult.message(ArchiveActivity.this, op.resultCode())
                                    + "\n\n" + String.valueOf(op.error()));
                    return;
                }
                Kv kv = Kv.parse(String.valueOf(op.output()));
                long bad = kv.getLong("badEntries", 0);
                showText(getString(R.string.op_testing_archive), bad == 0
                        ? getString(R.string.err_ok)
                        : bad + " damaged entries\nfirst: " + kv.get("firstBad"));
            }
        });
        toast(getString(R.string.operation_started, getString(R.string.op_testing_archive)));
    }
}
