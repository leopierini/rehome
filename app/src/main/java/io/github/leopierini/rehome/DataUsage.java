package io.github.leopierini.rehome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The data meter's pure logic (DataMeter has the Android side): sizes in the UI's language (2.3 GB, Italian 2,3 GB),
 * today's and this month's windows in local time, and which name an app's traffic goes under. Plain Java, tested on
 * the JVM (DataUsageTest).
 */
final class DataUsage {

    private DataUsage() {}

    /** NetworkStats.Bucket.UID_REMOVED and UID_TETHERING (the system's own values). */
    static final int UID_REMOVED = -4, UID_TETHERING = -5;
    /** Process.FIRST_APPLICATION_UID: below it, Android itself (system server, media, DNS, root). */
    private static final int FIRST_APP_UID = 10000;

    /*
     * The lines that are not one app's or one network's: keys in the maps, words only on screen (label). A control
     * character starts each, so no app label or Wi-Fi name can be one.
     */
    static final String SYSTEM = "\u0001system";
    static final String REMOVED = "\u0001removed";
    /**
     * The shell UID: ADB over the network (screenshots, installs). Its own line, not Android's: while debugging is on
     * it can be most of the day, and it is traffic inside the hotspot's network, not the internet.
     */
    static final String ADB = "\u0001adb";
    /** Process.SHELL_UID. */
    private static final int SHELL_UID = 2000;
    /** Apps this launcher may not see under Android 11's package visibility: services, never a tile. */
    static final String OTHER = "\u0001other";

    /** How many characters of an app's name the header line keeps. */
    static final int HEADER_LABEL_MAX = 16;
    /** The least an app must have used to be named on its own line (one threshold for header and topApp). */
    static final long APP_MIN = 1_000_000L;
    private static final String DOT = "  ·  ";

    // ---- text ----

    /**
     * "740 MB" under a gigabyte, "2.3 GB" from there (Italian "2,3 GB"), in decimal units like the phone that bills the
     * hotspot. Empty for nothing at all: the meter shows nothing rather than "0 MB".
     */
    static String size(Words w, long bytes) {
        if (bytes <= 0) return "";
        long mb = Math.round(bytes / 1e6);
        if (mb < 1) return "< 1 MB";
        if (mb < 1000) return mb + " MB";
        long tenths = Math.round(bytes / 1e8);
        return w.get(R.string.data_gb, tenths / 10.0);
    }

    /**
     * "Today 2.3 GB  ·  Netflix 1.9 GB", the app only when it used something worth naming; empty for nothing today.
     * Since the meter left the header (Task 5, the owner's call) DataMeter passes no app: the app part and topApp are
     * kept, tested, for a header line that names one again.
     */
    static String header(Words w, long today, String app, long appBytes) {
        String total = size(w, today);
        if (total.isEmpty()) return "";
        String line = w.get(R.string.data_today, total);
        if (app != null && !app.isEmpty() && appBytes >= APP_MIN) {
            line += DOT + shortLabel(app, HEADER_LABEL_MAX) + " " + size(w, appBytes);
        }
        return line;
    }

    /**
     * Without the usage-stats app-op: only what TrafficStats knows, everything since the last real boot. Not "since
     * switched on": the remote only puts a Philips set in standby, so the counters can span several days.
     */
    static String sinceBoot(Words w, long bytes) {
        String s = size(w, bytes);
        return s.isEmpty() ? "" : w.get(R.string.data_since_boot, s);
    }

    /** A line's name on screen: the groups' and the placeholders' own words, an app's or a network's name as it is. */
    static String label(Words w, String name) {
        switch (name) {
            case SYSTEM: return w.get(R.string.data_system);
            case REMOVED: return w.get(R.string.data_removed);
            case ADB: return w.get(R.string.data_adb);
            case OTHER: return w.get(R.string.data_other);
            case NAMING: return w.get(R.string.data_naming);
            case UNATTRIBUTED: return w.get(R.string.data_unattributed);
            case OTHER_NETWORKS: return w.get(R.string.data_other_networks);
            default: return name;
        }
    }

    static String shortLabel(String label, int max) {
        if (label == null) return "";
        String s = label.trim();
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }

    // ---- windows ----

