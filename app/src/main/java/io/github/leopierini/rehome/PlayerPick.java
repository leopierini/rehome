package io.github.leopierini.rehome;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * category_player: what the player shows. Pure logic, tested on the JVM: which media sessions are remembered and as
 * what kind, and how the remembered items and the TV provider's Watch Next rows become the player's list.
 *
 * <p>Kinds: a SONG gets ⏮ ⏯ ⏭, a VIDEO "Resume", an APP (Netflix, which tells nothing of what plays) "Back to …".
 * A song is remembered once per app (twenty minutes of Spotify are one card, following the track), a video once per
 * app and title, an APP once per app.
 */
final class PlayerPick {

    static final int SONG = 0, VIDEO = 1, APP = 2;
    static final int MAX_ITEMS = 6, MEMORY_MAX = 20;
    /** Played this long, it is remembered: an autoplaying preview or an ad is not. */
    static final long MIN_PLAY_MS = 10_000;
    static final long MAX_AGE_MS = WatchNext.MAX_AGE_MS;
    /** YouTube Music plays inside the YouTube app with the same metadata as a video: told apart by these. */
    static final String YOUTUBE = "com.google.android.youtube.tv";

    /** One remembered or published thing to resume. Times are wall-clock milliseconds. */
    static final class Item {
        String key, pkg, title, artist, album;
        /** A file path (remembered art) or a URL (a Watch Next poster), or null. */
        String art;
        int kind;
        long position, duration;
        /** The last time it was playing (not its last event: a paused session keeps sending them). */
        long lastPlayed;
        /** How long it has played in all; it is remembered from MIN_PLAY_MS. */
        long playedMs;
        /** Its session exists now, and plays now. Filled in by PlayerMemory, never saved. */
        boolean live, playing;
        /** Non-null when it comes from Watch Next: the link and the plot. Never saved. */
        WatchNext.Item next;
        /** From SongLookup, empty or 0 when unknown. */
        String year, genre;
        int track, tracks;
        /**
         * What the app called the content (its MEDIA_URI, or a MEDIA_ID that is a link: PlayerPick.link), for Resume
         * once the session is gone; null when it said nothing (YouTube and Spotify).
         */
        String link;
        /** The picture's https source, kept beside its file: a file lost is fetched again from it (fetchUrl). */
        String artUrl;

        Item copy() {
            Item i = new Item();
            i.key = key;
            i.pkg = pkg;
            i.title = title;
            i.artist = artist;
            i.album = album;
            i.art = art;
            i.kind = kind;
            i.position = position;
            i.duration = duration;
            i.lastPlayed = lastPlayed;
            i.playedMs = playedMs;
            i.live = live;
            i.playing = playing;
            i.next = next;
            i.year = year;
            i.genre = genre;
            i.track = track;
            i.tracks = tracks;
            i.link = link;
            i.artUrl = artUrl;
            return i;
        }
    }

    private PlayerPick() {}

    /** What kind of thing a session plays. {@code artW}/{@code artH}: its cover's size, 0 when it has none. */
    static int kind(String pkg, String title, String artist, int artW, int artH, Set<String> musicApps) {
        if (blank(title)) return APP;
        if (pkg != null && musicApps != null && musicApps.contains(pkg)) return SONG;
        if (YOUTUBE.equals(pkg) && youtubeSong(title, artist, artW, artH)) return SONG;
        return VIDEO;
    }

