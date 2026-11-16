package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The player's choices, with what the apps on a Philips TV really sent. */
public class PlayerPickTest {

    private static final String SPOTIFY = "com.spotify.tv.android", YOUTUBE = "com.google.android.youtube.tv",
            NETFLIX = "com.netflix.ninja", RAIPLAY = "it.rainet.androidtv", OWN = "io.github.leopierini.rehome";
    private static final Set<String> MUSIC = new HashSet<>(Arrays.asList(SPOTIFY, "com.google.android.youtube.tvmusic"));
    private static final Set<String> IGNORED = new HashSet<>(Collections.singletonList("org.droidtv.playtv"));
    private static final long NOW = 1_794_740_000_000L, MIN = 60_000L, DAY = 24 * 60 * MIN;

    // ---- kind ----

    @Test
    public void spotifyIsSong() {
        assertEquals(PlayerPick.SONG, PlayerPick.kind(SPOTIFY, "Lantern's Bay", "The Hollow Pines", 640, 640, MUSIC));
    }

    @Test
    public void youtubeAudioTitleIsSong() {
        assertEquals(PlayerPick.SONG,
                PlayerPick.kind(YOUTUBE, "Kestrel - Letters From Home (Audio)", "Kestrel", 1280, 720, MUSIC));
    }

    @Test
    public void youtubeVideoIsVideo() {
        assertEquals(PlayerPick.VIDEO, PlayerPick.kind(YOUTUBE, "Kestrel - Route 9", "Kestrel", 1280, 720, MUSIC));
    }

    @Test
    public void youtubeVideoWithoutArtIsVideo() {
        assertEquals(PlayerPick.VIDEO, PlayerPick.kind(YOUTUBE, "Kestrel - Route 9", "Kestrel", 0, 0, MUSIC));
    }

    @Test
    public void youtubeSquareArtIsSong() {
        assertEquals(PlayerPick.SONG, PlayerPick.kind(YOUTUBE, "Route 9", "Kestrel", 544, 512, MUSIC));
    }

    @Test
    public void youtubeTopicChannelIsSong() {
        assertEquals(PlayerPick.SONG, PlayerPick.kind(YOUTUBE, "Route 9", "Kestrel - Topic", 1280, 720, MUSIC));
    }

    @Test
    public void youtubeLyricsOrOfficialAudioIsSong() {
        assertEquals(PlayerPick.SONG, PlayerPick.kind(YOUTUBE, "Route 9 (Lyrics)", "Kestrel", 1280, 720, MUSIC));
        assertEquals(PlayerPick.SONG, PlayerPick.kind(YOUTUBE, "Route 9 [Official Audio]", "Kestrel", 1280, 720, MUSIC));
    }

    @Test
    public void netflixNullTitleIsApp() {
        assertEquals(PlayerPick.APP, PlayerPick.kind(NETFLIX, "", "", 0, 0, MUSIC));
        assertEquals(PlayerPick.APP, PlayerPick.kind(NETFLIX, null, null, 0, 0, MUSIC));
    }

    @Test
    public void otherAppWithTitleIsVideo() {
        assertEquals(PlayerPick.VIDEO, PlayerPick.kind(RAIPLAY, "Porto - Mare aperto", "", 0, 0, MUSIC));
    }

    // ---- remembers ----

    @Test
    public void spotifyAdNotRemembered() {
        assertFalse(PlayerPick.remembers(OWN, IGNORED, SPOTIFY, "Musica senza pubblicità.", "", true, MUSIC));
        assertFalse(PlayerPick.remembers(OWN, IGNORED, SPOTIFY, "Musica senza pubblicità.", null, false, MUSIC));
    }

    @Test
    public void songRemembered() {
        assertTrue(PlayerPick.remembers(OWN, IGNORED, SPOTIFY, "Lantern's Bay", "The Hollow Pines", false, MUSIC));
    }

    @Test
    public void pausedWithoutTitleNotRemembered() {
        assertFalse(PlayerPick.remembers(OWN, IGNORED, NETFLIX, null, null, false, MUSIC));
        assertFalse(PlayerPick.remembers(OWN, IGNORED, NETFLIX, "  ", "", false, MUSIC));
    }

    @Test
    public void netflixPlayingRemembered() {
        assertTrue(PlayerPick.remembers(OWN, IGNORED, NETFLIX, "", "", true, MUSIC));
    }

