package io.github.leopierini.rehome;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * locale: the language the launcher shows itself in, instead of the TV's. A China-market Xiaomi offers Chinese and
 * English only, so a Vietnamese owner saw the launcher in Chinese although it is translated into Vietnamese.
 * Each activity wraps its base context here; an empty or malformed tag leaves the TV's language.
 */
final class LauncherLocale {
    // Subtags after the language, so "sr-Latn" and "ar-EG-u-nu-latn" (Arabic with Western digits) pass too.
    private static final Pattern TAG = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{1,8})*");

    private LauncherLocale() {
    }

    /** The Locale for a config tag such as "vi", "pt-BR" or "ar-EG-u-nu-latn", or null for "" or anything that is not a plain tag. */
    static Locale parse(String tag) {
        if (tag == null || !TAG.matcher(tag.trim()).matches()) return null;
        return Locale.forLanguageTag(tag.trim());
    }

    /** base with the configured language, or base itself when locale is off. */
    static Context wrap(Context base) {
        return wrap(base, Config.load(base));
    }

    /** {@link #wrap(Context)} with the Config the caller already read from base (MainActivity keeps it for onCreate). */
    static Context wrap(Context base, Config config) {
        Locale locale = parse(config.locale);
        if (locale == null) return base;
        // Formatters that take no Context (the clock's date, the lunar line) follow the default locale.
        Locale.setDefault(locale);
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(locale);
        return base.createConfigurationContext(c);
    }

    /**
     * The Config an activity read in attachBaseContext, for its onCreate: only when wrap returned base itself, so it
     * was read through the same resources onCreate would read (Task 7: one config.xml and overlay read per Home start
     * instead of two). Null otherwise: onCreate loads it again through the language's resources.
     */
    static <T> T keptForCreate(Object wrapped, Object base, T config) {
        return wrapped == base ? config : null;
    }
}
