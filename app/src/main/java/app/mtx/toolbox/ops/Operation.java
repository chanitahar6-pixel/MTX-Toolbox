package app.mtx.toolbox.ops;

import app.mtx.toolbox.core.Native;
import app.mtx.toolbox.core.OpResult;
import app.mtx.toolbox.core.ProgressSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A unit of heavy work. Everything expensive in MTX runs as an Operation, which
 * gives every tool the same contract: progress, speed, cancel, retry, logs and a
 * real error instead of a silent failure.
 */
public final class Operation implements ProgressSink {

    public enum State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    /** The actual work. Receives the operation so it can report and check cancellation. */
    public interface Body {
        /** @return an {@link OpResult} code */
        int execute(Operation op) throws Exception;
    }

    public final long id;
    public final String title;
    public final String kind;
    private final Body body;
    private final List<String> log = Collections.synchronizedList(new ArrayList<String>());

    private volatile State state = State.QUEUED;
    private volatile long jobId;
    private volatile String current = "";
    private volatile long done, total = -1, speed, filesDone, filesTotal = -1;
    private volatile int resultCode;
    private volatile String error;
    private volatile Object output;
    private final long createdAt = System.currentTimeMillis();
    private volatile long startedAt, finishedAt;

    Operation(long id, String kind, String title, Body body) {
        this.id = id;
        this.kind = kind;
        this.title = title;
        this.body = body;
    }

    // ---- state ------------------------------------------------------------
    public State state() { return state; }
    public boolean isFinished() {
        return state == State.DONE || state == State.FAILED || state == State.CANCELLED;
    }
    public String current() { return current; }
    public long done() { return done; }
    public long total() { return total; }
    public long speed() { return speed; }
    public long filesDone() { return filesDone; }
    public long filesTotal() { return filesTotal; }
    public int resultCode() { return resultCode; }
    public String error() { return error; }
    public Object output() { return output; }
    public long elapsedMs() {
        long end = finishedAt > 0 ? finishedAt : System.currentTimeMillis();
        return startedAt > 0 ? end - startedAt : 0;
    }
    public long createdAt() { return createdAt; }

    public List<String> log() {
        synchronized (log) { return new ArrayList<>(log); }
    }

    public void addLog(String line) { log.add(line); }

    public void setOutput(Object output) { this.output = output; }

    /** Native job handle; pass this into any {@link Native} call so cancel works. */
    public long jobId() { return jobId; }

    public boolean isCancelRequested() { return cancelRequested; }

    private volatile boolean cancelRequested;

    public void cancel() {
        cancelRequested = true;
        long j = jobId;
        if (j != 0) Native.cancelJob(j);
        addLog("cancel requested");
    }

    @Override
    public void onProgress(String current, long done, long total, long speed,
                           long filesDone, long filesTotal) {
        this.current = current == null ? "" : current;
        this.done = done;
        this.total = total;
        this.speed = speed;
        this.filesDone = filesDone;
        this.filesTotal = filesTotal;
    }

    // ---- execution (called by OperationManager) ---------------------------
    void runInternal() {
        state = State.RUNNING;
        startedAt = System.currentTimeMillis();
        jobId = Native.isAvailable() ? Native.newJob() : 0;
        addLog("started: " + title);
        try {
            int code = body.execute(this);
            resultCode = code;
            if (OpResult.isOk(code)) {
                state = State.DONE;
                addLog("completed in " + elapsedMs() + " ms");
            } else if (OpResult.isCancelled(code) || cancelRequested) {
                state = State.CANCELLED;
                addLog("cancelled");
            } else {
                state = State.FAILED;
                error = OpResult.safeLastError();
                addLog("failed (code " + code + "): " + error);
            }
        } catch (Throwable t) {
            // No tool is allowed to crash the app: everything becomes a result.
            resultCode = OpResult.E_INTERNAL;
            state = cancelRequested ? State.CANCELLED : State.FAILED;
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            addLog("exception: " + error);
        } finally {
            finishedAt = System.currentTimeMillis();
            if (jobId != 0) {
                Native.releaseJob(jobId);
                jobId = 0;
            }
        }
    }

    Body body() { return body; }
}
