package io.github.leopierini.rehome;

/**
 * How long the Home's rows take to scroll (Task 7, "fulminea"). The system's animation scale (0.25 on the Philips)
 * already shortens the focus zoom, the fades and the glow, but not scrolls: ScrollView and RecyclerView run them on
 * an OverScroller, which ignores it. So a move to the next row took ScrollView's 250 ms, and Right at a row's edge
 * RecyclerView's (1 + distance / width) x 300 ms, about 370 ms for one tile: the slowest motion left on Home
 * (measured movement p90 ~285 ms against ~40 ms for the focus itself). Same curves, about half the time.
 */
final class RowScroll {

    private RowScroll() {}

    /** The vertical move to the next row (ScrollView.smoothScrollTo's 250 ms before). */
    static final int VERTICAL_MS = 160;
    static final int MIN_MS = 120, MAX_MS = 300;

    /** A row's horizontal scroll by dx pixels in a row width px wide: RecyclerView's rule at half, within limits. */
    static int horizontalMs(int dx, int width) {
        if (width <= 0) return MIN_MS;
        float rv = (Math.abs(dx) / (float) width + 1f) * 300f;   // RecyclerView.ViewFlinger.computeScrollDuration
        return Math.max(MIN_MS, Math.min(MAX_MS, Math.round(rv / 2f)));
    }

    /**
     * Whether a scrollTo(x, y) stops the row move under way (RowsScrollView): a jump elsewhere (HOME, a rebuild) does;
     * the move's own steps do not, and neither does ScrollView.onLayout's scrollTo to where the rows already are, which
     * runs at every layout (the first take of Task 7 stopped the move there: caught on screen).
     */
    static boolean jumpStopsMove(boolean stepping, int x, int y, int currentX, int currentY) {
        return !stepping && (x != currentX || y != currentY);
    }

    /** Whether ScrollView's own scroller, moving the rows from yBefore to yAfter in a frame, ends the row move. */
    static boolean otherScrollStopsMove(int yBefore, int yAfter, boolean rowMoving) {
        return rowMoving && yBefore != yAfter;
    }
}
