package app.mtx.toolbox.ui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.DisplayMetrics;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.core.Native;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Android tools: what the device really reports. Privileged data (Shizuku, root,
 * ADB) is only claimed when it is actually detected.
 */
public class DeviceInfoActivity extends BaseActivity {

    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/data/local/xbin/su", "/data/local/bin/su"
    };

    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolScreen(getString(R.string.title_device_info));
        render();
    }

    private void render() {
        List<SimpleAdapter.Row> rows = new ArrayList<>();

        rows.add(new SimpleAdapter.Row("Android",
                Build.VERSION.RELEASE + "   SDK " + Build.VERSION.SDK_INT
                        + "\n" + Build.DISPLAY));
        rows.add(new SimpleAdapter.Row("device",
                Build.MANUFACTURER + " " + Build.MODEL
                        + "\nboard " + Build.BOARD + "   hardware " + Build.HARDWARE));

        StringBuilder abis = new StringBuilder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (String abi : Build.SUPPORTED_ABIS) abis.append(abi).append("   ");
        }
        rows.add(new SimpleAdapter.Row("ABI", abis.toString().trim()));

        rows.add(new SimpleAdapter.Row("CPU",
                Runtime.getRuntime().availableProcessors() + " cores"));

        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            rows.add(new SimpleAdapter.Row("RAM",
                    Fmt.bytes(mi.totalMem) + " total   " + Fmt.bytes(mi.availMem) + " available"
                            + "\napp heap limit " + am.getMemoryClass() + " MB"
                            + (mi.lowMemory ? "   (low memory)" : "")));
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        rows.add(new SimpleAdapter.Row("display",
                dm.widthPixels + " x " + dm.heightPixels + "   " + dm.densityDpi + " dpi"
                        + "   " + getResources().getConfiguration().smallestScreenWidthDp + " dp"));

        addVolume(rows, getString(R.string.internal_storage),
                Environment.getExternalStorageDirectory().getAbsolutePath());
        addVolume(rows, "/data", Environment.getDataDirectory().getAbsolutePath());

        PackageManager pm = getPackageManager();
        try {
            rows.add(new SimpleAdapter.Row("packages",
                    pm.getInstalledPackages(0).size() + " installed"));
        } catch (Throwable ignored) {
        }

        rows.add(new SimpleAdapter.Row("root", detectRoot()));
        rows.add(new SimpleAdapter.Row("Shizuku", detectShizuku()));
        rows.add(new SimpleAdapter.Row(getString(R.string.native_core),
                Native.isAvailable() ? Native.coreVersion() : Native.loadError()));

        adapter.setRows(rows);
        setStatus(Build.FINGERPRINT);
    }

    private void addVolume(List<SimpleAdapter.Row> rows, String label, String path) {
        long[] usage = Native.isAvailable() ? Native.diskUsage(path) : null;
        if (usage == null || usage.length < 3) {
            rows.add(new SimpleAdapter.Row(label, path));
            return;
        }
        long used = usage[0] - usage[1];
        rows.add(new SimpleAdapter.Row(label,
                getString(R.string.storage_total) + " " + Fmt.bytes(usage[0])
                        + "   " + getString(R.string.storage_used) + " " + Fmt.bytes(used)
                        + "   " + getString(R.string.storage_free) + " " + Fmt.bytes(usage[2])
                        + "\n" + path));
    }

    /** Detection only: MTX never assumes root and never silently runs su. */
    private String detectRoot() {
        for (String p : SU_PATHS) {
            if (new File(p).exists()) return "su binary found at " + p + "  (not used unless you ask)";
        }
        String tags = Build.TAGS;
        if (tags != null && tags.contains("test-keys")) return "build signed with test-keys";
        return "not detected";
    }

    private String detectShizuku() {
        try {
            getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return "installed  (service binding lands with the Shizuku layer)";
        } catch (Throwable t) {
            return "not installed";
        }
    }
}
