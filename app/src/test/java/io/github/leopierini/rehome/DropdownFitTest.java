package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The Wi-Fi dropdown's height on screen: the networks scroll, the footer stays. */
public class DropdownFitTest {

    // The owner's TV in dp: 540 tall, rows of 42, the list's inset of 6 above and below.
    private static final int SCREEN = 540, ROW = 42, INSET = 6;
    /** "More Wi-Fi settings" and the data line, each under a separator (6 + 1 + 6). */
    private static final int FOOTER = 13 + ROW + 13 + ROW;
    private static final int TOP = 84;

    private static int content(int rows) {
        return INSET + rows * ROW + INSET;
    }

    @Test
    public void roomEndsAtTheSafeBottomMarginAboveTheFooter() {
        assertEquals(540 - 48 - 84 - 110, DropdownFit.room(SCREEN, TOP, 48, FOOTER));
    }

    @Test
    public void roomIsNeverNegative() {
        assertEquals(0, DropdownFit.room(SCREEN, 500, 48, FOOTER));
    }

    @Test
    public void aFewNetworksKeepTheirHeight() {
        // Nothing changes when everything fits: no gap, no scroll.
        int room = DropdownFit.room(SCREEN, TOP, 48, FOOTER);
        assertEquals(content(3), DropdownFit.listHeight(room, content(3), INSET, ROW));
        assertEquals(content(6), DropdownFit.listHeight(room, content(6), INSET, ROW));
    }

    @Test
    public void exactlyFullFits() {
        assertEquals(content(5), DropdownFit.listHeight(content(5), content(5), INSET, ROW));
    }

    @Test
    public void eightNetworksScrollAndTheFooterStaysOnScreen() {
        // The defect: 8 networks under the pill ran the data line past the bottom edge.
        int room = DropdownFit.room(SCREEN, TOP, 48, FOOTER);
        int h = DropdownFit.listHeight(room, content(8), INSET, ROW);
        assertEquals(INSET + 6 * ROW + ROW / 2, h);   // six whole rows and half of the seventh
        assertEquals(true, TOP + h + FOOTER <= SCREEN - 48);
    }

    @Test
    public void overflowShowsWholeRowsAndHalfARowUnderTheFade() {
        // Room for 6.9 rows: six whole rows and half a row peeking, not 0.9 of one.
        int room = INSET + 6 * ROW + 38;
        assertEquals(INSET + 6 * ROW + ROW / 2, DropdownFit.listHeight(room, content(20), INSET, ROW));
        // Room for 6.4 rows: the sixth row peeks.
        room = INSET + 6 * ROW + 17;
        assertEquals(INSET + 5 * ROW + ROW / 2, DropdownFit.listHeight(room, content(20), INSET, ROW));
    }

    @Test
    public void atLeastOneRowWhateverTheRoom() {
        assertEquals(INSET + ROW + ROW / 2, DropdownFit.listHeight(0, content(8), INSET, ROW));
        assertEquals(INSET + ROW + ROW / 2, DropdownFit.listHeight(-50, content(8), INSET, ROW));
    }

    @Test
    public void neverTallerThanTheContent() {
        // One row and a half asked of a list of one row and a bit: the content's own height.
        assertEquals(content(1) + 3, DropdownFit.listHeight(10, content(1) + 3, INSET, ROW));
    }

    @Test
    public void noRowHeightMeansNoCap() {
        assertEquals(content(8), DropdownFit.listHeight(100, content(8), INSET, 0));
    }

    // Up from the footer: rows of 42 from y = 6, a viewport of 6 + 6.5 rows.

    private static final int[] TOPS = new int[8], BOTTOMS = new int[8];
    static {
        for (int i = 0; i < 8; i++) {
            TOPS[i] = INSET + i * ROW;
            BOTTOMS[i] = TOPS[i] + ROW;
        }
    }

    private static final int VIEW = INSET + 6 * ROW + ROW / 2;

    @Test
    public void upFromTheFooterGoesBackToTheRowItCameFrom() {
        // Down from the last network scrolled to the end; Up goes back there.
        int end = content(8) - VIEW;
        assertEquals(7, DropdownFit.upTarget(7, TOPS, BOTTOMS, end, VIEW));
    }

    @Test
    public void upFromTheFooterTakesTheRememberedRowWhenInView() {
        assertEquals(3, DropdownFit.upTarget(3, TOPS, BOTTOMS, 0, VIEW));
    }

    @Test
    public void upFromTheFooterTakesTheLastWholeRowWhenTheRememberedOneIsOutOfView() {
        // Scrolled to the top (the data line focused on open, say): row 6 only peeks, row 5 is the last whole one.
        assertEquals(5, DropdownFit.upTarget(-1, TOPS, BOTTOMS, 0, VIEW));
        assertEquals(5, DropdownFit.upTarget(7, TOPS, BOTTOMS, 0, VIEW));
        // Remembered row half out of view at the top.
        int end = content(8) - VIEW;
        assertEquals(7, DropdownFit.upTarget(1, TOPS, BOTTOMS, end, VIEW));
    }

    @Test
    public void upFromTheFooterWithoutNetworks() {
        assertEquals(-1, DropdownFit.upTarget(-1, new int[0], new int[0], 0, VIEW));
    }

    @Test
    public void upFromTheFooterInATinyViewportTakesAPartlyVisibleRow() {
        // No whole row in view: the last one that shows at all.
        assertEquals(1, DropdownFit.upTarget(-1, TOPS, BOTTOMS, 20, 40));
    }

    @Test
    public void theFocusedRowIsRevealedWithItsGlow() {
        assertEquals(42, DropdownFit.revealTop(48, 6));
        assertEquals(96, DropdownFit.revealBottom(90, 6, 400));
        // At the ends: down to the content's own edge, so the first and last rows scroll the list right to its end.
        assertEquals(0, DropdownFit.revealTop(6, 6));
        assertEquals(0, DropdownFit.revealTop(3, 6));
        assertEquals(348, DropdownFit.revealBottom(342, 6, 348));
        assertEquals(348, DropdownFit.revealBottom(345, 6, 348));
    }

    @Test
    public void whateverTheNetworksTheListAndFooterStayAboveTheMarginAndShowAWholeFocusedRow() {
        // TV capture (defect 6): the list's own box must end above the footer, so nothing scrolled in it reaches the
        // footer once the list clips itself (ClipScrollView). From 1 to 20 networks, under pills at several heights.
        for (int top : new int[]{60, 84, 120}) {
            int room = DropdownFit.room(SCREEN, top, 48, FOOTER);
            for (int n = 1; n <= 20; n++) {
                int h = DropdownFit.listHeight(room, content(n), INSET, ROW);
                org.junit.Assert.assertTrue(n + " at " + top, top + h + FOOTER <= SCREEN - 48);
                org.junit.Assert.assertTrue(h <= content(n));
                // At least one whole row and its glow (6) fit in the box.
                org.junit.Assert.assertTrue(h >= INSET + ROW + 6);
            }
        }
    }
}
