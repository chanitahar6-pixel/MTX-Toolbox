package app.mtx.toolbox.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Hashing front-end. MD5 / SHA-1 / SHA-224 / SHA-256 run in the native engine.
 * SHA-384 / SHA-512 use the platform {@link MessageDigest}, because the NDK
 * exposes no crypto library and re-implementing them would add risk for no gain.
 * Both paths are streaming: memory use does not grow with file size.
 */
public final class HashEngine {

    public static final String[] ALGORITHMS = {
            "MD5", "SHA-1", "SHA-224", "SHA-256", "SHA-384", "SHA-512"
    };

    private HashEngine() {}

    public static boolean isNative(String algorithm) {
        return nativeId(algorithm) >= 0;
    }

    private static int nativeId(String algorithm) {
        if ("MD5".equalsIgnoreCase(algorithm)) return Native.MD5;
        if ("SHA-1".equalsIgnoreCase(algorithm)) return Native.SHA1;
        if ("SHA-224".equalsIgnoreCase(algorithm)) return Native.SHA224;
        if ("SHA-256".equalsIgnoreCase(algorithm)) return Native.SHA256;
        return -1;
    }

    /**
     * @return lowercase hex digest
     * @throws Exception with the real reason on failure (never a generic message)
     */
    public static String hash(long job, String path, String algorithm, ProgressSink sink) throws Exception {
        int id = nativeId(algorithm);
        if (id >= 0) {
            String hex = Native.hashFile(job, path, id, sink);
            if (hex == null) throw new Exception(OpResult.safeLastError());
            return hex;
        }
        return javaHash(job, path, algorithm, sink);
    }

    private static String javaHash(long job, String path, String algorithm, ProgressSink sink)
            throws Exception {
        File file = new File(path);
        MessageDigest md = MessageDigest.getInstance(algorithm);
        long total = file.length();
        long done = 0;
        long start = System.currentTimeMillis();
        byte[] buf = new byte[256 * 1024];
        InputStream in = new FileInputStream(file);
        try {
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
                done += r;
                if (sink != null) {
                    long elapsed = Math.max(1, System.currentTimeMillis() - start);
                    sink.onProgress(path, done, total, done * 1000 / elapsed, 0, 1);
                }
            }
        } finally {
            in.close();
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
