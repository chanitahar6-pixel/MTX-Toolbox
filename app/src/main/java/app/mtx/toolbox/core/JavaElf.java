package app.mtx.toolbox.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java ELF / {@code .so} analyzer: header, sections, dynamic table
 * ({@code DT_NEEDED}, {@code DT_SONAME}) and symbols with imports/exports.
 * Every read is bounds-checked, so a truncated or hostile binary yields an error
 * message rather than an exception reaching the UI.
 */
final class JavaElf {

    private JavaElf() {}

    private static final long MAX_SIZE = 128L * 1024 * 1024;

    static String info(String path, int maxSymbols) {
        JavaEngine.clearError();
        File file = new File(path);
        long size = file.length();
        if (size < 64) {
            JavaEngine.setError("file too small to be an ELF");
            return null;
        }
        if (size > MAX_SIZE) {
            JavaEngine.setError("binary is larger than " + (MAX_SIZE / (1024 * 1024))
                    + " MB, refusing to analyze it in the Java engine");
            return null;
        }
        byte[] d = JavaEngine.hexRead(path, 0, (int) size);
        if (d == null) return null;
        if (!(u8(d, 0) == 0x7F && d[1] == 'E' && d[2] == 'L' && d[3] == 'F')) {
            JavaEngine.setError("missing ELF magic");
            return null;
        }

        boolean is64 = u8(d, 4) == 2;
        boolean little = u8(d, 5) == 1;
        if (!little) {
            JavaEngine.setError("big-endian ELF files are not supported");
            return null;
        }

        int limit = maxSymbols > 0 ? maxSymbols : 2000;
        List<String> warnings = new ArrayList<>();

        int eType = u16(d, 16);
        int eMachine = u16(d, 18);
        long entry;
        long shOff;
        int shEntSize;
        int shNum;
        int shStrNdx;

        if (is64) {
            entry = le64(d, 24);
            shOff = le64(d, 40);
            shEntSize = u16(d, 58);
            shNum = u16(d, 60);
            shStrNdx = u16(d, 62);
        } else {
            entry = le32(d, 24) & 0xFFFFFFFFL;
            shOff = le32(d, 32) & 0xFFFFFFFFL;
            shEntSize = u16(d, 46);
            shNum = u16(d, 48);
            shStrNdx = u16(d, 50);
        }

        StringBuilder out = new StringBuilder();
        kv(out, "bits", is64 ? "64" : "32");
        kv(out, "type", fileType(eType));
        kv(out, "machine", machineName(eMachine));
        kv(out, "abi", abiName(eMachine, is64));
        kv(out, "entry", String.valueOf(entry));

        if (shOff <= 0 || shNum <= 0 || shOff + (long) shNum * shEntSize > size) {
            kv(out, "soname", "");
            kv(out, "interp", "");
            kv(out, "stripped", "true");
            kv(out, "warning", "no usable section headers (fully stripped or packed binary)");
            return out.toString();
        }

        int count = shNum;
        int[] nameOff = new int[count];
        int[] type = new int[count];
        int[] link = new int[count];
        long[] addr = new long[count];
        long[] off = new long[count];
        long[] len = new long[count];
        long[] entSize = new long[count];

        for (int i = 0; i < count; i++) {
            int sh = (int) (shOff + (long) i * shEntSize);
            nameOff[i] = le32(d, sh);
            type[i] = le32(d, sh + 4);
            if (is64) {
                addr[i] = le64(d, sh + 16);
                off[i] = le64(d, sh + 24);
                len[i] = le64(d, sh + 32);
                link[i] = le32(d, sh + 40);
                entSize[i] = le64(d, sh + 56);
            } else {
                addr[i] = le32(d, sh + 12) & 0xFFFFFFFFL;
                off[i] = le32(d, sh + 16) & 0xFFFFFFFFL;
                len[i] = le32(d, sh + 20) & 0xFFFFFFFFL;
                link[i] = le32(d, sh + 24);
                entSize[i] = le32(d, sh + 36) & 0xFFFFFFFFL;
            }
        }

        int shStrBase = 0;
        if (shStrNdx >= 0 && shStrNdx < count) shStrBase = (int) off[shStrNdx];

        boolean stripped = true;
        String soname = "";
        String interp = "";
        List<String> needed = new ArrayList<>();
        List<String> sectionRows = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String name = stringAt(d, shStrBase, nameOff[i]);
            if (".symtab".equals(name)) stripped = false;
            if (".interp".equals(name) && off[i] < size) interp = stringAt(d, (int) off[i], 0);

            StringBuilder row = new StringBuilder();
            row.append(name.isEmpty() ? "(unnamed)" : name).append(JavaEngine.SEP)
                    .append(sectionType(type[i])).append(JavaEngine.SEP)
                    .append(addr[i]).append(JavaEngine.SEP)
                    .append(off[i]).append(JavaEngine.SEP)
                    .append(len[i]);
            sectionRows.add(row.toString());
        }

        // DYNAMIC (type 6): DT_NEEDED = 1, DT_SONAME = 14.
        for (int i = 0; i < count; i++) {
            if (type[i] != 6) continue;
            int strBase = (link[i] >= 0 && link[i] < count) ? (int) off[link[i]] : 0;
            int step = is64 ? 16 : 8;
            long entries = step > 0 ? len[i] / step : 0;
            for (long k = 0; k < entries; k++) {
                int p = (int) (off[i] + k * step);
                long tag;
                long value;
                if (is64) {
                    tag = le64(d, p);
                    value = le64(d, p + 8);
                } else {
                    tag = le32(d, p) & 0xFFFFFFFFL;
                    value = le32(d, p + 4) & 0xFFFFFFFFL;
                }
                if (tag == 0) break;
                if (tag == 1) needed.add(stringAt(d, strBase, (int) value));
                else if (tag == 14) soname = stringAt(d, strBase, (int) value);
            }
        }

