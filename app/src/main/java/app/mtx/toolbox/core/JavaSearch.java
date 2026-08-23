package app.mtx.toolbox.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure-Java search engine. Same behaviour as the C++ one: wildcard name matching,
 * streaming content grep with an overlap window so matches on chunk borders are not
 * lost, optional archive entry search, and cancellation at every step. Results are
 * pushed to the {@link RowSink} as they are found, never collected first.
 */
final class JavaSearch {

    private JavaSearch() {}

    private static final long MAX_CONTENT_FILE = 64L * 1024 * 1024;
    private static final int MAX_DEPTH = 64;

    static int run(long job, String root, String namePattern, String content,
                   int flags, int maxResults, RowSink rows, ProgressSink progress) {
        JavaEngine.clearError();
        File start = new File(root);
        if (!start.exists()) return JavaEngine.fail(OpResult.E_NOENT, "not found: " + root);

        String pattern = namePattern == null ? "" : namePattern;
        String needle = content == null ? "" : content;
        if (pattern.isEmpty() && needle.isEmpty())
            return JavaEngine.fail(OpResult.E_RANGE, "nothing to search for");

        Runner runner = new Runner(job, flags, maxResults <= 0 ? 5000 : maxResults, rows, progress);
        runner.pattern = pattern;
        runner.needle = runner.caseSensitive ? needle : needle.toLowerCase();

        if (start.isDirectory()) runner.walk(start, 0);
        else if (runner.contentSearch()) runner.grep(start);

        if (progress != null)
            progress.onProgress("", runner.scanned, runner.scanned, 0, runner.hits, runner.hits);
        if (JavaEngine.cancelled(job)) return JavaEngine.fail(OpResult.E_CANCELLED, "cancelled by user");
        return OpResult.OK;
    }

    private static final class Runner {
        final long job;
        final int maxResults;
        final RowSink rows;
        final ProgressSink progress;
        final boolean recursive;
        final boolean caseSensitive;
        final boolean archives;
        final boolean hidden;
        final boolean wantContent;

        String pattern = "";
        String needle = "";
        int hits;
        long scanned;
        long lastReport;

        Runner(long job, int flags, int maxResults, RowSink rows, ProgressSink progress) {
            this.job = job;
            this.maxResults = maxResults;
            this.rows = rows;
            this.progress = progress;
            this.recursive = (flags & Native.SEARCH_RECURSIVE) != 0;
            this.caseSensitive = (flags & Native.SEARCH_CASE) != 0;
            this.archives = (flags & Native.SEARCH_ARCHIVES) != 0;
            this.hidden = (flags & Native.SEARCH_HIDDEN) != 0;
            this.wantContent = (flags & Native.SEARCH_CONTENT) != 0;
        }

        boolean contentSearch() { return wantContent && !needle.isEmpty(); }

        boolean done() { return hits >= maxResults || JavaEngine.cancelled(job); }

        void emit(String path, String preview, long line, long size) {
            if (rows != null) rows.onRow(path, preview, line, size);
            hits++;
        }

        void tick(String where) {
            if (progress == null) return;
            long now = System.currentTimeMillis();
            if (now - lastReport < JavaEngine.REPORT_MS) return;
            lastReport = now;
            progress.onProgress(where, scanned, -1, 0, hits, -1);
        }

        void walk(File dir, int depth) {
            if (done()) return;
            File[] children = dir.listFiles();
            if (children == null) return;   // unreadable folder: skip, never crash

            for (int i = 0; i < children.length; i++) {
                if (done()) return;
                File child = children[i];
                String name = child.getName();
                if (!hidden && name.startsWith(".")) continue;
                scanned++;
                tick(child.getAbsolutePath());

                if (child.isDirectory()) {
                    if (!contentSearch() && nameMatches(name)) emit(child.getAbsolutePath(), "folder", -1, -1);
                    if (recursive && depth < MAX_DEPTH) walk(child, depth + 1);
                    continue;
                }

                boolean nameHit = nameMatches(name);
                if (contentSearch()) {
                    if (!pattern.isEmpty() && !nameHit) continue;
                    if (child.length() <= MAX_CONTENT_FILE) grep(child);
                    continue;
                }
                if (!nameHit) continue;

                emit(child.getAbsolutePath(), "", -1, child.length());

                if (archives) {
                    String ext = JavaFileType.extensionOf(name);
                    if ("zip".equals(ext) || "apk".equals(ext) || "jar".equals(ext)
                            || "apks".equals(ext) || "xapk".equals(ext) || "apkm".equals(ext)
                            || "aab".equals(ext)) {
                        searchArchive(child);
                    }
                }
            }
        }

