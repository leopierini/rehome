package io.github.leopierini.rehome;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * How much of today's and this month's traffic went through which hotspot. Android 11's public API cannot split
 * usage by Wi-Fi name, so DataMeter samples TrafficStats' totals (all interfaces, since boot) at every network change
 * and whenever Home comes and goes, and this ledger puts each difference on the network that was connected since the
 * sample before. Plain Java, saved as text (DataMeter writes it with AtomicFile), tested on the JVM (DataLedgerTest).
 *
 * <p>An estimate, and DataMeter labels it so: the launcher does not always run, so a hotspot changed while it was
 * not running is credited with what came before the change, the traffic between the last sample and a shutdown is
 * lost, and after a reboot everything since boot goes to the network seen first. A gap across midnight is split by
 * time, as if the bytes had been used evenly.
 */
final class DataLedger {

    /** Traffic with no network name: offline, Ethernet, or a name the launcher could not read. */
    static final String UNKNOWN = "";
    /** Networks a period keeps by name; more fold their smallest into UNKNOWN (a TV meets two or three). */
    static final int MAX_NETWORKS = 8;

    private static final String MAGIC = "rehome-data 1";

    // The last sample. at < 0: none yet.
    long at = -1, elapsed = -1, boot = -1, counter = -1;
    /** The network at the last sample (null: none), which the next difference is put on. */
    String ssid;
    int dayKey, monthKey;
    final LinkedHashMap<String, Long> today = new LinkedHashMap<>();
    final LinkedHashMap<String, Long> month = new LinkedHashMap<>();

    /**
     * One reading of the counters.
     *
     * @param now     wall clock
     * @param elapsed SystemClock.elapsedRealtime(): smaller than the last one means a reboot
     * @param boot    Settings.Global.BOOT_COUNT, or -1 when the TV does not keep it
     * @param counter TrafficStats total received + sent since boot; negative (unsupported) is ignored
     * @param ssid    the network now, null for none
     * @return whether anything changed that is worth saving
     */
    boolean sample(long now, long elapsed, long boot, long counter, String ssid, TimeZone tz) {
        if (counter < 0) return false;
        boolean changed = roll(now, tz);
        boolean first = at < 0;
        boolean rebooted = !first && (elapsed < this.elapsed || (boot >= 0 && this.boot >= 0 && boot != this.boot));
        if (!first) {
            long delta;
            long start;
            String who;
            if (rebooted) {
                // The counters started again at boot; what the TV used since then is the network's it is on now.
                delta = counter;
                start = now - elapsed;
                who = ssid;
            } else {
                // Counters going back without a reboot: they were reset, what they say now is new.
                delta = counter >= this.counter ? counter - this.counter : counter;
                start = at;
                who = this.ssid;
            }
            if (delta > 0) {
                put(today, who, DataUsage.share(delta, start, now, DataUsage.dayStart(now, tz)));
                put(month, who, DataUsage.share(delta, start, now, DataUsage.monthStart(now, tz)));
                changed = true;
            }
        }
        changed |= first || rebooted || !same(ssid, this.ssid);
        this.at = now;
        this.elapsed = elapsed;
        this.boot = boot;
        this.counter = counter;
        this.ssid = ssid;
        return changed;
    }

    /** A new day or month starts empty. */
    private boolean roll(long now, TimeZone tz) {
        boolean changed = false;
        int d = DataUsage.dayKey(now, tz), m = DataUsage.monthKey(now, tz);
        if (d != dayKey) {
            today.clear();
            dayKey = d;
            changed = true;
        }
        if (m != monthKey) {
            month.clear();
            monthKey = m;
            changed = true;
        }
        return changed;
    }

    private static void put(LinkedHashMap<String, Long> by, String ssid, long bytes) {
        if (bytes <= 0) return;
        DataUsage.add(by, ssid == null ? UNKNOWN : ssid, bytes);
        while (by.size() > MAX_NETWORKS) {
            String smallest = null;
            for (Map.Entry<String, Long> e : by.entrySet()) {
                if (e.getKey().equals(UNKNOWN)) continue;
                if (smallest == null || e.getValue() < by.get(smallest)) smallest = e.getKey();
            }
            if (smallest == null) break;
            DataUsage.add(by, UNKNOWN, by.remove(smallest));
        }
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    long todayTotal() {
        return total(today);
    }

    long monthTotal() {
        return total(month);
    }

    private static long total(Map<String, Long> by) {
        long t = 0;
        for (long v : by.values()) t += v;
        return t;
    }

    // ---- text ----

    String encode() {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        sb.append("last ").append(at).append(' ').append(elapsed).append(' ').append(boot).append(' ')
                .append(counter).append('\n');
        if (ssid != null) sb.append("ssid ").append(escape(ssid)).append('\n');
        sb.append("day ").append(dayKey).append('\n');
        for (Map.Entry<String, Long> e : today.entrySet()) {
            sb.append("d ").append(e.getValue()).append(' ').append(escape(e.getKey())).append('\n');
        }
        sb.append("month ").append(monthKey).append('\n');
        for (Map.Entry<String, Long> e : month.entrySet()) {
            sb.append("m ").append(e.getValue()).append(' ').append(escape(e.getKey())).append('\n');
        }
        return sb.toString();
    }

    /** The ledger encode() wrote, or null for anything else (then DataMeter starts a new one). */
    static DataLedger decode(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[0].equals(MAGIC)) return null;
        DataLedger l = new DataLedger();
        boolean last = false;
        try {
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.startsWith("last ")) {
                    String[] p = line.substring(5).split(" ");
                    l.at = Long.parseLong(p[0]);
                    l.elapsed = Long.parseLong(p[1]);
                    l.boot = Long.parseLong(p[2]);
                    l.counter = Long.parseLong(p[3]);
                    last = true;
                } else if (line.startsWith("ssid ")) {
                    l.ssid = unescape(line.substring(5));
                } else if (line.startsWith("day ")) {
                    l.dayKey = Integer.parseInt(line.substring(4).trim());
                } else if (line.startsWith("month ")) {
                    l.monthKey = Integer.parseInt(line.substring(6).trim());
                } else if (line.startsWith("d ") || line.startsWith("m ")) {
                    int space = line.indexOf(' ', 2);
                    long bytes = Long.parseLong(space < 0 ? line.substring(2) : line.substring(2, space));
                    String name = space < 0 ? UNKNOWN : unescape(line.substring(space + 1));
                    DataUsage.add(line.charAt(0) == 'd' ? l.today : l.month, name, bytes);
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return last ? l : null;
    }

    /** One line per entry: a backslash and a line break in a network's name are written as \\ and \n. */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
