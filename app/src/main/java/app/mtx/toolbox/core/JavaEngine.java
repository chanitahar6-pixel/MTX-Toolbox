package app.mtx.toolbox.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-Java implementation of the file, hash, hex and comparison engines.
 *
 * <p>This is the fallback {@link Native} uses when {@code libmtxcore.so} is not in
 * the APK. It mirrors the C++ behaviour that matters:
 * <ul>
 *   <li>streaming, chunked IO: a file is never loaded whole into memory;</li>
 *   <li>cancellation checked on every chunk and every directory entry;</li>
 *   <li>progress reported through the same {@link ProgressSink};</li>
 *   <li>the same {@link OpResult} codes and the same separated row format.</li>
 * </ul>
 */
final class JavaEngine {

    private JavaEngine() {}

    static final char SEP = '\u0001';
    static final int CHUNK = 256 * 1024;
    static final long REPORT_MS = 120;

    // ---- error channel -----------------------------------------------------
    private static final ThreadLocal<String> ERROR = new ThreadLocal<>();

    static void setError(String message) { ERROR.set(message); }

    static void clearError() { ERROR.remove(); }

    static String lastError() {
        String e = ERROR.get();
        return e == null ? "" : e;
    }

    static int fail(int code, String message) {
        setError(message);
        return code;
    }

    static int failIo(String op, File file, Throwable t) {
        String detail = t == null ? "" : " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        String path = file == null ? "" : file.getAbsolutePath();
        if (file != null && !file.exists()) return fail(OpResult.E_NOENT, op + " failed: not found " + path);
        if (file != null && !file.canRead()) return fail(OpResult.E_PERM, op + " failed: no access to " + path);
        return fail(OpResult.E_IO, op + " failed: " + path + detail);
    }

    // ---- cancellation registry --------------------------------------------
    private static final ConcurrentHashMap<Long, AtomicBoolean> JOBS = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_JOB = new AtomicLong(1);

    static long newJob() {
        long id = NEXT_JOB.getAndIncrement();
        JOBS.put(Long.valueOf(id), new AtomicBoolean(false));
        return id;
    }

    static void cancelJob(long id) {
        AtomicBoolean flag = JOBS.get(Long.valueOf(id));
        if (flag != null) flag.set(true);
    }

    static void releaseJob(long id) { JOBS.remove(Long.valueOf(id)); }

    static boolean cancelled(long id) {
        if (id <= 0) return false;
        AtomicBoolean flag = JOBS.get(Long.valueOf(id));
        return flag != null && flag.get();
    }

    // ---- progress helper --------------------------------------------------
    private static final class Reporter {
        final ProgressSink sink;
        final long start = System.currentTimeMillis();
        long last;
        long done;
        long total = -1;
        long filesDone;
        long filesTotal = -1;

        Reporter(ProgressSink sink) { this.sink = sink; }

        void tick(String current, boolean force) {
            if (sink == null) return;
            long now = System.currentTimeMillis();
            if (!force && now - last < REPORT_MS) return;
            last = now;
            long elapsed = Math.max(1, now - start);
            sink.onProgress(current, done, total, done * 1000 / elapsed, filesDone, filesTotal);
        }
    }

    // ---- rows -------------------------------------------------------------
    private static String row(File f, String name) {
        boolean dir = f.isDirectory();
        long size = dir ? 0 : f.length();
        int mode = 0;
        if (f.canRead()) mode |= 0444;
        if (f.canWrite()) mode |= 0222;
        if (f.canExecute()) mode |= 0111;

        StringBuilder sb = new StringBuilder(name.length() + 48);
        sb.append(name).append(SEP)
                .append(dir ? '1' : '0').append(SEP)
                .append(size).append(SEP)
                .append(f.lastModified()).append(SEP)
                .append(mode).append(SEP)
                .append(isSymlink(f) ? '1' : '0').append(SEP)
                .append(f.canRead() ? '1' : '0').append(SEP)
                .append(f.canWrite() ? '1' : '0');
        return sb.toString();
    }