        boolean nameMatches(String name) {
            return pattern.isEmpty() || wildcardMatch(pattern, name, caseSensitive);
        }

        void searchArchive(File archive) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(archive);
                Enumeration<? extends ZipEntry> it = zip.entries();
                while (it.hasMoreElements()) {
                    if (done()) return;
                    ZipEntry e = it.nextElement();
                    String base = e.getName();
                    int slash = base.lastIndexOf('/');
                    if (slash >= 0) base = base.substring(slash + 1);
                    if (base.isEmpty()) continue;
                    if (nameMatches(base))
                        emit(archive.getAbsolutePath() + "!/" + e.getName(), "archive entry",
                                -1, Math.max(0, e.getSize()));
                }
            } catch (Throwable t) {
                // A broken archive is skipped, it never aborts the search.
            } finally {
                JavaEngine.closeQuietly(zip);
            }
        }

        /** Streaming grep with an overlap window, so border matches survive. */
        void grep(File file) {
            if (needle.isEmpty()) return;
            int plen = needle.length();
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                byte[] buf = new byte[JavaEngine.CHUNK + plen];
                int carry = 0;
                long lineBase = 1;
                long absolute = 0;

                while (!done()) {
                    int read = in.read(buf, carry, JavaEngine.CHUNK);
                    int available = carry + Math.max(0, read);
                    if (available < plen) break;

                    int limit = available - plen + 1;
                    for (int i = 0; i < limit && !done(); i++) {
                        if (!matchAt(buf, i, plen)) continue;

                        long line = lineBase;
                        for (int k = 0; k < i; k++) if (buf[k] == '\n') line++;

                        int lineStart = i;
                        while (lineStart > 0 && buf[lineStart - 1] != '\n') lineStart--;
                        int lineEnd = i;
                        while (lineEnd < available && buf[lineEnd] != '\n' && lineEnd - lineStart < 300) lineEnd++;

                        String preview = new String(buf, lineStart, lineEnd - lineStart, "UTF-8");
                        emit(file.getAbsolutePath(), preview, line, absolute + i);
                    }

                    if (read <= 0) break;
                    for (int k = 0; k < limit; k++) if (buf[k] == '\n') lineBase++;
                    carry = available - limit;
                    System.arraycopy(buf, limit, buf, 0, carry);
                    absolute += limit;
                    scanned += read;
                    tick(file.getAbsolutePath());
                }
            } catch (IOException e) {
                // Unreadable file: skipped silently, the scan continues.
            } finally {
                JavaEngine.closeQuietly(in);
            }
        }

        private boolean matchAt(byte[] buf, int at, int plen) {
            for (int k = 0; k < plen; k++) {
                char c = (char) (buf[at + k] & 0xFF);
                if (!caseSensitive && c >= 'A' && c <= 'Z') c = (char) (c + 32);
                if (c != needle.charAt(k)) return false;
            }
            return true;
        }
    }

    /**
     * Wildcard matcher supporting {@code *} and {@code ?}. A bare term without any
     * wildcard behaves like {@code *term*}, which is what a search box implies.
     */
    static boolean wildcardMatch(String patternIn, String textIn, boolean caseSensitive) {
        String pattern = caseSensitive ? patternIn : patternIn.toLowerCase();
        String text = caseSensitive ? textIn : textIn.toLowerCase();
        if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) return text.contains(pattern);

        int p = 0;
        int s = 0;
        int star = -1;
        int mark = 0;
        while (s < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == text.charAt(s))) {
                p++;
                s++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = s;
            } else if (star >= 0) {
                p = star + 1;
                s = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}
