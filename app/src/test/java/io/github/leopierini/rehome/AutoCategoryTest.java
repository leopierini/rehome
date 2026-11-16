package io.github.leopierini.rehome;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Plain JVM tests for the row a newly installed app lands in when no category row lists it. */
public class AutoCategoryTest {

    private static final List<String> ROWS = Arrays.asList("Media", "Musica", "Giochi e Live", "Strumenti");
    private static final int NONE = ApplicationInfo.CATEGORY_UNDEFINED;

    private static String row(String pkg, String label) {
        return AutoCategory.classify(pkg, label, NONE, false, ROWS);
    }

    @Test
    public void musicApps() {
        assertEquals("Musica", row("com.spotify.tv.android", "Spotify"));
        assertEquals("Musica", row("deezer.android.tv", "Deezer"));
        assertEquals("Musica", row("it.radioplayer.android", "Radioplayer"));
        assertEquals("Musica", row("com.google.android.youtube.tvmusic", "YouTube Music"));
        assertEquals("Musica", row("it.radiorai.soundtv", "RaiPlay Sound"));
    }

    @Test
    public void videoApps() {
        assertEquals("Media", row("com.dazn", "DAZN"));
        assertEquals("Media", row("tv.pluto.android", "Pluto TV"));
        assertEquals("Media", row("org.xbmc.kodi", "Kodi"));
        assertEquals("Media", row("org.videolan.vlc", "VLC"));
        assertEquals("Media", row("it.rainet.androidtv", "RaiPlay"));
        assertEquals("Media", row("com.nousguide.android.rbtv", "Red Bull TV"));
    }

    @Test
    public void gamesBeatStreamingWords() {
        assertEquals("Giochi e Live", AutoCategory.classify("com.example.puzzle", "Puzzle", NONE, true, ROWS));
        assertEquals("Giochi e Live", AutoCategory.classify("com.example.puzzle", "Puzzle",
                ApplicationInfo.CATEGORY_GAME, false, ROWS));
        assertEquals("Giochi e Live", row("com.boosteroid.streaming", "Boosteroid"));
        assertEquals("Giochi e Live", row("tv.twitch.android.app", "Twitch"));
    }

    @Test
    public void toolApps() {
        assertEquals("Strumenti", row("com.tcl.browser", "Browser"));
        assertEquals("Strumenti", row("com.alphainventor.filemanager", "File Manager"));
        assertEquals("Strumenti", row("de.twokit.castbrowser", "TV Cast"));
        assertEquals("Strumenti", row("com.android.vending", "Google Play Store"));
    }

    @Test
    public void declaredCategoryWins() {
        assertEquals("Musica", AutoCategory.classify("com.example.x", "X", ApplicationInfo.CATEGORY_AUDIO, false, ROWS));
        assertEquals("Media", AutoCategory.classify("com.example.x", "X", ApplicationInfo.CATEGORY_VIDEO, false, ROWS));
        assertEquals("Strumenti", AutoCategory.classify("com.example.tv", "Photo TV",
                ApplicationInfo.CATEGORY_IMAGE, false, ROWS));
        // NEWS falls through to the name: a TV news channel is video.
        assertEquals("Media", AutoCategory.classify("it.sky.tg24", "Sky TG24",
                ApplicationInfo.CATEGORY_NEWS, false, ROWS));
    }

    @Test
    public void radioBeatsBareTvButNotVideo() {
        assertEquals("Musica", row("com.example.radiotv", "Radio TV"));
        assertEquals("Media", row("com.example.radiovideo", "Radio Video"));
    }

    @Test
    public void shortWordsMatchWholeTokensOnly() {
        assertEquals("Strumenti", row("com.snowapps.snow", "Snow"));
        assertEquals("Media", row("it.sky.now", "NOW"));
    }

    @Test
    public void unknownAppGoesToTools() {
        assertEquals("Strumenti", row("com.example.foo", "Foo"));
        assertEquals("Strumenti", row(null, null));
    }

    @Test
    public void rolesFollowTheTitlesGiven() {
        List<String> en = Arrays.asList("Videos", "Music", "Games", "Tools");
        assertEquals("Music", AutoCategory.classify("com.spotify.tv.android", "Spotify", NONE, false, en));
        assertEquals("Games", AutoCategory.classify("tv.twitch.android.app", "Twitch", NONE, false, en));
        assertEquals("Tools", AutoCategory.classify("com.example.foo", "Foo", NONE, false, en));
        // No music row: music goes to the media row; no games or tools row: the last title.
        List<String> two = Arrays.asList("Film", "Varie");
        assertEquals("Film", AutoCategory.classify("com.spotify.tv.android", "Spotify", NONE, false, two));
        assertEquals("Varie", AutoCategory.classify("tv.twitch.android.app", "Twitch", NONE, false, two));
        assertEquals("Varie", AutoCategory.classify("com.example.foo", "Foo", NONE, false, two));
        assertNull(AutoCategory.classify("com.example.foo", "Foo", NONE, false, Collections.<String>emptyList()));
    }

    @Test
    public void namesInOneWordAndWithDigits() {
        assertEquals("Media", row("it.example.la7", "La7"));
        assertEquals("Media", row("it.example.tv8", "TV8"));
        assertEquals("Media", row("com.example.pluto", "PlutoTV"));
        assertEquals("Media", row("fr.example.ftv", "FranceTV"));
        assertEquals("Media", row("com.stremio.one", "Stremio"));
        assertEquals("Media", row("org.smarttube.stable", "SmartTube"));
        assertEquals("Media", row("tv.arte.plus7", "ARTE"));
    }

    @Test
    public void gameWordsInsideAName() {
        assertEquals("Giochi e Live", row("com.example.pass", "GamePass"));
        // "videogame" is a game, not a video.
        assertEquals("Giochi e Live", row("com.example.vg", "Videogame Hub"));
        assertEquals("Giochi e Live", row("it.example.vg", "Videogiochi"));
    }

    @Test
    public void toolsNamedForSoundOrAfterAnEmulator() {
        assertEquals("Strumenti", row("com.example.term", "Terminal Emulator"));
        assertEquals("Strumenti", row("com.example.audio", "Audio Settings"));
        assertEquals("Strumenti", row("com.example.bar", "Soundbar Setup"));
        // Music still wins where nothing says "settings".
        assertEquals("Musica", row("com.google.android.youtube.tvmusic", "YouTube Music"));
    }

    @Test
    public void tvInThePackageAloneSaysNothing() {
        assertEquals("Strumenti", row("tv.example.app", "Foo"));
        assertEquals("Strumenti", row("com.example.androidtv", "Foo"));
    }

    @Test
    public void declaredCategories() {
        assertEquals("Strumenti", AutoCategory.classify("com.example.a", "Radio Maps",
                ApplicationInfo.CATEGORY_MAPS, false, ROWS));
        assertEquals("Strumenti", AutoCategory.classify("com.example.a", "Video Chat",
                ApplicationInfo.CATEGORY_SOCIAL, false, ROWS));
        // A game that also declares itself as video is still a game.
        assertEquals("Giochi e Live", AutoCategory.classify("com.example.a", "Foo",
                ApplicationInfo.CATEGORY_VIDEO, true, ROWS));
    }

    @Test
    public void turkishLocaleDoesNotBreakTheWords() {
        java.util.Locale before = java.util.Locale.getDefault();
        java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
        try {
            assertEquals("Musica", row("com.example.t", "TIDAL"));
            assertEquals("Media", row("com.example.t", "TIMVISION"));
            assertEquals("Media", row("com.example.t", "IPTV"));
        } finally {
            java.util.Locale.setDefault(before);
        }
    }
}
