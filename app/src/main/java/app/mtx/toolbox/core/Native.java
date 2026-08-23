package app.mtx.toolbox.core;

/**
 * The single engine entry point used by the whole app.
 *
 * <p>Two interchangeable implementations sit behind this class:
 * <ul>
 *   <li>the <b>C++ core</b> ({@code libmtxcore.so}, built from {@code Android.mk})
 *       whenever it is packaged in the APK;</li>
 *   <li>the <b>pure-Java engines</b> ({@code JavaEngine}, {@code JavaZip},
 *       {@code JavaApk}, {@code JavaAxml}, {@code JavaDex}, {@code JavaElf},
 *       {@code JavaSearch}) otherwise.</li>
 * </ul>
 *
 * <p>Both paths return exactly the same payload formats, so no caller ever needs
 * to know which one ran. That is what allows the project to be built on a desktop
 * with an NDK <i>and</i> on the phone in AndroidIDE without touching any code.
 *
 * <p>Conventions:
 * <ul>
 *   <li>{@code int} results are {@link OpResult} codes ({@code 0} == success);</li>
 *   <li>{@code null} means failure, and {@link #lastError()} holds the reason;</li>
 *   <li>long-running calls take a {@code job} id so they can be cancelled.</li>
 * </ul>
 */
public final class Native {

    private Native() {}

    // ---- hash algorithm ids, shared with hash.h -----------------------------
    public static final int MD5 = 0;
    public static final int SHA1 = 1;
    public static final int SHA224 = 2;
    public static final int SHA256 = 3;

    // ---- search flags, shared with search.h --------------------------------
    public static final int SEARCH_RECURSIVE = 1;
    public static final int SEARCH_CASE = 1 << 1;
    public static final int SEARCH_CONTENT = 1 << 2;
    public static final int SEARCH_ARCHIVES = 1 << 3;
    public static final int SEARCH_HIDDEN = 1 << 4;
    public static final int SEARCH_WHOLE_WORD = 1 << 5;

    private static final boolean NATIVE = NativeLib.isLoaded();

    /** True whenever an engine is usable, which is always: Java is the fallback. */
    public static boolean isAvailable() { return true; }

    /** True only when the C++ core is the active engine. */
    public static boolean isNativeCore() { return NATIVE; }

    /** Why the native core is not in use, or null when it is. */
    public static String loadError() { return NATIVE ? null : NativeLib.loadError(); }

    public static String coreVersion() {
        if (NATIVE) {
            try {
                return NativeLib.coreVersion();
            } catch (Throwable ignored) {
            }
        }
        return "mtx-core 0.1.0 (Java engines, no NDK in this build)";
    }

    /** Technical detail of the most recent failure on this thread. */
    public static String lastError() {
        if (NATIVE) {
            try {
                return NativeLib.lastError();
            } catch (Throwable ignored) {
            }
        }
        return JavaEngine.lastError();
    }

    // ---- jobs --------------------------------------------------------------
    public static long newJob() {
        return NATIVE ? NativeLib.newJob() : JavaEngine.newJob();
    }

    public static void cancelJob(long job) {
        if (NATIVE) NativeLib.cancelJob(job);
        else JavaEngine.cancelJob(job);
    }

    public static void releaseJob(long job) {
        if (NATIVE) NativeLib.releaseJob(job);
        else JavaEngine.releaseJob(job);
    }

    // ---- file system -------------------------------------------------------
    public static String[] listDir(String path) {
        return NATIVE ? NativeLib.listDir(path) : JavaEngine.listDir(path);
    }

    public static String statPath(String path) {
        return NATIVE ? NativeLib.statPath(path) : JavaEngine.statPath(path);
    }

    public static int copyPath(long job, String src, String dstDir, boolean overwrite, ProgressSink sink) {
        return NATIVE
                ? NativeLib.copyPath(job, src, dstDir, overwrite, sink)
                : JavaEngine.copyPath(job, src, dstDir, overwrite, sink);
    }

    public static int movePath(long job, String src, String dstDir, boolean overwrite, ProgressSink sink) {
        return NATIVE
                ? NativeLib.movePath(job, src, dstDir, overwrite, sink)
                : JavaEngine.movePath(job, src, dstDir, overwrite, sink);
    }

    public static int deletePath(long job, String path, ProgressSink sink) {
        return NATIVE ? NativeLib.deletePath(job, path, sink) : JavaEngine.deletePath(job, path, sink);
    }

    public static int mkdirs(String path) {
        return NATIVE ? NativeLib.mkdirs(path) : JavaEngine.mkdirs(path);
    }

