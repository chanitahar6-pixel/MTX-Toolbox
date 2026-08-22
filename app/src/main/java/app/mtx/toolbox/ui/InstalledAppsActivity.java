package app.mtx.toolbox.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.core.Workspace;
import app.mtx.toolbox.ops.Operation;
import app.mtx.toolbox.ops.OperationManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * App manager. Extraction handles split packages properly: base.apk and every
 * split APK are copied into MTX/Extracted/ using the native copy engine.
 */
public class InstalledAppsActivity extends BaseActivity {

    private static final class App {
        String label;
        String pkg;
        String versionName;
        long versionCode;
        String sourceDir;
        String[] splits;
        String abi;
        boolean system;
        long size;
    }

    private final List<App> apps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_installed_apps));
        load();
    }

    private void load() {
        setStatus(getString(R.string.op_scanning));
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<App> list = new ArrayList<>();
                PackageManager pm = getPackageManager();
                try {
                    for (PackageInfo pi : pm.getInstalledPackages(0)) {
                        ApplicationInfo ai = pi.applicationInfo;
                        if (ai == null) continue;
                        App a = new App();
                        a.pkg = pi.packageName;
                        a.label = String.valueOf(pm.getApplicationLabel(ai));
                        a.versionName = pi.versionName == null ? "?" : pi.versionName;
                        a.versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                                ? pi.getLongVersionCode() : pi.versionCode;
                        a.sourceDir = ai.sourceDir;
                        a.splits = ai.splitSourceDirs;
                        a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        a.abi = ai.nativeLibraryDir == null
                                ? "" : new File(ai.nativeLibraryDir).getName();
                        a.size = a.sourceDir == null ? 0 : new File(a.sourceDir).length();
                        if (a.splits != null) {
                            for (String s : a.splits) if (s != null) a.size += new File(s).length();
                        }
                        list.add(a);
                    }
                } catch (Throwable ignored) {
                }
                Collections.sort(list, new Comparator<App>() {
                    @Override
                    public int compare(App a, App b) { return a.label.compareToIgnoreCase(b.label); }
                });
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        apps.clear();
                        apps.addAll(list);
                        render();
                    }
                });
            }
        }, "mtx-apps").start();
    }

    private void render() {
        List<SimpleAdapter.Row> rows = new ArrayList<>();
        for (final App a : apps) {
            StringBuilder sub = new StringBuilder();
            sub.append(a.pkg).append('\n')
                    .append(a.versionName).append("  (").append(a.versionCode).append(")   ")
                    .append(Fmt.bytes(a.size)).append("   ")
                    .append(a.system ? getString(R.string.system_app) : getString(R.string.user_app));
            if (!a.abi.isEmpty()) sub.append("   ").append(a.abi);
            int splitCount = a.splits == null ? 0 : a.splits.length;
            if (splitCount > 0)
                sub.append("   ").append(getString(R.string.splits)).append(": ").append(splitCount);

            rows.add(new SimpleAdapter.Row(a.label, sub.toString(), a, new View.OnClickListener() {
                @Override
                public void onClick(View v) { actions(a); }
            }));
        }
        adapter.setRows(rows);
        setStatus(apps.size() + " " + getString(R.string.title_installed_apps));
    }

    private void actions(final App a) {
        String[] labels = {
                getString(R.string.extract_apk),
                getString(R.string.title_apk_info),
                getString(R.string.launch),
                getString(R.string.app_info),
                getString(R.string.uninstall)};

        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        switch (which) {
                            case 0: extract(a); break;
                            case 1: openApkInfo(a); break;
                            case 2: launch(a); break;
                            case 3: appInfo(a); break;
                            default: uninstall(a); break;
                        }
                    }
                })
                .show();
    }

    /** base.apk plus every split, into MTX/Extracted/. */
    private void extract(final App a) {
        if (a.sourceDir == null) {
            toast(getString(R.string.err_not_found));
            return;
        }
        final File outDir = Workspace.uniqueFile(Workspace.dir(this, Workspace.EXTRACTED),
                a.label + " " + a.versionName);
        OperationManager.get(this).submit("extract-apk",
                getString(R.string.op_extract_apk) + ": " + a.label,
                new Operation.Body() {
                    @Override
                    public int execute(Operation op) {
                        int code = Native.mkdirs(outDir.getAbsolutePath());
                        if (!OpResult.isOk(code)) return code;

                        op.addLog("base: " + a.sourceDir);
                        code = Native.copyPath(op.jobId(), a.sourceDir, outDir.getAbsolutePath(), true, op);
                        if (!OpResult.isOk(code)) return code;

                        if (a.splits != null) {
                            for (String split : a.splits) {
                                if (split == null) continue;
                                if (op.isCancelRequested()) return OpResult.E_CANCELLED;
                                op.addLog("split: " + split);
                                code = Native.copyPath(op.jobId(), split, outDir.getAbsolutePath(), true, op);
                                if (!OpResult.isOk(code)) return code;
                            }
                        }
                        op.setOutput(outDir.getAbsolutePath());
                        return OpResult.OK;
                    }
                },
                new OperationManager.Completion() {
                    @Override
                    public void onFinished(Operation op) {
                        if (op.state() == Operation.State.DONE)
                            showText(getString(R.string.extract_apk),
                                    getString(R.string.extracted_to, String.valueOf(op.output())));
                        else if (op.state() == Operation.State.FAILED)
                            showError(getString(R.string.operation_failed, op.title),
                                    OpResult.message(InstalledAppsActivity.this, op.resultCode())
                                            + "\n\n" + String.valueOf(op.error()));
                    }
                });
        toast(getString(R.string.operation_started, getString(R.string.op_extract_apk)));
    }

    private void openApkInfo(App a) {
        if (a.sourceDir == null) return;
        Intent i = new Intent(this, ApkInfoActivity.class);
        i.putExtra(ToolRouter.EXTRA_PATH, a.sourceDir);
        startActivity(i);
    }

    private void launch(App a) {
        Intent i = getPackageManager().getLaunchIntentForPackage(a.pkg);
        if (i == null) {
            toast(getString(R.string.err_unsupported));
            return;
        }
        startActivity(i);
    }

    private void appInfo(App a) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + a.pkg));
            startActivity(i);
        } catch (Throwable t) {
            toast(getString(R.string.err_unsupported));
        }
    }

    private void uninstall(App a) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE);
            i.setData(Uri.parse("package:" + a.pkg));
            startActivity(i);
        } catch (Throwable t) {
            toast(getString(R.string.err_permission));
        }
    }
}
