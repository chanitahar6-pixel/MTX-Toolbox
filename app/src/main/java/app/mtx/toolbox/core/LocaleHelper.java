package app.mtx.toolbox.core;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

/**
 * Applies the language chosen in Settings (Arabic / English / follow system)
 * without restarting the process. RTL layout follows automatically because the
 * manifest declares {@code supportsRtl}.
 */
public final class LocaleHelper {

    private LocaleHelper() {}

    public static Context wrap(Context base) {
        String lang = Prefs.language(base);
        if (Prefs.LANG_SYSTEM.equals(lang)) return base;

        Locale locale = new Locale(lang);
        Locale.setDefault(locale);

        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale);
            config.setLayoutDirection(locale);
        } else {
            config.locale = locale;
        }
        return base.createConfigurationContext(config);
    }

    public static boolean isRtl(Context ctx) {
        return ctx.getResources().getConfiguration().getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL;
    }
}
