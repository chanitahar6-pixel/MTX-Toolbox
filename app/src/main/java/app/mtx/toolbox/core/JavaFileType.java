package app.mtx.toolbox.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Pure-Java file type analyzer. Same contract as the C++ {@code ftype} engine:
 * magic bytes decide, the extension is only a tie-breaker, and the result lists
 * every tool that can legitimately open the file. Nothing is ever executed.
 */
final class JavaFileType {

    private JavaFileType() {}

    private static final int HEAD = 4096;

    static String analyze(String path) {
        JavaEngine.clearError();
        File f = new File(path);
        if (!f.exists()) {
            JavaEngine.setError("not found: " + path);
            return null;
        }

        StringBuilder out = new StringBuilder();
        if (f.isDirectory()) {
            kv(out, "kind", "folder");
            kv(out, "mime", "inode/directory");
            kv(out, "description", "Folder");
            kv(out, "magic", "");
            kv(out, "encoding", "");
            kv(out, "size", "0");
            kv(out, "tool", "open");
            return out.toString();
        }

        byte[] head = new byte[HEAD];
        int n = 0;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            n = JavaEngine.fill(in, head);
        } catch (Throwable t) {
            JavaEngine.setError("cannot read: " + path);
            return null;
        } finally {
            JavaEngine.closeQuietly(in);
        }

        String ext = extensionOf(f.getName());
        String magic = JavaEngine.hex(copy(head, Math.min(n, 16)));

        String kind;
        String mime;
        String description;
        String encoding = "binary";
        String[] tools;

        if (starts(head, n, "PK\u0003\u0004") || starts(head, n, "PK\u0005\u0006")
                || starts(head, n, "PK\u0007\u0008")) {
            if (JavaZip.looksLikeApk(path)) {
                kind = "apk";
                mime = "application/vnd.android.package-archive";
                description = "Android package (APK)";
                tools = new String[]{"apk", "archive", "hex"};
            } else if ("jar".equals(ext)) {
                kind = "jar";
                mime = "application/java-archive";
                description = "Java archive";
                tools = new String[]{"archive", "hex"};
            } else if ("apks".equals(ext) || "apkm".equals(ext) || "xapk".equals(ext)) {
                kind = "apk-bundle";
                mime = "application/octet-stream";
                description = "Split APK bundle (" + ext + ")";
                tools = new String[]{"archive", "apk", "hex"};
            } else if ("aab".equals(ext)) {
                kind = "aab";
                mime = "application/octet-stream";
                description = "Android App Bundle";
                tools = new String[]{"archive", "hex"};
            } else {
                kind = "zip";
                mime = "application/zip";
                description = "ZIP archive";
                tools = new String[]{"archive", "hex"};
            }
        } else if (starts(head, n, "dex\n")) {
            kind = "dex";
            mime = "application/x-dex";
            description = "Dalvik executable";
            tools = new String[]{"dex", "hex"};
        } else if (n >= 4 && (head[0] & 0xFF) == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
            kind = "elf";
            mime = "application/x-sharedlib";
            description = "ELF binary / shared object";
            tools = new String[]{"elf", "binary", "hex"};
        } else if (n >= 4 && head[0] == 0x03 && head[1] == 0x00 && head[2] == 0x08 && head[3] == 0x00) {
            kind = "axml";
            mime = "application/octet-stream";
            description = "Android binary XML";
            tools = new String[]{"axml", "hex"};
        } else if (n >= 4 && head[0] == 0x02 && head[1] == 0x00 && head[2] == 0x0C && head[3] == 0x00) {
            kind = "arsc";
            mime = "application/octet-stream";
            description = "Android resource table (resources.arsc)";
            tools = new String[]{"hex"};
        } else if (starts(head, n, "\u0089PNG")) {
            kind = "png"; mime = "image/png"; description = "PNG image";
            tools = new String[]{"image", "hex"};
        } else if (n >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            kind = "jpeg"; mime = "image/jpeg"; description = "JPEG image";
            tools = new String[]{"image", "hex"};
        } else if (starts(head, n, "GIF8")) {
            kind = "gif"; mime = "image/gif"; description = "GIF image";
            tools = new String[]{"image", "hex"};
        } else if (starts(head, n, "%PDF")) {
            kind = "pdf"; mime = "application/pdf"; description = "PDF document";
            tools = new String[]{"external", "hex"};
        } else if (starts(head, n, "SQLite format 3")) {
            kind = "sqlite"; mime = "application/vnd.sqlite3"; description = "SQLite database";
            tools = new String[]{"hex"};
        } else if (starts(head, n, "7z")) {
            kind = "7z"; mime = "application/x-7z-compressed"; description = "7-Zip archive";
            tools = new String[]{"hex"};
        } else if (n >= 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
            kind = "gz"; mime = "application/gzip"; description = "GZIP stream";
            tools = new String[]{"hex"};
        } else if (starts(head, n, "BZh")) {
            kind = "bz2"; mime = "application/x-bzip2"; description = "BZIP2 archive";
            tools = new String[]{"hex"};
        } else if (starts(head, n, "Rar!")) {
            kind = "rar"; mime = "application/vnd.rar"; description = "RAR archive";
            tools = new String[]{"hex"};
        } else if (n >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            kind = "mp4"; mime = "video/mp4"; description = "ISO media (MP4/3GP)";
            tools = new String[]{"media", "hex"};
        } else {
            // Not a known binary signature: decide text vs binary.
            encoding = sniffEncoding(head, n);
            if ("binary".equals(encoding)) {
                kind = "binary";
                mime = "application/octet-stream";
                description = "Binary data";
                tools = new String[]{"hex", "binary"};
            } else {
                int i = 0;
                while (i < n && isSpace(head[i])) i++;
                char first = i < n ? (char) (head[i] & 0xFF) : ' ';
                if (first == '{' || first == '[') {
                    kind = "json"; mime = "application/json"; description = "JSON document";
                    tools = new String[]{"json", "text", "hex"};
                } else if (first == '<') {
                    kind = "xml"; mime = "text/xml"; description = "XML document";
                    tools = new String[]{"xml", "text", "hex"};
                } else if ("smali".equals(ext)) {
                    kind = "smali"; mime = "text/x-smali"; description = "Smali source";
                    tools = new String[]{"smali", "text", "hex"};
                } else {
                    kind = "text"; mime = "text/plain"; description = "Text file";
                    tools = new String[]{"text", "hex"};
                }
            }
        }

