package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The player's memory file: what is kept across restarts, and which art files are left over. */
public class PlayerStoreTest {

    private static PlayerPick.Item song() {
        PlayerPick.Item i = new PlayerPick.Item();
        i.kind = PlayerPick.SONG;
        i.pkg = "com.spotify.tv.android";
        i.key = PlayerPick.key(i.kind, i.pkg, "Lantern's Bay");
        i.title = "Lan\ttern's\nBay \\ end";
        i.artist = "The Hollow Pines";
        i.album = "Ash & Ember";
        i.art = "/data/user/0/io.github.leopierini.rehome/files/player_art/x.jpg";
        i.position = 107_000;
        i.duration = 238_000;
        i.lastPlayed = 1_794_740_000_000L;
        i.playedMs = 95_000;
        i.year = "2016";
        i.genre = "Folk";
        i.track = 3;
        i.tracks = 12;
        i.live = true;
        i.playing = true;
        i.next = new WatchNext.Item();
        return i;
    }

    @Test
    public void roundTripKeepsFields() {
        PlayerPick.Item a = song();
        List<PlayerPick.Item> back = PlayerStore.decode(PlayerStore.encode(Collections.singletonList(a)));
        assertEquals(1, back.size());
        PlayerPick.Item b = back.get(0);
        assertEquals(a.key, b.key);
        assertEquals(a.kind, b.kind);
        assertEquals(a.pkg, b.pkg);
        assertEquals(a.title, b.title);
        assertEquals(a.artist, b.artist);
        assertEquals(a.album, b.album);
        assertEquals(a.art, b.art);
        assertEquals(a.position, b.position);
        assertEquals(a.duration, b.duration);
        assertEquals(a.lastPlayed, b.lastPlayed);
        assertEquals(a.playedMs, b.playedMs);
        assertEquals(a.year, b.year);
        assertEquals(a.genre, b.genre);
        assertEquals(a.track, b.track);
        assertEquals(a.tracks, b.tracks);
    }

    @Test
    public void liveStateAndWatchNextAreNotSaved() {
        PlayerPick.Item b = PlayerStore.decode(PlayerStore.encode(Collections.singletonList(song()))).get(0);
        assertFalse(b.live);
        assertFalse(b.playing);
        assertNull(b.next);
    }

    @Test
    public void nullFieldsSurvive() {
        PlayerPick.Item a = new PlayerPick.Item();
        a.kind = PlayerPick.APP;
        a.pkg = "com.netflix.ninja";
        a.key = PlayerPick.key(a.kind, a.pkg, null);
        a.lastPlayed = 5;
        PlayerPick.Item b = PlayerStore.decode(PlayerStore.encode(Collections.singletonList(a))).get(0);
        assertEquals(a.key, b.key);
        assertNull(b.title);
        assertNull(b.art);
    }

    @Test
    public void decodeSkipsBrokenLines() {
        String good = PlayerStore.encode(Collections.singletonList(song()));
        String text = "garbage\n" + good + "\n1\t2\n\n" + good.replace("\t3\t", "\tx\t");
        List<PlayerPick.Item> back = PlayerStore.decode(text);
        assertTrue(back.size() >= 1);
        assertEquals(song().key, back.get(0).key);
    }

    @Test
    public void decodeNullOrEmpty() {
        assertTrue(PlayerStore.decode(null).isEmpty());
        assertTrue(PlayerStore.decode("").isEmpty());
    }

    /** Review focus 3: the next track never inherits the previous one's cover file. */
    @Test
    public void artNameChangesWithTitle() {
        String key = PlayerPick.key(PlayerPick.SONG, "com.spotify.tv.android", "x");
        assertNotEquals(PlayerStore.artName(key, "Lantern's Bay"), PlayerStore.artName(key, "Low Tide"));
        assertEquals(PlayerStore.artName(key, "Low Tide"), PlayerStore.artName(key, "Low Tide"));
    }

    @Test
    public void artNameIsFileSafe() {
        String n = PlayerStore.artName("1|com.google.android.youtube.tv|kestrel / route 9", "Kestrel / Route 9 ✅");
        assertTrue(n, n.matches("[a-z0-9_]+\\.jpg"));
    }

    @Test
    public void orphanArtListsFilesOfDroppedItems() {
        PlayerPick.Item kept = song();
        kept.art = "/files/player_art/" + PlayerStore.artName(kept.key, kept.title);
        String keptName = PlayerStore.artName(kept.key, kept.title);
        List<String> onDisk = new ArrayList<>(Arrays.asList(keptName, "old_one.jpg", "another.jpg"));
        Set<String> orphans = PlayerStore.orphanArt(Collections.singletonList(kept), onDisk);
        assertEquals(2, orphans.size());
        assertTrue(orphans.contains("old_one.jpg"));
        assertFalse(orphans.contains(keptName));
    }

    @Test
    public void artNameChangesWithSource() {
        // Review 8: a cover that replaces another (iTunes over the session's) never reuses its file name.
        String k = "0|com.spotify.tv.android";
        assertNotEquals(PlayerStore.artName(k, "Song"), PlayerStore.artName(k, "Song", "itunes"));
        assertNotEquals(PlayerStore.artName(k, "Song", "uri"), PlayerStore.artName(k, "Song", "itunes"));
        assertEquals(PlayerStore.artName(k, "Song", "uri"), PlayerStore.artName(k, "Song", "uri"));
    }

    @Test
    public void linkAndPictureUrlRoundTrip() {
        // The durable link (MEDIA_URI/MEDIA_ID) and the picture's https source survive a restart, so a lost art file
        // can be fetched again and Resume can open the content.
        PlayerPick.Item a = song();
        a.link = "spotify:track:4uLU6hMCjMI75M1A2tKUQC";
        a.artUrl = "https://i.scdn.co/image/ab67616d00001e02x";
        PlayerPick.Item b = PlayerStore.decode(PlayerStore.encode(Collections.singletonList(a))).get(0);
        assertEquals(a.link, b.link);
        assertEquals(a.artUrl, b.artUrl);
        PlayerPick.Item n = PlayerStore.decode(PlayerStore.encode(Collections.singletonList(song()))).get(0);
        assertNull(n.link);
        assertNull(n.artUrl);
    }

    @Test
    public void lineOfTheFirstFormatStillLoads() {
        // A memory written by the builds before the link (format p1, 16 fields) is read whole, without link.
        String p1 = "p1\t0|com.spotify.tv.android\t0\tcom.spotify.tv.android\tNorth Fields\tKMR\tNorth Fields\t"
                + "/data/user/0/io.github.leopierini.rehome/files/player_art/a_1.jpg\t9383\t180000\t1794740000000\t62000\t"
                + "2024\tJazz\t1\t1\n";
        List<PlayerPick.Item> back = PlayerStore.decode(p1);
        assertEquals(1, back.size());
        PlayerPick.Item b = back.get(0);
        assertEquals("0|com.spotify.tv.android", b.key);
        assertEquals("North Fields", b.title);
        assertEquals("KMR", b.artist);
        assertEquals(9383, b.position);
        assertEquals(62000, b.playedMs);
        assertEquals("Jazz", b.genre);
        assertEquals(1, b.tracks);
        assertNull(b.link);
        assertNull(b.artUrl);
        // A first-format line with the new fields' count, or a new-format line cut short, is not misread.
        assertTrue(PlayerStore.decode(p1.replace("\n", "\tx\ty\n")).isEmpty());
        String p2 = PlayerStore.encode(Collections.singletonList(song()));
        assertTrue(PlayerStore.decode(p2.substring(0, p2.lastIndexOf('\t')) + "\n").isEmpty());
    }
}
