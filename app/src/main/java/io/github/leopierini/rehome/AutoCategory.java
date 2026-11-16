package io.github.leopierini.rehome;

import android.content.pm.ApplicationInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks the Home category row for an app that no row lists by package (e.g. a fresh download).
 * Pure logic: the caller passes ApplicationInfo.category / FLAG_IS_GAME and the row titles in use.
 *
 * Signals, strongest first:
 * 1. FLAG_IS_GAME or CATEGORY_GAME -> games row.
 * 2. Declared category: AUDIO -> music, VIDEO -> media, IMAGE/SOCIAL/MAPS/PRODUCTIVITY/ACCESSIBILITY ->
 *    tools. NEWS (and any unknown value) falls through to the keywords, default tools: a TV news app is
 *    usually a video channel, so "Sky TG24 Live" lands in media while a text reader stays in tools.
 * 3. Keywords on the label and package name, split into tokens so "now" never hits "snow": letters only, and
 *    letters with digits too ("La7", "TV8"). A plain word must equal a token; a "*stem" may occur inside one
 *    ("radioplayer", "tvmusic", "videogame").
 *    Order: games, strong video, settings-like tools, music, tool words, weak video, else tools. So
 *    Twitch/Boosteroid are games even though they stream, "Radio TV" is music (bare "tv"/"live"/"play" are
 *    weak) but "Radio Video" is media, "TV Cast"/"TV Browser" stay tools, and "Audio Settings" is a tool.
 *    Last, a name written in one word that ends in "tv" ("PlutoTV") is media.
 * Real installs (Philips TV, API 30, Nov 2026) declare no appCategory at all; only Steam Link and a few
 * Philips Net TV web apps set isGame, so the keywords do almost all the work.
 */
final class AutoCategory {

    private static final int CATEGORY_ACCESSIBILITY = 8; // ApplicationInfo.CATEGORY_ACCESSIBILITY, API 31

    private static final String[] GAMES = words("*game *gioc *gaming *arcade "
            + "*retroarch retro ppsspp mame xbox *geforce gfn nvidia *boosteroid stadia *twitch *steam playstation");
    private static final String[] MEDIA_STRONG = words("*video *film *movie *cinema iptv vod *serie anime "
            + "*crunchyroll *netflix *dazn *disney *paramount pluto plex plexapp *jellyfin emby kodi xbmc vlc "
            + "*videolan *mubi *discovery *infinity *primevideo *timvision stremio tivimate vimeo tubi vvvvid "
            + "arte hbo max chili la7 tv8");
    /** Named for sound or pictures but there to set the TV up: checked before the music words. */
    private static final String[] TOOLS_FIRST = words("*settings *impostazion *soundbar *equalizer");
    private static final String[] MUSIC = words("*music *radio *podcast *audio *audible *sound *spotify "
            + "*deezer tidal *tunein *shazam *karaoke *napster *qobuz fm");
    private static final String[] TOOLS = words("*browser cast *chromecast file files *filemanager *explorer "
            + "manager *launcher *settings *keyboard vpn *speedtest *cleaner *weather *meteo *camera cam "
            + "store remote *mirror *backup");
    private static final String[] MEDIA_WEAK = words("tv live play player stream streaming *streamer now rai "
            + "raiplay sky skygo nowtv mediaset youtube *tube sport sports *eurosport channel canale canali tg news");
    /** Package tokens that say nothing about the content (".tv" marks the TV build of anything). */
    private static final Set<String> PKG_NOISE = new HashSet<>(Arrays.asList(
            "com", "org", "net", "it", "de", "io", "tv", "android", "androidtv", "atv", "app", "apps",
            "leanback", "mobile", "free"));

    private static final String[] ROLE_MEDIA = {"media", "video", "videos", "film", "tv", "cinema", "streaming"};
    private static final String[] ROLE_MUSIC = {"musica", "music", "audio", "radio"};
    private static final String[] ROLE_GAMES = {"giochi", "games", "game", "gaming", "live"};
    private static final String[] ROLE_TOOLS = {"strumenti", "tools", "utility", "utilities", "utilità",
            "altro", "altre", "other"};

    private AutoCategory() {}

    /** One of {@code titles}; null only when titles is null or empty. */
    static String classify(String pkg, String label, int appCategory, boolean isGameFlag, List<String> titles) {
        if (titles == null || titles.isEmpty()) return null;
        String tools = toolsRow(titles);
        String media = role(titles, ROLE_MEDIA, tools);
        String music = role(titles, ROLE_MUSIC, media);
        String games = role(titles, ROLE_GAMES, tools);

        if (isGameFlag || appCategory == ApplicationInfo.CATEGORY_GAME) return games;
        switch (appCategory) {
            case ApplicationInfo.CATEGORY_AUDIO: return music;
            case ApplicationInfo.CATEGORY_VIDEO: return media;
            case ApplicationInfo.CATEGORY_IMAGE:
            case ApplicationInfo.CATEGORY_SOCIAL:
            case ApplicationInfo.CATEGORY_MAPS:
            case ApplicationInfo.CATEGORY_PRODUCTIVITY:
            case CATEGORY_ACCESSIBILITY: return tools;
            default: break; // UNDEFINED, NEWS, anything newer: decide by name
        }

        Set<String> labelTokens = tokens(label, null);
        Set<String> tokens = new HashSet<>(labelTokens);
        tokens.addAll(tokens(pkg, PKG_NOISE));
        if (hit(tokens, GAMES)) return games;
        if (hit(tokens, MEDIA_STRONG)) return media;
        if (hit(tokens, TOOLS_FIRST)) return tools;
        if (hit(tokens, MUSIC)) return music;
        if (hit(tokens, TOOLS)) return tools;
        if (hit(tokens, MEDIA_WEAK)) return media;
        // "PlutoTV", "FranceTV": the name itself, in one word. Not the package, where ".tv" marks any TV build.
        for (String t : labelTokens) if (t.length() > 2 && t.endsWith("tv")) return media;
        return tools;
    }

    /** The row that holds tools (the one {@link #classify} sends tools to), or null without rows. */
    static String toolsRow(List<String> titles) {
        if (titles == null || titles.isEmpty()) return null;
        return role(titles, ROLE_TOOLS, titles.get(titles.size() - 1));
    }

    /** First title with a token from {@code keys}, else {@code fallback}. */
    private static String role(List<String> titles, String[] keys, String fallback) {
        for (String t : titles) {
            Set<String> tt = tokens(t, null);
            for (String k : keys) if (tt.contains(k)) return t;
        }
        return fallback;
    }

    private static boolean hit(Set<String> tokens, String[] keys) {
        for (String k : keys) {
            boolean stem = k.charAt(0) == '*';
            String w = stem ? k.substring(1) : k;
            if (!stem) {
                if (tokens.contains(w)) return true;
            } else {
                for (String t : tokens) if (t.contains(w)) return true;
            }
        }
        return false;
    }

    /** Lowercase tokens of {@code s}, letters only and letters with their digits ("la" and "la7"), minus {@code drop}. */
    private static Set<String> tokens(String s, Set<String> drop) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        String lower = s.toLowerCase(Locale.ROOT);
        for (String split : new String[]{"[^\\p{L}]+", "[^\\p{L}\\p{N}]+"}) {
            for (String t : lower.split(split)) {
                if (!t.isEmpty() && (drop == null || !drop.contains(t))) out.add(t);
            }
        }
        return out;
    }

    private static String[] words(String spec) {
        return spec.trim().split("\\s+");
    }
}
