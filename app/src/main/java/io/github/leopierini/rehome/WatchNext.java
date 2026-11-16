package io.github.leopierini.rehome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Watch Next for the player (category_watch_next): which of the TV provider's Watch Next rows the Home shows, and the
 * rules for opening one. Pure logic, tested on the JVM; WatchNextRow reads the provider.
 *
 * Apps leave rows behind for months (a six-month-old one, another at 99% of its episode), so the player keeps only
 * what was watched in the last 30 days and is not finished, one item per title and app, newest first.
 */
final class WatchNext {

    /** TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_*: copied, so these tests run without the Android jar. */
    static final int TYPE_CONTINUE = 0, TYPE_NEXT = 1, TYPE_NEW = 2, TYPE_WATCHLIST = 3;
    static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    /** Watched this far, it is finished: the credits are rolling. */
    static final float WATCHED = 0.95f;
    static final int MAX_ITEMS = 6;
    /**
     * A row stamped this far ahead of the TV's clock still counts (an app's server a little ahead). Further ahead it
     * is a wrong stamp (seconds written as milliseconds by a thousand) or a TV clock far behind: dropped, never pinned
     * first for ever.
     */
    static final long MAX_AHEAD_MS = 24L * 60 * 60 * 1000;
    /** Change notices settle this long before the read, and wait no longer than SETTLE_MAX_MS from the first one. */
    static final long SETTLE_MS = 1500, SETTLE_MAX_MS = 5000;

    /** One Watch Next row, as read from the provider. */
    static final class Item {
        long id;
        String pkg, title, episodeTitle, season, episode, poster, intentUri;
        /** COLUMN_SHORT_DESCRIPTION: the plot RaiPlay fills in (the player's right column), or null. */
        String description;
        int watchNextType;
        long lastEngagement, position, duration;
    }

    private WatchNext() {}

    /**
     * What the player shows: rows of usable apps (installed, enabled, not hidden) that have a title and a link, engaged
     * in the last 30 days and not watched to the end, not on a watchlist, one per title and app (the newest), newest
     * first, at most six.
     */
    static List<Item> filter(List<Item> rows, long now, Predicate<String> usable) {
        return filter(rows, now, 0, usable);
    }

    /**
     * @param floor a time the real clock is surely past (the launcher's install). A TV clock behind it is wrong (booted
     *              offline, no time yet): nothing can be aged then, so no row at all rather than years-old ones.
     */
    static List<Item> filter(List<Item> rows, long now, long floor, Predicate<String> usable) {
        List<Item> out = new ArrayList<>();
        if (rows == null || now < floor) return out;
        Map<String, Item> newest = new HashMap<>();
        for (Item i : rows) {
            if (i == null || i.pkg == null || blank(i.title) || blank(i.intentUri)) continue;
            // A watchlist row is something saved for later, not something to continue.
            if (i.watchNextType == TYPE_WATCHLIST) continue;
            // No date: it cannot be aged, and an undated row is how stale ones linger.
            long age = now - i.lastEngagement;
            if (i.lastEngagement <= 0 || age > MAX_AGE_MS || age < -MAX_AHEAD_MS) continue;
            if (i.duration > 0 && i.position >= WATCHED * i.duration) continue;
            if (!usable.test(i.pkg)) continue;
            String key = key(i);
            Item had = newest.get(key);
            if (had == null || newer(i, had)) newest.put(key, i);
        }
        out.addAll(newest.values());
        out.sort((a, b) -> newer(a, b) ? -1 : newer(b, a) ? 1 : 0);
        return out.size() > MAX_ITEMS ? new ArrayList<>(out.subList(0, MAX_ITEMS)) : out;
    }

