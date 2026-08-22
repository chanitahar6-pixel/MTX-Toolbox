package app.mtx.toolbox.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Appends technical detail to {@code MTX/Logs/<date>.log}. Never throws. */
public final class MtxLog {

    private static final String TAG = "MTX";
    private static final Object LOCK = new Object();

    private MtxLog() {}

    public static void i(Context ctx, String area, String message) {
        write(ctx, "INFO", area, message, null);
    }

    public static void w(Context ctx, String area, String message) {
        write(ctx, "WARN", area, message, null);
    }

    public static void e(Context ctx, String area, String message, Throwable t) {
        write(ctx, "ERROR", area, message, t);
    }

    public static void write(Context ctx, String level, String area, String message, Throwable t) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + "  " + level + "  [" + area + "]  " + message;
        if ("ERROR".equals(level)) Log.e(TAG, line, t);
        else Log.i(TAG, line);

        if (ctx == null) return;
        synchronized (LOCK) {
            OutputStreamWriter w = null;
            try {
                File dir = Workspace.dir(ctx, Workspace.LOGS);
                String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                File file = new File(dir, day + ".log");
                w = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
                w.write(line);
                w.write('\n');
                if (t != null) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    w.write(sw.toString());
                    w.write('\n');
                }
                w.flush();
            } catch (Throwable ignored) {
                // Logging must never take the app down.
            } finally {
                if (w != null) try { w.close(); } catch (Throwable ignored) {}
            }
        }
    }

    public static File logDir(Context ctx) { return Workspace.dir(ctx, Workspace.LOGS); }
}
