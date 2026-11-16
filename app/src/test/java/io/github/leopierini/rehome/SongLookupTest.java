package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A song's year and genre from the iTunes Search API: the query from noisy titles, and only a matching answer. */
public class SongLookupTest {

    private static SongLookup.Hit hit(String artist, String title, String album) {
        SongLookup.Hit h = new SongLookup.Hit();
        h.artist = artist;
        h.title = title;
        h.album = album;
        h.year = "2016";
        h.genre = "Folk";
        h.track = 3;
        h.tracks = 12;
        h.artUrl = "https://is1-ssl.mzstatic.com/image/thumb/x/300x300bb.jpg";
        return h;
    }

    @Test
    public void cleanSplitsArtistDashTitle() {
        SongLookup.Query q = SongLookup.clean("Kestrel", "Kestrel - Letters From Home (Audio)");
        assertEquals("Kestrel", q.artist);
        assertEquals("Letters From Home", q.title);
    }

    @Test
    public void cleanDropsAudioOfficialVideoBracketsFt() {
        SongLookup.Query q = SongLookup.clean("KestrelVEVO", "Kestrel - Route 9 [Official Video] ft. Someone");
        assertEquals("Kestrel", q.artist);
        assertEquals("Route 9", q.title);
        assertEquals("Route 9", SongLookup.clean("Kestrel", "Route 9 (Official Audio)").title);
        assertEquals("Route 9", SongLookup.clean("Kestrel", "Route 9 (Lyrics)").title);
        assertEquals("Route 9", SongLookup.clean("Kestrel", "Route 9 (feat. X)").title);
    }

    @Test
    public void cleanDropsTopicSuffix() {
        assertEquals("Kestrel", SongLookup.clean("Kestrel - Topic", "Route 9").artist);
    }

    @Test
    public void cleanKeepsSpotifyFieldsAsIs() {
        SongLookup.Query q = SongLookup.clean("The Hollow Pines", "Lantern's Bay");
        assertEquals("The Hollow Pines", q.artist);
        assertEquals("Lantern's Bay", q.title);
        // A dash inside a real title, the artist not in front of it: kept whole.
        assertEquals("Up - Down", SongLookup.clean("Someone", "Up - Down").title);
    }

    @Test
    public void urlEncodesTerm() {
        assertEquals("https://itunes.apple.com/search?term=The+Hollow+Pines+Lantern%27s+Bay&entity=song&limit=5&country=IT",
                SongLookup.url(SongLookup.clean("The Hollow Pines", "Lantern's Bay"), Locale.ITALY));
    }

    @Test
    public void urlAsksTheStoreOfTheTvsRegion() {
        SongLookup.Query q = SongLookup.clean("Kestrel", "Route 9");
        assertEquals("https://itunes.apple.com/search?term=Kestrel+Route+9&entity=song&limit=5&country=US",
                SongLookup.url(q, Locale.US));
        assertEquals("https://itunes.apple.com/search?term=Kestrel+Route+9&entity=song&limit=5&country=IT",
                SongLookup.url(q, Locale.ITALY));
    }

    @Test
    public void urlFallsBackToUsWithoutATwoLetterRegion() {
        SongLookup.Query q = SongLookup.clean("Kestrel", "Route 9");
        assertTrue(SongLookup.url(q, new Locale("it")).endsWith("&country=US"));
        assertTrue(SongLookup.url(q, Locale.ROOT).endsWith("&country=US"));
        assertTrue(SongLookup.url(q, Locale.forLanguageTag("es-419")).endsWith("&country=US"));
        assertTrue(SongLookup.url(q, null).endsWith("&country=US"));
    }

    @Test
    public void pickRejectsOtherArtist() {
        SongLookup.Query q = SongLookup.clean("The Hollow Pines", "Lantern's Bay");
        assertNull(SongLookup.pick(q, Collections.singletonList(hit("Someone Else", "Lantern's Bay", "Covers"))));
    }

