package app.mtx.toolbox.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Android binary XML (AXML) decoder in pure Java, a direct counterpart of the C++
 * {@code axml} engine. Every read is bounds-checked, so a corrupt or deliberately
 * malformed manifest produces a clear error instead of a crash.
 */
final class JavaAxml {

    private JavaAxml() {}

    static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static final int RES_STRING_POOL = 0x0001;
    private static final int RES_XML = 0x0003;
    private static final int RES_XML_START_TAG = 0x0102;
    private static final int RES_XML_END_TAG = 0x0103;
    private static final int RES_XML_CDATA = 0x0104;

    private static final int UTF8_FLAG = 1 << 8;

    private static final int TYPE_NULL = 0x00;
    private static final int TYPE_REFERENCE = 0x01;
    private static final int TYPE_ATTRIBUTE = 0x02;
    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_FLOAT = 0x04;
    private static final int TYPE_DIMENSION = 0x05;
    private static final int TYPE_FRACTION = 0x06;
    private static final int TYPE_INT_DEC = 0x10;
    private static final int TYPE_INT_HEX = 0x11;
    private static final int TYPE_INT_BOOL = 0x12;

    /** One decoded attribute of a start tag. */
    static final class Attr {
        String ns = "";
        String name = "";
        String value = "";
        int type;
        int data;
    }

    interface Handler {
        void startTag(String name, List<Attr> attrs, int line);
        void endTag(String name);
        void text(String value);
    }

    static final class AxmlException extends Exception {
        AxmlException(String message) { super(message); }
    }

    // ---- little-endian readers --------------------------------------------
    private static int u8(byte[] d, int off) { return d[off] & 0xFF; }

