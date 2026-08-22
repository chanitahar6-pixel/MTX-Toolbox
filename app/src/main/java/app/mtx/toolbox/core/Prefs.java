package app.mtx.toolbox.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/** All user settings in one place. */
public final class Prefs {

    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    public static final String LANG_SYSTEM = "system";
    public static final String LANG_AR = "ar";
    public static final String LANG_EN = "en";

    public static final int SORT_NAME = 0;
    public static final int SORT_SIZE = 1;
    public static final int SORT_DATE = 2;
    public static final int SORT_TYPE = 3;

    private static final String FILE = "mtx_prefs";
    private static final String K_THEME = "theme";
    private static final String K_LANG = "lang";
    private static final String K_HIDDEN = "show_hidden";
    private static final String K_FOLDERS_FIRST = "folders_first";
    private static final String K_SORT = "sort_by";
    private static final String K_ASC = "sort_asc";
    private static final String K_LEFT = "pane_left";
    private static final String K_RIGHT = "pane_right";
    private static final String K_BOOKMARKS = "bookmarks";
    private static final String K_CONFIRM_OVERWRITE = "confirm_overwrite";

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String theme(Context c) { return sp(c).getString(K_THEME, THEME_SYSTEM); }

    public static void setTheme(Context c, String v) { sp(c).edit().putString(K_THEME, v).apply(); }

    public static String language(Context c) { return sp(c).getString(K_LANG, LANG_SYSTEM); }

    public static void setLanguage(Context c, String v) { sp(c).edit().putString(K_LANG, v).apply(); }

    public static boolean showHidden(Context c) { return sp(c).getBoolean(K_HIDDEN, false); }

    public static void setShowHidden(Context c, boolean v) { sp(c).edit().putBoolean(K_HIDDEN, v).apply(); }

    public static boolean foldersFirst(Context c) { return sp(c).getBoolean(K_FOLDERS_FIRST, true); }

    public static void setFoldersFirst(Context c, boolean v) { sp(c).edit().putBoolean(K_FOLDERS_FIRST, v).apply(); }

    public static int sortBy(Context c) { return sp(c).getInt(K_SORT, SORT_NAME); }

    public static void setSortBy(Context c, int v) { sp(c).edit().putInt(K_SORT, v).apply(); }

    public static boolean sortAscending(Context c) { return sp(c).getBoolean(K_ASC, true); }

    public static void setSortAscending(Context c, boolean v) { sp(c).edit().putBoolean(K_ASC, v).apply(); }

    public static boolean confirmOverwrite(Context c) { return sp(c).getBoolean(K_CONFIRM_OVERWRITE, true); }

    public static void setConfirmOverwrite(Context c, boolean v) {
        sp(c).edit().putBoolean(K_CONFIRM_OVERWRITE, v).apply();
    }

    public static String panePath(Context c, boolean left, String def) {
        return sp(c).getString(left ? K_LEFT : K_RIGHT, def);
    }

    public static void setPanePath(Context c, boolean left, String path) {
        sp(c).edit().putString(left ? K_LEFT : K_RIGHT, path).apply();
    }

    public static Set<String> bookmarks(Context c) {
        return new LinkedHashSet<>(sp(c).getStringSet(K_BOOKMARKS, new LinkedHashSet<String>()));
    }

    public static void toggleBookmark(Context c, String path) {
        Set<String> set = bookmarks(c);
        if (!set.remove(path)) set.add(path);
        sp(c).edit().putStringSet(K_BOOKMARKS, set).apply();
    }

    public static boolean isBookmarked(Context c, String path) { return bookmarks(c).contains(path); }
}