    @Test
    public void ownAndIgnoredPackagesNotRemembered() {
        assertFalse(PlayerPick.remembers(OWN, IGNORED, OWN, "Prova", "Io", true, MUSIC));
        assertFalse(PlayerPick.remembers(OWN, IGNORED, "org.droidtv.playtv", "Rai 1", "", true, MUSIC));
        assertFalse(PlayerPick.remembers(OWN, IGNORED, null, "Prova", "Io", true, MUSIC));
    }

    @Test
    public void youtubeVideoWithChannelRemembered() {
        assertTrue(PlayerPick.remembers(OWN, IGNORED, YOUTUBE, "Kestrel - Route 9", "Kestrel", false, MUSIC));
    }

    // ---- key ----

    @Test
    public void songKeyIsPerApp() {
        assertEquals(PlayerPick.key(PlayerPick.SONG, SPOTIFY, "Lantern's Bay"),
                PlayerPick.key(PlayerPick.SONG, SPOTIFY, "Low Tide"));
    }

    @Test
    public void videoKeyIsPerTitle() {
        assertNotEquals(PlayerPick.key(PlayerPick.VIDEO, YOUTUBE, "Kestrel - Route 9"),
                PlayerPick.key(PlayerPick.VIDEO, YOUTUBE, "Another video"));
        assertEquals(PlayerPick.key(PlayerPick.VIDEO, YOUTUBE, "Kestrel - Route 9"),
                PlayerPick.key(PlayerPick.VIDEO, YOUTUBE, "  kestrel -  ROUTE 9 "));
    }

    @Test
    public void appKeyIsPerApp() {
        assertEquals(PlayerPick.key(PlayerPick.APP, NETFLIX, null), PlayerPick.key(PlayerPick.APP, NETFLIX, ""));
    }

    @Test
    public void normFoldsCaseAccentsAndSpaces() {
        assertEquals("perche si", PlayerPick.norm("  Perché   SÌ "));
        assertEquals("", PlayerPick.norm(null));
    }

    // ---- merge ----

    private static PlayerPick.Item memory(int kind, String pkg, String title, long lastPlayed) {
        PlayerPick.Item i = new PlayerPick.Item();
        i.kind = kind;
        i.pkg = pkg;
        i.title = title;
        i.key = PlayerPick.key(kind, pkg, title);
        i.lastPlayed = lastPlayed;
        return i;
    }

    private static WatchNext.Item row(String pkg, String title, long engaged, long position) {
        WatchNext.Item w = new WatchNext.Item();
        w.pkg = pkg;
        w.title = title;
        w.intentUri = "raiplaytv://x";
        w.lastEngagement = engaged;
        w.position = position;
        w.duration = 100;
        w.description = "Trama";
        return w;
    }

    private static List<PlayerPick.Item> merge(List<PlayerPick.Item> mem, List<WatchNext.Item> wn, Set<String> removed) {
        return PlayerPick.merge(mem, wn, removed, NOW);
    }

    @Test
    public void mergeEmptyGivesEmpty() {
        assertTrue(merge(new ArrayList<>(), new ArrayList<>(), new HashSet<>()).isEmpty());
        assertTrue(PlayerPick.merge(null, null, null, NOW).isEmpty());
    }

    @Test
    public void watchNextRowBecomesVideoItem() {
        List<PlayerPick.Item> out = merge(new ArrayList<>(),
                Collections.singletonList(row(RAIPLAY, "Porto - Mare aperto", NOW - 2 * DAY, 38)), new HashSet<>());
        assertEquals(1, out.size());
        PlayerPick.Item i = out.get(0);
        assertEquals(PlayerPick.VIDEO, i.kind);
        assertEquals("W|" + RAIPLAY + "|porto - mare aperto", i.key);
        assertNotNull(i.next);
        assertEquals(38, i.position);
        assertEquals(100, i.duration);
        assertEquals(NOW - 2 * DAY, i.lastPlayed);
    }