    public static int createFile(String path) {
        return NATIVE ? NativeLib.createFile(path) : JavaEngine.createFile(path);
    }

    public static int renamePath(String from, String to) {
        return NATIVE ? NativeLib.renamePath(from, to) : JavaEngine.renamePath(from, to);
    }

    /** @return {total, free, available} in bytes, or null. */
    public static long[] diskUsage(String path) {
        return NATIVE ? NativeLib.diskUsage(path) : JavaEngine.diskUsage(path);
    }

    /** @return {bytes, files, dirs}, or null. */
    public static long[] treeStats(long job, String path, ProgressSink sink) {
        return NATIVE ? NativeLib.treeStats(job, path, sink) : JavaEngine.treeStats(job, path, sink);
    }

    /** @return {firstDifferentByteOffset, identical ? 1 : 0}, or null. */
    public static long[] compareFiles(long job, String a, String b, ProgressSink sink) {
        return NATIVE ? NativeLib.compareFiles(job, a, b, sink) : JavaEngine.compareFiles(job, a, b, sink);
    }

    // ---- hash / type -------------------------------------------------------
    public static String hashFile(long job, String path, int algo, ProgressSink sink) {
        return NATIVE ? NativeLib.hashFile(job, path, algo, sink) : JavaEngine.hashFile(job, path, algo, sink);
    }

    public static String analyzeType(String path) {
        return NATIVE ? NativeLib.analyzeType(path) : JavaFileType.analyze(path);
    }

    // ---- hex / binary ------------------------------------------------------
    public static byte[] hexRead(String path, long offset, int len) {
        return NATIVE ? NativeLib.hexRead(path, offset, len) : JavaEngine.hexRead(path, offset, len);
    }

    public static int hexWrite(String path, long offset, byte[] data) {
        return NATIVE ? NativeLib.hexWrite(path, offset, data) : JavaEngine.hexWrite(path, offset, data);
    }

    public static long hexFind(long job, String path, long from, byte[] pattern, boolean backwards) {
        return NATIVE
                ? NativeLib.hexFind(job, path, from, pattern, backwards)
                : JavaEngine.hexFind(job, path, from, pattern, backwards);
    }

    public static int extractStrings(long job, String path, int minLen, int maxResults, RowSink sink) {
        return NATIVE
                ? NativeLib.extractStrings(job, path, minLen, maxResults, sink)
                : JavaEngine.extractStrings(job, path, minLen, maxResults, sink);
    }

    // ---- archives ----------------------------------------------------------
    public static String[] zipList(String path) {
        return NATIVE ? NativeLib.zipList(path) : JavaZip.list(path);
    }

    /** entry == null or "" extracts the whole archive. */
    public static int zipExtract(long job, String zip, String entry, String outDir, ProgressSink sink) {
        return NATIVE
                ? NativeLib.zipExtract(job, zip, entry, outDir, sink)
                : JavaZip.extract(job, zip, entry, outDir, sink);
    }

    public static byte[] zipRead(String zip, String entry, int maxBytes) {
        return NATIVE ? NativeLib.zipRead(zip, entry, maxBytes) : JavaZip.read(zip, entry, maxBytes);
    }

    public static String zipTest(long job, String zip, ProgressSink sink) {
        return NATIVE ? NativeLib.zipTest(job, zip, sink) : JavaZip.test(job, zip, sink);
    }

    // ---- apk / dex / axml --------------------------------------------------
    public static String apkInfo(String path) {
        return NATIVE ? NativeLib.apkInfo(path) : JavaApk.info(path);
    }

    public static String apkManifestXml(String apk) {
        return NATIVE ? NativeLib.apkManifestXml(apk) : JavaApk.manifestXml(apk);
    }

    public static String axmlToXml(String path) {
        return NATIVE ? NativeLib.axmlToXml(path) : JavaApk.axmlFileToXml(path);
    }

    public static String dexInfo(String path) {
        return NATIVE ? NativeLib.dexInfo(path) : JavaDex.info(path);
    }

    // ---- native binaries ---------------------------------------------------
    public static String elfInfo(String path, int maxSymbols) {
        return NATIVE ? NativeLib.elfInfo(path, maxSymbols) : JavaElf.info(path, maxSymbols);
    }

    // ---- search ------------------------------------------------------------
    public static int search(long job, String root, String namePattern, String content,
                             int flags, int maxResults, RowSink rows, ProgressSink progress) {
        return NATIVE
                ? NativeLib.search(job, root, namePattern, content, flags, maxResults, rows, progress)
                : JavaSearch.run(job, root, namePattern, content, flags, maxResults, rows, progress);
    }
}
