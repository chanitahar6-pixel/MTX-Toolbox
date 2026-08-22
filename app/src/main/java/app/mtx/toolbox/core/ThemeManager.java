package app.mtx.toolbox.core;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

/** Theme follows the phone by default; the user can force light or dark. */
public final class ThemeManager {

    private ThemeManager() {}

    public static void apply(Context ctx) {
        apply(Prefs.theme(ctx));
    }

    public static void apply(String theme) {
        int mode;
        if (Prefs.THEME_LIGHT.equals(theme)) mode = AppCompatDelegate.MODE_NIGHT_NO;
        else if (Prefs.THEME_DARK.equals(theme)) mode = AppCompatDelegate.MODE_NIGHT_YES;
        else mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
