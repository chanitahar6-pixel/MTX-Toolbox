package app.mtx.toolbox.core;

/**
 * Raw JNI declarations for {@code libmtxcore.so}. Every method here maps 1:1 to a
 * {@code Java_app_mtx_toolbox_core_NativeLib_*} export in {@code jni_bridge.cpp}.
 *
 * <p>Nothing in the app calls this class directly. {@link Native} is the entry
 * point: it uses these methods when the native core is present and falls back to
 * the pure-Java engines when it is not (for example in builds produced without an
 * NDK, such as an on-device AndroidIDE build).
 */
final class NativeLib {

    private NativeLib() {}

    private static final boolean LOADED;
    private static final String LOAD_ERROR;

    static {
        boolean ok;
        String error = null;
        try {
            System.loadLibrary("mtxcore");
            ok = true;
        } catch (Throwable t) {
            ok = false;
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOADED = ok;
        LOAD_ERROR = error;
    }

    static boolean isLoaded() { return LOADED; }

    static String loadError() { return LOAD_ERROR; }

    static native String coreVersion();
    static native String lastError();

    static native long newJob();
    static native void cancelJob(long job);
    static native void releaseJob(long job);

    static native String[] listDir(String path);
    static native String statPath(String path);
    static native int copyPath(long job, String src, String dstDir, boolean overwrite, ProgressSink sink);
    static native int movePath(long job, String src, String dstDir, boolean overwrite, ProgressSink sink);
    static native int deletePath(long job, String path, ProgressSink sink);
    static native int mkdirs(String path);
    static native int createFile(String path);
    static native int renamePath(String from, String to);
    static native long[] diskUsage(String path);
    static native long[] treeStats(long job, String path, ProgressSink sink);
    static native long[] compareFiles(long job, String a, String b, ProgressSink sink);

    static native String hashFile(long job, String path, int algo, ProgressSink sink);
    static native String analyzeType(String path);

    static native byte[] hexRead(String path, long offset, int len);
    static native int hexWrite(String path, long offset, byte[] data);
    static native long hexFind(long job, String path, long from, byte[] pattern, boolean backwards);
    static native int extractStrings(long job, String path, int minLen, int maxResults, RowSink sink);

    static native String[] zipList(String path);
    static native int zipExtract(long job, String zip, String entry, String outDir, ProgressSink sink);
    static native byte[] zipRead(String zip, String entry, int maxBytes);
    static native String zipTest(long job, String zip, ProgressSink sink);

    static native String apkInfo(String path);
    static native String apkManifestXml(String apk);
    static native String axmlToXml(String path);
    static native String dexInfo(String path);

    static native String elfInfo(String path, int maxSymbols);

    static native int search(long job, String root, String namePattern, String content,
                            int flags, int maxResults, RowSink rows, ProgressSink progress);
}