    /** Local midnight that starts the day of {@code now} (a 23- or 25-hour day around a clock change too). */
    static long dayStart(long now, TimeZone tz) {
        Calendar c = calendar(now, tz);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Local midnight on the first of the calendar month of {@code now}. */
    static long monthStart(long now, TimeZone tz) {
        Calendar c = calendar(now, tz);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 20261115 for 15 November 2026, local time. */
    static int dayKey(long now, TimeZone tz) {
        Calendar c = calendar(now, tz);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    /** 202611 for November 2026, local time. */
    static int monthKey(long now, TimeZone tz) {
        Calendar c = calendar(now, tz);
        return c.get(Calendar.YEAR) * 100 + (c.get(Calendar.MONTH) + 1);
    }

    private static Calendar calendar(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        return c;
    }

    /**
     * The part of {@code bytes}, used evenly from {@code start} to {@code end}, that falls after {@code from}. A
     * clock set back leaves no interval: then all of it counts.
     */
    static long share(long bytes, long start, long end, long from) {
        if (end <= start || from <= start) return bytes;
        if (from >= end) return 0;
        return Math.round(bytes * ((double) (end - from) / (end - start)));
    }

    // ---- apps ----

    /** The fixed name for traffic that is not one app's, or null for an app (named by its label). */
    static String group(int uid) {
        if (uid == UID_REMOVED) return REMOVED;
        if (uid == SHELL_UID) return ADB;
        if (uid < FIRST_APP_UID) return SYSTEM;   // UID_TETHERING too: the TV shares no network, so it is the system's
        return null;
    }

    /**
     * The package that names a UID several packages share: the one with a tile (a launch entry), else always the same
     * one (the first by name), whatever order PackageManager lists them in.
     */
    static String pickPackage(String[] pkgs, Set<String> launchable) {
        if (pkgs == null || pkgs.length == 0) return null;
        String[] sorted = pkgs.clone();
        Arrays.sort(sorted);
        for (String p : sorted) if (launchable.contains(p)) return p;
        return sorted[0];
    }

    static void add(Map<String, Long> by, String name, long bytes) {
        Long was = by.get(name);
        by.put(name, (was == null ? 0 : was) + bytes);
    }

    /** The biggest first (a tie by name, so the list does not shuffle between refreshes), at most n, none empty. */
    static List<Map.Entry<String, Long>> top(Map<String, Long> by, int n) {
        List<Map.Entry<String, Long>> all = new ArrayList<>();
        for (Map.Entry<String, Long> e : by.entrySet()) if (e.getValue() > 0) all.add(e);
        all.sort((a, b) -> {
            int c = Long.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareToIgnoreCase(b.getKey());
        });
        return all.size() > n ? new ArrayList<>(all.subList(0, n)) : all;
    }

    /** The detail's line while today's apps are still being named (DataMeter.showDetail). */
    static final String NAMING = "\u0001naming";

    /**
     * The lines of the detail's "Top apps today" section, {name, size}: the top n, none when no app used data, or
     * one {@link #NAMING} line while the names are not ready (null), so the detail opens at once with its totals and
     * the names fill in (speed review M3: before, nothing showed until they were ready, then the detail popped up).
     */
    static List<String[]> appLines(Words w, Map<String, Long> named, int n) {
        List<String[]> lines = new ArrayList<>();
        if (named == null) {
            lines.add(new String[]{label(w, NAMING), ""});
            return lines;
        }
        for (Map.Entry<String, Long> e : top(named, n)) {
            lines.add(new String[]{label(w, e.getKey()), size(w, e.getValue())});
        }
        return lines;
    }

    /**
     * The app the header names: the biggest that is an app (not Android itself), when it used at least 1 MB. Unused
     * while the meter's line names no app (see header); kept with its tests.
     */
    static Map.Entry<String, Long> topApp(Map<String, Long> by) {
        for (Map.Entry<String, Long> e : top(by, by.size())) {
            String k = e.getKey();
            if (k.equals(SYSTEM) || k.equals(REMOVED) || k.equals(OTHER) || k.equals(ADB)) continue;
            return e.getValue() >= APP_MIN ? e : null;
        }
        return null;
    }

    /**
     * No app used more than the day did: per-UID history comes in 2-hour buckets aligned to UTC, and the one that
     * holds local midnight (CET) is counted in full, so in the first hour of a day yesterday evening's film could
     * otherwise read as today's. The device total uses hourly buckets and does not have that error.
     */
    static void capAt(Map<String, Long> by, long max) {
        for (Map.Entry<String, Long> e : by.entrySet()) {
            if (e.getValue() > max) e.setValue(Math.max(0, max));
        }
    }

    // ---- when to ask NetworkStatsManager again ----

    /** Traffic since the last reading below which the numbers shown are still right (to the megabyte shown). */
    static final long READ_STEP = 1_000_000L;

    /**
     * Whether a refresh must query NetworkStatsManager again. Each query makes the system poll and persist its
     * netstats files, so a Home left in front for hours does not do it every few minutes for nothing: the last full
     * reading stays while the TV's own counter has moved less than {@link #READ_STEP} on the same day. A new day, a
     * counter that went back (reboot or reset) or an unsupported counter always read again.
     */
    static boolean needsRead(boolean lastFull, int lastDay, int day, long lastCounter, long counter) {
        if (!lastFull || lastDay != day) return true;
        if (counter < 0 || lastCounter < 0 || counter < lastCounter) return true;
        return counter - lastCounter >= READ_STEP;
    }

    // ---- per network (the dropdown) ----

    static final String UNATTRIBUTED = "\u0001unattributed";
    static final String OTHER_NETWORKS = "\u0001other-networks";
    /** Below this much (or this share of the day) the ledger's gap from Android's own total is rounding, not news. */
    static final long UNATTRIBUTED_MIN = 10_000_000L;

    /** One line of the detail's "By network" part: a network (or the rest) with today's and this month's bytes. */
    static final class Net {
        final String name;
        final long today, month;

        Net(String name, long today, long month) {
            this.name = name;
            this.today = today;
            this.month = month;
        }
    }

    /**
     * The per-network lines, biggest this month first. The ledger is an estimate (DataLedger): a gap it never sampled
     * is spread over time, so it can give a network more than Android's own total for the day or month. Its named
     * networks are scaled down together so they never add up to more than {@code today} / {@code month} (display
     * only). At most {@code maxRows} network lines: from one more on, the last line is "Other networks" with the
     * rest. Then "Unattributed", what Android counted and the ledger did not place, when it is clearly more than rounding.
     * Nothing at all when no network has a name (no location permission, so no Wi-Fi name).
     */
    static List<Net> networks(Map<String, Long> netToday, Map<String, Long> netMonth, long today, long month,
                              int maxRows) {
        Map<String, Long> order = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : netMonth.entrySet()) {
            if (!e.getKey().equals(DataLedger.UNKNOWN)) order.put(e.getKey(), Math.max(1, e.getValue()));
        }
        // Today-only networks sort after the month's, by name; a zero would drop them from top().
        for (Map.Entry<String, Long> e : netToday.entrySet()) {
            if (!e.getKey().equals(DataLedger.UNKNOWN) && !order.containsKey(e.getKey())) order.put(e.getKey(), 1L);
        }
        List<Map.Entry<String, Long>> named = top(order, order.size());
        List<Net> out = new ArrayList<>();
        if (named.isEmpty()) return out;
        int n = named.size();
        long sumToday = 0;
        for (Map.Entry<String, Long> e : named) sumToday += value(netToday, e.getKey());
        // Today first, scaled to Android's day. Each month is then its today plus the rest of its month, and only that
        // rest is scaled into what the day leaves of Android's month: a network's month is never less than its today,
        // and the months still never add up to more than Android's.
        long[] ts = new long[n], before = new long[n];
        long placedTodayFirst = 0, sumBefore = 0;
        for (int k = 0; k < n; k++) {
            String ssid = named.get(k).getKey();
            ts[k] = scaled(value(netToday, ssid), sumToday, today);
            before[k] = Math.max(0, value(netMonth, ssid) - ts[k]);
            placedTodayFirst += ts[k];
            sumBefore += before[k];
        }
        long monthRoom = Math.max(0, month - placedTodayFirst);
        long placedToday = 0, placedMonth = 0, restNetToday = 0, restNetMonth = 0;
        int rows = n > maxRows ? maxRows - 1 : n;
        for (int k = 0; k < n; k++) {
            String ssid = named.get(k).getKey();
            long t = ts[k];
            long m = t + scaled(before[k], sumBefore, monthRoom);
            placedToday += t;
            placedMonth += m;
            if (k < rows) {
                out.add(new Net(ssid, t, m));
            } else {
                restNetToday += t;
                restNetMonth += m;
            }
        }
        if (rows < named.size()) out.add(new Net(OTHER_NETWORKS, restNetToday, restNetMonth));
        long restToday = today - placedToday, restMonth = month - placedMonth;
        if (worth(restToday, today) || worth(restMonth, month)) {
            out.add(new Net(UNATTRIBUTED, Math.max(0, restToday), Math.max(0, restMonth)));
        }
        return out;
    }

    /** {@code v} of a sum that may not exceed {@code total}: scaled down (rounded down) when the sum does. */
    private static long scaled(long v, long sum, long total) {
        if (sum <= total || sum <= 0) return v;
        return (long) (v * ((double) Math.max(0, total) / sum));
    }

    private static long value(Map<String, Long> by, String k) {
        Long v = by.get(k);
        return v == null ? 0 : v;
    }

    private static boolean worth(long rest, long total) {
        return rest >= UNATTRIBUTED_MIN && rest * 20 >= total;
    }
}