    @Test
    public void mergeJoinsWatchNextAndVideoMemory() {
        PlayerPick.Item m = memory(PlayerPick.VIDEO, RAIPLAY, "Porto - Mare aperto", NOW - MIN);
        m.position = 55;
        m.duration = 100;
        List<PlayerPick.Item> out = merge(new ArrayList<>(Collections.singletonList(m)),
                Collections.singletonList(row(RAIPLAY, "porto -  mare aperto", NOW - 2 * DAY, 38)), new HashSet<>());
        assertEquals(1, out.size());
        assertNotNull(out.get(0).next);              // the link comes from Watch Next
        assertEquals(55, out.get(0).position);       // the position from the newer source
        assertEquals(NOW - MIN, out.get(0).lastPlayed);
    }

    @Test
    public void mergeJoinTakesNewerWatchNextPosition() {
        PlayerPick.Item m = memory(PlayerPick.VIDEO, RAIPLAY, "Porto", NOW - 3 * DAY);
        m.position = 10;
        List<PlayerPick.Item> out = merge(new ArrayList<>(Collections.singletonList(m)),
                Collections.singletonList(row(RAIPLAY, "Porto", NOW - DAY, 38)), new HashSet<>());
        assertEquals(1, out.size());
        assertEquals(38, out.get(0).position);
        assertEquals(NOW - DAY, out.get(0).lastPlayed);
    }

    @Test
    public void mergeDropsOlderThan30Days() {
        List<PlayerPick.Item> mem = new ArrayList<>(Collections.singletonList(
                memory(PlayerPick.SONG, SPOTIFY, "Old", NOW - 31 * DAY)));
        List<PlayerPick.Item> out = merge(mem,
                Collections.singletonList(row(RAIPLAY, "Old show", NOW - 40 * DAY, 3)), new HashSet<>());
        assertTrue(out.isEmpty());
    }

    @Test
    public void mergeDropsRemovedKeys() {
        PlayerPick.Item song = memory(PlayerPick.SONG, SPOTIFY, "Low Tide", NOW - MIN);
        Set<String> removed = new HashSet<>(Arrays.asList(song.key, "W|" + RAIPLAY + "|porto"));
        List<PlayerPick.Item> out = merge(new ArrayList<>(Collections.singletonList(song)),
                Collections.singletonList(row(RAIPLAY, "Porto", NOW - DAY, 3)), removed);
        assertTrue(out.isEmpty());
    }

    @Test
    public void mergeLivePlayingFirst() {
        PlayerPick.Item video = memory(PlayerPick.VIDEO, YOUTUBE, "Kestrel - Route 9", NOW - MIN);
        PlayerPick.Item song = memory(PlayerPick.SONG, SPOTIFY, "Low Tide", NOW - 3 * DAY);
        song.live = true;
        song.playing = true;
        List<PlayerPick.Item> out = merge(new ArrayList<>(Arrays.asList(video, song)), null, null);
        assertEquals(song.key, out.get(0).key);
        assertEquals(video.key, out.get(1).key);
    }

    /** Review focus 1: a song paused for hours is ordered by when it last played, not by its last event. */
    @Test
    public void mergeOrdersByLastPlayedNotByLastEvent() {
        PlayerPick.Item song = memory(PlayerPick.SONG, SPOTIFY, "Lantern's Bay", NOW - 3 * 60 * MIN);
        song.live = true;            // its session is still there, paused
        song.playing = false;
        PlayerPick.Item video = memory(PlayerPick.VIDEO, YOUTUBE, "Kestrel - Route 9", NOW - 10 * MIN);
        List<PlayerPick.Item> out = merge(new ArrayList<>(Arrays.asList(song, video)), null, null);
        assertEquals(video.key, out.get(0).key);
        assertEquals(song.key, out.get(1).key);
    }

    @Test
    public void mergeCapsAtSix() {
        List<PlayerPick.Item> mem = new ArrayList<>();
        for (int i = 0; i < 9; i++) mem.add(memory(PlayerPick.VIDEO, YOUTUBE, "Video " + i, NOW - i * MIN));
        List<PlayerPick.Item> out = merge(mem, null, null);
        assertEquals(PlayerPick.MAX_ITEMS, out.size());
        assertEquals(PlayerPick.key(PlayerPick.VIDEO, YOUTUBE, "Video 0"), out.get(0).key);
        assertEquals(PlayerPick.key(PlayerPick.VIDEO, YOUTUBE, "Video 5"), out.get(5).key);
    }

