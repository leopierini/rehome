package io.github.leopierini.rehome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.ScrollView;

/**
 * A ScrollView that never draws its content past its own edges, and scrolls a focused row into view with its glow.
 *
 * <p>A ScrollView leaves its clipping to its parent (the parent's clipChildren). The dropdowns and the settings pages
 * switch that off all the way up (popup_inputs, FocusLook.unclipTree) so a focused row's glow can spill past the row,
 * and then a list that scrolled drew its hidden rows over whatever lay under it: the Wi-Fi dropdown's footer, the
 * city picker's "Turn weather off", the bottom of the screen. This view clips itself, whatever its parents do. Lay it
 * out a glow's width into its parent's padding (negative margins) and pad its content by as much, so a row's glow at
 * the edge still fits inside the clip.
 *
 * <p>Scrolling is immediate, not animated: the focused row is never caught half out of view while the list catches
 * up, and a quick second Up or Down is never eaten by ScrollView finishing an animation (arrowScroll).
 */
public class ClipScrollView extends ScrollView {

    private int glow;
    private final Rect reveal = new Rect();

    public ClipScrollView(Context context) {
        super(context);
        setSmoothScrollingEnabled(false);
    }

    public ClipScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setSmoothScrollingEnabled(false);
    }

    /** How far a focused row's glow reaches past the row: brought into view with it (0: the row alone). */
    void setGlow(int px) {
        glow = Math.max(0, px);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // The canvas is in the content's coordinates here (scrolled): the visible window is the scroll offset on.
        int save = canvas.save();
        canvas.clipRect(getScrollX(), getScrollY(), getScrollX() + getWidth(), getScrollY() + getHeight());
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        if (getChildCount() == 0 || glow == 0) return super.computeScrollDeltaToGetChildRectOnScreen(rect);
        int contentH = getChildAt(0).getHeight();
        reveal.set(rect.left, DropdownFit.revealTop(rect.top, glow), rect.right,
                DropdownFit.revealBottom(rect.bottom, glow, contentH));
        return super.computeScrollDeltaToGetChildRectOnScreen(reveal);
    }
}
