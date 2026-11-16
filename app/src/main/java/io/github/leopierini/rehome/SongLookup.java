package io.github.leopierini.rehome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * category_player (player_lookup_online): a song's album, year, genre, track number and a square cover from the iTunes
 * Search API, free and without a key: one request of a few KB per song, the answer (also "not found") kept in
 * filesDir/song_lookup.txt so a song is never looked up twice. Only an answer whose artist and title both match is
 * taken: no data is better than a wrong album. The parts that decide are static and tested on the JVM; find() is the
 * network call, run by MediaMemory on a thread of its own and only online.
 *
 * <p>Privacy: Apple sees the artist and title searched. Launcher settings › Look up song details turns it off ({@link
 * #on(Context)}); config.xml's player_lookup_online is only the default.
 */
final class SongLookup {

    static final String FILE = "song_lookup.txt";
    /** Launcher settings › Look up song details, in Config.SETTINGS_NAME; absent until the row is pressed once. */
    static final String PREF_KEY = "song_lookup";
    static final int CACHE_MAX = 200;
    private static final int TIMEOUT_MS = 8000, MAX_BYTES = 64 * 1024;

    static final class Query {
        final String artist, title;

        Query(String artist, String title) {
            this.artist = artist;
            this.title = title;
        }
    }

    static final class Hit {
        String artist, title, album, year, genre, artUrl;
        int track, tracks;
    }

    /** Looked up before and not found: not asked again. */
    static final Hit NOT_FOUND = new Hit();

    private final File cache;

    SongLookup(File filesDir) {
        cache = new File(filesDir, FILE);
    }

    // ---- pure ----

    /** The choice made on the TV (null: none yet) over config.xml's player_lookup_online. False: no request at all. */
    static boolean on(Boolean choice, boolean configDefault) {
        return choice != null ? choice : configDefault;
    }

    /**
     * The artist and title to search. A YouTube title often carries the artist ("Kestrel - Route 9") and tags ("(Audio)",
     * "[Official Video]", "ft. X"): the artist in front is split off when it is the channel's, tags in brackets and a
     * trailing "ft./feat." part dropped; a "VEVO" or " - Topic" channel name loses its suffix.
     */
    static Query clean(String artist, String title) {
        String a = PlayerPick.clean(artist);
        if (a.endsWith(" - Topic")) a = a.substring(0, a.length() - " - Topic".length()).trim();
        if (a.endsWith("VEVO") && a.length() > 4) a = a.substring(0, a.length() - 4).trim();
        String t = PlayerPick.clean(title);
        int dash = t.indexOf(" - ");
        if (dash > 0) {
            String left = t.substring(0, dash).trim(), na = PlayerPick.norm(a), nl = PlayerPick.norm(left);
            if (!na.isEmpty() && (nl.contains(na) || na.contains(nl))) {
                a = left;
                t = t.substring(dash + 3).trim();
            }
        }
        t = t.replaceAll("\\([^)]*\\)|\\[[^]]*]", " ");
        t = t.replaceAll("(?i)\\s+(ft\\.?|feat\\.?|featuring)\\s.*$", "");
        return new Query(PlayerPick.clean(a), PlayerPick.clean(t));
    }

    /** The search in the iTunes store of the TV's region ({@link #country}): albums and years differ between stores. */
    static String url(Query q, Locale region) {
        try {
            return "https://itunes.apple.com/search?term=" + URLEncoder.encode(q.artist + " " + q.title, "UTF-8")
                    .replace("'", "%27") + "&entity=song&limit=5&country=" + country(region);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The locale's two-letter region, upper case; US when it has none (a bare language, a UN M.49 code like 419). */
    static String country(Locale region) {
        String c = region == null ? "" : region.getCountry();
        return c != null && c.matches("[A-Za-z]{2}") ? c.toUpperCase(Locale.ROOT) : "US";
    }

    /** The first hit with the same artist and title (case, accents, one inside the other), or null. */
    static Hit pick(Query q, List<Hit> hits) {
        if (hits == null || q == null) return null;
        String qa = PlayerPick.norm(q.artist), qt = PlayerPick.norm(q.title);
        if (qa.isEmpty() || qt.isEmpty()) return null;
        for (Hit h : hits) {
            if (h == null) continue;
            String ha = PlayerPick.norm(h.artist), ht = PlayerPick.norm(h.title);
            if (ha.isEmpty() || ht.isEmpty()) continue;
            if ((ha.contains(qa) || qa.contains(ha)) && (ht.contains(qt) || qt.contains(ht))) return h;
        }
        return null;
    }

    /** iTunes' 100×100 cover as 300×300 (the player's image column is about that tall). https only. */
    static String artUrl(String artworkUrl100) {
        if (artworkUrl100 == null || !artworkUrl100.startsWith("https://")) return null;
        return artworkUrl100.replaceAll("\\d+x\\d+bb", "300x300bb");
    }

    static String lookupKey(Query q) {
        return PlayerPick.norm(q.artist) + "|" + PlayerPick.norm(q.title);
    }

    static String cacheLine(String key, Hit h) {
        if (h == null || h == NOT_FOUND) return key + "\t-";
        return key + "\th\t" + PlayerStore.escape(h.artist) + "\t" + PlayerStore.escape(h.title) + "\t"
                + PlayerStore.escape(h.album) + "\t" + PlayerStore.escape(h.year) + "\t" + PlayerStore.escape(h.genre)
                + "\t" + h.track + "\t" + h.tracks + "\t" + PlayerStore.escape(h.artUrl);
    }

    /** A cache line's hit, NOT_FOUND for a "-" line, null for a line that does not read. */
    static Hit fromCacheLine(String line) {
        if (line == null) return null;
        String[] f = line.split("\t", -1);
        if (f.length == 2 && "-".equals(f[1])) return NOT_FOUND;
        if (f.length != 10 || !"h".equals(f[1])) return null;
        try {
            Hit h = new Hit();
            h.artist = PlayerStore.unescape(f[2]);
            h.title = PlayerStore.unescape(f[3]);
            h.album = PlayerStore.unescape(f[4]);
            h.year = PlayerStore.unescape(f[5]);
            h.genre = PlayerStore.unescape(f[6]);
            h.track = Integer.parseInt(f[7]);
            h.tracks = Integer.parseInt(f[8]);
            h.artUrl = PlayerStore.unescape(f[9]);
            return h;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Android: the cache file and the request (MediaMemory's lookup thread) ----

    /** Whether songs are looked up now: read at each use, so a change in Launcher settings counts at once. */
    static boolean on(Context c) {
        SharedPreferences p = c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        return on(p.contains(PREF_KEY) ? p.getBoolean(PREF_KEY, true) : null,
                c.getResources().getBoolean(R.bool.player_lookup_online));
    }

    /** The cached answer for a song (NOT_FOUND included), or null when it was never looked up. */
    synchronized Hit cached(Query q) {
        String key = lookupKey(q) + "\t";
        for (String line : lines()) if (line.startsWith(key)) return fromCacheLine(line);
        return null;
    }

    /** Asks iTunes and keeps the answer; null when the request failed (asked again another time). */
    Hit find(Query q) {
        String json;
        try {
            json = get(url(q, Locale.getDefault()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        Hit h;
        try {
            h = pick(q, parse(json));
        } catch (org.json.JSONException | RuntimeException e) {
            return null;
        }
        remember(q, h);
        return h == null ? NOT_FOUND : h;
    }

    private synchronized void remember(Query q, Hit h) {
        List<String> all = lines();
        String key = lookupKey(q);
        all.removeIf(l -> l.startsWith(key + "\t"));
        all.add(cacheLine(key, h));
        List<String> kept = all.size() > CACHE_MAX ? all.subList(all.size() - CACHE_MAX, all.size()) : all;
        try {
            Files.write(cache.toPath(), String.join("\n", kept).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private List<String> lines() {
        List<String> out = new ArrayList<>();
        try {
            if (cache.exists()) {
                for (String l : new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8).split("\n")) {
                    if (!l.isEmpty()) out.add(l);
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return out;
    }

    private static List<Hit> parse(String json) throws org.json.JSONException {
        List<Hit> out = new ArrayList<>();
        org.json.JSONArray results = new org.json.JSONObject(json).optJSONArray("results");
        if (results == null) return out;
        for (int i = 0; i < results.length(); i++) {
            org.json.JSONObject o = results.optJSONObject(i);
            if (o == null) continue;
            Hit h = new Hit();
            h.artist = o.optString("artistName", null);
            h.title = o.optString("trackName", null);
            h.album = o.optString("collectionName", null);
            String date = o.optString("releaseDate", "");
            h.year = date.length() >= 4 ? date.substring(0, 4) : null;
            h.genre = o.optString("primaryGenreName", null);
            h.track = o.optInt("trackNumber", 0);
            h.tracks = o.optInt("trackCount", 0);
            h.artUrl = artUrl(o.optString("artworkUrl100", null));
            out.add(h);
        }
        return out;
    }

    /** One https GET, at most MAX_BYTES (or {@code cap}), within TIMEOUT_MS to connect and to read. */
    static byte[] fetch(String url, int cap) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("http " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return read(in, cap);
            }
        } finally {
            c.disconnect();
        }
    }

    /** A stream read whole, or an IOException past {@code cap} bytes (MediaMemory's covers by content:// too). */
    static byte[] read(InputStream in, int cap) throws IOException {
        if (in == null) throw new IOException("no stream");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > cap) throw new IOException("too big");
        }
        return out.toByteArray();
    }

    private static String get(String url) throws IOException {
        return new String(fetch(url, MAX_BYTES), StandardCharsets.UTF_8);
    }
}
