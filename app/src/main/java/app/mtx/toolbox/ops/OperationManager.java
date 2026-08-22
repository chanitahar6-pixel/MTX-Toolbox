package app.mtx.toolbox.ops;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import app.mtx.toolbox.core.MtxLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs every heavy task in MTX. Multiple operations may run concurrently, but
 * the pool is bounded so low-RAM devices are not thrashed. Nothing ever runs on
 * the UI thread.
 */
public final class OperationManager {

    public interface Listener {
        void onOperationsChanged();
    }

    private static OperationManager instance;

    private final Context appContext;
    private final ExecutorService pool;
    private final AtomicLong ids = new AtomicLong(1);
    private final List<Operation> operations = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private OperationManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(2, Math.min(4, cores / 2));
        this.pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
            private int n = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mtx-op-" + (++n));
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
    }

    public static synchronized OperationManager get(Context ctx) {
        if (instance == null) instance = new OperationManager(ctx);
        return instance;
    }

    public Operation submit(String kind, String title, Operation.Body body) {
        final Operation op = new Operation(ids.getAndIncrement(), kind, title, body);
        operations.add(0, op);
        notifyChanged();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                notifyChanged();
                op.runInternal();
                writeLog(op);
                notifyChanged();
            }
        });
        return op;
    }

    /** Re-runs a finished operation with the same body. */
    public Operation retry(Operation source) {
        return submit(source.kind, source.title, source.body());
    }

    public List<Operation> operations() {
        return Collections.unmodifiableList(new ArrayList<>(operations));
    }

    public int activeCount() {
        int n = 0;
        for (Operation op : operations) if (!op.isFinished()) n++;
        return n;
    }

    public Operation byId(long id) {
        for (Operation op : operations) if (op.id == id) return op;
        return null;
    }

    public void cancelAll() {
        for (Operation op : operations) if (!op.isFinished()) op.cancel();
    }

    public void clearFinished() {
        for (Operation op : operations) if (op.isFinished()) operations.remove(op);
        notifyChanged();
    }

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    private void notifyChanged() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    try { l.onOperationsChanged(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    private void writeLog(Operation op) {
        StringBuilder sb = new StringBuilder();
        sb.append(op.kind).append(" | ").append(op.title)
                .append(" | ").append(op.state())
                .append(" | ").append(op.elapsedMs()).append(" ms");
        if (op.error() != null) sb.append(" | ").append(op.error());
        for (String line : op.log()) sb.append("\n    ").append(line);

        if (op.state() == Operation.State.FAILED) {
            MtxLog.e(appContext, "op", sb.toString(), null);
        } else {
            MtxLog.i(appContext, "op", sb.toString());
        }
    }
}
