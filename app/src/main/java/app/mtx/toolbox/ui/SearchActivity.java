package app.mtx.toolbox.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.core.RowSink;
import app.mtx.toolbox.fs.FileItem;
import app.mtx.toolbox.ops.Operation;
import app.mtx.toolbox.ops.OperationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Streaming search: results appear while the engine is still scanning, and stop cancels it. */
public class SearchActivity extends BaseActivity {

    private static final int MAX_RESULTS = 5000;

    private String root;
    private EditText nameInput;
    private EditText contentInput;
    private CheckBox caseBox;
    private CheckBox recursiveBox;
    private CheckBox archivesBox;
    private CheckBox hiddenBox;
    private Operation running;
    private boolean flushScheduled;

    private final List<SimpleAdapter.Row> pending =
            Collections.synchronizedList(new ArrayList<SimpleAdapter.Row>());
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_search));
        root = getIntent().getStringExtra(ToolRouter.EXTRA_PATH);
        if (root == null) root = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        nameInput = new EditText(this);
        nameInput.setHint(R.string.search_name_hint);
        nameInput.setSingleLine(true);
        box.addView(nameInput);

        contentInput = new EditText(this);
        contentInput.setHint(R.string.search_content_hint);
        contentInput.setSingleLine(true);
        box.addView(contentInput);

        recursiveBox = check(getString(R.string.search_recursive), true);
        caseBox = check(getString(R.string.search_case), false);
        archivesBox = check(getString(R.string.search_archives), false);
        hiddenBox = check(getString(R.string.show_hidden), false);

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        options.addView(recursiveBox);
        options.addView(caseBox);
        options.addView(archivesBox);
        options.addView(hiddenBox);
        HorizontalScrollView optionScroll = new HorizontalScrollView(this);
        optionScroll.addView(options);
        box.addView(optionScroll);
        addHeaderView(box);

        addToolbarAction(getString(R.string.start_search), new View.OnClickListener() {
            @Override
            public void onClick(View v) { start(); }
        });
        addToolbarAction(getString(R.string.stop), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running != null) running.cancel();
            }
        });

        setStatus(root);
    }

    private CheckBox check(String label, boolean value) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(value);
        return cb;
    }

    private void start() {
        final String name = nameInput.getText().toString().trim();
        final String content = contentInput.getText().toString();
        if (name.isEmpty() && content.isEmpty()) {
            toast(getString(R.string.err_range));
            return;
        }
        adapter.clear();
        pending.clear();

        int flags = 0;
        if (recursiveBox.isChecked()) flags |= Native.SEARCH_RECURSIVE;
        if (caseBox.isChecked()) flags |= Native.SEARCH_CASE;
        if (!content.isEmpty()) flags |= Native.SEARCH_CONTENT;
        if (archivesBox.isChecked()) flags |= Native.SEARCH_ARCHIVES;
        if (hiddenBox.isChecked()) flags |= Native.SEARCH_HIDDEN;
        final int finalFlags = flags;

        setStatus(getString(R.string.op_searching) + ": " + root);

        running = OperationManager.get(this).submit("search",
                getString(R.string.op_searching) + " " + (name.isEmpty() ? content : name),
                new Operation.Body() {
                    @Override
                    public int execute(Operation op) {
                        return Native.search(op.jobId(), root, name, content, finalFlags,
                                MAX_RESULTS, new RowSink() {
                                    @Override
                                    public void onRow(String path, String preview, long line, long size) {
                                        String sub = (line > 0 ? "line " + line + "   " : "")
                                                + (size >= 0 ? Fmt.bytes(size) : "")
                                                + (preview == null || preview.isEmpty() ? "" : "\n" + preview);
                                        pending.add(row(path, sub));
                                        scheduleFlush();
                                    }
                                }, op);
                    }
                },
                new OperationManager.Completion() {
                    @Override
                    public void onFinished(Operation op) {
                        flushNow();
                        running = null;
                        String state;
                        if (op.state() == Operation.State.CANCELLED) state = getString(R.string.err_cancelled);
                        else if (op.state() == Operation.State.FAILED)
                            state = OpResult.message(SearchActivity.this, op.resultCode());
                        else state = getString(R.string.err_ok);
                        setStatus(adapter.size() + " " + getString(R.string.search) + "   " + state
                                + "   " + Fmt.duration(op.elapsedMs()));
                    }
                });
    }

    private SimpleAdapter.Row row(final String path, String sub) {
        int bang = path.indexOf("!/");
        String real = bang < 0 ? path : path.substring(0, bang);
        String title = new File(real).getName();
        if (bang >= 0) title = title + "  ::  " + path.substring(bang + 2);
        return new SimpleAdapter.Row(title, sub + "\n" + path, path, new View.OnClickListener() {
            @Override
            public void onClick(View v) { openResult(path); }
        });
    }

    private void openResult(String path) {
        int bang = path.indexOf("!/");
        String real = bang < 0 ? path : path.substring(0, bang);
        FileItem item = FileItem.of(real);
        if (item == null) {
            toast(getString(R.string.err_not_found));
            return;
        }
        if (item.isDir) {
            toast(real);
            return;
        }
        ToolRouter.open(this, item);
    }

    private void scheduleFlush() {
        if (flushScheduled) return;
        flushScheduled = true;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                flushScheduled = false;
                flushNow();
            }
        }, 200);
    }

    private void flushNow() {
        final List<SimpleAdapter.Row> batch;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                for (SimpleAdapter.Row r : batch) adapter.add(r);
                setStatus(adapter.size() + " " + getString(R.string.search));
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (running != null) running.cancel();
        super.onDestroy();
    }
}
