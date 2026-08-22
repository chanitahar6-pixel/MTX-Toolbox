package app.mtx.toolbox.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.ops.Operation;
import app.mtx.toolbox.ops.OperationManager;

import java.util.ArrayList;
import java.util.List;

/** Live view of every operation: progress, speed, ETA, cancel, retry and its log. */
public class OperationsActivity extends BaseActivity implements OperationManager.Listener {

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            if (OperationManager.get(OperationsActivity.this).activeCount() > 0)
                ticker.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_operations));

        addToolbarAction(getString(R.string.cancel), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OperationManager.get(OperationsActivity.this).cancelAll();
                render();
            }
        });
        addToolbarAction(getString(R.string.clear_finished), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                OperationManager.get(OperationsActivity.this).clearFinished();
                render();
            }
        });
        OperationManager.get(this).addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        ticker.post(tick);
    }

    @Override
    protected void onPause() {
        ticker.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        OperationManager.get(this).removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onOperationsChanged() { render(); }

    private void render() {
        OperationManager mgr = OperationManager.get(this);
        List<Operation> ops = mgr.operations();
        List<SimpleAdapter.Row> rows = new ArrayList<>();

        for (final Operation op : ops) {
            StringBuilder sub = new StringBuilder();
            sub.append(op.state().name());
            if (!op.isFinished()) {
                sub.append('\n').append(Fmt.bar(op.done(), op.total(), 20))
                        .append(' ').append(Fmt.percent(op.done(), op.total()));
                sub.append('\n').append(Fmt.bytes(op.done()));
                if (op.total() > 0) sub.append(" / ").append(Fmt.bytes(op.total()));
                sub.append("   ").append(Fmt.speed(op.speed()));
                sub.append("   ETA ").append(Fmt.eta(op.done(), op.total(), op.speed()));
                if (op.filesTotal() > 0)
                    sub.append("\n").append(op.filesDone()).append(" / ").append(op.filesTotal())
                            .append(" files");
                if (!op.current().isEmpty()) sub.append('\n').append(op.current());
            } else {
                sub.append("   ").append(Fmt.duration(op.elapsedMs()));
                if (op.error() != null) sub.append('\n').append(op.error());
            }

            rows.add(new SimpleAdapter.Row(op.title, sub.toString(), op, new View.OnClickListener() {
                @Override
                public void onClick(View v) { showDetails(op); }
            }));
        }

        adapter.setRows(rows);
        setStatus(ops.isEmpty()
                ? getString(R.string.no_operations)
                : mgr.activeCount() + " / " + ops.size());
    }

    private void showDetails(final Operation op) {
        StringBuilder log = new StringBuilder();
        for (String line : op.log()) log.append(line).append('\n');
        if (op.error() != null) log.append("\n").append(op.error());
        if (op.resultCode() != OpResult.OK)
            log.append("\n").append(OpResult.message(this, op.resultCode()));

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(op.title)
                .setMessage(log.toString())
                .setNegativeButton(R.string.close, null);

        if (!op.isFinished()) {
            b.setPositiveButton(R.string.cancel_operation, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int w) {
                    op.cancel();
                    render();
                }
            });
        } else {
            b.setPositiveButton(R.string.retry, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface d, int w) {
                    OperationManager.get(OperationsActivity.this).retry(op);
                    render();
                    ticker.post(tick);
                }
            });
        }
        b.show();
    }
}
