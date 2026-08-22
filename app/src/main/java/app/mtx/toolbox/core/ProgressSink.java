package app.mtx.toolbox.core;

/** Progress callback invoked from the native engines on the worker thread. */
public interface ProgressSink {
    /**
     * @param current    file or entry currently being processed
     * @param done       bytes processed so far
     * @param total      total bytes, or -1 when unknown
     * @param speed      bytes per second
     * @param filesDone  files processed so far
     * @param filesTotal total files, or -1 when unknown
     */
    void onProgress(String current, long done, long total, long speed, long filesDone, long filesTotal);
}