    @Test
    public void mergeDoesNotChangeTheMemoryItems() {
        PlayerPick.Item m = memory(PlayerPick.VIDEO, RAIPLAY, "Porto", NOW - 3 * DAY);
        merge(new ArrayList<>(Collections.singletonList(m)),
                Collections.singletonList(row(RAIPLAY, "Porto", NOW - DAY, 38)), new HashSet<>());
        assertEquals(null, m.next);
        assertEquals(NOW - 3 * DAY, m.lastPlayed);
    }

    // ---- evict ----

    @Test
    public void evictKeepsNewestTwenty() {
        List<PlayerPick.Item> mem = new ArrayList<>();
        for (int i = 0; i < 25; i++) mem.add(memory(PlayerPick.VIDEO, YOUTUBE, "Video " + i, NOW - (24 - i) * MIN));
        List<PlayerPick.Item> kept = PlayerPick.evict(mem);
        assertEquals(PlayerPick.MEMORY_MAX, kept.size());
        assertEquals(PlayerPick.key(PlayerPick.VIDEO, YOUTUBE, "Video 24"), kept.get(0).key);
        for (PlayerPick.Item i : kept) assertNotEquals(PlayerPick.key(PlayerPick.VIDEO, YOUTUBE, "Video 0"), i.key);
    }

    // ---- player fixes (TV check and code review) ----

    @Test
    public void musicAppPlayingWithoutTitleNotRemembered() {
        // Review 18: Spotify playing with a blank title would become an APP card ("Back to Spotify").
        assertFalse(PlayerPick.remembers(OWN, IGNORED, SPOTIFY, "", "", true, MUSIC));
        assertTrue(PlayerPick.remembers(OWN, IGNORED, NETFLIX, null, null, true, MUSIC));
    }

    @Test
    public void cardPositionCountsFromTheRefreshNotFromTheLastTick() {
        // Spotify at 0:25-0:39, the card at 0:10 (the 10 s save) - the tick added only the time
        // since it last ran, so a minute idle (no key) or off screen was lost until the next save.
        assertEquals(70_000, PlayerPick.positionAfter(10_000, 1_000, 61_000, 120_000));
        assertEquals(120_000, PlayerPick.positionAfter(10_000, 1_000, 999_000, 120_000));   // never past the end
        assertEquals(10_000, PlayerPick.positionAfter(10_000, 5_000, 1_000, 120_000));     // clock never backwards
        assertEquals(70_000, PlayerPick.positionAfter(10_000, 1_000, 61_000, 0));          // no duration: no cap
    }

    @Test
    public void positionNowMovesOnWhilePlaying() {
        // Review 5: the memory is saved every 30 s, so a playing song's position is moved on to now.
        PlayerPick.Item i = new PlayerPick.Item();
        i.position = 60_000;
        i.duration = 200_000;
        i.lastPlayed = NOW - 20_000;
        i.live = true;
        i.playing = true;
        assertEquals(80_000, PlayerPick.positionNow(i, NOW));
        i.lastPlayed = NOW - 10 * MIN;
        assertEquals(200_000, PlayerPick.positionNow(i, NOW));      // never past the end
        i.lastPlayed = NOW - 20_000;
        i.playing = false;
        assertEquals(60_000, PlayerPick.positionNow(i, NOW));       // paused: where it stopped
        i.playing = true;
        i.live = false;
        assertEquals(60_000, PlayerPick.positionNow(i, NOW));
        i.live = true;
        i.duration = 0;
        assertEquals(80_000, PlayerPick.positionNow(i, NOW));       // unknown length: no cap
    }

    @Test
    public void removeKeysOfMergedItemIncludeWatchNextRow() {
        // Review 4: a remembered video merged with its Watch Next row keeps the memory key; the row must go too.
        PlayerPick.Item v = new PlayerPick.Item();
        v.kind = PlayerPick.VIDEO;
        v.pkg = RAIPLAY;
        v.title = "Porto - Mare aperto";
        v.key = PlayerPick.key(PlayerPick.VIDEO, RAIPLAY, v.title);
        assertEquals(Collections.singletonList(v.key), PlayerPick.removeKeys(v));
        v.next = new WatchNext.Item();
        v.next.pkg = RAIPLAY;
        v.next.title = "Porto - Mare Aperto";
        assertEquals(Arrays.asList(v.key, "W|" + RAIPLAY + "|porto - mare aperto"), PlayerPick.removeKeys(v));
        PlayerPick.Item w = new PlayerPick.Item();
        w.key = "W|" + RAIPLAY + "|porto";
        w.pkg = RAIPLAY;
        w.title = "Porto";
        w.next = new WatchNext.Item();
        assertEquals(Collections.singletonList(w.key), PlayerPick.removeKeys(w));
    }

