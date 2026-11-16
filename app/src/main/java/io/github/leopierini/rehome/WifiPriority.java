package io.github.leopierini.rehome;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The owner's order for the saved networks (first = most wanted, e.g. "Home", then "Phone A", then "Phone B"): the one
 * set in Launcher settings (WifiOrderStore), else config.xml wifi_priority. Android 11 has no such order: it joins
 * some saved network and keeps it while it works, so WifiLink applies this one after each scan.
 *
 * <p>Names in the order are matched to the TV's with ’ and ' taken as the same ({@link #key}): an iPhone names its
 * hotspot "Sam’s Phone", and a hand-typed list may say "Sam's Phone".
 */
final class WifiPriority {

    /** Weaker than this a better-ranked network is not worth leaving a working one for. */
    static final int MIN_RSSI = -75;

    private WifiPriority() {
    }

    /**
     * The network to move to, or null to stay. Walks the order from the top: a network that is set aside (no internet
     * last time, password refused) is skipped; reaching the current one means nothing better is in range; the first
     * one in range with a usable signal wins.
     *
     * @param inRange the strongest RSSI seen for each SSID in the last scan
     * @param setAside SSIDs not to be chosen now, the current one included when it is set aside
     */
    static String pick(List<String> order, String current, Map<String, Integer> inRange, Set<String> setAside) {
        for (String ssid : order) {
            if (setAside.contains(ssid)) continue;
            if (ssid.equals(current)) return null;
            Integer rssi = inRange.get(ssid);
            if (rssi != null && rssi >= MIN_RSSI) return ssid;
        }
        return null;
    }

    /** Position in the order, for the dropdown (saved networks listed in this order); MAX_VALUE when not listed. */
    static int rank(List<String> order, String ssid) {
        String e = entry(order, ssid);
        return e == null ? Integer.MAX_VALUE : order.indexOf(e);
    }

    /** The order's entry for the TV's {@code ssid} (its own spelling), or null when it is not in the order. */
    static String entry(List<String> order, String ssid) {
        if (ssid == null) return null;
        String k = key(ssid);
        for (String e : order) if (key(e).equals(k)) return e;
        return null;
    }

    /** {@code ssid} for matching only: typographic single quotes as '. Never shown or stored. */
    static String key(String ssid) {
        return ssid.replace('\u2019', '\'').replace('\u2018', '\'');
    }

    /** How long a network that gave no internet is set aside: 15 min, doubled each time in a row, at most 4 h. */
    static long noInternetPause(int timesInARow) {
        int n = Math.max(0, Math.min(timesInARow - 1, 4));
        return 15L * 60_000L << n;
    }
}