    @Test
    public void pickRejectsOtherTitle() {
        SongLookup.Query q = SongLookup.clean("The Hollow Pines", "Lantern's Bay");
        assertNull(SongLookup.pick(q, Collections.singletonList(hit("The Hollow Pines", "Old Roads", "Far Away"))));
    }

    @Test
    public void pickAcceptsAccentAndCaseDifferences() {
        SongLookup.Query q = SongLookup.clean("Beyonce", "Halo");
        SongLookup.Hit good = hit("Beyoncé", "HALO", "I Am... Sasha Fierce");
        assertSame(good, SongLookup.pick(q, Arrays.asList(hit("Other", "Halo", "x"), good)));
    }

    @Test
    public void pickAcceptsLongerOfficialTitle() {
        SongLookup.Query q = SongLookup.clean("The Hollow Pines", "Lantern's Bay");
        SongLookup.Hit h = hit("The Hollow Pines", "Lantern's Bay (Live)", "Live");
        assertSame(h, SongLookup.pick(q, Collections.singletonList(h)));
    }

    @Test
    public void pickNullOnEmpty() {
        assertNull(SongLookup.pick(SongLookup.clean("A", "B"), Collections.emptyList()));
        assertNull(SongLookup.pick(SongLookup.clean("A", "B"), null));
    }

    @Test
    public void artUrlTo300() {
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/a/b/100x100bb.jpg".replace("100x100", "300x300"),
                SongLookup.artUrl("https://is1-ssl.mzstatic.com/image/thumb/Music/a/b/100x100bb.jpg"));
        assertNull(SongLookup.artUrl(null));
        assertNull(SongLookup.artUrl("http://insecure/100x100bb.jpg"));
    }

    @Test
    public void cacheLineRoundTripAndNotFound() {
        SongLookup.Hit h = hit("The Hollow Pines", "Lantern's\tBay", "Ash & Ember");
        String line = SongLookup.cacheLine("k1", h);
        assertTrue(line.startsWith("k1\t"));
        SongLookup.Hit back = SongLookup.fromCacheLine(line);
        assertNotNull(back);
        assertEquals(h.album, back.album);
        assertEquals(h.year, back.year);
        assertEquals(h.genre, back.genre);
        assertEquals(h.track, back.track);
        assertEquals(h.tracks, back.tracks);
        assertEquals(h.artUrl, back.artUrl);
        assertEquals(h.title, back.title);
        String none = SongLookup.cacheLine("k2", null);
        assertEquals("k2\t-", none);
        assertSame(SongLookup.NOT_FOUND, SongLookup.fromCacheLine(none));
        assertNull(SongLookup.fromCacheLine("garbage"));
    }

    @Test
    public void cacheLineOfTheBuildsBeforeStillReads() {
        // As song_lookup.txt on the TV holds them (written before the escaping was shared with PlayerStore): a null
        // field as backslash-0, a backslash doubled, a tab as backslash-t.
        SongLookup.Hit h = SongLookup.fromCacheLine(
                "k\th\tAC\\\\DC\tBack\\tIn Black\t\\0\t1980\tRock\t6\t10\thttps://x/300x300bb.jpg");
        assertNotNull(h);
        assertEquals("AC\\DC", h.artist);
        assertEquals("Back\tIn Black", h.title);
        assertNull(h.album);
        assertEquals("1980", h.year);
        assertEquals(6, h.track);
    }

    @Test
    public void lookupKeyIsArtistAndTitle() {
        assertEquals(SongLookup.lookupKey(SongLookup.clean("Kestrel", "Kestrel - Route 9 (Audio)")),
                SongLookup.lookupKey(SongLookup.clean("kestrel", "Route 9")));
    }
    @Test
    public void switchOffInSettingsMeansNoLookup() {
        // Launcher settings › Look up song details: the choice made on the TV wins over config.xml's default.
        assertFalse(SongLookup.on(Boolean.FALSE, true));
        assertTrue(SongLookup.on(Boolean.TRUE, false));
    }

    @Test
    public void noChoiceOnTheTvFollowsConfig() {
        assertTrue(SongLookup.on(null, true));
        assertFalse(SongLookup.on(null, false));
    }
}
