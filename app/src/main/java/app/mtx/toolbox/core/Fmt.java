package app.mtx.toolbox.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Display formatting helpers. */
public final class Fmt {

    private Fmt() {}

    public static String bytes(long n) {
        if (n < 0) return "?";
        if (n < 1024) return n + " B";
        double v = n;
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        int u = -1;
        while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
        return String.format(Locale.US, "%.2f %s", v, units[u]);
    }

    public static String speed(long bytesPerSecond) {
        return bytesPerSecond <= 0 ? "--" : bytes(bytesPerSecond) + "/s";
    }

    public static String date(long millis) {
        if (millis <= 0) return "--";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }

    public static String duration(long millis) {
        if (millis < 0) return "--";
        long s = millis / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        s %= 60;
        if (m < 60) return m + "m " + s + "s";
        long h = m / 60;
        m %= 60;
        return h + "h " + m + "m";
    }

    public static String eta(long done, long total, long speed) {
        if (total <= 0 || speed <= 0 || done >= total) return "--";
        return duration((total - done) * 1000 / speed);
    }

    public static String percent(long done, long total) {
        if (total <= 0) return "--";
        long p = done * 100 / total;
        return (p > 100 ? 100 : p) + "%";
    }

    /** Text progress bar used in logs and the operations screen. */
    public static String bar(long done, long total, int width) {
        StringBuilder sb = new StringBuilder(width + 2);
        int filled = total > 0 ? (int) Math.min(width, done * width / total) : 0;
        for (int i = 0; i < width; i++) sb.append(i < filled ? '\u2588' : '\u2591');
        return sb.toString();
    }

    public static String mode(int m) {
        char[] out = new char[9];
        String rwx = "rwx";
        for (int i = 0; i < 9; i++) {
            out[i] = (m & (1 << (8 - i))) != 0 ? rwx.charAt(i % 3) : '-';
        }
        return new String(out) + String.format(Locale.US, "  (%o)", m);
    }
}
