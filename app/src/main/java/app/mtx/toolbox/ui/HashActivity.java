package app.mtx.toolbox.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.HashEngine;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.ops.Operation;
import app.mtx.toolbox.ops.OperationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Streaming hashes: MD5, SHA-1, SHA-224, SHA-256 native; SHA-384/512 via the platform. */
public class HashActivity extends BaseActivity {

    private String path;
    private final Map<String, String> results = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_hash));
        path = getIntent().getStringExtra(ToolRouter.EXTRA_PATH);
        if (path == null) { finish(); return; }
        titleView.setText(new File(path).getName());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        for (final String algo : HashEngine.ALGORITHMS) {
            Button b = new Button(this, null, android.R.attr.buttonBarButtonStyle);
            b.setText(algo);
            b.setAllCaps(false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { compute(algo); }
            });
            box.addView(b);
        }
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.addView(box);
        addHeaderView(scroll);

        render();
        compute("SHA-256");
    }

    private void compute(final String algo) {
        setStatus(getString(R.string.op_hashing) + ": " + algo);
        OperationManager.get(this).submit("hash", getString(R.string.op_hashing) + " " + algo,
                new Operation.Body() {
                    @Override
                    public int execute(Operation op) throws Exception {
                        String hex = HashEngine.hash(op.jobId(), path, algo, op);
                        op.setOutput(hex);
                        return OpResult.OK;
                    }
                },
                new OperationManager.Completion() {
                    @Override
                    public void onFinished(Operation op) {
                        if (op.state() == Operation.State.DONE) {
                            results.put(algo, String.valueOf(op.output()));
                            render();
                            setStatus(path);
                        } else {
                            setStatus(OpResult.message(HashActivity.this, op.resultCode()));
                            if (op.state() == Operation.State.FAILED)
                                showError(getString(R.string.operation_failed, algo),
                                        String.valueOf(op.error()));
                        }
                    }
                });
    }

    private void render() {
        List<SimpleAdapter.Row> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : results.entrySet()) {
            final String value = e.getValue();
            rows.add(new SimpleAdapter.Row(e.getKey(), value, null, new View.OnClickListener() {
                @Override
                public void onClick(View v) { copy(value); }
            }));
        }
        if (rows.isEmpty()) rows.add(new SimpleAdapter.Row(getString(R.string.op_hashing), path));
        adapter.setRows(rows);
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("hash", text));
            toast(getString(R.string.copied_to_clipboard));
        }
    }
}
