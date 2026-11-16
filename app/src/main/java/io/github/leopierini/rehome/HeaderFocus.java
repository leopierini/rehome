package io.github.leopierini.rehome;

import java.util.List;

/**
 * Up from the first row of tiles: which header element is above this tile. Pure, over the header's focusable
 * elements as they sit on screen (left and right edges in window pixels), so a new header item joins by being in
 * the list, with no new rule.
 *
 * Two groups share the header: the block beside the clock (the weather, and whatever sits next to it) and the
 * top-bar pills at the right edge. From the first {@link #CLOCK_COLUMNS} columns Up goes to the clock group, from
 * the others to the pills, and in either group to the element nearest the tile horizontally. When the chosen group
 * has nothing to offer (no weather on this TV) the other group answers. Between two elements over the tile, the
 * lower one wins: an item on the line under the pills is nearer the rows than the pill above it.
 *
 * <p>{@link #beside} is Left and Right inside the header (the weather to the card or the first pill, pill to pill),
 * by the same measure Android's focus finder uses, so the header has one spatial rule instead of fixed jumps.
 */
final class HeaderFocus {

    private HeaderFocus() {}

    /** Tiles in these first on-screen columns look up to the block beside the clock. */
    static final int CLOCK_COLUMNS = 2;

    /** One focusable header element, as laid out now. */
    static final class Target {
        final float left, right, top, bottom;
        /** In the block beside the clock (weather, ...), not one of the top-bar pills or the card under them. */
        final boolean clockSide;

        Target(float left, float right, boolean clockSide) {
            this(left, right, 0, 0, clockSide);
        }

        Target(float left, float right, float top, float bottom, boolean clockSide) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
            this.clockSide = clockSide;
        }
    }

    /**
     * Index in {@code targets} of the element Up goes to from a tile in on-screen column {@code column} (0 = the
     * first whole tile) whose centre is at {@code x}; -1 when there is none.
     */
    static int pick(int column, float x, List<Target> targets) {
        int best = nearest(x, targets, column < CLOCK_COLUMNS);
        return best >= 0 ? best : nearest(x, targets, column >= CLOCK_COLUMNS);
    }

    /**
     * The element of one group nearest to x: 0 when x is over it, else the gap to its nearer edge. Ties go to the
     * lower one (nearer the rows), then left.
     */
    private static int nearest(float x, List<Target> targets, boolean clockSide) {
        int best = -1;
        float bestGap = Float.MAX_VALUE;
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t.clockSide != clockSide) continue;
            float gap = x < t.left ? t.left - x : x > t.right ? x - t.right : 0f;
            Target b = best >= 0 ? targets.get(best) : null;
            if (gap < bestGap || (gap == bestGap && b != null
                    && (t.top > b.top || (t.top == b.top && t.left < b.left)))) {
                best = i;
                bestGap = gap;
            }
        }
        return best;
    }

    /**
     * Left ({@code dir} -1) or Right (+1) from {@code from}: index in {@code targets} of the nearest element wholly on
     * that side, or -1. Distance as in Android's FocusFinder: the gap along the move counts 13 times more (squared)
     * than how far the centres sit apart across it, so the next element on the same line wins over a farther one,
     * and between the pills' line and the card's the nearer edge decides. {@code from} itself is skipped.
     */
    static int beside(Target from, int dir, List<Target> targets) {
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        float cy = (from.top + from.bottom) / 2f;
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            if (t == from) continue;
            float major = dir > 0 ? t.left - from.right : from.left - t.right;
            // Wholly on that side (a pixel of overlap is rounding).
            if (major < -1) continue;
            major = Math.max(0f, major);
            float minor = Math.abs((t.top + t.bottom) / 2f - cy);
            double score = 13.0 * major * major + (double) minor * minor;
            if (score < bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }
}
