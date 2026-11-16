package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the owner's row edits: arranging over the defaults, the edits, and the file format. */
public class RowEditsTest {

    private static LinkedHashMap<String, List<String>> rows(Object... titleThenPkgs) {
        LinkedHashMap<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < titleThenPkgs.length; i += 2) {
            m.put((String) titleThenPkgs[i], Arrays.asList(((String) titleThenPkgs[i + 1]).isEmpty()
                    ? new String[0] : ((String) titleThenPkgs[i + 1]).split(",")));
        }
        return m;
    }

    private static final LinkedHashMap<String, List<String>> HOME = rows(
            "Media", "netflix,prime,youtube,raiplay",
            "Musica", "spotify,ytmusic",
            "Strumenti", "store,browser");

    @Test
    public void noEditsShowTheDefaults() {
        assertEquals(HOME, RowEdits.arrange(HOME, RowEdits.empty()));
        assertEquals(HOME, RowEdits.arrange(HOME, null));
    }

    @Test
    public void moveRightAndLeftSwapNeighbours() {
        RowEdits e = RowEdits.move(HOME, RowEdits.empty(), "netflix", +1);
        assertEquals(Arrays.asList("prime", "netflix", "youtube", "raiplay"), RowEdits.arrange(HOME, e).get("Media"));
        RowEdits back = RowEdits.move(RowEdits.arrange(HOME, e), e, "netflix", -1);
        assertEquals(HOME.get("Media"), RowEdits.arrange(HOME, back).get("Media"));
    }

    @Test
    public void moveAtTheRowsEndsChangesNothing() {
        RowEdits none = RowEdits.empty();
        assertSame(none, RowEdits.move(HOME, none, "netflix", -1));
        assertSame(none, RowEdits.move(HOME, none, "raiplay", +1));
        assertSame(none, RowEdits.move(HOME, none, "not-there", +1));
    }

    @Test
    public void onlyTheTouchedRowIsSaved() {
        RowEdits e = RowEdits.move(HOME, RowEdits.empty(), "spotify", +1);
        assertEquals(1, e.rows.size());
        assertEquals(Arrays.asList("ytmusic", "spotify"), e.rows.get("Musica"));
    }

    @Test
    public void moveToAnotherRowGoesToItsEndAndSavesBothRows() {
        RowEdits e = RowEdits.moveToRow(HOME, RowEdits.empty(), "youtube", "Musica");
        LinkedHashMap<String, List<String>> shown = RowEdits.arrange(HOME, e);
        assertEquals(Arrays.asList("netflix", "prime", "raiplay"), shown.get("Media"));
        assertEquals(Arrays.asList("spotify", "ytmusic", "youtube"), shown.get("Musica"));
        assertEquals(HOME.get("Strumenti"), shown.get("Strumenti"));
        assertEquals(2, e.rows.size());
    }

    @Test
    public void moveToTheSameOrAnUnknownRowChangesNothing() {
        RowEdits none = RowEdits.empty();
        assertSame(none, RowEdits.moveToRow(HOME, none, "youtube", "Media"));
        assertSame(none, RowEdits.moveToRow(HOME, none, "youtube", "Giochi"));
    }

    @Test
    public void aRowEmptiedByMovesIsLeftOut() {
        RowEdits e = RowEdits.moveToRow(HOME, RowEdits.empty(), "store", "Media");
        e = RowEdits.moveToRow(RowEdits.arrange(HOME, e), e, "browser", "Media");
        LinkedHashMap<String, List<String>> shown = RowEdits.arrange(HOME, e);
        assertFalse(shown.containsKey("Strumenti"));
        assertEquals(Arrays.asList("Media", "Musica"), Arrays.asList(shown.keySet().toArray()));
    }

    @Test
    public void hiddenAppsAreNotShownAndComeBackWithUnhideAll() {
        RowEdits e = RowEdits.hide(RowEdits.empty(), "prime");
        assertEquals(Arrays.asList("netflix", "youtube", "raiplay"), RowEdits.arrange(HOME, e).get("Media"));
        // Hidden after being moved: still hidden.
        RowEdits moved = RowEdits.moveToRow(HOME, RowEdits.empty(), "prime", "Musica");
        RowEdits both = RowEdits.hide(moved, "prime");
        assertFalse(RowEdits.arrange(HOME, both).get("Musica").contains("prime"));
        // Unhidden: back where the edits put it.
        assertEquals(Arrays.asList("spotify", "ytmusic", "prime"),
                RowEdits.arrange(HOME, RowEdits.unhideAll(both)).get("Musica"));
    }

    @Test
    public void newInstallsLandInTheirDefaultRowAfterTheListedApps() {
        RowEdits e = RowEdits.move(HOME, RowEdits.empty(), "netflix", +1);
        LinkedHashMap<String, List<String>> later = rows(
                "Media", "netflix,prime,youtube,raiplay,disney",
                "Musica", "spotify,ytmusic",
                "Strumenti", "store,browser");
        assertEquals(Arrays.asList("prime", "netflix", "youtube", "raiplay", "disney"),
                RowEdits.arrange(later, e).get("Media"));
    }

    @Test
    public void uninstalledPackagesAndUnknownRowsAreIgnored() {
        LinkedHashMap<String, List<String>> saved = new LinkedHashMap<>();
        saved.put("Media", Arrays.asList("gone", "raiplay", "netflix"));
        saved.put("Vecchia riga", Arrays.asList("youtube"));
        RowEdits e = new RowEdits(saved, new LinkedHashSet<>(Arrays.asList("also.gone")));
        LinkedHashMap<String, List<String>> shown = RowEdits.arrange(HOME, e);
        assertEquals(Arrays.asList("raiplay", "netflix", "prime", "youtube"), shown.get("Media"));
        assertEquals(HOME.keySet(), shown.keySet());
    }

    @Test
    public void theFileWinsOverTheDefaultRow() {
        LinkedHashMap<String, List<String>> saved = new LinkedHashMap<>();
        saved.put("Strumenti", Arrays.asList("netflix", "store", "browser"));
        RowEdits e = new RowEdits(saved, new LinkedHashSet<>());
        LinkedHashMap<String, List<String>> shown = RowEdits.arrange(HOME, e);
        assertFalse(shown.get("Media").contains("netflix"));
        assertEquals(Arrays.asList("netflix", "store", "browser"), shown.get("Strumenti"));
    }

    @Test
    public void roundTrips() {
        RowEdits hidden = RowEdits.hide(RowEdits.empty(), "prime");
        RowEdits e = RowEdits.moveToRow(RowEdits.arrange(HOME, hidden), hidden, "youtube", "Musica");
        String text = RowEdits.encode(e);
        assertEquals("rehome-rows 1\nhide prime\nrow Media\nnetflix\nraiplay\nrow Musica\nspotify\nytmusic\nyoutube\n",
                text);
        RowEdits back = RowEdits.decode(text);
        assertEquals(e.rows, back.rows);
        assertEquals(e.hidden, back.hidden);
    }

    @Test
    public void aRowTitleWithSpacesRoundTrips() {
        LinkedHashMap<String, List<String>> saved = new LinkedHashMap<>();
        saved.put("Giochi e Live", Arrays.asList("tv.twitch.android.app"));
        RowEdits back = RowEdits.decode(RowEdits.encode(new RowEdits(saved, new LinkedHashSet<>())));
        assertEquals(saved, back.rows);
    }

    @Test
    public void corruptInputIsRejectedAsAWhole() {
        assertNull(RowEdits.decode(null));
        assertNull(RowEdits.decode(""));
        assertNull(RowEdits.decode("rehome-order 1\ndock x\n"));            // the other file
        assertNull(RowEdits.decode("rehome-rows 1\nnetflix\n"));            // a package before any row
        assertNull(RowEdits.decode("rehome-rows 1\nrow Media\na\n\nb\n"));  // a blank line inside
        assertNull(RowEdits.decode("rehome-rows 1\nrow Media\na\nrow Musica\na\n"));   // listed twice
        assertNull(RowEdits.decode("rehome-rows 1\nrow Media\nrow Media\n"));          // a row twice
        assertNull(RowEdits.decode("rehome-rows 1\nrow \n"));                          // no title
        assertNull(RowEdits.decode("rehome-rows 1\nrow Media\nhide a\n"));             // hide after the rows
        assertNull(RowEdits.decode("rehome-rows 1\nhide a\nhide a\n"));                // hidden twice
        assertNull(RowEdits.decode("rehome-rows 1\nrow Media\nnot a package\n"));
        assertNull(RowEdits.decode("rehome-rows 1\nrow Media\n\u0000\u0001garbage\n"));
    }

    @Test
    public void anEmptyFileBodyIsNoEdits() {
        RowEdits e = RowEdits.decode("rehome-rows 1\n");
        assertTrue(e.isEmpty());
        assertTrue(RowEdits.decode("rehome-rows 1").isEmpty());
    }

    @Test
    public void anEmptyRowIsKeptAsAnEdit() {
        // Every app of a row moved out: the row stays listed (empty), so it does not refill from config.xml's list.
        RowEdits e = RowEdits.decode("rehome-rows 1\nrow Strumenti\nrow Media\nstore\nnetflix\n");
        assertTrue(e.rows.get("Strumenti").isEmpty());
        LinkedHashMap<String, List<String>> shown = RowEdits.arrange(HOME, e);
        assertEquals(Arrays.asList("store", "netflix", "prime", "youtube", "raiplay"), shown.get("Media"));
        // browser is not listed anywhere, so it stays in its default row, which therefore still shows.
        assertEquals(Arrays.asList("browser"), shown.get("Strumenti"));
    }

    @Test
    public void packageNames() {
        assertTrue(RowEdits.isPackage("com.netflix.ninja"));
        assertTrue(RowEdits.isPackage("it.rainet.androidtv"));
        assertTrue(RowEdits.isPackage("com.example.weather"));
        assertFalse(RowEdits.isPackage(""));
        assertFalse(RowEdits.isPackage("a b"));
        assertFalse(RowEdits.isPackage(".a"));
        assertFalse(RowEdits.isPackage("a."));
    }
}
