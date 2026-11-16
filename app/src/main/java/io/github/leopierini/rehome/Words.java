package io.github.leopierini.rehome;

import android.content.res.Resources;

import java.util.Locale;

/**
 * The launcher's words by resource id, for the pure text helpers (PlayerText, RainNote, WindNote, DataUsage,
 * WifiPopup.status): the app's resources on the TV, the parsed res files in the JVM tests (TestWords), so the tests
 * check the real English and Italian strings.
 */
interface Words {

    /** R.string {@code id}, formatted with {@code args} in {@link #locale()} when there are any. */
    String get(int id, Object... args);

    /** R.plurals {@code id} for {@code count}, formatted with {@code args}. */
    String plural(int id, int count, Object... args);

    /** R.array {@code id}. */
    String[] array(int id);

    /** The language the words are in: numbers and dates follow it (2.3 GB, 2,3 GB). */
    Locale locale();

    /**
     * The locale numbers and dates are formatted in: the TV's own when the words are in its language (English or
     * Italian, the only ones the launcher has), else English, as the words then are: a German TV gets "2.3 GB" and
     * "Nov 12", not "2,3 GB" and "Nov. 12" inside English text.
     */
    static Locale formatLocale(Locale configured) {
        if (configured == null) return Locale.ENGLISH;
        String lang = configured.getLanguage();
        return lang.equals("en") || lang.equals("it") ? configured : Locale.ENGLISH;
    }

    static Words of(Resources res) {
        return new Words() {
            @Override
            public String get(int id, Object... args) {
                return args.length == 0 ? res.getString(id) : String.format(locale(), res.getString(id), args);
            }

            @Override
            public String plural(int id, int count, Object... args) {
                return String.format(locale(), res.getQuantityText(id, count).toString(), args);
            }

            @Override
            public String[] array(int id) {
                return res.getStringArray(id);
            }

            @Override
            public Locale locale() {
                return formatLocale(res.getConfiguration().getLocales().get(0));
            }
        };
    }
}
