package app.mtx.toolbox.core;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;

import java.io.File;

/**
 * The MTX workspace. Created on first launch and used as the output root for
 * every tool, so originals are never touched implicitly.
 *
 * <pre>
 * MTX/Extracted  MTX/APK  MTX/Projects  MTX/Backups
 * MTX/Signed     MTX/Temp MTX/Logs      MTX/Exports
 * </pre>
 */
public final class Workspace {

    public static final String NAME = "MTX";

    public static final String EXTRACTED = "Extracted";
    public static final String APK = "APK";
    public static final String PROJECTS = "Projects";
    public static final String BACKUPS = "Backups";
    public static final String SIGNED = "Signed";
    public static final String TEMP = "Temp";
    public static final String LOGS = "Logs";
    public static final String EXPORTS = "Exports";

    private static final String[] ALL = {EXTRACTED, APK, PROJECTS, BACKUPS, SIGNED, TEMP, LOGS, EXPORTS};

    private static File root;

    private Workspace() {}

    /**
     * Resolves the workspace root. Shared storage is preferred so the user can
     * reach the output from any app; if it is not writable (scoped storage without
     * the all-files permission) we fall back to the app-specific external dir and
     * keep working instead of failing.
     */
    public static synchronized File root(Context ctx) {
        if (root != null && root.isDirectory()) return root;

        File candidate = new File(Environment.getExternalStorageDirectory(), NAME);
        if (ensureUsable(candidate)) {
            root = candidate;
        } else {
            File[] dirs = ctx.getExternalFilesDirs(null);
            File base = dirs != null && dirs.length > 0 && dirs[0] != null
                    ? dirs[0] : ctx.getFilesDir();
            candidate = new File(base, NAME);
            ensureUsable(candidate);
            root = candidate;
        }
        for (String sub : ALL) new File(root, sub).mkdirs();
        return root;
    }

    private static boolean ensureUsable(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        return dir.canWrite();
    }

    public static File dir(Context ctx, String which) {
        File d = new File(root(ctx), which);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** {@code MTX/Projects/<name>/} for APK decode workspaces. */
    public static File project(Context ctx, String name) {
        File d = new File(dir(ctx, PROJECTS), sanitizeName(name));
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Returns a path that does not exist yet, appending " (2)", " (3)"... */
    public static File uniqueFile(File dir, String fileName) {
        File f = new File(dir, sanitizeName(fileName));
        if (!f.exists()) return f;
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            File c = new File(dir, sanitizeName(base + " (" + i + ")" + ext));
            if (!c.exists()) return c;
        }
        return new File(dir, sanitizeName(base + "-" + System.currentTimeMillis() + ext));
    }

    /** Keeps Arabic and other Unicode names intact, strips only path-hostile characters. */
    public static String sanitizeName(String name) {
        if (TextUtils.isEmpty(name)) return "unnamed";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == 0) sb.append('_');
            else sb.append(c);
        }
        String out = sb.toString().trim();
        if (out.equals(".") || out.equals("..")) return "_";
        return out.isEmpty() ? "unnamed" : out;
    }
}
