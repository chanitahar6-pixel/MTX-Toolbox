package app.mtx.toolbox.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Kv;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.core.RowSink;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Native library analyzer: headers, sections, symbols, imports/exports, strings. */
public class ElfActivity extends BaseActivity {

    private static final int MAX_SYMBOLS = 4000;

    private String path;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_elf));
        path = getIntent().getStringExtra(ToolRouter.EXTRA_PATH);
        if (path == null) { finish(); return; }
        titleView.setText(new File(path).getName());

        addToolbarAction("strings", new View.OnClickListener() {
            @Override
            public void onClick(View v) { extractStrings(); }
        });
        load();
    }

    private void load() {
        setStatus(getString(R.string.op_scanning));
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String raw = Native.isAvailable() ? Native.elfInfo(path, MAX_SYMBOLS) : null;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (raw == null) {
                            setStatus(getString(R.string.err_corrupt));
                            showError(getString(R.string.err_corrupt), OpResult.safeLastError());
                            return;
                        }
                        render(Kv.parse(raw));
                    }
                });
            }
        }, "mtx-elf").start();
    }

    private void render(Kv kv) {
        List<SimpleAdapter.Row> rows = new ArrayList<>();
        rows.add(new SimpleAdapter.Row(kv.get("type"),
                kv.get("bits") + "-bit   " + kv.get("machine") + "   ABI " + kv.get("abi")
                        + "\nentry 0x" + Long.toHexString(kv.getLong("entry", 0))
                        + (kv.getBool("stripped", true) ? "   stripped" : "   has .symtab")));

        if (!kv.get("soname").isEmpty())
            rows.add(new SimpleAdapter.Row("SONAME", kv.get("soname")));
        if (!kv.get("interp").isEmpty())
            rows.add(new SimpleAdapter.Row("interpreter", kv.get("interp")));

        List<String> needed = kv.all("needed");
        rows.add(new SimpleAdapter.Row("DT_NEEDED  (" + needed.size() + ")",
                needed.isEmpty() ? "\u2014" : ApkInfoActivity.join(needed, "\n")));

        List<String> sections = kv.all("section");
        rows.add(new SimpleAdapter.Row("sections  (" + sections.size() + ")", null));
        for (String s : sections) {
            String[] f = Kv.fields(s);
            if (f.length < 5) continue;
            rows.add(new SimpleAdapter.Row(f[0].isEmpty() ? "(unnamed)" : f[0],
                    f[1] + "   addr 0x" + Long.toHexString(safeLong(f[2]))
                            + "   off 0x" + Long.toHexString(safeLong(f[3]))
                            + "   size " + safeLong(f[4])));
        }

        List<String> symbols = kv.all("symbol");
        int imports = 0, exports = 0;
        for (String s : symbols) {
            String[] f = Kv.fields(s);
            if (f.length < 6) continue;
            if ("import".equals(f[5])) imports++;
            else exports++;
        }
        rows.add(new SimpleAdapter.Row("symbols  (" + symbols.size() + ")",
                imports + " imports   " + exports + " exports"));
        int shown = 0;
        for (String s : symbols) {
            if (shown++ > 600) break;   // keep the list responsive; full data is in the engine
            String[] f = Kv.fields(s);
            if (f.length < 6) continue;
            rows.add(new SimpleAdapter.Row(f[0],
                    f[5] + "   " + f[1] + "   " + f[2] + "   size " + f[4]));
        }

        List<String> warnings = kv.all("warning");
        if (!warnings.isEmpty())
            rows.add(new SimpleAdapter.Row(getString(R.string.apk_warnings),
                    ApkInfoActivity.join(warnings, "\n")));

        adapter.setRows(rows);
        setStatus(path);
    }

    private long safeLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private void extractStrings() {
        final List<SimpleAdapter.Row> rows = new ArrayList<>();
        final Handler main = new Handler(Looper.getMainLooper());
        setStatus(getString(R.string.op_scanning));
        new Thread(new Runnable() {
            @Override
            public void run() {
                long job = Native.newJob();
                try {
                    Native.extractStrings(job, path, 5, 3000, new RowSink() {
                        @Override
                        public void onRow(String a, String b, long n1, long n2) {
                            rows.add(new SimpleAdapter.Row(a, "offset 0x" + Long.toHexString(n1)
                                    + "   len " + n2));
                        }
                    });
                } finally {
                    Native.releaseJob(job);
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        adapter.setRows(rows);
                        setStatus(rows.size() + " strings");
                    }
                });
            }
        }, "mtx-strings").start();
    }
}
