package app.mtx.toolbox.ui;

import android.os.Bundle;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.core.HashEngine;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.fs.FileOps;
import app.mtx.toolbox.ops.Operation;
import app.mtx.toolbox.ops.OperationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Byte-exact comparison of two files plus a SHA-256 cross-check. */
public class CompareActivity extends BaseActivity {

    private String a;
    private String b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_compare));
        a = getIntent().getStringExtra(ToolRouter.EXTRA_PATH);
        b = getIntent().getStringExtra(ToolRouter.EXTRA_ENTRY);
        if (a == null || b == null) { finish(); return; }

        List<SimpleAdapter.Row> rows = new ArrayList<>();
        rows.add(new SimpleAdapter.Row(new File(a).getName(),
                Fmt.bytes(new File(a).length()) + "\n" + a));
        rows.add(new SimpleAdapter.Row(new File(b).getName(),
                Fmt.bytes(new File(b).length()) + "\n" + b));
        adapter.setRows(rows);

        run();
    }

    private void run() {
        setStatus(getString(R.string.op_comparing));
        FileOps.compare(this, a, b, new OperationManager.Completion() {
            @Override
            public void onFinished(Operation op) {
                if (op.state() != Operation.State.DONE) {
                    setStatus(OpResult.message(CompareActivity.this, op.resultCode()));
                    return;
                }
                long[] result = (long[]) op.output();
                boolean identical = result != null && result.length > 1 && result[1] == 1;
                long firstDiff = result == null ? -1 : result[0];
                SimpleAdapter.Row row = new SimpleAdapter.Row(
                        identical ? getString(R.string.err_ok) : getString(R.string.compare),
                        identical
                                ? "Files are byte-for-byte identical"
                                : "First difference at offset " + firstDiff
                                + "  (0x" + Long.toHexString(Math.max(0, firstDiff)) + ")");
                adapter.add(row);
                setStatus(Fmt.duration(op.elapsedMs()));
                hashBoth();
            }
        });
    }

    private void hashBoth() {
        hash(a);
        hash(b);
    }

    private void hash(final String path) {
        OperationManager.get(this).submit("hash", "SHA-256 " + new File(path).getName(),
                new Operation.Body() {
                    @Override
                    public int execute(Operation op) throws Exception {
                        op.setOutput(HashEngine.hash(op.jobId(), path, "SHA-256", op));
                        return OpResult.OK;
                    }
                },
                new OperationManager.Completion() {
                    @Override
                    public void onFinished(Operation op) {
                        if (op.state() == Operation.State.DONE)
                            adapter.add(new SimpleAdapter.Row("SHA-256 " + new File(path).getName(),
                                    String.valueOf(op.output())));
                    }
                });
    }
}
