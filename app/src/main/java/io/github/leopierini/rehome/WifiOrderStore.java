package io.github.leopierini.rehome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Wi-Fi priority order set in Launcher settings (WifiOrderActivity): the SSIDs, most wanted first, exactly as the
 * TV named them, kept with the other settings (Config.SETTINGS_NAME, key {@link #KEY}, a JSON array of strings) so a
 * reinstall that keeps data keeps it. Nothing stored: config.xml wifi_priority counts. Stored, even empty, it wins:
 * an order emptied here means no automatic switching, whatever config.xml says.
 *
 * <p>WifiLink reads it every time it applies the order (and the dropdown every time it ranks a network), so a change
 * here counts from the next scan, with no restart. Home does not rebuild for it (the settings' change count is left
 * alone): nothing on Home shows the order until the Wi-Fi dropdown opens.
 */
final class WifiOrderStore {
    static final String KEY = "wifi_order";

    private WifiOrderStore() {
    }

    /** The order in force: the one set here, else config.xml wifi_priority (empty: no switching). */
    static List<String> get(Context c) {
        return get(prefs(c), config(c));
    }

    static void set(Context c, List<String> order) {
        set(prefs(c), order);
    }

    static List<String> config(Context c) {
        return Collections.unmodifiableList(Arrays.asList(c.getResources().getStringArray(R.array.wifi_priority)));
    }

    /** Whether an order was set here (it then overrides config.xml, even when empty). */
    static boolean isSet(SharedPreferences p) {
        return parse(p.getString(KEY, null)) != null;
    }

    static List<String> get(SharedPreferences p, List<String> config) {
        List<String> stored = parse(p.getString(KEY, null));
        return stored != null ? stored : config;
    }

    /** apply(): the in-memory value changes at once, so WifiLink (same process) sees it on its next scan. */
    static void set(SharedPreferences p, List<String> order) {
        JSONArray a = new JSONArray();
        for (String ssid : order) a.put(ssid);
        p.edit().putString(KEY, a.toString()).apply();
    }

    /** The stored list, or null when there is none or it is damaged (then config.xml counts). */
    private static List<String> parse(String json) {
        if (json == null) return null;
        try {
            JSONArray a = new JSONArray(json);
            List<String> out = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) {
                Object o = a.get(i);
                if (!(o instanceof String)) return null;
                out.add((String) o);
            }
            return Collections.unmodifiableList(out);
        } catch (JSONException e) {
            return null;
        }
    }

    // ---- what the screen shows and does --------------------------------------------------------------

    /**
     * The entries of {@code order} that are still saved networks, in order, each spelt as in {@code order}; names
     * match with ’ and ' taken as the same (WifiPriority.key), and a second spelling of one name is dropped. Saved
     * networks that are not in the order are not appended ({@link #notOrdered} lists them).
     */
    static List<String> merge(List<String> order, List<String> saved) {
        Set<String> savedKeys = keys(saved);
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String ssid : order) {
            String k = WifiPriority.key(ssid);
            if (savedKeys.contains(k) && seen.add(k)) out.add(ssid);
        }
        return out;
    }

    /**
     * What to store after the page edited the order it shows: {@code edited}, with each entry of {@code stored} that
     * the page could not show (not in {@code saved}, e.g. a partial answer right after a boot) put back right after
     * the network it followed in {@code stored}, or at the top when none it followed is still in the order. So an edit
     * never drops a network the TV merely failed to list; one removed on the page is a listed one and stays out.
     */
    static List<String> withUnseen(List<String> stored, List<String> edited, List<String> saved) {
        Set<String> listed = keys(saved);
        Set<String> kept = keys(edited);
        List<String> top = new ArrayList<>();
        java.util.Map<String, List<String>> after = new java.util.HashMap<>();
        String anchor = null;
        for (String ssid : stored) {
            String k = WifiPriority.key(ssid);
            if (kept.contains(k)) {
                anchor = k;
            } else if (!listed.contains(k)) {
                List<String> to = anchor == null ? top : after.get(anchor);
                if (to == null) after.put(anchor, to = new ArrayList<>());
                to.add(ssid);
            }
            // Listed but no longer kept: removed on the page; what followed it follows the one before it.
        }
        List<String> out = new ArrayList<>(top);
        for (String ssid : edited) {
            out.add(ssid);
            List<String> followers = after.remove(WifiPriority.key(ssid));
            if (followers != null) out.addAll(followers);
        }
        return out;
    }

    /**
     * The networks the settings row counts: those the page shows (order ∩ saved) once the companion has answered;
     * the whole order while it has not, or cannot (helper missing or refused: the page then shows no list to
     * contradict).
     */
    static int shownCount(List<String> order, List<String> savedOrNull) {
        return savedOrNull == null ? order.size() : merge(order, savedOrNull).size();
    }

    /** The saved networks not in {@code order}, by name (case aside), for "Add to order". */
    static List<String> notOrdered(List<String> order, List<String> saved) {
        Set<String> ordered = keys(order);
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String ssid : saved) {
            String k = WifiPriority.key(ssid);
            if (!ordered.contains(k) && seen.add(k)) out.add(ssid);
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** {@code order} with entry {@code i} moved by {@code delta} places; unchanged past either end. */
    static List<String> moved(List<String> order, int i, int delta) {
        List<String> out = new ArrayList<>(order);
        int to = i + delta;
        if (i < 0 || i >= out.size() || to < 0 || to >= out.size()) return out;
        out.add(to, out.remove(i));
        return out;
    }

    static List<String> removed(List<String> order, int i) {
        List<String> out = new ArrayList<>(order);
        if (i >= 0 && i < out.size()) out.remove(i);
        return out;
    }

    /** {@code order} with {@code ssid} at the end, unless it is already in it (either apostrophe). */
    static List<String> added(List<String> order, String ssid) {
        List<String> out = new ArrayList<>(order);
        if (WifiPriority.entry(order, ssid) == null) out.add(ssid);
        return out;
    }

    /**
     * Where focus goes in the order after entry {@code i} was removed and {@code size} are left: the one that slid
     * into its place, else the one before; -1 when the order is now empty.
     */
    static int focusAfterRemove(int i, int size) {
        if (size <= 0) return -1;
        return Math.min(i, size - 1);
    }

    private static Set<String> keys(List<String> ssids) {
        Set<String> out = new HashSet<>();
        for (String s : ssids) out.add(WifiPriority.key(s));
        return out;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
}
