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
import app.mtx.toolbox.core.Workspace;
import app.mtx.toolbox.fs.FileOps;
import app.mtx.toolbox.ops.Operation;
import app.mtx.toolbox.ops.OperationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Real APK inspection: the manifest is decoded by the native AXML parser, the
 * signing block is probed directly in the file, and nothing is assumed. When a
 * field cannot be read, the warning is shown instead of a fake value.
 */
public class ApkInfoActivity extends BaseActivity {

    private String path;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_apk_info));
        path = getIntent().getStringExtra(ToolRouter.EXTRA_PATH);
        if (path == null) { finish(); return; }

        titleView.setText(new File(path).getName());

        addToolbarAction(getString(R.string.view_manifest), new View.OnClickListener() {
            @Override
            public void onClick(View v) { showManifest(); }
        });
        addToolbarAction(getString(R.string.view_entries), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(ApkInfoActivity.this, ArchiveActivity.class);
                i.putExtra(ToolRouter.EXTRA_PATH, path);
                startActivity(i);
            }
        });
        addToolbarAction(getString(R.string.extract), new View.OnClickListener() {
            @Override
            public void onClick(View v) { extractContents(); }
        });

        load();
    }

    private void load() {
        setStatus(getString(R.string.op_scanning));
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String raw = Native.isAvailable() ? Native.apkInfo(path) : null;
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
        }, "mtx-apk").start();
    }

    private void render(Kv kv) {
        List<SimpleAdapter.Row> rows = new ArrayList<>();

        if (!kv.getBool("ok", true)) {
            rows.add(new SimpleAdapter.Row(getString(R.string.err_corrupt), kv.get("error")));
        }

        rows.add(new SimpleAdapter.Row(getString(R.string.apk_package), kv.get("package")));
        rows.add(new SimpleAdapter.Row(getString(R.string.apk_version),
                kv.get("versionName") + "   (" + getString(R.string.apk_version_code) + " "
                        + kv.get("versionCode") + ")"));
        rows.add(new SimpleAdapter.Row(getString(R.string.apk_min_sdk) + " / "
                + getString(R.string.apk_target_sdk),
                kv.get("minSdk") + "  \u2192  " + kv.get("targetSdk")
                        + "   compileSdk " + kv.get("compileSdk")));
        rows.add(new SimpleAdapter.Row(getString(R.string.apk_size),
                Fmt.bytes(kv.getLong("fileSize", -1)) + "   \u2022   "
                        + kv.get("entryCount") + " entries"));

        List<String> abis = kv.all("abi");
        rows.add(new SimpleAdapter.Row(getString(R.string.apk_abi),
                abis.isEmpty() ? "\u2014" : join(abis, ", ")));

        String label = kv.get("label");
        if (!label.isEmpty()) rows.add(new SimpleAdapter.Row("label", label));
        String main = kv.get("mainActivity");
        if (!main.isEmpty()) rows.add(new SimpleAdapter.Row("launcher activity", main));
        String split = kv.get("split");
        if (!split.isEmpty()) rows.add(new SimpleAdapter.Row("split", split));

        StringBuilder flags = new StringBuilder();
        if (kv.getBool("debuggable", false)) flags.append("debuggable  ");
        if (!kv.getBool("extractNativeLibs", true)) flags.append("extractNativeLibs=false  ");
        if (kv.getBool("cleartextTraffic", false)) flags.append("cleartextTraffic  ");
        if (kv.getBool("hasArsc", false)) flags.append("resources.arsc  ");
        if (kv.getBool("hasAssets", false)) flags.append("assets/  ");
        if (flags.length() > 0) rows.add(new SimpleAdapter.Row("flags", flags.toString().trim()));

        // Signature facts, stated exactly as observed.
        StringBuilder sig = new StringBuilder();
        boolean block = kv.getBool("signingBlock", false);
        if (block) {
            sig.append("APK Signing Block present");
            if (kv.getBool("schemeV2", false)) sig.append("   v2");
            if (kv.getBool("schemeV3", false)) sig.append("   v3");
            if (kv.getBool("schemeV31", false)) sig.append("   v3.1");
        }
        if (kv.getBool("schemeV1Files", false)) {
            if (sig.length() > 0) sig.append('\n');
            sig.append("v1 (JAR) signature files in META-INF");
        }
        if (sig.length() == 0) sig.append(getString(R.string.apk_unsigned));
        rows.add(new SimpleAdapter.Row(getString(R.string.apk_signature), sig.toString()));

        List<String> dex = kv.all("dex");
        rows.add(new SimpleAdapter.Row(getString(R.string.apk_dex) + "  (" + dex.size() + ")",
                dex.isEmpty() ? "\u2014" : join(dex, "   ")));

        final List<String> libs = kv.all("lib");
        rows.add(new SimpleAdapter.Row(getString(R.string.apk_native_libs) + "  (" + libs.size() + ")",
                libs.isEmpty() ? "\u2014" : libs.get(0) + (libs.size() > 1 ? "  \u2026" : ""),
                null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!libs.isEmpty()) showText(getString(R.string.apk_native_libs), join(libs, "\n"));
            }
        }));

        final List<String> perms = kv.all("permission");
        rows.add(new SimpleAdapter.Row(getString(R.string.apk_permissions) + "  (" + perms.size() + ")",
                perms.isEmpty() ? "\u2014" : perms.get(0) + (perms.size() > 1 ? "  \u2026" : ""),
                null, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!perms.isEmpty()) showText(getString(R.string.apk_permissions), join(perms, "\n"));
            }
        }));

        List<String> components = kv.all("component");
        rows.add(new SimpleAdapter.Row(getString(R.string.apk_components) + "  (" + components.size() + ")", null));
        for (String c : components) {
            String[] f = Kv.fields(c);
            if (f.length < 5) continue;
            String sub = f[0] + ("1".equals(f[2]) ? "   exported" : "")
                    + ("1".equals(f[3]) ? "" : "   disabled")
                    + (f[4].isEmpty() ? "" : "\n" + f[4]);
            rows.add(new SimpleAdapter.Row(f[1], sub));
        }

        List<String> warnings = kv.all("warning");
        if (!warnings.isEmpty()) {
            rows.add(new SimpleAdapter.Row(getString(R.string.apk_warnings), join(warnings, "\n")));
        }

        adapter.setRows(rows);
        setStatus(path);
    }

    private void showManifest() {
        final Handler main = new Handler(Looper.getMainLooper());
        setStatus(getString(R.string.op_scanning));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String xml = Native.apkManifestXml(path);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(path);
                        if (xml == null) showError(getString(R.string.err_corrupt), OpResult.safeLastError());
                        else showText(getString(R.string.apk_manifest), xml);
                    }
                });
            }
        }, "mtx-manifest").start();
    }

    private void extractContents() {
        String name = new File(path).getName();
        File out = Workspace.uniqueFile(Workspace.dir(this, Workspace.EXTRACTED), name + "-contents");
        FileOps.extractArchive(this, path, out.getAbsolutePath(), new OperationManager.Completion() {
            @Override
            public void onFinished(Operation op) {
                if (op.state() == Operation.State.DONE)
                    toast(getString(R.string.extracted_to, String.valueOf(op.output())));
                else if (op.state() == Operation.State.FAILED)
                    showError(getString(R.string.operation_failed, op.title),
                            OpResult.message(ApkInfoActivity.this, op.resultCode())
                                    + "\n\n" + String.valueOf(op.error()));
            }
        });
        toast(getString(R.string.operation_started, getString(R.string.op_extracting)));
    }

    static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