    @Test
    public void appSessionContinuesOnlyWithinHalfAnHour() {
        // Review 10: Netflix's "per N min" counts one sitting, not every evening since the first.
        PlayerPick.Item had = new PlayerPick.Item();
        had.lastPlayed = NOW - 10 * MIN;
        assertTrue(PlayerPick.sameSitting(had, NOW));
        had.lastPlayed = NOW - 31 * MIN;
        assertFalse(PlayerPick.sameSitting(had, NOW));
        assertFalse(PlayerPick.sameSitting(null, NOW));
    }

    @Test
    public void nextSaveAtTheFirstDueTrack() {
        // Review 1: a track still under MIN_PLAY_MS is saved when it reaches it, even with a 30 s save due later.
        long el = 1_000_000;
        // Playing since el - 2 s, 0 played before: due at MIN_PLAY_MS after its start (+ 500 ms of margin).
        assertEquals(el - 2_000 + PlayerPick.MIN_PLAY_MS + 500,
                PlayerPick.nextSave(el, new long[]{el - 2_000}, new long[]{0}));
        // Already remembered: the next save is SAVE_EVERY_MS from now.
        assertEquals(el + PlayerPick.SAVE_EVERY_MS, PlayerPick.nextSave(el, new long[]{el}, new long[]{60_000}));
        // Two: the earlier wins.
        assertEquals(el - 2_000 + PlayerPick.MIN_PLAY_MS + 500,
                PlayerPick.nextSave(el, new long[]{el, el - 2_000}, new long[]{60_000, 0}));
        // Not playing (-1): nothing due.
        assertEquals(Long.MAX_VALUE, PlayerPick.nextSave(el, new long[]{-1}, new long[]{0}));
    }

    @Test
    public void onlyTheNewestPlayingSessionPlays() {
        // YouTube and Spotify both kept saying "playing" after losing the audio focus to the other.
        // Audio focus is exclusive on a TV: the session that last said "playing" is the one heard.
        assertEquals(1, PlayerPick.audible(new boolean[]{true, true}, new long[]{51_862_192, 52_035_530}));
        assertEquals(0, PlayerPick.audible(new boolean[]{true, false}, new long[]{51_862_192, 52_035_530}));
        assertEquals(-1, PlayerPick.audible(new boolean[]{false, false}, new long[]{1, 2}));
        assertEquals(-1, PlayerPick.audible(new boolean[0], new long[0]));
    }

    @Test
    public void newCoverTakenOnlyWhileTheTrackSettles() {
        // Spotify alternates two different bitmaps for the same track all along; a new cover is taken
        // when there is none yet, or in the first seconds of a track (an app that sends the title before its art).
        assertTrue(PlayerPick.takesNewArt(false, 60_000));
        assertTrue(PlayerPick.takesNewArt(true, 1_000));
        assertFalse(PlayerPick.takesNewArt(true, PlayerPick.ART_SETTLE_MS));
    }

    @Test
    public void appLogoNeverReplacesTheCoverAndTheCoverAlwaysReplacesTheLogo() {
        // Spotify's metadata alternates ALBUM_ART 640x640 + DISPLAY_ICON 160x160 (its logo)
        // with DISPLAY_ICON alone; whichever was in at 5 s stayed, so the card showed the Spotify logo.
        long late = 60_000;
        assertFalse(PlayerPick.takesNewArt(PlayerPick.ART_COVER, PlayerPick.ART_ICON, true, 1_000));
        assertFalse(PlayerPick.takesNewArt(PlayerPick.ART_COVER_URI, PlayerPick.ART_ICON, true, 1_000));
        assertFalse(PlayerPick.takesNewArt(PlayerPick.ART_STORED, PlayerPick.ART_ICON, true, 1_000));
        assertTrue(PlayerPick.takesNewArt(PlayerPick.ART_ICON, PlayerPick.ART_COVER, true, late));
        assertTrue(PlayerPick.takesNewArt(PlayerPick.ART_ICON, PlayerPick.ART_COVER_URI, true, late));
        assertTrue(PlayerPick.takesNewArt(PlayerPick.ART_STORED, PlayerPick.ART_COVER, true, late));
        // The bitmap cover is not swapped for the same cover by URL (no download for it).
        assertFalse(PlayerPick.takesNewArt(PlayerPick.ART_COVER, PlayerPick.ART_COVER_URI, true, 1_000));
        // Same rank: the settle rule; no picture yet: anything.
        assertTrue(PlayerPick.takesNewArt(PlayerPick.ART_COVER, PlayerPick.ART_COVER, true, 1_000));
        assertFalse(PlayerPick.takesNewArt(PlayerPick.ART_COVER, PlayerPick.ART_COVER, true, late));
        assertTrue(PlayerPick.takesNewArt(PlayerPick.ART_NONE, PlayerPick.ART_ICON, false, late));
    }