    /** One card per app and title. */
    private static String key(Item i) {
        return i.pkg + '\n' + (i.title == null ? "" : i.title.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * After a change notice at {@code now}, how long until the read: SETTLE_MS after the latest notice, but never
     * past SETTLE_MAX_MS from the first one still waiting ({@code firstPending}), so an app that rewrites its row every
     * second does not put the read off for ever.
     */
    static long settleDelay(long firstPending, long now) {
        return Math.max(0, Math.min(SETTLE_MS, firstPending + SETTLE_MAX_MS - now));
    }

    private static boolean newer(Item a, Item b) {
        return a.lastEngagement != b.lastEngagement ? a.lastEngagement > b.lastEngagement : a.id > b.id;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** How far into it, 0 to 1, for the bar; -1 for no bar (unknown, or not started: a "next episode" row). */
    static float progress(Item i) {
        if (i.duration <= 0 || i.position <= 0) return -1f;
        return Math.min(1f, i.position / (float) i.duration);
    }

    /** "S2 E5 · Il ritorno": what the provider gives of it, in short; empty when it gives nothing. */
    static String episodeLine(String season, String episode, String episodeTitle) {
        StringBuilder sb = new StringBuilder();
        if (!blank(season)) sb.append('S').append(season.trim());
        if (!blank(episode)) sb.append(sb.length() > 0 ? " " : "").append('E').append(episode.trim());
        if (!blank(episodeTitle)) sb.append(sb.length() > 0 ? " · " : "").append(episodeTitle.trim());
        return sb.toString();
    }

    /** Everything a card shows (and whether its poster is in): an unchanged row is not drawn again. */
    static String signature(List<Item> items, List<Boolean> withPoster) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < items.size(); k++) {
            Item i = items.get(k);
            sb.append(i.id).append('|').append(i.pkg).append('|').append(i.title).append('|').append(i.episodeTitle)
                    .append('|').append(i.season).append('|').append(i.episode).append('|').append(i.intentUri)
                    .append('|').append(Math.round(progress(i) * 100)).append('|').append(withPoster.get(k))
                    .append('\n');
        }
        return sb.toString();
    }

    // ---- the row's last cards, kept for the next start (final review M2) ---------------------------------

    /** A card as kept: what its text card shows and opens. */
    static final class Saved {
        Item item;
        /** The publishing app's name. */
        String app;
    }

    /**
     * The cards on screen, as one string for the launcher's own preferences: the next start builds "Continua a
     * guardare" from it in the first frame (text cards), and the real reading then corrects it in place. One card per
     * line, tab-separated, escaped; null fields as "\\0".
     */
    static String encode(List<Item> items, List<String> apps) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < items.size() && k < MAX_ITEMS; k++) {
            Item i = items.get(k);
            String[] f = {String.valueOf(i.id), i.pkg, i.title, i.episodeTitle, i.season, i.episode, i.poster,
                    i.intentUri, String.valueOf(i.watchNextType), String.valueOf(i.lastEngagement),
                    String.valueOf(i.position), String.valueOf(i.duration), k < apps.size() ? apps.get(k) : null};
            for (int n = 0; n < f.length; n++) sb.append(n == 0 ? "" : "\t").append(escape(f[n]));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** The cards {@link #encode} kept; a line it cannot read (another version's, damaged) is left out. */
    static List<Saved> decode(String text) {
        List<Saved> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            if (out.size() >= MAX_ITEMS) break;
            String[] f = line.split("\t", -1);
            if (f.length != 13) continue;
            try {
                Item i = new Item();
                i.id = Long.parseLong(f[0]);
                i.pkg = unescape(f[1]);
                i.title = unescape(f[2]);
                i.episodeTitle = unescape(f[3]);
                i.season = unescape(f[4]);
                i.episode = unescape(f[5]);
                i.poster = unescape(f[6]);
                i.intentUri = unescape(f[7]);
                i.watchNextType = Integer.parseInt(f[8]);
                i.lastEngagement = Long.parseLong(f[9]);
                i.position = Long.parseLong(f[10]);
                i.duration = Long.parseLong(f[11]);
                if (i.pkg == null || i.title == null || i.pkg.isEmpty() || i.title.isEmpty()) continue;
                Saved sv = new Saved();
                sv.item = i;
                sv.app = unescape(f[12]);
                if (sv.app == null) sv.app = i.pkg;
                out.add(sv);
            } catch (RuntimeException e) {
                // NumberFormatException, a bad escape: that card only.
            }
        }
        return out;
    }

    private static String escape(String s) {
        if (s == null) return "\\0";
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\\') sb.append("\\\\");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        if ("\\0".equals(s)) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++k >= s.length()) throw new IllegalArgumentException("escape at the end");
            char e = s.charAt(k);
            if (e == '\\') sb.append('\\');
            else if (e == 't') sb.append('\t');
            else if (e == 'n') sb.append('\n');
            else if (e == 'r') sb.append('\r');
            else throw new IllegalArgumentException("bad escape");
        }
        return sb.toString();
    }

    // ---- opening a row --------------------------------------------------------------------------------

    /**
     * Intent.FLAG_GRANT_READ/WRITE/PERSISTABLE/PREFIX_URI_PERMISSION, copied so these tests run without the Android
     * jar (WatchNextTest checks them against Intent's).
     */
    static final int GRANT_FLAGS = 0x00000001 | 0x00000002 | 0x00000040 | 0x00000080;
    /** Intent.FLAG_ACTIVITY_NEW_TASK. */
    static final int NEW_TASK = 0x10000000;

    /**
     * The flags a row's link starts with: every URI grant it asks for dropped (the launcher passes on none of its own
     * access, the theme provider's for one), in a task of its own. Other launch flags pass: the target can only be
     * the publisher's own exported activity (mayLaunch), which it could start with them itself.
     */
    static int safeFlags(int flags) {
        return (flags & ~GRANT_FLAGS) | NEW_TASK;
    }

    /** A link with no package and no component (a plain https link) is pinned to its publisher: no browser, no chooser. */
    static boolean needsPackage(boolean hasComponent, boolean hasPackage) {
        return !hasComponent && !hasPackage;
    }

    /**
     * A row's link is opened only into the app that published it, and only an activity that app exports: the provider
     * stamps package_name itself, while the link is whatever the app wrote. Resolved anywhere else (a browser for an
     * https link, another app), or to an activity not exported, the launcher would be lending its hand, so it opens
     * the app itself instead. Never a factory or service menu.
     */
    static boolean mayLaunch(String publisher, String resolvedPkg, boolean exported) {
        if (!exported || publisher == null || resolvedPkg == null || !resolvedPkg.equals(publisher)) return false;
        return !AppLaunchActivity.isServiceMenu(resolvedPkg);
    }

    /** A link worth parsing: intent:, android-app:, a web or app scheme. Not a file, a provider or a script. */
    static boolean launchableLink(String uri) {
        if (blank(uri)) return false;
        int colon = uri.indexOf(':');
        if (colon <= 0) return false;
        String scheme = uri.substring(0, colon).toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "file":
            case "content":
            case "javascript":
            case "data":
            case "about":
                return false;
            default:
                return true;
        }
    }

    /** A poster fetched over the network: https only (and only then on the metered hotspot, see PosterCache). */
    static boolean posterOverNetwork(String uri) {
        return uri != null && uri.regionMatches(true, 0, "https://", 0, 8);
    }

    /** A poster another app serves on the TV itself: read without the network. */
    static boolean posterLocal(String uri) {
        return uri != null && (uri.regionMatches(true, 0, "content://", 0, 10)
                || uri.regionMatches(true, 0, "android.resource://", 0, 19));
    }
}