        kv(out, "kind", kind);
        kv(out, "mime", mime);
        kv(out, "description", description);
        kv(out, "magic", magic);
        kv(out, "encoding", encoding);
        kv(out, "size", String.valueOf(f.length()));
        for (int i = 0; i < tools.length; i++) kv(out, "tool", tools[i]);
        return out.toString();
    }

    private static boolean isSpace(byte b) {
        int c = b & 0xFF;
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == 0xEF || c == 0xBB || c == 0xBF;
    }

    private static String sniffEncoding(byte[] d, int n) {
        if (n == 0) return "empty";
        if (n >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF)
            return "utf-8-bom";
        if (n >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) return "utf-16le";
        if (n >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) return "utf-16be";

        int nul = 0;
        int ctrl = 0;
        boolean validUtf8 = true;
        int i = 0;
        while (i < n) {
            int c = d[i] & 0xFF;
            if (c == 0) nul++;
            if (c < 0x09 || (c > 0x0D && c < 0x20)) ctrl++;
            int extra = 0;
            if (c >= 0xC2 && c <= 0xDF) extra = 1;
            else if (c >= 0xE0 && c <= 0xEF) extra = 2;
            else if (c >= 0xF0 && c <= 0xF4) extra = 3;
            else if (c >= 0x80) validUtf8 = false;
            for (int k = 1; k <= extra; k++) {
                if (i + k >= n) break;                 // truncated at the block edge
                if (((d[i + k] & 0xFF) & 0xC0) != 0x80) {
                    validUtf8 = false;
                    break;
                }
            }
            i += extra + 1;
        }
        if (nul == 0 && ctrl * 100 < n * 5) return validUtf8 ? "utf-8" : "ascii/8-bit";
        return "binary";
    }

    private static boolean starts(byte[] data, int len, String signature) {
        if (len < signature.length()) return false;
        for (int i = 0; i < signature.length(); i++) {
            if ((data[i] & 0xFF) != (signature.charAt(i) & 0xFF)) return false;
        }
        return true;
    }

    private static byte[] copy(byte[] src, int len) {
        byte[] out = new byte[Math.max(0, len)];
        System.arraycopy(src, 0, out, 0, out.length);
        return out;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase();
    }

    private static void kv(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value == null ? "" : value).append('\n');
    }
}
