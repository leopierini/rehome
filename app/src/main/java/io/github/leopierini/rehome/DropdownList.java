package io.github.leopierini.rehome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;

/**
 * The Wi-Fi dropdown's list of networks (WifiPopup): a {@link ClipScrollView} no taller than {@link #setMaxHeight}
 * allows, with a fading edge where rows go on beyond it. ScrollView's own D-pad handling moves the focus row by row
 * and scrolls the focused row into view; ClipScrollView brings in its glow too (FocusGlow's 6 dp spread, which the
 * row background input_row_bg draws outside the row), clear of the fading edge, so the focus is never cut or faded.
 *
 * <p>Laid out with -inset margins and its content padded by the same inset (WifiPopup), so the rows stand where
 * they stood in the panel without a scroll, and the glow spills into the content's padding. The panel above it is
 * unclipped (popup_inputs) for the glows: this view clips itself at its own bounds, so a row scrolled below the list
 * is never drawn over the footer ("More Wi-Fi settings", the data line) under it (TV capture, defect 6).
 */
@SuppressLint("ViewConstructor")   // built in code only (WifiPopup), never inflated from XML
final class DropdownList extends ClipScrollView {

    private int maxHeight;

    DropdownList(Context context, int glow, int fade) {
        super(context);
        setGlow(glow);
        // Only its rows take the focus: a focusable ScrollView would take it when a refill removes the focused row.
        setFocusable(false);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(fade);
    }

    /** At most this tall; 0 for no limit (the rows fit). */
    void setMaxHeight(int px) {
        if (px == maxHeight) return;
        maxHeight = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (maxHeight > 0) {
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int size = mode == MeasureSpec.UNSPECIFIED ? maxHeight : Math.min(maxHeight, MeasureSpec.getSize(heightMeasureSpec));
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