    /**
     * Symlink probe that works on every API level: a link resolves to a canonical
     * path different from its own location. Good enough for display purposes.
     */
    static boolean isSymlink(File f) {
        try {
            File parent = f.getParentFile();
            if (parent == null) return false;
            File candidate = new File(parent.getCanonicalFile(), f.getName());
            return !candidate.getCanonicalFile().equals(candidate.getAbsoluteFile());
        } catch (Throwable t) {
            return false;
        }
    }

    static String[] listDir(String path) {
        clearError();
        File dir = new File(path);
        if (!dir.exists()) {
            setError("not found: " + path);
            return null;
        }
        if (!dir.isDirectory()) {
            setError("not a folder: " + path);
            return null;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            setError("cannot read folder (permission denied): " + path);
            return null;
        }
        String[] rows = new String[children.length];
        for (int i = 0; i < children.length; i++) rows[i] = row(children[i], children[i].getName());
        return rows;
    }

    static String statPath(String path) {
        clearError();
        File f = new File(path);
        if (!f.exists()) {
            setError("not found: " + path);
            return null;
        }
        String name = f.getName();
        return row(f, name.isEmpty() ? path : name);
    }

    // ---- copy / move / delete ---------------------------------------------
    static int copyPath(long job, String src, String dstDir, boolean overwrite, ProgressSink sink) {
        clearError();
        File source = new File(src);
        File destDir = new File(dstDir);
        if (!source.exists()) return fail(OpResult.E_NOENT, "source not found: " + src);
        if (!destDir.isDirectory() && !destDir.mkdirs())
            return fail(OpResult.E_IO, "cannot create destination folder: " + dstDir);

        File target = new File(destDir, source.getName());
        if (source.isDirectory() && isInside(source, target))
            return fail(OpResult.E_UNSUPPORTED, "cannot copy a folder into itself");

        Reporter reporter = new Reporter(sink);
        long[] stats = new long[3];
        measure(job, source, stats);
        reporter.total = stats[0];
        reporter.filesTotal = stats[1];
        reporter.tick(src, true);

        int code = copyInto(job, source, target, overwrite, reporter);
        reporter.tick(src, true);
        return code;
    }