    private static int u16(byte[] d, int off) throws AxmlException {
        if (off < 0 || off + 2 > d.length) throw new AxmlException("binary XML truncated");
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] d, int off) throws AxmlException {
        if (off < 0 || off + 4 > d.length) throw new AxmlException("binary XML truncated");
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    // ---- string pool ------------------------------------------------------
    private static final class Pool {
        byte[] data;
        int count;
        boolean utf8;
        int offsetsAt;
        int dataStart;
        int end;
        String[] cache;

        String get(int index) {
            if (index < 0 || index >= count) return "";
            if (cache[index] != null) return cache[index];
            String value;
            try {
                int off = u32(data, offsetsAt + index * 4);
                int p = dataStart + off;
                value = utf8 ? readUtf8(p) : readUtf16(p);
            } catch (Throwable t) {
                value = "";
            }
            cache[index] = value;
            return value;
        }

        private String readUtf8(int p) throws AxmlException {
            if (p < 0 || p >= end) return "";
            int[] cursor = new int[]{p};
            readLen8(cursor);                 // char length, unused
            int byteLen = readLen8(cursor);
            int start = cursor[0];
            if (start + byteLen > end) byteLen = Math.max(0, end - start);
            try {
                return new String(data, start, byteLen, "UTF-8");
            } catch (Exception e) {
                return new String(data, start, byteLen);
            }
        }

        private int readLen8(int[] cursor) throws AxmlException {
            int p = cursor[0];
            if (p >= end) throw new AxmlException("string pool truncated");
            int b = u8(data, p++);
            int value;
            if ((b & 0x80) != 0) {
                if (p >= end) throw new AxmlException("string pool truncated");
                value = ((b & 0x7F) << 8) | u8(data, p++);
            } else {
                value = b;
            }
            cursor[0] = p;
            return value;
        }

        private String readUtf16(int p) throws AxmlException {
            int lenLo = u16(data, p);
            int q = p + 2;
            int charLen = lenLo;
            if ((lenLo & 0x8000) != 0) {
                int lenHi = u16(data, q);
                charLen = ((lenLo & 0x7FFF) << 16) | lenHi;
                q += 2;
            }
            StringBuilder sb = new StringBuilder(Math.max(0, charLen));
            for (int i = 0; i < charLen; i++) {
                if (q + 2 > data.length) break;
                sb.append((char) u16(data, q));
                q += 2;
            }
            return sb.toString();
        }

        static Pool load(byte[] d, int chunkOff) throws AxmlException {
            Pool pool = new Pool();
            pool.data = d;
            int headerSize = u16(d, chunkOff + 2);
            int chunkSize = u32(d, chunkOff + 4);
            pool.count = u32(d, chunkOff + 8);
            int flags = u32(d, chunkOff + 16);
            int stringsStart = u32(d, chunkOff + 20);

            if (pool.count < 0 || chunkSize < 0 || chunkOff + chunkSize > d.length)
                throw new AxmlException("corrupt string pool");

            pool.utf8 = (flags & UTF8_FLAG) != 0;
            pool.offsetsAt = chunkOff + headerSize;
            pool.dataStart = chunkOff + stringsStart;
            pool.end = chunkOff + chunkSize;
            if (pool.offsetsAt + pool.count * 4 > d.length)
                throw new AxmlException("corrupt string pool offsets");
            pool.cache = new String[pool.count];
            return pool;
        }
    }

    // ---- value formatting -------------------------------------------------
    private static String formatValue(Pool pool, int type, int data, int rawIndex) {
        switch (type) {
            case TYPE_STRING:
                return pool.get(rawIndex != -1 ? rawIndex : data);
            case TYPE_INT_BOOL:
                return data != 0 ? "true" : "false";
            case TYPE_INT_DEC:
                return String.valueOf(data);
            case TYPE_INT_HEX:
                return "0x" + Integer.toHexString(data);
            case TYPE_FLOAT:
                return String.valueOf(Float.intBitsToFloat(data));
            case TYPE_REFERENCE:
                return "@0x" + Integer.toHexString(data);
            case TYPE_ATTRIBUTE:
                return "?0x" + Integer.toHexString(data);
            case TYPE_DIMENSION: {
                String[] units = {"px", "dip", "sp", "pt", "in", "mm"};
                int unit = data & 0xF;
                return (data >> 8) + (unit < units.length ? units[unit] : "");
            }
            case TYPE_FRACTION:
                return ((data >> 8) / 100.0) + "%";
            case TYPE_NULL:
                return "";
            default:
                return "0x" + Integer.toHexString(data);
        }
    }

    // ---- parser -----------------------------------------------------------
    static void parse(byte[] d, Handler handler) throws AxmlException {
        if (d == null || d.length < 8) throw new AxmlException("binary XML too small");
        int type = u16(d, 0);
        int headerSize = u16(d, 2);
        int totalSize = u32(d, 4);
        if (type != RES_XML) throw new AxmlException("not an Android binary XML file");
        if (totalSize <= 0 || totalSize > d.length) totalSize = d.length;

        Pool pool = null;
        int pos = headerSize >= 8 ? headerSize : 8;

        while (pos + 8 <= totalSize) {
            int chunkType = u16(d, pos);
            int chunkSize = u32(d, pos + 4);
            if (chunkSize < 8 || pos + chunkSize > totalSize)
                throw new AxmlException("corrupt chunk in binary XML");

            if (chunkType == RES_STRING_POOL) {
                pool = Pool.load(d, pos);
            } else if (chunkType == RES_XML_START_TAG) {
                if (pool == null) throw new AxmlException("start tag before string pool");
                int line = u32(d, pos + 8);
                int nameIdx = u32(d, pos + 20);
                int attrStart = u16(d, pos + 24);
                int attrSize = u16(d, pos + 26);
                int attrCount = u16(d, pos + 28);
                if (attrSize == 0) attrSize = 20;

                List<Attr> attrs = new ArrayList<>(Math.max(0, attrCount));
                // attributeStart is relative to the attrExt struct, which begins
                // 16 bytes into the chunk (after the ResXMLTree_node header).
                int base = pos + 16 + attrStart;
                for (int i = 0; i < attrCount; i++) {
                    int a = base + i * attrSize;
                    if (a + 20 > pos + chunkSize || a + 20 > d.length) break;
                    Attr attr = new Attr();
                    int nsIdx = u32(d, a);
                    int nameIndex = u32(d, a + 4);
                    int rawIndex = u32(d, a + 8);
                    int valueType = u8(d, a + 15);
                    int valueData = u32(d, a + 16);
                    attr.ns = nsIdx == -1 ? "" : pool.get(nsIdx);
                    attr.name = pool.get(nameIndex);
                    attr.type = valueType;
                    attr.data = valueData;
                    attr.value = formatValue(pool, valueType, valueData, rawIndex);
                    attrs.add(attr);
                }
                handler.startTag(pool.get(nameIdx), attrs, line);
            } else if (chunkType == RES_XML_END_TAG) {
                if (pool == null) throw new AxmlException("end tag before string pool");
                handler.endTag(pool.get(u32(d, pos + 20)));
            } else if (chunkType == RES_XML_CDATA) {
                if (pool != null) handler.text(pool.get(u32(d, pos + 16)));
            }
            pos += chunkSize;
        }
    }

    /** Pretty-printed XML text, as close to the original source as AXML allows. */
    static String toXml(byte[] data) throws AxmlException {
        final StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        parse(data, new Handler() {
            int depth;

            @Override
            public void startTag(String name, List<Attr> attrs, int line) {
                indent(depth);
                out.append('<').append(name);
                for (int i = 0; i < attrs.size(); i++) {
                    Attr a = attrs.get(i);
                    out.append('\n');
                    indent(depth + 1);
                    out.append(a.ns.isEmpty() ? a.name : prefix(a.ns) + ":" + a.name)
                            .append("=\"").append(escape(a.value)).append('"');
                }
                out.append(">\n");
                depth++;
            }

            @Override
            public void endTag(String name) {
                if (depth > 0) depth--;
                indent(depth);
                out.append("</").append(name).append(">\n");
            }

            @Override
            public void text(String value) {
                if (value == null || value.isEmpty()) return;
                indent(depth);
                out.append(escape(value)).append('\n');
            }

            private void indent(int n) {
                for (int i = 0; i < n; i++) out.append("    ");
            }

            private String prefix(String uri) {
                if (ANDROID_NS.equals(uri)) return "android";
                if (uri.contains("apk/res-auto")) return "app";
                return "ns";
            }

            private String escape(String s) {
                StringBuilder sb = new StringBuilder(s.length());
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (c == '&') sb.append("&amp;");
                    else if (c == '<') sb.append("&lt;");
                    else if (c == '>') sb.append("&gt;");
                    else if (c == '"') sb.append("&quot;");
                    else sb.append(c);
                }
                return sb.toString();
            }
        });
        return out.toString();
    }
}
