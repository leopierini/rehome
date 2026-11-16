package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The test hooks act only while ADB is on (final review I1): a key left in Settings.Global when debugging is switched
 * off could never be deleted again, so with adb_enabled 0 (or missing) every hook reads as unset.
 */
public class TestHooksTest {

    /** Every hook set, as an agent would leave them, and adb_enabled as given (null: not in Settings.Global). */
    private static TestHooks.Source settings(String adb) {
        Map<String, String> m = new HashMap<>();
        if (adb != null) m.put("adb_enabled", adb);
        m.put("rehome_test_offline", "1");
        m.put("rehome_test_watchnext", "dump");
        m.put("rehome_test_rain", "0,0,80");
        m.put("rehome_test_meter", "Oggi 12,3 GB");
        m.put("rehome_test_memory", "log");
        m.put("rehome_test_player", "all");
        m.put("rehome_test_hide_debug", "1");
        return m::get;
    }

    @Test
    public void everyHookActsWhileAdbIsOn() {
        TestHooks.Source on = settings("1");
        assertTrue(TestHooks.offline(on));
        assertEquals("dump", TestHooks.watchNext(on));
        assertArrayEquals(new int[]{0, 0, 80}, TestHooks.rain(on));
        assertEquals("Oggi 12,3 GB", TestHooks.meter(on));
        assertTrue(TestHooks.memoryLog(on));
        assertEquals("all", TestHooks.player(on));
        assertTrue(TestHooks.hideDebug(on));
    }

    @Test
    public void everyHookIsOffOnceAdbIsOff() {
        for (String adb : new String[]{"0", null, "", " 0 ", "no"}) {
            TestHooks.Source off = settings(adb);
            assertFalse(adb, TestHooks.offline(off));
            assertNull(adb, TestHooks.watchNext(off));
            assertNull(adb, TestHooks.rain(off));
            assertNull(adb, TestHooks.meter(off));
            assertFalse(adb, TestHooks.memoryLog(off));
            assertNull(adb, TestHooks.player(off));
            assertFalse(adb, TestHooks.hideDebug(off));
        }
    }

    @Test
    public void resumeHookValuesAndAdbOff() {
        Map<String, String> m = new HashMap<>();
        m.put("adb_enabled", "1");
        m.put("rehome_test_resume", "go:3");
        assertEquals("go:3", TestHooks.resume(m::get));
        m.put("rehome_test_resume", " dry:1 ");
        assertEquals("dry:1", TestHooks.resume(m::get));
        m.put("rehome_test_resume", "probe:1");
        assertNull(TestHooks.resume(m::get));
        m.put("rehome_test_resume", "go:4");
        m.put("adb_enabled", "0");
        assertNull(TestHooks.resume(m::get));
    }

    @Test
    public void playerHookValues() {
        for (String v : new String[]{"song", "video", "app", "all"}) {
            Map<String, String> m = new HashMap<>();
            m.put("adb_enabled", "1");
            m.put("rehome_test_player", v);
            assertEquals(v, TestHooks.player(m::get));
        }
        Map<String, String> m = new HashMap<>();
        m.put("adb_enabled", "1");
        m.put("rehome_test_player", "boh");
        assertNull(TestHooks.player(m::get));
    }

    @Test
    public void adbOnReadsLikeSettingsGetInt() {
        assertTrue(TestHooks.adbOn("1"));
        assertTrue(TestHooks.adbOn(" 2 "));
        assertFalse(TestHooks.adbOn("0"));
        assertFalse(TestHooks.adbOn(null));
        assertFalse(TestHooks.adbOn(""));
        assertFalse(TestHooks.adbOn("true"));
    }

    @Test
    public void theGateIsReadEveryTimeNotCached() {
        Map<String, String> m = new HashMap<>();
        m.put("adb_enabled", "1");
        m.put("rehome_test_offline", "1");
        TestHooks.Source s = m::get;
        assertTrue(TestHooks.offline(s));
        m.put("adb_enabled", "0");   // Debug USB switched off while the launcher runs
        assertFalse(TestHooks.offline(s));
    }

    @Test
    public void anUnreadableSettingIsOff() {
        TestHooks.Source refuses = k -> {
            throw new SecurityException("unknown key");
        };
        assertFalse(TestHooks.offline(refuses));
        assertNull(TestHooks.rain(refuses));
    }

    @Test
    public void unsetHooksStayOffWithAdbOn() {
        Map<String, String> m = new HashMap<>();
        m.put("adb_enabled", "1");
        TestHooks.Source s = m::get;
        assertFalse(TestHooks.offline(s));
        assertNull(TestHooks.watchNext(s));
        assertNull(TestHooks.rain(s));
        assertNull(TestHooks.meter(s));
        assertNull(TestHooks.player(s));
        assertFalse(TestHooks.memoryLog(s));
    }

    /** The README captures: the red pill hidden while the hook is set, back as soon as it is deleted. */
    @Test
    public void hideDebugHidesThePillOnlyWhileSetToOne() {
        Map<String, String> m = new HashMap<>();
        m.put("adb_enabled", "1");
        TestHooks.Source s = m::get;
        assertTrue(DebugPill.wanted(s));
        m.put("rehome_test_hide_debug", "1");
        assertTrue(TestHooks.hideDebug(s));
        assertFalse(DebugPill.wanted(s));
        m.put("rehome_test_hide_debug", " 1 ");
        assertFalse(DebugPill.wanted(s));
        m.put("rehome_test_hide_debug", "0");
        assertTrue(DebugPill.wanted(s));
        m.remove("rehome_test_hide_debug");
        assertTrue(DebugPill.wanted(s));
        m.put("adb_enabled", "0");
        assertFalse(DebugPill.wanted(s));
    }
}
