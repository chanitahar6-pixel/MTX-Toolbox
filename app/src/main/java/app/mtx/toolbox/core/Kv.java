package app.mtx.toolbox.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for the {@code key=value} line format the native layer returns.
 * Repeated keys are preserved in order, which is how lists (permissions,
 * components, sections, symbols...) are transported.
 */
public final class Kv {

    public static final char SEP = '\u0001';

    private final Map<String, List<String>> map = new LinkedHashMap<>();

    private Kv() {}

    public static Kv parse(String raw) {
        Kv kv = new Kv();
        if (raw == null) return kv;
        int start = 0;
        while (start < raw.length()) {
            int nl = raw.indexOf('\n', start);
            if (nl < 0) nl = raw.length();
            int eq = raw.indexOf('=', start);
            if (eq > start && eq < nl) {
                String key = raw.substring(start, eq);
                String value = raw.substring(eq + 1, nl);
                List<String> list = kv.map.get(key);
                if (list == null) {
                    list = new ArrayList<>(1);
                    kv.map.put(key, list);
                }
                list.add(value);
            }
            start = nl + 1;
        }
        return kv;
    }

    public String get(String key) { return get(key, ""); }

    public String get(String key, String def) {
        List<String> list = map.get(key);
        return list == null || list.isEmpty() ? def : list.get(0);
    }

    public long getLong(String key, long def) {
        try {
            String v = get(key, null);
            return v == null || v.isEmpty() ? def : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public int getInt(String key, int def) { return (int) getLong(key, def); }

    public boolean getBool(String key, boolean def) {
        String v = get(key, null);
        return v == null || v.isEmpty() ? def : "true".equals(v) || "1".equals(v);
    }

    public List<String> all(String key) {
        List<String> list = map.get(key);
        return list == null ? new ArrayList<String>() : list;
    }

    public boolean has(String key) { return map.containsKey(key); }

    /** Splits a multi-field value produced with the {@code \u0001} separator. */
    public static String[] fields(String value) {
        return value == null ? new String[0] : value.split("\u0001", -1);
    }
}