    private static int copyInto(long job, File source, File target, boolean overwrite, Reporter reporter) {
        if (cancelled(job)) return fail(OpResult.E_CANCELLED, "cancelled by user");

        if (source.isDirectory()) {
            if (!target.isDirectory() && !target.mkdirs())
                return fail(OpResult.E_IO, "cannot create folder: " + target.getAbsolutePath());
            File[] children = source.listFiles();
            if (children == null)
                return fail(OpResult.E_PERM, "cannot read folder: " + source.getAbsolutePath());
            for (int i = 0; i < children.length; i++) {
                int code = copyInto(job, children[i], new File(target, children[i].getName()),
                        overwrite, reporter);
                if (!OpResult.isOk(code)) return code;
            }
            return OpResult.OK;
        }

        if (!overwrite && target.exists())
            return fail(OpResult.E_EXISTS, "destination already exists: " + target.getAbsolutePath());

        InputStream in = null;
        OutputStream out = null;
        boolean failed = false;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(target, false);
            byte[] buf = new byte[CHUNK];
            int read;
            while ((read = in.read(buf)) > 0) {
                if (cancelled(job)) {
                    failed = true;
                    return fail(OpResult.E_CANCELLED, "cancelled by user");
                }
                out.write(buf, 0, read);
                reporter.done += read;
                reporter.tick(source.getAbsolutePath(), false);
            }
            out.flush();
        } catch (IOException e) {
            failed = true;
            if (isNoSpace(e))
                return fail(OpResult.E_NOSPC, "not enough storage space for " + target.getAbsolutePath());
            return failIo("copy", source, e);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            // Never leave a half-written file behind.
            if (failed) deleteQuietly(target);
        }
        target.setLastModified(source.lastModified());
        reporter.filesDone++;
        reporter.tick(source.getAbsolutePath(), true);
        return OpResult.OK;
    }

    static int movePath(long job, String src, String dstDir, boolean overwrite, ProgressSink sink) {
        clearError();
        File source = new File(src);
        File destDir = new File(dstDir);
        if (!source.exists()) return fail(OpResult.E_NOENT, "source not found: " + src);
        if (!destDir.isDirectory() && !destDir.mkdirs())
            return fail(OpResult.E_IO, "cannot create destination folder: " + dstDir);

        File target = new File(destDir, source.getName());
        if (!overwrite && target.exists())
            return fail(OpResult.E_EXISTS, "destination already exists: " + target.getAbsolutePath());
        if (overwrite && target.isFile()) deleteQuietly(target);

        if (source.renameTo(target)) {
            if (sink != null) sink.onProgress(src, 1, 1, 0, 1, 1);
            return OpResult.OK;
        }
        // Different volume, or a rename the kernel refuses: copy, then remove.
        int code = copyPath(job, src, dstDir, overwrite, sink);
        if (!OpResult.isOk(code)) return code;
        return deletePath(job, src, null);
    }

    static int deletePath(long job, String path, ProgressSink sink) {
        clearError();
        File f = new File(path);
        if (!f.exists()) return fail(OpResult.E_NOENT, "not found: " + path);
        return deleteTree(job, f, sink);
    }

    private static int deleteTree(long job, File f, ProgressSink sink) {
        if (cancelled(job)) return fail(OpResult.E_CANCELLED, "cancelled by user");
        if (f.isDirectory() && !isSymlink(f)) {
            File[] children = f.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    int code = deleteTree(job, children[i], sink);
                    if (!OpResult.isOk(code)) return code;
                }
            }
        }
        if (!f.delete() && f.exists())
            return fail(OpResult.E_PERM, "cannot delete: " + f.getAbsolutePath());
        if (sink != null) sink.onProgress(f.getAbsolutePath(), 0, -1, 0, 0, -1);
        return OpResult.OK;
    }

    static int mkdirs(String path) {
        clearError();
        File f = new File(path);
        if (f.isDirectory()) return OpResult.OK;
        if (f.exists()) return fail(OpResult.E_EXISTS, "path exists and is not a folder: " + path);
        if (f.mkdirs()) return OpResult.OK;
        return fail(OpResult.E_PERM, "cannot create folder: " + path);
    }

    static int createFile(String path) {
        clearError();
        File f = new File(path);
        if (f.exists()) return fail(OpResult.E_EXISTS, "already exists: " + path);
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                return fail(OpResult.E_PERM, "cannot create parent folder of " + path);
            if (f.createNewFile()) return OpResult.OK;
            return fail(OpResult.E_IO, "cannot create file: " + path);
        } catch (IOException e) {
            return failIo("create", f, e);
        }
    }

    static int renamePath(String from, String to) {
        clearError();
        File source = new File(from);
        File target = new File(to);
        if (!source.exists()) return fail(OpResult.E_NOENT, "not found: " + from);
        if (target.exists()) return fail(OpResult.E_EXISTS, "name already used: " + target.getName());
        if (source.renameTo(target)) return OpResult.OK;
        return fail(OpResult.E_PERM, "cannot rename: " + from);
    }

    static long[] diskUsage(String path) {
        clearError();
        File f = new File(path);
        File probe = f.exists() ? f : f.getParentFile();
        if (probe == null) {
            setError("cannot resolve volume for " + path);
            return null;
        }
        long total = probe.getTotalSpace();
        if (total <= 0) {
            setError("volume information unavailable for " + path);
            return null;
        }
        return new long[]{total, probe.getFreeSpace(), probe.getUsableSpace()};
    }

    static long[] treeStats(long job, String path, ProgressSink sink) {
        clearError();
        File f = new File(path);
        if (!f.exists()) {
            setError("not found: " + path);
            return null;
        }
        long[] stats = new long[3];
        measure(job, f, stats);
        if (sink != null) sink.onProgress(path, stats[0], stats[0], 0, stats[1], stats[1]);
        return new long[]{stats[0], stats[1], stats[2]};
    }

    /** stats = {bytes, files, dirs}. Unreadable subtrees are skipped, never fatal. */
    private static void measure(long job, File f, long[] stats) {
        if (cancelled(job)) return;
        if (f.isDirectory()) {
            stats[2]++;
            File[] children = f.listFiles();
            if (children == null) return;
            for (int i = 0; i < children.length; i++) measure(job, children[i], stats);
            return;
        }
        stats[0] += f.length();
        stats[1]++;
    }

    static long[] compareFiles(long job, String a, String b, ProgressSink sink) {
        clearError();
        File fa = new File(a);
        File fb = new File(b);
        if (!fa.isFile() || !fb.isFile()) {
            setError("both paths must be readable files");
            return null;
        }
        InputStream ia = null;
        InputStream ib = null;
        try {
            ia = new FileInputStream(fa);
            ib = new FileInputStream(fb);
            byte[] ba = new byte[CHUNK];
            byte[] bb = new byte[CHUNK];
            long offset = 0;
            long total = Math.min(fa.length(), fb.length());
            long start = System.currentTimeMillis();
            long lastReport = 0;

            while (true) {
                if (cancelled(job)) {
                    setError("cancelled by user");
                    return null;
                }
                int ra = fill(ia, ba);
                int rb = fill(ib, bb);
                int n = Math.min(ra, rb);
                for (int i = 0; i < n; i++) {
                    if (ba[i] != bb[i]) return new long[]{offset + i, 0};
                }
                if (ra != rb) return new long[]{offset + n, 0};
                if (ra == 0) {
                    boolean identical = fa.length() == fb.length();
                    return new long[]{identical ? -1 : total, identical ? 1 : 0};
                }
                offset += n;
                if (sink != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= REPORT_MS) {
                        lastReport = now;
                        long elapsed = Math.max(1, now - start);
                        sink.onProgress(a, offset, total, offset * 1000 / elapsed, 0, 2);
                    }
                }
            }
        } catch (IOException e) {
            failIo("compare", fa, e);
            return null;
        } finally {
            closeQuietly(ia);
            closeQuietly(ib);
        }
    }

    // ---- hashing -----------------------------------------------------------
    static String hashFile(long job, String path, int algo, ProgressSink sink) {
        clearError();
        String name;
        switch (algo) {
            case Native.MD5: name = "MD5"; break;
            case Native.SHA1: name = "SHA-1"; break;
            case Native.SHA224: name = "SHA-224"; break;
            default: name = "SHA-256"; break;
        }
        File f = new File(path);
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance(name);
            in = new FileInputStream(f);
            byte[] buf = new byte[CHUNK];
            long done = 0;
            long total = f.length();
            long start = System.currentTimeMillis();
            long lastReport = 0;
            int read;
            while ((read = in.read(buf)) > 0) {
                if (cancelled(job)) {
                    setError("cancelled by user");
                    return null;
                }
                md.update(buf, 0, read);
                done += read;
                if (sink != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= REPORT_MS) {
                        lastReport = now;
                        long elapsed = Math.max(1, now - start);
                        sink.onProgress(path, done, total, done * 1000 / elapsed, 0, 1);
                    }
                }
            }
            if (sink != null) sink.onProgress(path, done, total, 0, 1, 1);
            return hex(md.digest());
        } catch (Throwable t) {
            setError(name + " failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (int i = 0; i < data.length; i++) {
            int b = data[i] & 0xFF;
            sb.append(Character.forDigit(b >> 4, 16));
            sb.append(Character.forDigit(b & 0x0F, 16));
        }
        return sb.toString();
    }

    // ---- hex / binary ------------------------------------------------------
    static byte[] hexRead(String path, long offset, int len) {
        clearError();
        if (offset < 0 || len <= 0) {
            setError("invalid offset or length");
            return null;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(path, "r");
            long size = raf.length();
            if (offset >= size) return new byte[0];
            int want = (int) Math.min((long) len, size - offset);
            byte[] out = new byte[want];
            raf.seek(offset);
            int got = readUpTo(raf, out, want);
            if (got == want) return out;
            byte[] trimmed = new byte[Math.max(0, got)];
            System.arraycopy(out, 0, trimmed, 0, trimmed.length);
            return trimmed;
        } catch (Throwable t) {
            failIo("read", new File(path), t);
            return null;
        } finally {
            closeQuietly(raf);
        }
    }

    static int hexWrite(String path, long offset, byte[] data) {
        clearError();
        if (data == null || data.length == 0) return fail(OpResult.E_RANGE, "nothing to write");
        if (offset < 0) return fail(OpResult.E_RANGE, "negative offset");
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(path, "rw");
            if (offset + data.length > raf.length())
                return fail(OpResult.E_RANGE, "edit would grow the file; in-place edit only");
            raf.seek(offset);
            raf.write(data);
            return OpResult.OK;
        } catch (Throwable t) {
            return failIo("write", new File(path), t);
        } finally {
            closeQuietly(raf);
        }
    }

    static long hexFind(long job, String path, long from, byte[] pattern, boolean backwards) {
        clearError();
        if (pattern == null || pattern.length == 0) {
            setError("empty pattern");
            return -1;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(path, "r");
            long size = raf.length();
            int plen = pattern.length;
            if (plen > size) return -1;
            byte[] window = new byte[CHUNK + plen - 1];

            if (!backwards) {
                long pos = Math.max(0, from);
                while (pos < size) {
                    if (cancelled(job)) {
                        setError("cancelled by user");
                        return -1;
                    }
                    int want = (int) Math.min((long) window.length, size - pos);
                    raf.seek(pos);
                    int got = readUpTo(raf, window, want);
                    if (got < plen) return -1;
                    for (int i = 0; i + plen <= got; i++) {
                        if (matches(window, i, pattern)) return pos + i;
                    }
                    pos += CHUNK;
                }
                return -1;
            }

            long end = (from < 0 || from > size) ? size : from;
            while (end > 0) {
                if (cancelled(job)) {
                    setError("cancelled by user");
                    return -1;
                }
                long start = Math.max(0, end - CHUNK);
                int want = (int) Math.min((long) window.length, size - start);
                raf.seek(start);
                int got = readUpTo(raf, window, want);
                for (int i = got - plen; i >= 0; i--) {
                    if (matches(window, i, pattern)) return start + i;
                }
                if (start == 0) return -1;
                end = start;
            }
            return -1;
        } catch (Throwable t) {
            failIo("search", new File(path), t);
            return -1;
        } finally {
            closeQuietly(raf);
        }
    }

    private static boolean matches(byte[] haystack, int at, byte[] needle) {
        for (int k = 0; k < needle.length; k++) {
            if (haystack[at + k] != needle[k]) return false;
        }
        return true;
    }

    private static int readUpTo(RandomAccessFile raf, byte[] buf, int want) throws IOException {
        int got = 0;
        while (got < want) {
            int r = raf.read(buf, got, want - got);
            if (r <= 0) break;
            got += r;
        }
        return got;
    }

    static int extractStrings(long job, String path, int minLen, int maxResults, RowSink sink) {
        clearError();
        int min = Math.max(2, minLen);
        int limit = maxResults <= 0 ? 1000 : maxResults;
        InputStream in = null;
        try {
            in = new FileInputStream(path);
            byte[] buf = new byte[CHUNK];
            StringBuilder current = new StringBuilder();
            long position = 0;
            long currentStart = 0;
            int found = 0;
            int read;
            while ((read = in.read(buf)) > 0) {
                if (cancelled(job)) return fail(OpResult.E_CANCELLED, "cancelled by user");
                for (int i = 0; i < read; i++) {
                    int c = buf[i] & 0xFF;
                    boolean printable = (c >= 0x20 && c < 0x7F) || c == '\t';
                    if (printable) {
                        if (current.length() == 0) currentStart = position + i;
                        current.append((char) c);
                        continue;
                    }
                    if (current.length() >= min && sink != null) {
                        sink.onRow(current.toString(), "", currentStart, current.length());
                        if (++found >= limit) return OpResult.OK;
                    }
                    current.setLength(0);
                }
                position += read;
            }
            if (current.length() >= min && sink != null && found < limit)
                sink.onRow(current.toString(), "", currentStart, current.length());
            return OpResult.OK;
        } catch (IOException e) {
            return failIo("read", new File(path), e);
        } finally {
            closeQuietly(in);
        }
    }

    // ---- shared helpers ---------------------------------------------------
    static int fill(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int r = in.read(buf, got, buf.length - got);
            if (r <= 0) break;
            got += r;
        }
        return got;
    }

    static boolean isInside(File parent, File candidate) {
        try {
            String p = parent.getCanonicalPath();
            String c = candidate.getCanonicalPath();
            return c.equals(p) || c.startsWith(p + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    static boolean isNoSpace(IOException e) {
        String m = e.getMessage();
        if (m == null) return false;
        String lower = m.toLowerCase();
        return lower.contains("enospc") || lower.contains("no space");
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {
        }
    }
}
