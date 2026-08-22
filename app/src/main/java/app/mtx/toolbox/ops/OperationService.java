package app.mtx.toolbox.ops;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import app.mtx.toolbox.R;
import app.mtx.toolbox.core.Fmt;
import app.mtx.toolbox.ui.OperationsActivity;

/**
 * Keeps long operations alive when the user leaves the app, and shows their
 * progress in a notification. Started only while work is actually running.
 */
public class OperationService extends Service implements OperationManager.Listener {

    private static final String CHANNEL = "mtx_operations";
    private static final int NOTIF_ID = 1001;

    public static void ensureRunning(Context ctx) {
        try {
            Intent i = new Intent(ctx, OperationService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable ignored) {
            // Background-start limits: the operation still runs, just without a notification.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        OperationManager.get(this).addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIF_ID, build());
        } catch (Throwable ignored) {
        }
        onOperationsChanged();
        return START_NOT_STICKY;
    }

    @Override
    public void onOperationsChanged() {
        OperationManager mgr = OperationManager.get(this);
        if (mgr.activeCount() == 0) {
            stopForeground(true);
            stopSelf();
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIF_ID, build());
            } catch (Throwable ignored) {
            }
        }
    }

    private Notification build() {
        OperationManager mgr = OperationManager.get(this);
        Operation first = null;
        for (Operation op : mgr.operations()) {
            if (!op.isFinished()) { first = op; break; }
        }
        String title = first != null ? first.title : getString(R.string.menu_operations);
        String text = first != null
                ? Fmt.percent(first.done(), first.total()) + "  " + Fmt.speed(first.speed())
                + "  " + Fmt.bytes(first.done())
                : "";

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, OperationsActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setContentIntent(pi);
        if (first != null && first.total() > 0) {
            int pct = (int) Math.min(100, first.done() * 100 / first.total());
            b.setProgress(100, pct, false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                getString(R.string.menu_operations), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Override
    public void onDestroy() {
        OperationManager.get(this).removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
