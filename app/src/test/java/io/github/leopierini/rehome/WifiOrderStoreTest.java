package io.github.leopierini.rehome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The Wi-Fi priority order kept in the launcher's settings (WifiOrderActivity), and what it does to the switching. */
public class WifiOrderStoreTest {

    private static final String H = "Home", A = "Phone A", S = "Sam’s Phone";
    private static final Set<String> NONE = Collections.emptySet();

    private static List<String> list(String... s) {
        return Arrays.asList(s);
    }

    private static Map<String, Integer> seen(Object... pairs) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    // ---- merge --------------------------------------------------------------------------------------

    @Test
    public void mergeKeepsTheOrderedNetworksStillSavedMatchingEitherApostrophe() {
        assertEquals(list("B’s", "C"), WifiOrderStore.merge(list("A", "B’s", "C"), list("B's", "C")));
    }

    @Test
    public void mergeOfAnEmptyOrderIsEmpty() {
        assertEquals(list(), WifiOrderStore.merge(list(), list("B's", "C")));
    }

    @Test
    public void mergeDoesNotAppendSavedNetworksOutsideTheOrder() {
        assertEquals(list(A), WifiOrderStore.merge(list(A), list(H, A, S)));
    }

    @Test
    public void mergeKeepsTheOrderNotTheSavedListsOrder() {
        assertEquals(list(S, H, A), WifiOrderStore.merge(list(S, H, A), list(A, H, "Sam's Phone")));
    }

    @Test
    public void mergeDropsADuplicateSpelling() {
        assertEquals(list(S), WifiOrderStore.merge(list(S, "Sam's Phone"), list(S)));
    }

    // ---- storage ------------------------------------------------------------------------------------

    @Test
    public void setThenGetComesBackTheSame() {
        CityStoreTest.FakePrefs p = new CityStoreTest.FakePrefs();
        List<String> order = list(S, H, "Phone A, 5G", "[\"odd\"]");
        WifiOrderStore.set(p, order);
        assertEquals(order, WifiOrderStore.get(p, list()));
        assertTrue(WifiOrderStore.isSet(p));
    }

    @Test
    public void nothingStoredFallsBackToTheConfigList() {
        CityStoreTest.FakePrefs p = new CityStoreTest.FakePrefs();
        assertFalse(WifiOrderStore.isSet(p));
        assertEquals(list(H, A), WifiOrderStore.get(p, list(H, A)));
        assertEquals(list(), WifiOrderStore.get(p, list()));
    }

    @Test
    public void anEmptyStoredOrderMeansNoSwitching() {
        CityStoreTest.FakePrefs p = new CityStoreTest.FakePrefs();
        WifiOrderStore.set(p, list());
        // Emptied here: the config list no longer counts either.
        List<String> order = WifiOrderStore.get(p, list(H, A));
        assertEquals(list(), order);
        assertNull(WifiPriority.pick(order, A, seen(H, -40, A, -70), NONE));
        assertNull(WifiPriority.pick(order, null, seen(H, -40), NONE));
    }

    @Test
    public void aStoredOrderWinsOverTheConfigList() {
        CityStoreTest.FakePrefs p = new CityStoreTest.FakePrefs();
        List<String> config = list(H, A);
        // The config order alone: on Phone A with Home in range, move to Home.
        assertEquals(H, WifiPriority.pick(WifiOrderStore.get(p, config), A, seen(H, -50, A, -60), NONE));
        // Reordered here: Phone A first, so the TV stays on it, and moves to it from Home.
        WifiOrderStore.set(p, list(A, H));
        assertNull(WifiPriority.pick(WifiOrderStore.get(p, config), A, seen(H, -50, A, -60), NONE));
        assertEquals(A, WifiPriority.pick(WifiOrderStore.get(p, config), H, seen(H, -50, A, -60), NONE));
        assertEquals(1, WifiPriority.rank(WifiOrderStore.get(p, config), H));
    }

    @Test
    public void aDamagedValueCountsAsNothingStored() {
        CityStoreTest.FakePrefs p = new CityStoreTest.FakePrefs();
        p.values.put(WifiOrderStore.KEY, "not json");
        assertEquals(list(H), WifiOrderStore.get(p, list(H)));
        p.values.put(WifiOrderStore.KEY, "[\"Home\", 3, null]");
        assertEquals(list(A), WifiOrderStore.get(p, list(A)));
    }

    @Test
    public void storingLeavesTheOtherSettingsAlone() {
        CityStoreTest.FakePrefs p = new CityStoreTest.FakePrefs();
        p.values.put(Config.SETTINGS_REVISION_KEY, 7L);
        WifiOrderStore.set(p, list(H));
        assertEquals(7L, p.values.get(Config.SETTINGS_REVISION_KEY));
    }

    // ---- edits on the screen ------------------------------------------------------------------------

