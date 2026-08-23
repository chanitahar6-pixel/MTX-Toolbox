package app.mtx.toolbox.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure-Java archive engine, built on {@link ZipFile} so only the central directory
 * is read up front and entries are inflated on demand. Same guarantees as the C++
 * engine: streaming, cancellable, CRC-verified, and **path traversal is blocked**.
 */
final class JavaZip {

    private JavaZip() {}

    static String[] list(String path) {
        JavaEngine.clearError();
        ZipFile zip = null;
        try {
            zip = new ZipFile(new File(path));
            List<String> rows = new ArrayList<>();
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                StringBuilder sb = new StringBuilder();
                sb.append(e.getName()).append(JavaEngine.SEP)
                        .append(e.isDirectory() ? '1' : '0').append(JavaEngine.SEP)
                        .append(Math.max(0, e.getSize())).append(JavaEngine.SEP)
                        .append(Math.max(0, e.getCompressedSize())).append(JavaEngine.SEP)
                        .append(e.getTime()).append(JavaEngine.SEP)
                        .append(e.getMethod() < 0 ? 8 : e.getMethod()).append(JavaEngine.SEP)
                        .append('0').append(JavaEngine.SEP)
                        .append(Math.max(0, e.getCrc()));
                rows.add(sb.toString());
            }
            return rows.toArray(new String[0]);
        } catch (Throwable t) {
            JavaEngine.setError("cannot open archive: " + path
                    + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            return null;
        } finally {
            JavaEngine.closeQuietly(zip);
        }
    }