    @Test
    public void pictureSourceCoverBitmapThenCoverUrlThenIcon() {
        // sizes: ART, ALBUM_ART, DISPLAY_ICON (0 = absent); then whether a cover URI (ART_URI/ALBUM_ART_URI) and an
        // icon URI came. Spotify: ALBUM_ART 640 + icon 160 → the album art; icon alone + an https album URI → the URI.
        assertEquals(PlayerPick.SRC_ALBUM_ART, PlayerPick.pictureSource(new int[]{0, 0, 640, 640, 160, 160}, true, false));
        assertEquals(PlayerPick.SRC_COVER_URI, PlayerPick.pictureSource(new int[]{0, 0, 0, 0, 160, 160}, true, false));
        assertEquals(PlayerPick.SRC_ICON, PlayerPick.pictureSource(new int[]{0, 0, 0, 0, 160, 160}, false, true));
        assertEquals(PlayerPick.SRC_ICON_URI, PlayerPick.pictureSource(new int[]{0, 0, 0, 0, 0, 0}, false, true));
        assertEquals(PlayerPick.SRC_NONE, PlayerPick.pictureSource(new int[]{0, 0, 0, 0, 0, 0}, false, false));
        // Both covers: the larger square one; a square one over a bigger 16:9 frame.
        assertEquals(PlayerPick.SRC_ALBUM_ART, PlayerPick.pictureSource(new int[]{300, 300, 640, 640, 0, 0}, false, false));
        assertEquals(PlayerPick.SRC_ART, PlayerPick.pictureSource(new int[]{640, 640, 300, 300, 0, 0}, false, false));
        assertEquals(PlayerPick.SRC_ALBUM_ART, PlayerPick.pictureSource(new int[]{1280, 720, 640, 640, 0, 0}, false, false));
        assertEquals(PlayerPick.SRC_ART, PlayerPick.pictureSource(new int[]{480, 270, 0, 0, 160, 160}, true, false));
        assertEquals(PlayerPick.ART_COVER, PlayerPick.artRank(PlayerPick.SRC_ALBUM_ART));
        assertEquals(PlayerPick.ART_COVER_URI, PlayerPick.artRank(PlayerPick.SRC_COVER_URI));
        assertEquals(PlayerPick.ART_ICON, PlayerPick.artRank(PlayerPick.SRC_ICON));
        assertEquals(PlayerPick.ART_ICON_URI, PlayerPick.artRank(PlayerPick.SRC_ICON_URI));
    }

    @Test
    public void httpsPictureKeptAsPendingUntilFetched() {
        // An https picture the recorder could not fetch while the track played (offline, or the track ended first)
        // stays on the item as its URL, so it is fetched later; a file already there is kept meanwhile; a content://
        // URI is never kept (its permission may not outlive the session).
        String url = "https://i.ytimg.com/vi/x/hqdefault.jpg";
        assertEquals(url, PlayerPick.keepUriArt(null, false, url));
        assertEquals("/files/player_art/a_1.jpg", PlayerPick.keepUriArt("/files/player_art/a_1.jpg", true, url));
        assertEquals(url, PlayerPick.keepUriArt("/files/player_art/gone.jpg", false, url));
        assertNull(PlayerPick.keepUriArt(null, false, "content://media/1"));
        assertNull(PlayerPick.keepUriArt(null, false, null));
    }

