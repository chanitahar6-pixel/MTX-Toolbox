package app.mtx.toolbox.core;

import android.content.Context;

import app.mtx.toolbox.R;

/**
 * Maps the native error taxonomy onto messages the user can actually act on.
 * The technical detail from {@link Native#lastError()} is kept separately and
 * written to {@code MTX/Logs/}, never shown as the primary message.
 */
public final class OpResult {

    public static final int OK = 0;
    public static final int E_NOENT = -2;
    public static final int E_PERM = -13;
    public static final int E_IO = -5;
    public static final int E_EXISTS = -17;
    public static final int E_NOSPC = -28;
    public static final int E_CANCELLED = -1000;
    public static final int E_CORRUPT = -1001;
    public static final int E_UNSUPPORTED = -1002;
    public static final int E_RANGE = -1003;
    public static final int E_ENCODING = -1004;
    public static final int E_BUSY = -1005;
    public static final int E_INTERNAL = -1006;

    private OpResult() {}

    public static boolean isOk(int code) { return code == OK; }

    public static boolean isCancelled(int code) { return code == E_CANCELLED; }

    public static String message(Context ctx, int code) {
        int res;
        switch (code) {
            case OK: res = R.string.err_ok; break;
            case E_NOENT: res = R.string.err_not_found; break;
            case E_PERM: res = R.string.err_permission; break;
            case E_IO: res = R.string.err_io; break;
            case E_EXISTS: res = R.string.err_exists; break;
            case E_NOSPC: res = R.string.err_no_space; break;
            case E_CANCELLED: res = R.string.err_cancelled; break;
            case E_CORRUPT: res = R.string.err_corrupt; break;
            case E_UNSUPPORTED: res = R.string.err_unsupported; break;
            case E_RANGE: res = R.string.err_range; break;
            case E_ENCODING: res = R.string.err_encoding; break;
            case E_BUSY: res = R.string.err_busy; break;
            default: res = R.string.err_internal; break;
        }
        return ctx.getString(res);
    }

    /** User-facing message plus the real technical reason, for dialogs and logs. */
    public static String detailed(Context ctx, int code) {
        String tech = safeLastError();
        String msg = message(ctx, code);
        return tech == null || tech.isEmpty() ? msg : msg + "\n\n" + tech;
    }

    public static String safeLastError() {
        try {
            return Native.isAvailable() ? Native.lastError() : Native.loadError();
        } catch (Throwable t) {
            return String.valueOf(t.getMessage());
        }
    }
}