    static byte[] read(String path, String entryName, int maxBytes) {
        JavaEngine.clearError();
        if (maxBytes <= 0) {
            JavaEngine.setError("invalid size limit");
            return null;
        }
        ZipFile zip = null;
        InputStream in = null;
        try {
            zip = new ZipFile(new File(path));
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                JavaEngine.setError("entry not found in archive: " + entryName);
                return null;
            }
            if (entry.getSize() > maxBytes) {
                JavaEngine.setError("entry is larger than the allowed in-memory limit: " + entryName);
                return null;
            }
            in = zip.getInputStream(entry);
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = in.read(chunk)) > 0) {
                if (buffer.size() + read > maxBytes) {
                    JavaEngine.setError("entry exceeded the in-memory limit: " + entryName);
                    return null;
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (Throwable t) {
            JavaEngine.setError("cannot read entry " + entryName + ": "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        } finally {
            JavaEngine.closeQuietly(in);
            JavaEngine.closeQuietly(zip);
        }
    }

    /** entryName null or empty extracts the whole archive. */
    static int extract(long job, String path, String entryName, String outDir, ProgressSink sink) {
        JavaEngine.clearError();
        File root = new File(outDir);
        if (!root.isDirectory() && !root.mkdirs())
            return JavaEngine.fail(OpResult.E_IO, "cannot create output folder: " + outDir);

        String rootCanonical;
        try {
            rootCanonical = root.getCanonicalPath();
        } catch (IOException e) {
            return JavaEngine.fail(OpResult.E_IO, "cannot resolve output folder: " + outDir);
        }

        boolean single = entryName != null && !entryName.isEmpty();
        ZipFile zip = null;
        try {
            zip = new ZipFile(new File(path));

            long total = 0;
            long filesTotal = 0;
            Enumeration<? extends ZipEntry> scan = zip.entries();
            while (scan.hasMoreElements()) {
                ZipEntry e = scan.nextElement();
                if (single && !e.getName().equals(entryName)) continue;
                if (e.isDirectory()) continue;
                total += Math.max(0, e.getSize());
                filesTotal++;
            }
            if (single && filesTotal == 0)
                return JavaEngine.fail(OpResult.E_NOENT, "entry not found in archive: " + entryName);

            long done = 0;
            long filesDone = 0;
            long start = System.currentTimeMillis();
            long lastReport = 0;

            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                if (JavaEngine.cancelled(job))
                    return JavaEngine.fail(OpResult.E_CANCELLED, "cancelled by user");

                ZipEntry e = it.nextElement();
                if (single && !e.getName().equals(entryName)) continue;

                File target = safeTarget(root, rootCanonical, e.getName());
                if (target == null)
                    return JavaEngine.fail(OpResult.E_PERM, "path traversal blocked: " + e.getName());

                if (e.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs())
                        return JavaEngine.fail(OpResult.E_IO, "cannot create folder: " + target.getAbsolutePath());
                    continue;
                }

                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                    return JavaEngine.fail(OpResult.E_IO, "cannot create folder: " + parent.getAbsolutePath());

                InputStream in = null;
                OutputStream out = null;
                boolean failed = false;
                CRC32 crc = new CRC32();
                try {
                    in = zip.getInputStream(e);
                    if (in == null) {
                        failed = true;
                        return JavaEngine.fail(OpResult.E_CORRUPT, "cannot read entry: " + e.getName());
                    }
                    out = new FileOutputStream(target, false);
                    byte[] buf = new byte[JavaEngine.CHUNK];
                    int read;
                    while ((read = in.read(buf)) > 0) {
                        if (JavaEngine.cancelled(job)) {
                            failed = true;
                            return JavaEngine.fail(OpResult.E_CANCELLED, "cancelled by user");
                        }
                        out.write(buf, 0, read);
                        crc.update(buf, 0, read);
                        done += read;
                        if (sink != null) {
                            long now = System.currentTimeMillis();
                            if (now - lastReport >= JavaEngine.REPORT_MS) {
                                lastReport = now;
                                long elapsed = Math.max(1, now - start);
                                sink.onProgress(e.getName(), done, total,
                                        done * 1000 / elapsed, filesDone, filesTotal);
                            }
                        }
                    }
                    out.flush();
                } catch (IOException io) {
                    failed = true;
                    if (JavaEngine.isNoSpace(io))
                        return JavaEngine.fail(OpResult.E_NOSPC, "not enough storage space while extracting "
                                + e.getName());
                    return JavaEngine.fail(OpResult.E_CORRUPT, "failed to extract " + e.getName()
                            + ": " + io.getMessage());
                } finally {
                    JavaEngine.closeQuietly(in);
                    JavaEngine.closeQuietly(out);
                    if (failed) JavaEngine.deleteQuietly(target);
                }

                if (e.getCrc() >= 0 && crc.getValue() != e.getCrc()) {
                    JavaEngine.deleteQuietly(target);
                    return JavaEngine.fail(OpResult.E_CORRUPT,
                            "CRC mismatch, archive entry is damaged: " + e.getName());
                }
                if (e.getTime() > 0) target.setLastModified(e.getTime());
                filesDone++;
            }
            if (sink != null) sink.onProgress("", done, total, 0, filesDone, filesTotal);
            return OpResult.OK;
        } catch (Throwable t) {
            return JavaEngine.fail(OpResult.E_CORRUPT, "cannot open archive: " + path
                    + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        } finally {
            JavaEngine.closeQuietly(zip);
        }
    }

    static String test(long job, String path, ProgressSink sink) {
        JavaEngine.clearError();
        ZipFile zip = null;
        try {
            zip = new ZipFile(new File(path));
            long bad = 0;
            String firstBad = "";
            long index = 0;
            long count = zip.size();

            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                if (JavaEngine.cancelled(job)) {
                    JavaEngine.setError("cancelled by user");
                    return null;
                }
                ZipEntry e = it.nextElement();
                index++;
                if (e.isDirectory()) continue;

                CRC32 crc = new CRC32();
                InputStream in = null;
                boolean ok = true;
                try {
                    in = zip.getInputStream(e);
                    if (in == null) {
                        ok = false;
                    } else {
                        byte[] buf = new byte[JavaEngine.CHUNK];
                        int read;
                        while ((read = in.read(buf)) > 0) crc.update(buf, 0, read);
                    }
                } catch (Throwable t) {
                    ok = false;
                } finally {
                    JavaEngine.closeQuietly(in);
                }
                if (!ok || (e.getCrc() >= 0 && crc.getValue() != e.getCrc())) {
                    bad++;
                    if (firstBad.isEmpty()) firstBad = e.getName();
                }
                if (sink != null) sink.onProgress(e.getName(), index, count, 0, index, count);
            }
            return "badEntries=" + bad + "\nfirstBad=" + firstBad + "\n";
        } catch (Throwable t) {
            JavaEngine.setError("cannot open archive: " + path
                    + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
            return null;
        } finally {
            JavaEngine.closeQuietly(zip);
        }
    }

    /** Cheap probe used by the file type analyzer: manifest plus dex or resources. */
    static boolean looksLikeApk(String path) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(new File(path));
            boolean manifest = zip.getEntry("AndroidManifest.xml") != null;
            if (!manifest) return false;
            if (zip.getEntry("resources.arsc") != null) return true;
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                String name = it.nextElement().getName();
                if (name.endsWith(".dex")) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            JavaEngine.closeQuietly(zip);
        }
    }

    /**
     * Resolves an entry name inside the output folder, rejecting absolute paths,
     * {@code ..} segments and anything that escapes the destination root.
     */
    private static File safeTarget(File root, String rootCanonical, String entryName) {
        if (entryName == null || entryName.isEmpty()) return null;
        if (entryName.startsWith("/") || entryName.contains(":\\")) return null;

        StringBuilder clean = new StringBuilder();
        String[] parts = entryName.split("[/\\\\]");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) return null;
            if (clean.length() > 0) clean.append('/');
            clean.append(part);
        }
        if (clean.length() == 0) return null;

        File target = new File(root, clean.toString());
        try {
            String canonical = target.getCanonicalPath();
            if (!canonical.equals(rootCanonical) && !canonical.startsWith(rootCanonical + File.separator))
                return null;
        } catch (IOException e) {
            return null;
        }
        return target;
    }
}
