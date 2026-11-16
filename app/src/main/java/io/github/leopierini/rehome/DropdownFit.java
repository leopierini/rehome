package io.github.leopierini.rehome;

/**
 * How tall the Wi-Fi dropdown's list of networks may be (WifiPopup, DropdownList). Pure, tested on the JVM.
 *
 * <p>The dropdown is the list (it scrolls) over a footer that never moves ("More Wi-Fi settings" and the data line).
 * With many networks in range the whole panel used to run past the bottom edge of the screen and take the data line
 * with it. Now the list gets the height left between the dropdown's top and a safe margin above the bottom edge,
 * less the footer; when its rows need more, it shows whole rows and half of the next one, which peeks under the
 * list's fading edge to say there is more below.
 */
final class DropdownFit {

    private DropdownFit() {}

    /** What the dropdown keeps clear above the bottom edge of the screen (the TV's overscan and a margin, in dp). */
    static final int BOTTOM_MARGIN_DP = 48;

    /** The list's fading edge, as long as the Home rows' (CategoryHome.fadeLen), in dp. */
    static final int FADE_DP = 14;

    /**
     * The height left for the list.
     *
     * @param screenH the screen's height
     * @param top the dropdown panel's top on screen
     * @param bottomMargin kept clear above the screen's bottom edge
     * @param footerH what the panel holds besides the list (the footer; the panel's padding is the list's own inset)
     */
    static int room(int screenH, int top, int bottomMargin, int footerH) {
        return Math.max(0, screenH - bottomMargin - top - footerH);
    }

    /**
     * The list's height: all of it when it fits in {@code room}; otherwise whole rows plus half of the next one (at
     * least one whole row, whatever the room), never taller than the content.
     *
     * @param contentH the list's content: its rows and an inset above and below
     * @param inset the content's top inset, above the first row
     * @param rowH one row's height; 0 or less leaves the content's height
     */
    static int listHeight(int room, int contentH, int inset, int rowH) {
        if (contentH <= room || rowH <= 0) return contentH;
        int rows = Math.max(1, Math.floorDiv(room - inset - rowH / 2, rowH));
        return Math.min(contentH, inset + rows * rowH + rowH / 2);
    }

    /**
     * Up from the footer's first stop: the list row to focus. The row the focus last left when it is wholly in view
     * (Down from the last network, then Up, goes back to it); else the last row wholly in view; else the last one
     * that shows at all; -1 with no row.
     *
     * @param remembered the row the focus was last on, -1 for none
     * @param tops the focusable rows' tops, in the list's content
     * @param bottoms their bottoms
     * @param scrollY how far the list is scrolled
     * @param viewH the list's height on screen
     */
    static int upTarget(int remembered, int[] tops, int[] bottoms, int scrollY, int viewH) {
        int end = scrollY + viewH;
        if (remembered >= 0 && remembered < tops.length && tops[remembered] >= scrollY && bottoms[remembered] <= end) {
            return remembered;
        }
        int partly = -1;
        for (int i = tops.length - 1; i >= 0; i--) {
            if (tops[i] >= scrollY && bottoms[i] <= end) return i;
            if (partly < 0 && bottoms[i] > scrollY && tops[i] < end) partly = i;
        }
        return partly;
    }

    /** The top of what the list scrolls into view for a focused row: the row and its glow, inside the content. */
    static int revealTop(int top, int glow) {
        return Math.max(0, top - glow);
    }

    /** The bottom of the same, inside a content {@code contentH} tall. */
    static int revealBottom(int bottom, int glow, int contentH) {
        return Math.min(contentH, bottom + glow);
    }
}
