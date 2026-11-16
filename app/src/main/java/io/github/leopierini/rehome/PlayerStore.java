package io.github.leopierini.rehome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * category_player: the player's memory as a text file (filesDir/player_memory.txt), one item per line, fields split by
 * tabs, and the names of its art files (filesDir/player_art). Pure, tested on the JVM; MediaMemory does the I/O.
 *
 * <p>Not saved: whether a session is live or playing (that is now, not then) and the Watch Next row (read again).
 */
final class PlayerStore {

    static final String FILE = "player_memory.txt", ART_DIR = "player_art";
    /**
     * First field of every line: a line of another format is skipped, never misread. p2 adds the content's link and
     * the picture's https source at the end; p1 lines (the builds before) are still read, without them.
     */
    private static final String VERSION = "p2", VERSION_1 = "p1";
    private static final String NULL = "\\0";
    private static final int FIELDS = 18, FIELDS_1 = 16;

    private PlayerStore() {}

    static String encode(List<PlayerPick.Item> items) {
        StringBuilder sb = new StringBuilder();
        if (items == null) return "";
        for (PlayerPick.Item i : items) {
            if (i == null || i.key == null) continue;
            String[] f = {VERSION, i.key, Integer.toString(i.kind), i.pkg, i.title, i.artist, i.album, i.art,
                    Long.toString(i.position), Long.toString(i.duration), Long.toString(i.lastPlayed),
                    Long.toString(i.playedMs), i.year, i.genre, Integer.toString(i.track), Integer.toString(i.tracks),
                    i.link, i.artUrl};
            for (int k = 0; k < f.length; k++) {
                if (k > 0) sb.append('\t');
                sb.append(escape(f[k]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** The items a file holds; a line that does not read whole is skipped. Never throws. */
    static List<PlayerPick.Item> decode(String text) {
        List<PlayerPick.Item> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            boolean v2 = f.length == FIELDS && VERSION.equals(f[0]);
            if (!v2 && !(f.length == FIELDS_1 && VERSION_1.equals(f[0]))) continue;
            try {
                PlayerPick.Item i = new PlayerPick.Item();
                i.key = unescape(f[1]);
                i.kind = Integer.parseInt(f[2]);
                i.pkg = unescape(f[3]);
                i.title = unescape(f[4]);
                i.artist = unescape(f[5]);
                i.album = unescape(f[6]);
                i.art = unescape(f[7]);
                i.position = Long.parseLong(f[8]);
                i.duration = Long.parseLong(f[9]);
                i.lastPlayed = Long.parseLong(f[10]);
                i.playedMs = Long.parseLong(f[11]);
                i.year = unescape(f[12]);
                i.genre = unescape(f[13]);
                i.track = Integer.parseInt(f[14]);
                i.tracks = Integer.parseInt(f[15]);
                if (v2) {
                    i.link = unescape(f[16]);
                    i.artUrl = unescape(f[17]);
                }
                if (i.key == null || i.pkg == null || i.kind < PlayerPick.SONG || i.kind > PlayerPick.APP) continue;
                out.add(i);
            } catch (NumberFormatException e) {
                // A line cut short by a crash mid-write, or edited by hand: skipped.
            }
        }
        return out;
    }

    /**
     * The art file of one item: from its key and title, so a song card (one key per app) gets a new file with each
     * track and never shows the previous track's cover.
     */
    static String artName(String key, String title) {
        String s = (key == null ? "" : key) + "\u0001" + (title == null ? "" : title);
        long h = 0xcbf29ce484222325L;   // FNV-1a, 64 bit: stable across runs and JVMs
        for (int k = 0; k < s.length(); k++) {
            h ^= s.charAt(k);
            h *= 0x100000001b3L;
        }
        return "a_" + Long.toHexString(h) + ".jpg";
    }

    /**
     * The same, for a picture from another source (a URI the session gave, iTunes' cover): a cover that replaces
     * another gets a new file, so the Home (whose picture cache goes by path) shows it, and a decode of the old file
     * never reads a half-written new one.
     */
    static String artName(String key, String title, String source) {
        return artName(key, (title == null ? "" : title) + "\u0002" + (source == null ? "" : source));
    }

    /** The art files on disk no kept item points to (by file name). */
    static Set<String> orphanArt(List<PlayerPick.Item> kept, List<String> filesOnDisk) {
        Set<String> used = new HashSet<>();
        if (kept != null) {
            for (PlayerPick.Item i : kept) {
                if (i == null || i.art == null) continue;
                int slash = i.art.lastIndexOf('/');
                used.add(slash >= 0 ? i.art.substring(slash + 1) : i.art);
            }
        }
        Set<String> out = new HashSet<>();
        if (filesOnDisk != null) for (String f : filesOnDisk) if (f != null && !used.contains(f)) out.add(f);
        return out;
    }

    /** A field on one tab-separated line: null as "\\0"; backslash, tab and line breaks escaped (SongLookup's cache too). */
    static String escape(String s) {
        if (s == null) return NULL;
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    static String unescape(String s) {
        if (NULL.equals(s)) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c != '\\' || k + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++k);
            sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
        }
        return sb.toString();
    }
}
