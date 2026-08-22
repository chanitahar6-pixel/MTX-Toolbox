package app.mtx.toolbox.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.core.Kv;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.fs.FileItem;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Everything known about one file, including the analyzed type and suggested tools. */
public class PropertiesActivity extends BaseActivity {

    private String path;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_properties));
        path = getIntent().getStringExtra(ToolRouter.EXTRA_PATH);
        if (path == null) { finish(); return; }
        titleView.setText(new File(path).getName());

        addToolbarAction(getString(R.string.hash), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PropertiesActivity.this, HashActivity.class);
                i.putExtra(ToolRouter.EXTRA_PATH, path);
                startActivity(i);
            }
        });
        load();
    }

    private void load() {
        final Handler main = new Handler(Looper.getMainLooper());
        setStatus(getString(R.string.op_scanning));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final FileItem item = FileItem.of(path);
                final String typeRaw = Native.isAvailable() ? Native.analyzeType(path) : null;
                final long[] usage = Native.isAvailable() ? Native.diskUsage(path) : null;
                main.post(new Runnable() {
                    @Override
                    public void run() { render(item, typeRaw, usage); }
                });
            }
        }, "mtx-props").start();
    }

    private void render(FileItem item, String typeRaw, long[] usage) {
        List<SimpleAdapter.Row> rows = new ArrayList<>();
        File f = new File(path);

        rows.add(new SimpleAdapter.Row("name", f.getName()));
        rows.add(new SimpleAdapter.Row("path", path));

        if (item == null) {
            rows.add(new SimpleAdapter.Row(getString(R.string.err_not_found), OpResult.safeLastError()));
            adapter.setRows(rows);
            return;
        }

        rows.add(new SimpleAdapter.Row("size", item.isDir
                ? "<dir>"
                : Fmt.bytes(item.size) + "   (" + item.size + " bytes)"));
        rows.add(new SimpleAdapter.Row("modified", Fmt.date(item.mtime)));
        rows.add(new SimpleAdapter.Row("permissions", Fmt.mode(item.mode)
                + (item.readable ? "   r" : "") + (item.writable ? "w" : "")));
        if (item.isLink) rows.add(new SimpleAdapter.Row("symlink", "yes"));

        if (typeRaw != null) {
            Kv kv = Kv.parse(typeRaw);
            rows.add(new SimpleAdapter.Row("type", kv.get("description")
                    + "\nkind: " + kv.get("kind")
                    + "   mime: " + kv.get("mime")
                    + "\nencoding: " + kv.get("encoding")
                    + "\nmagic: " + kv.get("magic")));
            List<String> tools = kv.all("tool");
            if (!tools.isEmpty())
                rows.add(new SimpleAdapter.Row(getString(R.string.choose_tool),
                        ApkInfoActivity.join(tools, "   \u2022   ")));
        }

        if (usage != null && usage.length >= 3) {
            rows.add(new SimpleAdapter.Row("volume",
                    getString(R.string.storage_total) + " " + Fmt.bytes(usage[0])
                            + "   " + getString(R.string.storage_free) + " " + Fmt.bytes(usage[2])));
        }

        adapter.setRows(rows);
        setStatus(path);
    }
}
