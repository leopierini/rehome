package io.github.leopierini.rehome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WifiPriorityTest {

    private static final String A = "Phone A", H = "Home", S = "Sam’s Phone";
    private static final List<String> ORDER = Arrays.asList(A, H, S);

    private static Map<String, Integer> seen(Object... pairs) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    private static final Set<String> NONE = Collections.emptySet();

    @Test
    public void movesUpWhenABetterNetworkIsInRange() {
        assertEquals(A, WifiPriority.pick(ORDER, S, seen(A, -60, H, -50, S, -40), NONE));
        assertEquals(H, WifiPriority.pick(ORDER, S, seen(H, -70, S, -40), NONE));
    }

    @Test
    public void staysOnTheBestInRange() {
        assertNull(WifiPriority.pick(ORDER, A, seen(A, -80, H, -40, S, -40), NONE));
        assertNull(WifiPriority.pick(ORDER, H, seen(H, -60, S, -40), NONE));
    }

    @Test
    public void aWeakBetterNetworkIsNotWorthIt() {
        assertNull(WifiPriority.pick(ORDER, S, seen(A, -82, S, -50), NONE));
        assertEquals(H, WifiPriority.pick(ORDER, S, seen(A, -82, H, -75, S, -50), NONE));
    }

    @Test
    public void aSetAsideNetworkIsSkippedEvenWhenOnIt() {
        Set<String> aside = new HashSet<>(Collections.singletonList(A));
        assertEquals(H, WifiPriority.pick(ORDER, S, seen(A, -50, H, -60), aside));
        // On Phone A's hotspot with no internet: down to Home when it is there, else stay.
        assertEquals(H, WifiPriority.pick(ORDER, A, seen(A, -50, H, -60), aside));
        assertNull(WifiPriority.pick(ORDER, A, seen(A, -50), aside));
    }

    @Test
    public void anUnlistedCurrentNetworkGivesWayToAListedOne() {
        assertEquals(S, WifiPriority.pick(ORDER, "Other", seen(S, -60), NONE));
        assertNull(WifiPriority.pick(ORDER, "Other", seen(), NONE));
        assertEquals(S, WifiPriority.pick(ORDER, null, seen(S, -60), NONE));
    }

    @Test
    public void rankFollowsTheOrder() {
        assertEquals(0, WifiPriority.rank(ORDER, A));
        assertEquals(2, WifiPriority.rank(ORDER, S));
        assertEquals(Integer.MAX_VALUE, WifiPriority.rank(ORDER, "Other"));
    }

    @Test
    public void rankAndEntryMatchEitherApostrophe() {
        assertEquals(2, WifiPriority.rank(ORDER, "Sam's Phone"));
        assertEquals(S, WifiPriority.entry(ORDER, "Sam's Phone"));
        assertEquals(S, WifiPriority.entry(ORDER, S));
        assertEquals(A, WifiPriority.entry(ORDER, A));
        assertNull(WifiPriority.entry(ORDER, "phone a"));   // SSIDs are case-sensitive
        assertNull(WifiPriority.entry(ORDER, null));
        assertEquals("Sams's", WifiPriority.key("Sams’s"));
        assertEquals("'x'", WifiPriority.key("‘x’"));
    }

    @Test
    public void noInternetPauseGrows() {
        assertEquals(15 * 60_000L, WifiPriority.noInternetPause(1));
        assertEquals(30 * 60_000L, WifiPriority.noInternetPause(2));
        assertEquals(240 * 60_000L, WifiPriority.noInternetPause(5));
        assertEquals(240 * 60_000L, WifiPriority.noInternetPause(9));
    }
}
