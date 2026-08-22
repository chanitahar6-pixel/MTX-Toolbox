package app.mtx.toolbox.core;

/**
 * The single JNI surface of MTX. Every method here maps 1:1 to a function in
 * {@code jni_bridge.cpp}. Nothing in this class does work in Java: it only
 * forwards to the C++ engines.
 *
 * <p>Return conventions:
 * <ul>
 *   <li>{@code int} results are {@link OpResult} codes ({@code 0} == success);</li>
 *   <li>{@code null} means failure, and {@link #lastError()} holds the technical reason;</li>
 *   <li>long-running calls take a {@code job} id so they can be cancelled.</li>
 * </ul>
 */
public final class Native {

    private Native() {}

    private static boolean loaded;
    private static String loadError;

    static {
        try {
            System.loadLibrary("mtxcore");
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
            loadError = String.valueOf(t.getMessage());
        }
    }

    public static boolean isAvailable() { return loaded; }

    public static String loadError() { return loadError; }

    // ---- hash algorithm ids shared with hash.h -----------------------------
    public static final int MD5 = 0;
    public static final int SHA1 = 1;
    public static final int SHA224 = 2;
    public static final int SHA256 = 3;

    // ---- search flags shared with search.h ---------------------------------
    public static final int SEARCH_RECURSIVE = 1;
    public static final int SEARCH_CASE = 1 << 1;
    public static final int SEARCH_CONTENT = 1 << 2;
    public static final int SEARCH_ARCHIVES = 1 << 3;
    public static final int SEARCH_HIDDEN = 1 << 4;
    public static final int SEARCH_WHOLE_WORD = 1 << 5;

    public static native String coreVersion();

    /** Technical detail of the most recent failure on this thread. */
    public static native String lastError();

    public static native long newJob();
    public static native void cancelJob(long job);
    public static native void releaseJob(long job);

    // ---- file system -------------------------------------------------------
    public static native String[] listDir(String path);
    public static native String statPath(String path);
    public static native int copyPath(long job, String src, String dstDir, boolean overwrite, ProgressSink sink);
    public static native int movePath(long job, String src, String dstDir, boolean overwrite, ProgressSink sink);
    public static native int deletePath(long job, String path, ProgressSink sink);
    public static native int mkdirs(String path);
    public static native int createFile(String path);
    public static native int renamePath(String from, String to);
    /** @return {total, free, available} in bytes, or null. */
    public static native long[] diskUsage(String path);
    /** @return {bytes, files, dirs}, or null. */
    public static native long[] treeStats(long job, String path, ProgressSink sink);
    /** @return {firstDifferentByteOffset, identical ? 1 : 0}, or null. */
    public static native long[] compareFiles(long job, String a, String b, ProgressSink sink);

    // ---- hash / type -------------------------------------------------------
    public static native String hashFile(long job, String path, int algo, ProgressSink sink);
    public static native String analyzeType(String path);

    // ---- hex / binary ------------------------------------------------------
    public static native byte[] hexRead(String path, long offset, int len);
    public static native int hexWrite(String path, long offset, byte[] data);
    public static native long hexFind(long job, String path, long from, byte[] pattern, boolean backwards);
    public static native int extractStrings(long job, String path, int minLen, int maxResults, RowSink sink);

    // ---- archives ----------------------------------------------------------
    public static native String[] zipList(String path);
    /** entry == null or "" extracts the whole archive. */
    public static native int zipExtract(long job, String zip, String entry, String outDir, ProgressSink sink);
    public static native byte[] zipRead(String zip, String entry, int maxBytes);
    public static native String zipTest(long job, String zip, ProgressSink sink);

    // ---- apk / dex / axml --------------------------------------------------
    public static native String apkInfo(String path);
    public static native String apkManifestXml(String apk);
    public static native String axmlToXml(String path);
    public static native String dexInfo(String path);

    // ---- native binaries ---------------------------------------------------
    public static native String elfInfo(String path, int maxSymbols);

    // ---- search ------------------------------------------------------------
    public static native int search(long job, String root, String namePattern, String content,
                                    int flags, int maxResults, RowSink rows, ProgressSink progress);
}