        // Symbols: .dynsym (11) gives imports/exports, .symtab (2) when present.
        List<String> symbolRows = new ArrayList<>();
        for (int i = 0; i < count && symbolRows.size() < limit; i++) {
            if (type[i] != 11 && type[i] != 2) continue;
            int strBase = (link[i] >= 0 && link[i] < count) ? (int) off[link[i]] : 0;
            int step = (int) (entSize[i] > 0 ? entSize[i] : (is64 ? 24 : 16));
            long entries = step > 0 ? len[i] / step : 0;

            for (long k = 0; k < entries && symbolRows.size() < limit; k++) {
                int p = (int) (off[i] + k * step);
                int symNameOff;
                int info;
                int shndx;
                long value;
                long symSize;
                if (is64) {
                    symNameOff = le32(d, p);
                    info = u8(d, p + 4);
                    shndx = u16(d, p + 6);
                    value = le64(d, p + 8);
                    symSize = le64(d, p + 16);
                } else {
                    symNameOff = le32(d, p);
                    value = le32(d, p + 4) & 0xFFFFFFFFL;
                    symSize = le32(d, p + 8) & 0xFFFFFFFFL;
                    info = u8(d, p + 12);
                    shndx = u16(d, p + 14);
                }
                String name = stringAt(d, strBase, symNameOff);
                if (name.isEmpty()) continue;

                StringBuilder row = new StringBuilder();
                row.append(name).append(JavaEngine.SEP)
                        .append(symType(info)).append(JavaEngine.SEP)
                        .append(symBind(info)).append(JavaEngine.SEP)
                        .append(value).append(JavaEngine.SEP)
                        .append(symSize).append(JavaEngine.SEP)
                        .append(shndx == 0 ? "import" : "export");
                symbolRows.add(row.toString());
            }
        }
        if (symbolRows.size() >= limit) warnings.add("symbol list truncated at " + limit);

        kv(out, "soname", soname);
        kv(out, "interp", interp);
        kv(out, "stripped", stripped ? "true" : "false");
        for (int i = 0; i < needed.size(); i++) kv(out, "needed", needed.get(i));
        for (int i = 0; i < sectionRows.size(); i++) kv(out, "section", sectionRows.get(i));
        for (int i = 0; i < symbolRows.size(); i++) kv(out, "symbol", symbolRows.get(i));
        for (int i = 0; i < warnings.size(); i++) kv(out, "warning", warnings.get(i));
        return out.toString();
    }

    private static String fileType(int t) {
        switch (t) {
            case 1: return "REL (relocatable)";
            case 2: return "EXEC (executable)";
            case 3: return "DYN (shared object / PIE)";
            case 4: return "CORE";
            default: return "unknown";
        }
    }

    private static String machineName(int m) {
        switch (m) {
            case 3: return "Intel 80386";
            case 8: return "MIPS";
            case 40: return "ARM";
            case 62: return "AMD x86-64";
            case 183: return "AArch64";
            case 243: return "RISC-V";
            default: return "unknown";
        }
    }

    private static String abiName(int m, boolean is64) {
        switch (m) {
            case 40: return "armeabi-v7a";
            case 183: return "arm64-v8a";
            case 3: return "x86";
            case 62: return "x86_64";
            case 8: return is64 ? "mips64" : "mips";
            default: return "unknown";
        }
    }

    private static String sectionType(int t) {
        switch (t) {
            case 0: return "NULL";
            case 1: return "PROGBITS";
            case 2: return "SYMTAB";
            case 3: return "STRTAB";
            case 4: return "RELA";
            case 5: return "HASH";
            case 6: return "DYNAMIC";
            case 7: return "NOTE";
            case 8: return "NOBITS";
            case 9: return "REL";
            case 11: return "DYNSYM";
            case 14: return "INIT_ARRAY";
            case 15: return "FINI_ARRAY";
            case 0x6ffffff6: return "GNU_HASH";
            case 0x6fffffff: return "VERNEED";
            default: return "OTHER";
        }
    }

    private static String symType(int info) {
        switch (info & 0xF) {
            case 0: return "NOTYPE";
            case 1: return "OBJECT";
            case 2: return "FUNC";
            case 3: return "SECTION";
            case 4: return "FILE";
            case 6: return "TLS";
            default: return "OTHER";
        }
    }

    private static String symBind(int info) {
        switch (info >> 4) {
            case 0: return "LOCAL";
            case 1: return "GLOBAL";
            case 2: return "WEAK";
            default: return "OTHER";
        }
    }

    private static String stringAt(byte[] d, int base, int offset) {
        int p = base + offset;
        if (p < 0 || p >= d.length) return "";
        int end = p;
        while (end < d.length && d[end] != 0) end++;
        try {
            return new String(d, p, end - p, "UTF-8");
        } catch (Exception e) {
            return new String(d, p, end - p);
        }
    }

    private static int u8(byte[] d, int off) {
        return (off < 0 || off >= d.length) ? 0 : d[off] & 0xFF;
    }

    private static int u16(byte[] d, int off) {
        if (off < 0 || off + 2 > d.length) return 0;
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] d, int off) { return JavaApk.le32(d, off); }

    private static long le64(byte[] d, int off) { return JavaApk.le64(d, off); }

    private static void kv(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value == null ? "" : value).append('\n');
    }
}
