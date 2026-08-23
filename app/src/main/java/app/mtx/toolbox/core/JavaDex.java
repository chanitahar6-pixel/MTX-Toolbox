package app.mtx.toolbox.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java DEX header inspector. Structural validation only, exactly like the C++
 * engine: counts and offsets are checked against the real file size and every
 * inconsistency is reported as a warning instead of being silently accepted.
 */
final class JavaDex {

    private JavaDex() {}

    static String info(String path) {
        JavaEngine.clearError();
        File file = new File(path);
        byte[] head = JavaEngine.hexRead(path, 0, 0x70);
        if (head == null) return null;
        if (head.length < 0x70) {
            JavaEngine.setError("file is smaller than a DEX header");
            return null;
        }
        if (head[0] != 'd' || head[1] != 'e' || head[2] != 'x' || head[3] != '\n') {
            JavaEngine.setError("missing DEX magic (dex\\n)");
            return null;
        }

        long actualSize = file.length();
        String version = new String(head, 4, 3);
        long checksum = JavaApk.le32(head, 8) & 0xFFFFFFFFL;
        String signature = JavaEngine.hex(slice(head, 12, 20));
        long headerFileSize = JavaApk.le32(head, 32) & 0xFFFFFFFFL;
        long headerSize = JavaApk.le32(head, 36) & 0xFFFFFFFFL;
        long endianTag = JavaApk.le32(head, 40) & 0xFFFFFFFFL;
        long mapOff = JavaApk.le32(head, 52) & 0xFFFFFFFFL;
        long stringIds = JavaApk.le32(head, 56) & 0xFFFFFFFFL;
        long stringIdsOff = JavaApk.le32(head, 60) & 0xFFFFFFFFL;
        long typeIds = JavaApk.le32(head, 64) & 0xFFFFFFFFL;
        long protoIds = JavaApk.le32(head, 72) & 0xFFFFFFFFL;
        long fieldIds = JavaApk.le32(head, 80) & 0xFFFFFFFFL;
        long methodIds = JavaApk.le32(head, 88) & 0xFFFFFFFFL;
        long classDefs = JavaApk.le32(head, 96) & 0xFFFFFFFFL;

        List<String> warnings = new ArrayList<>();
        if (endianTag == 0x78563412L) {
            JavaEngine.setError("byte-swapped DEX files are not supported");
            return null;
        }
        if (endianTag != 0x12345678L) warnings.add("unexpected endian tag");
        if (headerSize != 0x70) warnings.add("unusual header size: " + headerSize);
        if (headerFileSize != actualSize)
            warnings.add("header file_size (" + headerFileSize
                    + ") does not match the real size (" + actualSize + ")");
        if (stringIdsOff + stringIds * 4 > actualSize)
            warnings.add("string_ids table extends past end of file");
        if (mapOff >= actualSize) warnings.add("map_off points outside the file");

        StringBuilder out = new StringBuilder();
        kv(out, "version", version);
        kv(out, "signature", signature);
        kv(out, "checksum", String.valueOf(checksum));
        kv(out, "headerFileSize", String.valueOf(headerFileSize));
        kv(out, "actualFileSize", String.valueOf(actualSize));
        kv(out, "strings", String.valueOf(stringIds));
        kv(out, "types", String.valueOf(typeIds));
        kv(out, "protos", String.valueOf(protoIds));
        kv(out, "fields", String.valueOf(fieldIds));
        kv(out, "methods", String.valueOf(methodIds));
        kv(out, "classes", String.valueOf(classDefs));
        kv(out, "valid", warnings.isEmpty() ? "true" : "false");
        for (int i = 0; i < warnings.size(); i++) kv(out, "warning", warnings.get(i));
        return out.toString();
    }

    private static byte[] slice(byte[] src, int from, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, from, out, 0, len);
        return out;
    }

    private static void kv(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value == null ? "" : value).append('\n');
    }
}