    /** A YouTube Music track: a square cover, an audio-only upload's title, or an auto-generated "Topic" channel. */
    private static boolean youtubeSong(String title, String artist, int w, int h) {
        if (w > 0 && h > 0 && Math.abs(w - h) <= Math.max(w, h) / 10) return true;
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("(audio)") || t.contains("official audio") || t.contains("lyrics") || t.contains("testo")) {
            return true;
        }
        return artist != null && artist.trim().endsWith(" - Topic");
    }

    /** The memory's key: a song and an APP per app, a video per app and title. */
    static String key(int kind, String pkg, String title) {
        if (kind == VIDEO) return kind + "|" + pkg + "|" + norm(title);
        return kind + "|" + pkg;
    }

    /** Lower case, accents dropped, runs of spaces folded: the same title written twice is one. */
    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /**
     * Whether a session is worth remembering. Not the launcher's own, not an ignored one (the TV's own player), not a
     * paused one with no title (Netflix after playing), not a music app's
     * titleless play, and not a music app's title without an artist: Spotify's ad
     * ("Musica senza pubblicità.") comes that way. A titleless session that plays is remembered as an APP.
     */
    static boolean remembers(String ownPkg, Set<String> ignored, String pkg, String title, String artist,
                             boolean playing, Set<String> musicApps) {
        if (pkg == null || pkg.equals(ownPkg) || (ignored != null && ignored.contains(pkg))) return false;
        boolean music = musicApps != null && musicApps.contains(pkg);
        // A music app between tracks or in an ad can play with no title: no "Back to Spotify" card for that.
        if (blank(title)) return playing && !music;
        return !(music && blank(artist));
    }

    /**
     * The player's list: remembered items and Watch Next rows (already filtered by WatchNext.filter) of the last 30
     * days, one item per video when both know it (the link from Watch Next, the position from the newer of the two),
     * without the keys the owner removed, the ones playing now first and then the most recently played, at most
     * MAX_ITEMS. The memory's items are copied, never changed.
     */
    static List<Item> merge(List<Item> memory, List<WatchNext.Item> watchNext, Set<String> removed, long now) {
        Map<String, Item> byTitle = new LinkedHashMap<>();
        List<Item> out = new ArrayList<>();
        if (memory != null) {
            for (Item m : memory) {
                if (m == null || m.key == null || tooOld(m.lastPlayed, now)) continue;
                Item c = m.copy();
                out.add(c);
                if (c.kind == VIDEO) byTitle.put(c.pkg + "|" + norm(c.title), c);
            }
        }
        if (watchNext != null) {
            for (WatchNext.Item w : watchNext) {
                if (w == null || w.pkg == null || blank(w.title) || tooOld(w.lastEngagement, now)) continue;
                Item had = byTitle.get(w.pkg + "|" + norm(w.title));
                if (had != null) {
                    had.next = w;
                    if (w.lastEngagement > had.lastPlayed) {
                        had.lastPlayed = w.lastEngagement;
                        had.position = w.position;
                        if (w.duration > 0) had.duration = w.duration;
                    }
                    continue;
                }
                Item i = new Item();
                i.kind = VIDEO;
                i.pkg = w.pkg;
                i.title = w.title;
                i.key = "W|" + w.pkg + "|" + norm(w.title);
                i.art = w.poster;
                i.position = w.position;
                i.duration = w.duration;
                i.lastPlayed = w.lastEngagement;
                i.next = w;
                byTitle.put(w.pkg + "|" + norm(w.title), i);
                out.add(i);
            }
        }
        if (removed != null && !removed.isEmpty()) out.removeIf(i -> removed.contains(i.key));
        out.sort((a, b) -> {
            boolean pa = a.live && a.playing, pb = b.live && b.playing;
            if (pa != pb) return pa ? -1 : 1;
            return Long.compare(b.lastPlayed, a.lastPlayed);
        });
        return out.size() > MAX_ITEMS ? new ArrayList<>(out.subList(0, MAX_ITEMS)) : out;
    }

    /** The memory kept: the MEMORY_MAX most recently played, newest first. */
    static List<Item> evict(List<Item> memory) {
        if (memory == null) return Collections.emptyList();
        List<Item> out = new ArrayList<>(memory);
        out.removeIf(i -> i == null);
        out.sort((a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
        return out.size() > MEMORY_MAX ? new ArrayList<>(out.subList(0, MEMORY_MAX)) : out;
    }

    private static boolean tooOld(long time, long now) {
        return time <= 0 || now - time > MAX_AGE_MS;
    }

    /** While something plays, its position and played time are saved this often (and at pause, stop, a new track). */
    static final long SAVE_EVERY_MS = 30_000;
    /** A pause longer than this ends a sitting: Netflix's "per N min" then counts the new one only. */
    static final long SITTING_GAP_MS = 30 * 60_000L;

    /**
     * A running song's position on the card: where the last refresh put it ({@code base}, at elapsedRealtime
     * {@code baseAt}) moved on by the time since. Counted from the refresh, not from the last tick: the minutes the
     * tick was stopped (Home idle, card off screen) are not lost (0:10 shown at 0:38).
     */
    static long positionAfter(long base, long baseAt, long now, long duration) {
        long p = base + Math.max(0, now - baseAt);
        return duration > 0 ? Math.min(duration, p) : p;
    }

    /**
     * Where a remembered item is now. The memory is saved at most every SAVE_EVERY_MS while it plays, with its
     * lastPlayed, so a song live and playing has moved on by the time since (never past its end).
     */
    static long positionNow(Item i, long now) {
        if (!i.live || !i.playing || i.lastPlayed <= 0) return i.position;
        long p = i.position + Math.max(0, now - i.lastPlayed);
        return i.duration > 0 ? Math.min(i.duration, p) : p;
    }

    /**
     * The keys "Remove from player" takes out: the item's own, and for a remembered video merged with its
     * Watch Next row the row's too ("W|pkg|title", as merge names it), or the row would come back on its own.
     */
    static List<String> removeKeys(Item i) {
        List<String> out = new ArrayList<>();
        if (i == null || i.key == null) return out;
        out.add(i.key);
        if (i.next != null) {
            String w = "W|" + i.pkg + "|" + norm(i.next.title != null ? i.next.title : i.title);
            if (!w.equals(i.key)) out.add(w);
        }
        return out;
    }

    /** Whether playing again continues the last sitting (its played time goes on) or starts a new one. */
    static boolean sameSitting(Item had, long now) {
        return had != null && had.lastPlayed > 0 && now - had.lastPlayed <= SITTING_GAP_MS;
    }

    /**
     * When the recorder saves next (elapsed ms), for the tracks playing now: one still under MIN_PLAY_MS when it
     * reaches it (half a second late, so it is surely past), the others SAVE_EVERY_MS from now; the earliest wins.
     * {@code since[k]} is when track k started playing (-1: it does not), {@code played[k]} its play time before that.
     * Long.MAX_VALUE: nothing plays.
     */
    static long nextSave(long el, long[] since, long[] played) {
        long due = Long.MAX_VALUE;
        for (int k = 0; k < since.length; k++) {
            if (since[k] < 0) continue;
            long total = played[k] + (el - since[k]);
            long at = total < MIN_PLAY_MS ? since[k] + (MIN_PLAY_MS - played[k]) + 500 : el + SAVE_EVERY_MS;
            due = Math.min(due, at);
        }
        return due;
    }

    /**
     * Which of the sessions saying "playing" is heard: the one that said it last ({@code since}: its state's
     * last update). A TV gives the audio focus to one app at a time, and on the TV YouTube and Spotify both
     * kept a stale "playing" after the other took the focus. -1: none plays.
     */
    static int audible(boolean[] playing, long[] since) {
        int best = -1;
        for (int k = 0; k < playing.length; k++) {
            if (playing[k] && (best < 0 || since[k] > since[best])) best = k;
        }
        return best;
    }

    /** A track's cover may still change this long after it starts (the title can come before its picture). */
    static final long ART_SETTLE_MS = 5_000;

    /**
     * Whether a session's new picture of the same rank replaces the track's: when it has none, or while the track
     * settles (Spotify's first metadata of a track can still carry the previous track's cover). Later changes are
     * ignored: each would be a new file and a redraw. (The "two bitmaps" Spotify alternates for a whole track were its
     * cover and its logo: ranked apart by artRank, see the overload below.)
     */
    static boolean takesNewArt(boolean hasArt, long sinceTrackStart) {
        return !hasArt || sinceTrackStart < ART_SETTLE_MS;
    }

    /**
     * How good a track's picture is. A session's DISPLAY_ICON can be the app's logo: Spotify (logged on the TV)
     * alternates metadata with ALBUM_ART 640x640 + DISPLAY_ICON 160x160 (its logo) and metadata with the icon alone.
     * ART_STORED: a file remembered from before this run, its source unknown.
     */
    static final int ART_NONE = 0, ART_ICON_URI = 1, ART_ICON = 2, ART_STORED = 3, ART_COVER_URI = 4, ART_COVER = 5;

    /** Where a session's picture comes from (pictureSource). */
    static final int SRC_NONE = 0, SRC_ART = 1, SRC_ALBUM_ART = 2, SRC_COVER_URI = 3, SRC_ICON = 4, SRC_ICON_URI = 5;

    /**
     * Which of the session's pictures is the track's: a cover bitmap (ART or ALBUM_ART: the square one, then the
     * larger), else a cover URI (ART_URI, ALBUM_ART_URI), else the DISPLAY_ICON bitmap, else its URI.
     * {@code sizes}: width and height of ART, ALBUM_ART and DISPLAY_ICON, 0 when absent.
     */
    static int pictureSource(int[] sizes, boolean coverUri, boolean iconUri) {
        boolean art = sizes[0] > 0 && sizes[1] > 0, album = sizes[2] > 0 && sizes[3] > 0;
        if (art && album) {
            boolean sqArt = squarish(sizes[0], sizes[1]), sqAlbum = squarish(sizes[2], sizes[3]);
            if (sqArt != sqAlbum) return sqArt ? SRC_ART : SRC_ALBUM_ART;
            return (long) sizes[0] * sizes[1] >= (long) sizes[2] * sizes[3] ? SRC_ART : SRC_ALBUM_ART;
        }
        if (art) return SRC_ART;
        if (album) return SRC_ALBUM_ART;
        if (coverUri) return SRC_COVER_URI;
        if (sizes[4] > 0 && sizes[5] > 0) return SRC_ICON;
        return iconUri ? SRC_ICON_URI : SRC_NONE;
    }

    static int artRank(int source) {
        switch (source) {
            case SRC_ART:
            case SRC_ALBUM_ART:
                return ART_COVER;
            case SRC_COVER_URI:
                return ART_COVER_URI;
            case SRC_ICON:
                return ART_ICON;
            case SRC_ICON_URI:
                return ART_ICON_URI;
            default:
                return ART_NONE;
        }
    }

    private static boolean squarish(int w, int h) {
        return Math.abs(w - h) <= Math.max(w, h) / 10;
    }

    /**
     * Whether a session's new picture (rank {@code next}) replaces the track's (rank {@code had}): always a better one
     * (the cover after the logo, at any time), never a worse one (the logo after the cover), and one as good only
     * while the track settles (takesNewArt above).
     */
    static boolean takesNewArt(int had, int next, boolean hasArt, long sinceTrackStart) {
        if (!hasArt) return true;
        if (next != had) return next > had;
        return takesNewArt(true, sinceTrackStart);
    }

    /**
     * An item's art while its session's picture (a URI) is being fetched: a file it has stays; otherwise an https URL
     * is kept as it is, so a fetch that failed (offline) or came after the track ended is done later from the memory
     * (artToFetch). A content:// URI is not kept: its permission may not outlive the session.
     */
    static String keepUriArt(String art, boolean artFileExists, String uri) {
        if (artFileExists) return art;
        return uri != null && uri.startsWith("https://") ? uri : art != null && art.startsWith("https://") ? art : null;
    }

    /** The remembered items whose picture is still an https URL not tried yet: fetched once each. */
    static List<Item> artToFetch(List<Item> items, Set<String> tried) {
        return artToFetch(items, tried, path -> true);
    }

    /**
     * The same, counting also the items whose art file is gone ({@code exists} says which files are there) but whose
     * picture's https source is known (artUrl).
     */
    static List<Item> artToFetch(List<Item> items, Set<String> tried, java.util.function.Predicate<String> exists) {
        List<Item> out = new ArrayList<>();
        if (items == null) return out;
        for (Item i : items) {
            String url = i == null ? null : fetchUrl(i, exists);
            if (url != null && (tried == null || !tried.contains(url))) out.add(i);
        }
        return out;
    }

    /**
     * Where an item's picture is to be fetched from, or null when it needs no fetch: its art when that is still an
     * https URL, else its artUrl when it has no art file or the file is gone.
     */
    static String fetchUrl(Item i, java.util.function.Predicate<String> exists) {
        if (i == null) return null;
        if (i.art != null && i.art.startsWith("https://")) return i.art;
        boolean file = i.art != null && exists != null && exists.test(i.art);
        return !file && i.artUrl != null && i.artUrl.startsWith("https://") ? i.artUrl : null;
    }

    /** A session's picture URI kept as the item's artUrl: a new https one replaces it, anything else keeps the old. */
    static String artUrl(String uri, String had) {
        return uri != null && uri.startsWith("https://") ? uri : had;
    }

    /** A link longer than this is not kept: the memory is a small text file. */
    static final int LINK_MAX = 1024;

    /**
     * The content's link from a session's metadata: METADATA_KEY_MEDIA_URI, else a METADATA_KEY_MEDIA_ID that is a
     * link (an opaque id like "12345" is not). Never a file, a provider or a script (WatchNext.launchableLink): it is
     * opened later only into the app that played it (WatchNextRow.open's checks).
     */
    static String link(String mediaUri, String mediaId) {
        for (String s : new String[]{mediaUri, mediaId}) {
            if (s == null) continue;
            String t = s.trim();
            if (t.length() > LINK_MAX || !WatchNext.launchableLink(t)) continue;
            return t;
        }
        return null;
    }

    /** A closed music app is asked to play at most this many times by a resume (MediaResume). */
    static final int PLAY_TRIES = 4;

    /** Whether a session's state is playing, or about to (buffering, connecting). */
    static boolean plays(int state) {
        return state == android.media.session.PlaybackState.STATE_PLAYING
                || state == android.media.session.PlaybackState.STATE_BUFFERING
                || state == android.media.session.PlaybackState.STATE_CONNECTING;
    }

    /**
     * Whether a resume asks again: the app does not play yet and has been asked fewer than PLAY_TRIES times. On the
     * TV, Spotify, started by its media browser, played ~5 s and was paused by its own screen coming up.
     */
    static boolean playAgain(int state, int asked) {
        return !plays(state) && asked < PLAY_TRIES;
    }

    /**
     * The same, for a session's metadata that may be an ad: {@code ad} is its METADATA_KEY_ADVERTISEMENT, and a music
     * app's "track" by the app itself ({@code appLabel}: Spotify's "Musica senza pubblicità." by "Spotify")
     * is one too. An ad never becomes a memory nor replaces the song on its card.
     */
    static boolean remembers(String ownPkg, Set<String> ignored, String pkg, String title, String artist,
                             boolean playing, Set<String> musicApps, boolean ad, String appLabel) {
        if (ad) return false;
        boolean music = musicApps != null && musicApps.contains(pkg);
        if (music && !blank(artist) && !blank(appLabel) && norm(artist).equals(norm(appLabel))) return false;
        return remembers(ownPkg, ignored, pkg, title, artist, playing, musicApps);
    }

    static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ---- a session's text (MediaMemory, SongLookup) ----

    /** The first candidate with some text, cleaned, or "". */
    static String first(CharSequence... candidates) {
        if (candidates == null) return "";
        for (CharSequence c : candidates) {
            String s = clean(c);
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    /** One line of plain text: runs of spaces, tabs and line breaks become one space, the ends trimmed. */
    static String clean(CharSequence c) {
        if (c == null) return "";
        StringBuilder out = new StringBuilder(c.length());
        boolean space = false;
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch) || Character.isISOControl(ch)) {
                space = out.length() > 0;
                continue;
            }
            if (space) out.append(' ');
            space = false;
            out.append(ch);
        }
        return out.toString();
    }
}