    @Test
    public void moveUpAndDownStopAtTheEnds() {
        assertEquals(list(A, H, S), WifiOrderStore.moved(list(H, A, S), 1, -1));
        assertEquals(list(H, S, A), WifiOrderStore.moved(list(H, A, S), 1, +1));
        assertEquals(list(H, A, S), WifiOrderStore.moved(list(H, A, S), 0, -1));
        assertEquals(list(H, A, S), WifiOrderStore.moved(list(H, A, S), 2, +1));
    }

    @Test
    public void editsReturnNewListsAndLeaveTheirInputAlone() {
        List<String> order = list(H, A);
        WifiOrderStore.moved(order, 0, +1);
        WifiOrderStore.removed(order, 0);
        WifiOrderStore.added(order, S);
        assertEquals(list(H, A), order);
    }

    @Test
    public void removeAndAdd() {
        assertEquals(list(H, S), WifiOrderStore.removed(list(H, A, S), 1));
        assertEquals(list(H, A, S), WifiOrderStore.added(list(H, A), S));
        // Already in it (either apostrophe): nothing changes.
        assertEquals(list(H, S), WifiOrderStore.added(list(H, S), "Sam's Phone"));
    }

    @Test
    public void notOrderedIsTheRestOfTheSavedNetworksByName() {
        assertEquals(list("garden", H, A),
                WifiOrderStore.notOrdered(list(S), list(A, "Sam's Phone", H, "garden")));
        assertEquals(list(), WifiOrderStore.notOrdered(list(H, A), list(A, H)));
        assertEquals(list(H, A), WifiOrderStore.notOrdered(list(), list(A, H)));
    }

    // ---- networks the TV did not list (a partial SAVED answer) -----------------------------------------

    @Test
    public void anUnlistedNetworkStaysRightAfterTheOneItFollowedWhenTheOrderIsEdited() {
        // Stored [A, B, C]; the TV lists only A and C; the page shows [A, C] and C is moved up.
        List<String> shown = WifiOrderStore.merge(list("A", "B", "C"), list("A", "C"));
        assertEquals(list("A", "C"), shown);
        List<String> edited = WifiOrderStore.moved(shown, 1, -1);
        assertEquals(list("C", "A", "B"), WifiOrderStore.withUnseen(list("A", "B", "C"), edited, list("A", "C")));
    }

    @Test
    public void anUnlistedNetworkAfterARemovedOneFollowsTheOneBeforeOrGoesToTheTop() {
        // A removed: B followed A, nothing before A is left, so B goes to the top.
        assertEquals(list("B", "C"), WifiOrderStore.withUnseen(list("A", "B", "C"), list("C"), list("A", "C")));
        // C removed from [A, C, B, D] with B, D unlisted: B and D now follow A.
        assertEquals(list("A", "B", "D"),
                WifiOrderStore.withUnseen(list("A", "C", "B", "D"), list("A"), list("A", "C")));
    }

    @Test
    public void unlistedNetworksAtTheTopStayAtTheTop() {
        assertEquals(list("B", "C", "A"), WifiOrderStore.withUnseen(list("B", "A", "C"), list("C", "A"), list("A", "C")));
    }

    @Test
    public void anAddedNetworkGoesAfterTheUnlistedOnesBeforeIt() {
        assertEquals(list("A", "B", "C", "D"),
                WifiOrderStore.withUnseen(list("A", "B", "C"), list("A", "C", "D"), list("A", "C", "D")));
    }

    @Test
    public void withEverythingListedTheEditIsStoredAsIs() {
        assertEquals(list(A, H), WifiOrderStore.withUnseen(list(H, A, S), list(A, H), list(H, A, S)));
        // Apostrophes: the listed spelling counts as seen, nothing is put back twice.
        assertEquals(list(S), WifiOrderStore.withUnseen(list(S), list(S), list("Sam's Phone")));
    }

    @Test
    public void theSettingsRowCountsWhatThePageShowsOnceTheTvHasAnswered() {
        assertEquals(2, WifiOrderStore.shownCount(list("A", "B", "C"), list("A", "C", "D")));
        assertEquals(0, WifiOrderStore.shownCount(list("A"), list("D")));
        // Not answered (yet, or no helper): the whole order.
        assertEquals(3, WifiOrderStore.shownCount(list("A", "B", "C"), null));
        assertEquals(0, WifiOrderStore.shownCount(list(), null));
    }

    @Test
    public void focusAfterRemovingStaysInPlaceOrStepsBack() {
        assertEquals(1, WifiOrderStore.focusAfterRemove(1, 3));   // the next one slid up into the spot
        assertEquals(1, WifiOrderStore.focusAfterRemove(2, 2));   // was last: the one before
        assertEquals(-1, WifiOrderStore.focusAfterRemove(0, 0));  // order now empty
    }
}
