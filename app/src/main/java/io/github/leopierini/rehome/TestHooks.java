package io.github.leopierini.rehome;

import android.content.Context;
import android.provider.Settings;

/**
 * Switches for trying on a real TV what cannot be produced over ADB: cutting the network to see the offline Home
 * would cut ADB too, and the forecast is dry when it is dry. Off unless set from an ADB shell; read from
 * Settings.Global, which only the shell and system apps can write, so no other app can turn them on.
 *
 * <pre>
 *   settings put global rehome_test_offline 1            # the Home as if offline (hint, dimmed tiles, "No network")
 *   settings put global rehome_test_rain 0,0,80,90,20,0  # the rain chances from this hour on, in %
 *   settings delete global rehome_test_offline           # back to normal (and rehome_test_rain)
 *   settings put global rehome_test_watchnext dump       # log every Watch Next row read (logcat -s Rehome.WatchNext)
 *   settings put global rehome_test_watchnext test       # dump, plus the launcher's own test rows (debug build only)
 *   settings delete global rehome_test_watchnext         # off; the test rows are deleted at the next read
 *   settings put global rehome_test_memory log           # the player's memory logs its saves (logcat -s Rehome.Memory)
 *   settings put global rehome_test_player all           # made-up player items: song, video, app or all
 *   settings put global rehome_test_hide_debug 1         # no red "Debugging on" pill, for screenshots
 * </pre>
 * The offline switch is read when Home comes back to the front (leave Home and return). The rain one whenever a
 * reading is shown: at the next fetch, or at once after `am force-stop io.github.leopierini.rehome` and Home, even over a
 * saved reading that has no hourly part (the hours are then counted from now).
 * They ship in the release build on purpose: off by default, a cached Settings read each, and the only way to see
 * these states on the TV. The one hook that writes (the Watch Next test rows) is in the debug build only.
 *
 * They act only while ADB is on (Settings.Global.ADB_ENABLED, as DebugPill reads it): a key left set when debugging
 * is switched off survives reboots and reinstalls, and with ADB closed nobody could delete it. Switched off, every
 * hook reads as unset at once: the next Home resume shows the real state, and while Home is in front
 * CategoryHome.onTestHooksChanged (from DebugPill's watch on the same setting) puts it back without leaving Home.
 * Before closing ADB, {@code settings list global | grep rehome_test} must still print nothing.
 */
final class TestHooks {

    private TestHooks() {}

    static boolean offline(Context context) {
        return offline(source(context));
    }

    static boolean offline(Source source) {
        String v = read(source, "rehome_test_offline");
        return "1".equals(v);
    }

    /** "dump", "test" or null: the Watch Next row's checks (WatchNextRow). Read at each Home resume. */
    static String watchNext(Context context) {
        return watchNext(source(context));
    }

    static String watchNext(Source source) {
        String v = read(source, "rehome_test_watchnext");
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    /** The rain chances to use instead of the forecast's, or null. */
    static int[] rain(Context context) {
        return rain(source(context));
    }

    static int[] rain(Source source) {
        String v = read(source, "rehome_test_rain");
        if (v == null || v.trim().isEmpty()) return null;
        String[] parts = v.split(",");
        int[] p = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) p[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return p;
    }

    /**
     * The data meter's row text in the Wi-Fi dropdown to show instead of the real one, or null: to see a long text
     * there ({@code settings put global rehome_test_meter "Today 12.3 GB"}). Read at each refresh (Home resume).
     */
    static String meter(Context context) {
        return meter(source(context));
    }

    static String meter(Source source) {
        String v = read(source, "rehome_test_meter");
        return v == null || v.trim().isEmpty() ? null : v;
    }


    /**
     * The player's memory logs one line per save (logcat -s Rehome.Memory: key, kind, title), to check on the TV what
     * it records while another app is in front ({@code settings put global rehome_test_memory log}). Titles are
     * what the person watches: never logged without it.
     */
    static boolean memoryLog(Context context) {
        return memoryLog(source(context));
    }

    static boolean memoryLog(Source source) {
        return "log".equals(read(source, "rehome_test_memory"));
    }

    /**
     * Made-up items in the player, over the real ones, for the screenshots of states hard to
     * produce ({@code settings put global rehome_test_player song|video|app|all}): a song playing (Spotify), a YouTube
     * video, Netflix's "Back to". Never saved; read at each refresh of the player. Anything else: off.
     */
    static String player(Context context) {
        return player(source(context));
    }

    static String player(Source source) {
        String v = read(source, "rehome_test_player");
        if (v == null) return null;
        v = v.trim();
        return v.equals("song") || v.equals("video") || v.equals("app") || v.equals("all") ? v : null;
    }

    /**
     * Resume without a key press, for the TV check of a session gone ({@code settings put global rehome_test_resume
     * <word>:<n>}): each new value acts once ({@code dry} logs what Resume or ⏯ would do for each item, {@code go}
     * also presses ⏯ on the first song card). Anything else: off.
     */
    static String resume(Context context) {
        return resume(source(context));
    }

    static String resume(Source source) {
        String v = read(source, "rehome_test_resume");
        if (v == null) return null;
        v = v.trim();
        return v.startsWith("dry") || v.startsWith("go") ? v : null;
    }

    /**
     * The red "Debugging on" pill hidden, so screenshots look like a TV with debugging off: Inputs and Wi-Fi with
     * their names, the gear alone ({@code settings put global rehome_test_hide_debug 1}). DebugPill watches the key
     * while Home is in front; deleting it brings the pill back. Anything but 1: off.
     */
    static boolean hideDebug(Context context) {
        return hideDebug(source(context));
    }

    static boolean hideDebug(Source source) {
        String v = read(source, HIDE_DEBUG);
        return v != null && "1".equals(v.trim());
    }

    static final String HIDE_DEBUG = "rehome_test_hide_debug";

    /** Where the hooks are read from: Settings.Global on the TV, a map in TestHooksTest. */
    interface Source {
        String get(String key);
    }

    static Source source(Context context) {
        android.content.ContentResolver cr = context.getContentResolver();
        return k -> Settings.Global.getString(cr, k);
    }

    /** A hook's value, or null when it is unset or ADB is off (adb_enabled missing, 0 or unreadable). */
    static String read(Source source, String key) {
        try {
            return adbOn(source.get(Settings.Global.ADB_ENABLED)) ? source.get(key) : null;
        } catch (RuntimeException e) {
            // Newer Android versions refuse to read keys they do not know: the hooks are simply off there.
            return null;
        }
    }

    /** adb_enabled as Settings.Global.getInt reads it: on when a number other than 0; missing or not a number, off. */
    static boolean adbOn(String value) {
        if (value == null) return false;
        try {
            return Integer.parseInt(value.trim()) != 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
