package io.github.leopierini.rehome;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the Home shortcuts: digits into the Media row, colour keys from home_color_keys. */
public class HomeKeysTest {

    private static final List<String> MEDIA = Arrays.asList("netflix", "prime", "youtube", "raiplay");
    private static final Map<String, String> COLORS = HomeKeys.parseColors(new String[]{
            "red|com.netflix.ninja", "green|it.rainet.androidtv",
            "yellow|com.google.android.youtube.tv", "blue|com.amazon.amazonvideo.livingroom"});

    @Test
    public void digitsOpenTheNthMediaApp() {
        assertEquals("netflix", HomeKeys.target(KeyEvent.KEYCODE_1, true, MEDIA, COLORS));
        assertEquals("raiplay", HomeKeys.target(KeyEvent.KEYCODE_4, true, MEDIA, COLORS));
        assertEquals("prime", HomeKeys.target(KeyEvent.KEYCODE_NUMPAD_2, true, MEDIA, COLORS));
    }

    @Test
    public void digitsPastTheRowOrZeroOpenNothing() {
        assertNull(HomeKeys.target(KeyEvent.KEYCODE_5, true, MEDIA, COLORS));
        assertNull(HomeKeys.target(KeyEvent.KEYCODE_9, true, MEDIA, COLORS));
        assertNull(HomeKeys.target(KeyEvent.KEYCODE_0, true, MEDIA, COLORS));
        assertNull(HomeKeys.target(KeyEvent.KEYCODE_1, true, Collections.emptyList(), COLORS));
        assertNull(HomeKeys.target(KeyEvent.KEYCODE_1, true, null, COLORS));
    }

    @Test
    public void digitsOffMeansNothingAndNotTaken() {
        assertNull(HomeKeys.target(KeyEvent.KEYCODE_1, false, MEDIA, COLORS));
        assertFalse(HomeKeys.handles(KeyEvent.KEYCODE_1, false));
        assertTrue(HomeKeys.handles(KeyEvent.KEYCODE_1, true));
        // The colours still work with the digits off.
        assertTrue(HomeKeys.handles(KeyEvent.KEYCODE_PROG_RED, false));
        assertEquals("com.netflix.ninja", HomeKeys.target(KeyEvent.KEYCODE_PROG_RED, false, MEDIA, COLORS));
    }

    @Test
    public void colourKeys() {
        assertEquals("com.netflix.ninja", HomeKeys.target(KeyEvent.KEYCODE_PROG_RED, true, MEDIA, COLORS));
        assertEquals("it.rainet.androidtv", HomeKeys.target(KeyEvent.KEYCODE_PROG_GREEN, true, MEDIA, COLORS));
        assertEquals("com.google.android.youtube.tv", HomeKeys.target(KeyEvent.KEYCODE_PROG_YELLOW, true, MEDIA, COLORS));
        assertEquals("com.amazon.amazonvideo.livingroom", HomeKeys.target(KeyEvent.KEYCODE_PROG_BLUE, true, MEDIA, COLORS));
    }

    @Test
    public void otherKeysAreNotTaken() {
        for (int k : new int[]{KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP}) {
            assertFalse(HomeKeys.handles(k, true));
            assertNull(HomeKeys.target(k, true, MEDIA, COLORS));
        }
    }

    @Test
    public void aColourWithNoAppOpensNothing() {
        Map<String, String> onlyRed = HomeKeys.parseColors(new String[]{"red|com.netflix.ninja"});
        assertNull(HomeKeys.target(KeyEvent.KEYCODE_PROG_BLUE, true, MEDIA, onlyRed));
        assertNull(HomeKeys.target(KeyEvent.KEYCODE_PROG_BLUE, true, MEDIA, null));
    }

    @Test
    public void parsingSkipsBadLinesAndKeepsTheFirst() {
        Map<String, String> m = HomeKeys.parseColors(new String[]{
                " RED | com.netflix.ninja ", "red|it.rainet.androidtv", "purple|x.y", "green|", "|a.b", "blue",
                "yellow|not a package", null});
        assertEquals(1, m.size());
        assertEquals("com.netflix.ninja", m.get("red"));
        assertTrue(HomeKeys.parseColors(null).isEmpty());
    }

    @Test
    public void digitValues() {
        assertEquals(1, HomeKeys.digit(KeyEvent.KEYCODE_1));
        assertEquals(9, HomeKeys.digit(KeyEvent.KEYCODE_9));
        assertEquals(0, HomeKeys.digit(KeyEvent.KEYCODE_0));
        assertEquals(0, HomeKeys.digit(KeyEvent.KEYCODE_NUMPAD_0));
        assertEquals(9, HomeKeys.digit(KeyEvent.KEYCODE_NUMPAD_9));
    }

    // ---- the DOWN / repeat / UP gate (CategoryHome.onHomeKey) ----

    @Test
    public void aTakenKeyHasItsRepeatsAndUpEaten() {
        HomeKeys.Gate g = new HomeKeys.Gate();
        assertTrue(g.onKey(KeyEvent.KEYCODE_1, true, 0, true));
        assertTrue(g.onKey(KeyEvent.KEYCODE_1, true, 1, true));
        assertTrue(g.onKey(KeyEvent.KEYCODE_1, false, 0, true));
        // Its UP once only: a second UP is not Home's.
        assertFalse(g.onKey(KeyEvent.KEYCODE_1, false, 0, true));
    }

    @Test
    public void anUpWithoutItsDownIsNotEaten() {
        // The digit went down inside an app; its UP lands on Home after the app closed.
        HomeKeys.Gate g = new HomeKeys.Gate();
        assertFalse(g.onKey(KeyEvent.KEYCODE_2, false, 0, true));
    }

    @Test
    public void aRepeatAfterTheGateClosedIsNotEaten() {
        // Held while a popup was open (Home did not take it): its repeats go on to the popup.
        HomeKeys.Gate g = new HomeKeys.Gate();
        assertFalse(g.onKey(KeyEvent.KEYCODE_3, true, 0, false));
        assertFalse(g.onKey(KeyEvent.KEYCODE_3, true, 1, true));
        assertFalse(g.onKey(KeyEvent.KEYCODE_3, false, 0, true));
    }

    @Test
    public void anotherKeyDownForgetsTheTakenOne() {
        HomeKeys.Gate g = new HomeKeys.Gate();
        assertTrue(g.onKey(KeyEvent.KEYCODE_PROG_RED, true, 0, true));
        // D-pad down before red's UP (two keys at once): red's UP is no longer Home's, the D-pad is not eaten.
        assertFalse(g.onKey(KeyEvent.KEYCODE_DPAD_DOWN, true, 0, false));
        assertFalse(g.onKey(KeyEvent.KEYCODE_PROG_RED, false, 0, true));
        assertFalse(g.onKey(KeyEvent.KEYCODE_DPAD_DOWN, false, 0, false));
    }
}
