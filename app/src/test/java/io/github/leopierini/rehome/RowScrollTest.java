package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** How long the rows' scrolls take (RowScroll): RecyclerView's own rule, at half its length, within limits. */
public class RowScrollTest {

    @Test
    public void oneTileAlongAFullRowIsAboutHalfRecyclerViewsTime() {
        // A row 1728 px wide on the Philips, one tile (361 + 16 gap) further: RecyclerView would take 365 ms.
        int ms = RowScroll.horizontalMs(377, 1728);
        assertEquals(183, ms);
    }

    @Test
    public void theDirectionDoesNotMatter() {
        assertEquals(RowScroll.horizontalMs(377, 1728), RowScroll.horizontalMs(-377, 1728));
    }

    @Test
    public void longerJumpsTakeLongerButNeverLong() {
        assertTrue(RowScroll.horizontalMs(1500, 1728) > RowScroll.horizontalMs(377, 1728));
        assertEquals(RowScroll.MAX_MS, RowScroll.horizontalMs(100_000, 1728));
    }

    @Test
    public void tinyNudgesTakeHalfOfRecyclerViewsShortest() {
        // RecyclerView's shortest scroll is 300 ms.
        assertEquals(150, RowScroll.horizontalMs(1, 1728));
    }

    @Test
    public void anUnmeasuredRowUsesTheShortestTime() {
        assertEquals(RowScroll.MIN_MS, RowScroll.horizontalMs(377, 0));
    }

    @Test
    public void layoutsScrollToWhereTheRowsAreDoesNotStopTheMove() {
        // ScrollView.onLayout calls scrollTo with the present position at every layout (Task 7's first take stopped
        // every move there).
        assertFalse(RowScroll.jumpStopsMove(false, 0, 420, 0, 420));
        // The move's own steps never stop it.
        assertFalse(RowScroll.jumpStopsMove(true, 0, 500, 0, 420));
    }

    @Test
    public void aJumpElsewhereStopsTheMove() {
        assertTrue(RowScroll.jumpStopsMove(false, 0, 0, 0, 420));      // HOME: back to the top
        assertTrue(RowScroll.jumpStopsMove(false, 0, 900, 0, 420));    // a rebuild's restore
    }

    @Test
    public void scrollViewsOwnScrollerMovingTheRowsEndsTheMove() {
        // Speed review M2: ScrollView's scroller started after the move (arrow or page key) wins, rather than fight it.
        assertTrue(RowScroll.otherScrollStopsMove(420, 460, true));
        assertFalse(RowScroll.otherScrollStopsMove(420, 420, true));   // it did nothing this frame
        assertFalse(RowScroll.otherScrollStopsMove(420, 460, false));  // no move to stop
    }
}