    @Test
    public void pendingPicturesFetchedOncePerUrl() {
        // Remembered items whose art is still an https URL are fetched once (per process): a URL tried already, a
        // file or no art at all is not fetched.
        PlayerPick.Item a = new PlayerPick.Item();
        a.key = "1|yt|a";
        a.art = "https://i.ytimg.com/a.jpg";
        PlayerPick.Item b = new PlayerPick.Item();
        b.key = "1|yt|b";
        b.art = "https://i.ytimg.com/b.jpg";
        PlayerPick.Item c = new PlayerPick.Item();
        c.key = "1|yt|c";
        c.art = "/files/player_art/c.jpg";
        PlayerPick.Item d = new PlayerPick.Item();
        d.key = "2|nf";
        java.util.Set<String> tried = new java.util.HashSet<>(Collections.singletonList("https://i.ytimg.com/b.jpg"));
        assertEquals(Collections.singletonList(a), PlayerPick.artToFetch(Arrays.asList(a, b, c, d, null), tried));
        assertTrue(PlayerPick.artToFetch(null, tried).isEmpty());
    }

    @Test
    public void advertisementNotRemembered() {
        // Spotify's ad "Musica senza pubblicità." came with artist "Spotify" and replaced the song on the
        // card; its metadata carries METADATA_KEY_ADVERTISEMENT. An ad is never remembered, nor is a music app's
        // "track" whose artist is the app itself.
        assertFalse(PlayerPick.remembers(OWN, IGNORED, SPOTIFY, "Musica senza pubblicità.", "Spotify", true, MUSIC,
                true, "Spotify"));
        assertFalse(PlayerPick.remembers(OWN, IGNORED, SPOTIFY, "Musica senza pubblicità.", "Spotify", true, MUSIC,
                false, "Spotify"));
        assertTrue(PlayerPick.remembers(OWN, IGNORED, SPOTIFY, "Olive", "Zmeyev", true, MUSIC, false, "Spotify"));
        assertFalse(PlayerPick.remembers(OWN, IGNORED, YOUTUBE, "Ad", "Brand", true, MUSIC, true, "YouTube"));
    }

    @Test
    public void spotifyAdOfTheUxReviewNotRemembered() {
        // Spotify's "Musica senza pubblicità." with artist "Spotify", from com.spotify.tv.android,
        // with or without the advertisement flag, playing or not, whatever the label's case and spaces.
        for (boolean ad : new boolean[]{true, false}) {
            for (boolean playing : new boolean[]{true, false}) {
                assertFalse(PlayerPick.remembers(OWN, IGNORED, "com.spotify.tv.android", "Musica senza pubblicità.",
                        "Spotify", playing, MUSIC, ad, "Spotify"));
            }
        }
        assertFalse(PlayerPick.remembers(OWN, IGNORED, SPOTIFY, "Musica senza pubblicità.", "spotify ", true, MUSIC,
                false, "Spotify"));
    }

