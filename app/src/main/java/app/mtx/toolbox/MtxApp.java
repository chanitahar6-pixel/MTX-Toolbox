package app.mtx.toolbox;

import android.app.Application;
import android.content.Context;

import app.mtx.toolbox.core.LocaleHelper;
import app.mtx.toolbox.core.MtxLog;
import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.ThemeManager;
import app.mtx.toolbox.core.Workspace;

/**
 * Boots the workspace, theme and language, and installs a last-resort handler so
 * a crash is always written to {@code MTX/Logs/} before the process dies.
 */
public class MtxApp extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeManager.apply(this);

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                MtxLog.e(MtxApp.this, "crash", "uncaught on " + t.getName(), e);
                if (previous != null) previous.uncaughtException(t, e);
            }
        });

        // Workspace creation touches storage, so keep it off the main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Workspace.root(MtxApp.this);
                    MtxLog.i(MtxApp.this, "boot", Native.isAvailable()
                            ? Native.coreVersion()
                            : "native core unavailable: " + Native.loadError());
                } catch (Throwable t) {
                    MtxLog.e(MtxApp.this, "boot", "workspace init failed", t);
                }
            }
        }, "mtx-boot").start();
    }
}
