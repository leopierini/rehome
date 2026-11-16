package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for which Home tiles the offline Home dims. */
public class NeedsInternetTest {

    private static boolean needs(String pkg, boolean internet, boolean tools, boolean tvInput) {
        return NeedsInternet.needs(pkg, internet, tools, tvInput);
    }

    @Test
    public void streamingAppsNeedIt() {
        assertTrue(needs("com.netflix.ninja", true, false, false));
        assertTrue(needs("com.spotify.tv.android", true, false, false));
        assertTrue(needs("tv.twitch.android.app", true, false, false));
        // Steam Link streams from a PC over the network: dimmed too.
        assertTrue(needs("com.valvesoftware.steamlink", true, false, false));
    }

    @Test
    public void appsWithoutANetworkPermissionAreNeverDimmed() {
        assertFalse(needs("com.example.offlinegame", false, false, false));
    }

    @Test
    public void theToolsRowIsNeverDimmed() {
        // The Play Store needs the network, but the tools row is where the TV is set up and fixed.
        assertFalse(needs("com.android.vending", true, true, false));
        assertFalse(needs("org.droidtv.contentexplorer", true, true, false));
    }

    @Test
    public void tvInputsAndTvSystemAppsWorkOffline() {
        // An app that brings a TV input (the tuner, HDMI) plays without the internet.
        assertFalse(needs("com.example.iptvtuner", true, false, true));
        assertFalse(needs("org.droidtv.channels", true, false, false));
        assertFalse(needs("com.mediatek.tvinput", true, false, false));
        assertFalse(needs("com.android.tv.settings", true, false, false));
        assertFalse(needs("com.android.tv", true, false, false));
        assertFalse(needs("com.google.android.tv", true, false, false));
    }

    @Test
    public void onlyHardwareInputsMakeAnAppWorkOffline() {
        // Google Play Film registers two software inputs (playback, previews) and streams through them: it dims
        // offline like every streaming app (final review M9). An HDMI passthrough or an HDMI device's input counts.
        boolean playFilm = NeedsInternet.inputPlaysOffline(false, false);
        assertFalse(playFilm);
        assertTrue(needs("com.google.android.videos", true, false, playFilm));
        assertTrue(NeedsInternet.inputPlaysOffline(true, false));
        assertTrue(NeedsInternet.inputPlaysOffline(false, true));
        assertFalse(needs("com.example.hdmiapp", true, false, NeedsInternet.inputPlaysOffline(true, false)));
    }

    @Test
    public void localPlayersWorkOffline() {
        assertFalse(needs("org.videolan.vlc", true, false, false));
        assertFalse(needs("org.xbmc.kodi", true, false, false));
    }

    @Test
    public void prefixesMatchWholeNameParts() {
        // "com.android.tv" covers its own sub-packages, not a stranger whose name only starts the same way.
        assertTrue(needs("com.android.tvx.streaming", true, false, false));
        assertTrue(needs("org.droidtvshop", true, false, false));
        assertFalse(needs("org.droidtv.playtv", true, false, false));
    }

    @Test
    public void nullPackageIsNotDimmed() {
        assertFalse(needs(null, true, false, false));
    }

    @Test
    public void toolsRowIsAutoCategorysToolsRow() {
        List<String> rows = Arrays.asList("Media", "Musica", "Giochi e Live", "Strumenti");
        assertEquals("Strumenti", AutoCategory.toolsRow(rows));
        assertEquals("Tools", AutoCategory.toolsRow(Arrays.asList("Video", "Tools", "Games")));
        // No row says it: the last one, as AutoCategory itself falls back to.
        assertEquals("Games", AutoCategory.toolsRow(Arrays.asList("Video", "Games")));
        assertEquals(null, AutoCategory.toolsRow(Collections.emptyList()));
    }

    @Test
    public void asksNetworkReadsTheRequestedPermissions() {
        assertFalse(NeedsInternet.asksNetwork(null));
        assertFalse(NeedsInternet.asksNetwork(new String[0]));
        assertFalse(NeedsInternet.asksNetwork(new String[]{"android.permission.WAKE_LOCK"}));
        // YouTube Music for TV asks only for the network state: it streams through the YouTube app.
        assertTrue(NeedsInternet.asksNetwork(new String[]{"android.permission.ACCESS_NETWORK_STATE"}));
        assertTrue(NeedsInternet.asksNetwork(new String[]{"android.permission.WAKE_LOCK", "android.permission.INTERNET"}));
        assertEquals("android.permission.INTERNET", android.Manifest.permission.INTERNET);
        assertEquals("android.permission.ACCESS_NETWORK_STATE", android.Manifest.permission.ACCESS_NETWORK_STATE);
    }

    private static java.util.LinkedHashMap<String, List<String>> defaults() {
        java.util.LinkedHashMap<String, List<String>> d = new java.util.LinkedHashMap<>();
        d.put("Media", Arrays.asList("com.netflix.ninja", "it.rainet.androidtv"));
        d.put("Musica", Collections.singletonList("com.spotify.tv.android"));
        d.put("Strumenti", Arrays.asList("com.android.vending", "org.droidtv.contentexplorer"));
        return d;
    }

    @Test
    public void movingAnAppToAnotherRowDoesNotChangeItsDimming() {
        java.util.LinkedHashMap<String, List<String>> d = defaults();
        String tools = AutoCategory.toolsRow(new java.util.ArrayList<>(d.keySet()));
        assertEquals("Strumenti", tools);
        // The owner moves Netflix into "Strumenti" and the Media Player into "Media".
        RowEdits e = RowEdits.moveToRow(RowEdits.arrange(d, RowEdits.empty()), RowEdits.empty(), "com.netflix.ninja",
                "Strumenti");
        e = RowEdits.moveToRow(RowEdits.arrange(d, e), e, "org.droidtv.contentexplorer", "Media");
        assertTrue(RowEdits.arrange(d, e).get("Strumenti").contains("com.netflix.ninja"));
        assertTrue(RowEdits.arrange(d, e).get("Media").contains("org.droidtv.contentexplorer"));
        // Dimming is decided from the rows before the edits: Netflix still dims offline, the Media Player does not.
        java.util.Map<String, Boolean> inTools = NeedsInternet.inToolsRow(d, tools);
        assertTrue(needs("com.netflix.ninja", true, inTools.get("com.netflix.ninja"), false));
        assertFalse(needs("org.droidtv.contentexplorer", true, inTools.get("org.droidtv.contentexplorer"), false));
        assertTrue(inTools.get("com.android.vending"));
        assertFalse(inTools.get("com.spotify.tv.android"));
    }

    @Test
    public void inToolsRowWithNoRows() {
        assertTrue(NeedsInternet.inToolsRow(null, "Strumenti").isEmpty());
        assertFalse(NeedsInternet.inToolsRow(defaults(), null).get("com.android.vending"));
    }

    @Test
    public void toolsRowIsWhereClassifySendsTools() {
        // The dimming exclusion and the actual tools row must not drift apart.
        List<String> titles = Arrays.asList("Media", "Musica", "Giochi e Live", "Strumenti");
        assertEquals(AutoCategory.toolsRow(titles),
                AutoCategory.classify("com.example.filemanager", "File Manager", -1, false, titles));
        List<String> english = Arrays.asList("Watch", "Tools", "Listen");
        assertEquals(AutoCategory.toolsRow(english),
                AutoCategory.classify("com.example.settings", "Settings", -1, false, english));
    }
}