    @Test
    public void linkFromTheSessionsMetadata() {
        // YouTube sends no MEDIA_ID/MEDIA_URI, Spotify neither; an app that does gives a link Resume can
        // open in that app. The URI first, then an id that is a link; an opaque id, a file or a provider is none.
        assertEquals("spotify:track:abc", PlayerPick.link("spotify:track:abc", "123"));
        assertEquals("spotify:track:abc", PlayerPick.link(null, "spotify:track:abc"));
        assertEquals("spotify:track:abc", PlayerPick.link(" ", " spotify:track:abc "));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                PlayerPick.link("https://www.youtube.com/watch?v=dQw4w9WgXcQ", null));
        assertNull(PlayerPick.link(null, "12345"));
        assertNull(PlayerPick.link("content://media/1", "file:///sdcard/x.mp3"));
        assertNull(PlayerPick.link("javascript:alert(1)", null));
        assertNull(PlayerPick.link(null, null));
        StringBuilder huge = new StringBuilder("spotify:");
        while (huge.length() <= PlayerPick.LINK_MAX) huge.append('x');
        assertNull(PlayerPick.link(huge.toString(), null));
    }

    @Test
    public void pictureUrlKeptBesideItsFile() {
        // Spotify sends the bitmap and its https ALBUM_ART_URI: the file is shown, the URL kept to fetch it again.
        assertEquals("https://i.scdn.co/image/a", PlayerPick.artUrl("https://i.scdn.co/image/a", null));
        assertEquals("https://i.scdn.co/image/a", PlayerPick.artUrl(null, "https://i.scdn.co/image/a"));
        assertEquals("https://i.scdn.co/image/b", PlayerPick.artUrl("https://i.scdn.co/image/b", "https://x/a"));
        assertEquals("https://x/a", PlayerPick.artUrl("content://media/1", "https://x/a"));
        assertNull(PlayerPick.artUrl("http://plain/a", null));
    }

    @Test
    public void lostArtFileFetchedAgainFromItsUrl() {
        PlayerPick.Item a = new PlayerPick.Item();
        a.key = "0|spotify";
        a.art = "/files/player_art/gone.jpg";
        a.artUrl = "https://i.scdn.co/image/a";
        PlayerPick.Item b = new PlayerPick.Item();
        b.key = "1|yt|b";
        b.art = "/files/player_art/here.jpg";
        b.artUrl = "https://i.ytimg.com/b.jpg";
        PlayerPick.Item c = new PlayerPick.Item();
        c.key = "1|yt|c";
        c.artUrl = "https://i.ytimg.com/c.jpg";
        PlayerPick.Item d = new PlayerPick.Item();
        d.key = "1|yt|d";
        d.art = "/files/player_art/gone2.jpg";
        java.util.function.Predicate<String> exists = p -> p.endsWith("here.jpg");
        assertEquals("https://i.scdn.co/image/a", PlayerPick.fetchUrl(a, exists));
        assertNull(PlayerPick.fetchUrl(b, exists));
        assertEquals("https://i.ytimg.com/c.jpg", PlayerPick.fetchUrl(c, exists));
        assertNull(PlayerPick.fetchUrl(d, exists));
        assertEquals(Arrays.asList(a, c), PlayerPick.artToFetch(Arrays.asList(a, b, c, d, null), null, exists));
        java.util.Set<String> tried = new java.util.HashSet<>(Collections.singletonList("https://i.ytimg.com/c.jpg"));
        assertEquals(Collections.singletonList(a), PlayerPick.artToFetch(Arrays.asList(a, b, c, d), tried, exists));
        // A pending https art (no file yet) is still fetched from the art itself.
        PlayerPick.Item e = new PlayerPick.Item();
        e.art = "https://i.ytimg.com/e.jpg";
        assertEquals("https://i.ytimg.com/e.jpg", PlayerPick.fetchUrl(e, exists));
    }

    @Test
    public void copyKeepsLinkAndPictureUrl() {
        PlayerPick.Item a = new PlayerPick.Item();
        a.link = "spotify:track:x";
        a.artUrl = "https://i.scdn.co/image/a";
        assertEquals(a.link, a.copy().link);
        assertEquals(a.artUrl, a.copy().artUrl);
    }

    @Test
    public void closedMusicAppAskedToPlayUntilItDoes() {
        // On the TV, Spotify closed: play() through its media browser played for ~5 s, then Spotify's own screen came
        // up and paused it. The resume asks again at each check while it does not play, at most PLAY_TRIES times.
        assertTrue(PlayerPick.playAgain(android.media.session.PlaybackState.STATE_NONE, 1));
        assertTrue(PlayerPick.playAgain(android.media.session.PlaybackState.STATE_PAUSED, 2));
        assertTrue(PlayerPick.playAgain(android.media.session.PlaybackState.STATE_STOPPED, 1));
        assertFalse(PlayerPick.playAgain(android.media.session.PlaybackState.STATE_PLAYING, 1));
        assertFalse(PlayerPick.playAgain(android.media.session.PlaybackState.STATE_BUFFERING, 1));
        assertFalse(PlayerPick.playAgain(android.media.session.PlaybackState.STATE_CONNECTING, 1));
        assertFalse(PlayerPick.playAgain(android.media.session.PlaybackState.STATE_PAUSED, PlayerPick.PLAY_TRIES));
        assertTrue(PlayerPick.plays(android.media.session.PlaybackState.STATE_PLAYING));
        assertFalse(PlayerPick.plays(android.media.session.PlaybackState.STATE_ERROR));
    }

    @Test
    public void cleanMakesOneLine() {
        assertEquals("Live at the Harbour '86", PlayerPick.clean("  Live at\n the\tHarbour\t'86  "));
        assertEquals("a b", PlayerPick.clean("a  b"));
        assertEquals("", PlayerPick.clean(null));
        assertEquals("", PlayerPick.clean(" \n "));
        assertEquals("Città", PlayerPick.first(null, "", " Città "));
    }
}
