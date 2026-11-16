package io.github.leopierini.rehome;

import android.view.KeyEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shortcuts on the category Home, read by MainActivity only while Home is in front and focused with nothing open
 * over it: digits 1-9 open the Nth app of the "Media" row as it is shown (after the owner's own order), and the
 * four colour keys open the apps home_color_keys names. Pure: the key and the current state in, a package out.
 * Nothing here goes through an accessibility service or a key filter, so inside any app the keys work as before.
 */
final class HomeKeys {

    private HomeKeys() {}

    /**
     * The DOWN, repeat and UP of a key on Home: a key Home took on its way down has its repeats and its UP eaten too
     * (an app opened on the DOWN must not get a stray UP), and nothing else is eaten. One per Home.
     */
    static final class Gate {
        private int down = -1;

        /**
         * Whether Home eats this event. {@code takes}: Home would act on this key now (in front, nothing open, a key it
         * handles). True for a first DOWN means: act on it.
         */
        boolean onKey(int keyCode, boolean isDown, int repeatCount, boolean takes) {
            if (!isDown) {
                if (keyCode != down) return false;
                down = -1;
                return true;
            }
            if (repeatCount > 0) return keyCode == down;
            down = -1;
            if (!takes) return false;
            down = keyCode;
            return true;
        }
    }

    /** The row the digits count in. */
    static final String MEDIA_ROW = "Media";

    /**
     * The package this key opens, or null when the key is none of these, or names nothing: a digit past the row's
     * end, a colour with no app set, the digits switched off (home_number_keys false).
     */
    static String target(int keyCode, boolean numberKeys, List<String> mediaRow, Map<String, String> colorKeys) {
        int n = digit(keyCode);
        if (n > 0) {
            if (!numberKeys || mediaRow == null || n > mediaRow.size()) return null;
            return mediaRow.get(n - 1);
        }
        String color = color(keyCode);
        return color == null || colorKeys == null ? null : colorKeys.get(color);
    }

    /** Whether Home takes this key at all (the digits only while home_number_keys is on). */
    static boolean handles(int keyCode, boolean numberKeys) {
        return (numberKeys && digit(keyCode) > 0) || color(keyCode) != null;
    }

    /** 1-9 for the digit keys (top row or keypad), else 0. Zero opens nothing: there is no 0th app. */
    static int digit(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) return keyCode - KeyEvent.KEYCODE_0;
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_1 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }
        return 0;
    }

    /** "red", "green", "yellow" or "blue" for the colour keys, else null. */
    static String color(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PROG_RED:
                return "red";
            case KeyEvent.KEYCODE_PROG_GREEN:
                return "green";
            case KeyEvent.KEYCODE_PROG_YELLOW:
                return "yellow";
            case KeyEvent.KEYCODE_PROG_BLUE:
                return "blue";
            default:
                return null;
        }
    }

    /**
     * home_color_keys entries, "colour|package": the colour in any case, spaces around either part ignored. Lines
     * that are not one (another colour, no package, no bar) are skipped; a colour given twice keeps the first.
     */
    static Map<String, String> parseColors(String[] entries) {
        Map<String, String> out = new HashMap<>();
        if (entries == null) return out;
        for (String e : entries) {
            if (e == null) continue;
            int bar = e.indexOf('|');
            if (bar <= 0) continue;
            String color = e.substring(0, bar).trim().toLowerCase(Locale.ROOT);
            String pkg = e.substring(bar + 1).trim();
            if (pkg.isEmpty() || !RowEdits.isPackage(pkg)) continue;
            if (!color.equals("red") && !color.equals("green") && !color.equals("yellow") && !color.equals("blue")) {
                continue;
            }
            if (!out.containsKey(color)) out.put(color, pkg);
        }
        return out;
    }
}
