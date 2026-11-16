package io.github.leopierini.rehome;

import android.content.Context;
import android.graphics.Rect;
import android.widget.OverScroller;
import android.widget.ScrollView;

/**
 * The Home's rows (CategoryHome): a ScrollView that focus never scrolls by itself, and whose move to the focused row
 * takes {@link RowScroll#VERTICAL_MS} instead of ScrollView's fixed 250 ms (Task 7). ScrollView does not let its
 * own scroller's duration be chosen, so the move runs on this one, with the same default (viscous) curve.
 */
class RowsScrollView extends ScrollView {
    private final OverScroller rowScroller;
    /** True while computeScroll moves it, so that only other scrollTo calls stop the move. */
    private boolean stepping;

    RowsScrollView(Context context) {
        super(context);
        rowScroller = new OverScroller(context);
    }

    /** Focus never scrolls the rows by itself: CategoryHome.onTileFocused puts the focused row at the top, smoothly. */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }

    /** A smooth move to {@code y}, from wherever the rows are now (also in the middle of another move). */
    void smoothScrollToRow(int y) {
        rowScroller.forceFinished(true);
        // ScrollView's own scroller (its arrow/page keys, a fling) stops, or it would pull the rows its way on every
        // frame and jump them when it ends (speed review M2). smoothScrollBy has no "stop", but a second call within
        // ScrollView's 250 ms gap aborts whatever the first left running and moves by 0.
        smoothScrollBy(0, 0);
        smoothScrollBy(0, 0);
        int from = getScrollY();
        if (from == y) return;
        rowScroller.startScroll(0, from, 0, y - from, RowScroll.VERTICAL_MS);
        postInvalidateOnAnimation();
    }

    @Override
    public void scrollTo(int x, int y) {
        // A jump elsewhere (HOME, a rebuild) wins over a move under way. ScrollView's own onLayout calls this with the
        // present position at every layout: that must not stop the move.
        if (RowScroll.jumpStopsMove(stepping, x, y, getScrollX(), getScrollY())) rowScroller.forceFinished(true);
        super.scrollTo(x, y);
    }

    @Override
    public void computeScroll() {
        int before = getScrollY();
        super.computeScroll();
        // ScrollView's scroller moved the rows (it bypasses scrollTo above): it was started after this move, which
        // stopped it when it began, so the later one wins and this move ends here rather than fight it frame by frame.
        if (RowScroll.otherScrollStopsMove(before, getScrollY(), !rowScroller.isFinished())) {
            rowScroller.forceFinished(true);
        }
        if (rowScroller.computeScrollOffset()) {
            stepping = true;
            try {
                scrollTo(getScrollX(), rowScroller.getCurrY());
            } finally {
                stepping = false;
            }
            if (!rowScroller.isFinished()) postInvalidateOnAnimation();
        }
    }
}
