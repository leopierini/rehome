package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for a tile glow's shape: the Play Store's fall-off, around a rounded rectangle, with a hole. */
public class FocusGlowTest {

    /**
     * The Play Store's focused Disney+ card on this TV, every 10 px up from the card's edge: the glow's green over
     * the page's #0E0E0F, divided by the value at the edge (47, 41, 35, 30, 24, 19, 14, 11, 7, 5, 3).
     */
    private static final float[] PLAY_STORE = {1f, .87f, .74f, .64f, .51f, .40f, .30f, .23f, .15f, .11f, .06f};

    @Test
    public void fallsOffLikeThePlayStore() {
        float s = 110, p = FocusLook.TILE_GLOW_POWER;
        for (int i = 0; i < PLAY_STORE.length; i++) {
            float ours = FocusGlow.ramp(i * 10, s, p);
            assertEquals("at " + i * 10 + " px", PLAY_STORE[i], ours, 0.05f);
        }
    }

    @Test
    public void fullAtTheEdgeNothingAtTheSpread() {
        assertEquals(1f, FocusGlow.ramp(0, 100, 1.5f), 1e-6f);
        assertEquals(0f, FocusGlow.ramp(100, 100, 1.5f), 1e-6f);
        assertEquals(0f, FocusGlow.ramp(150, 100, 1.5f), 1e-6f);
        float last = 2;
        for (int d = 0; d <= 100; d++) {
            float v = FocusGlow.ramp(d, 100, 1.5f);
            assertTrue("never rises at " + d, v <= last);
            last = v;
        }
    }

    private static int at(byte[] m, int stride, int x, int y) {
        return m[y * stride + x] & 0xFF;
    }

    @Test
    public void maskHasAHoleAndAFadingHalo() {
        int w = 60, h = 40, r = 8, s = 20, stride = w + 2 * s + 3;   // a padded row, as a Bitmap may have
        byte[] m = FocusGlow.rampMask(w, h, r, s, 1.5f, stride);
        assertEquals(stride * (h + 2 * s), m.length);
        // Inside the shape: nothing (the glow never shows through glass or over the banner).
        assertEquals(0, at(m, stride, s + w / 2, s + h / 2));
        assertEquals(0, at(m, stride, s + 2, s + h / 2));
        // Just outside the top edge: almost full; half way out: weaker; at the spread: nothing.
        int edge = at(m, stride, s + w / 2, s - 1), mid = at(m, stride, s + w / 2, s - 10), far = at(m, stride, s + w / 2, 0);
        assertTrue("edge " + edge, edge > 230);
        assertTrue("mid " + mid, mid < edge && mid > 60);
        assertTrue("far " + far, far < 8);
        // The padding past each row stays empty.
        assertEquals(0, at(m, stride, w + 2 * s + 1, s - 1));
    }

    @Test
    public void maskIsSymmetric() {
        int w = 50, h = 30, r = 6, s = 15, W = w + 2 * s, H = h + 2 * s;
        byte[] m = FocusGlow.rampMask(w, h, r, s, 1.5f, W);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = at(m, W, x, y);
                assertEquals(v, at(m, W, W - 1 - x, y));
                assertEquals(v, at(m, W, x, H - 1 - y));
            }
        }
    }

    @Test
    public void maskFollowsTheRoundedCorner() {
        int w = 80, h = 80, r = 20, s = 30, W = w + 2 * s;
        byte[] m = FocusGlow.rampMask(w, h, r, s, 1.5f, W);
        // The bounding box's corner pixel lies outside the rounded shape: it glows, it is not cut out.
        assertTrue(at(m, W, s, s) > 0);
        // Same distance from the shape along the diagonal as straight up from an edge: the same value.
        double off = 10;
        double cx = s + r - (r + off) / Math.sqrt(2), cy = cx;   // 10 px out from the corner's arc
        int diag = at(m, W, (int) Math.floor(cx), (int) Math.floor(cy));
        int up = at(m, W, s + w / 2, s - 11);
        assertEquals(up, diag, 12);
    }

    @Test
    public void flatPowerIsALineAndAStrongerOneFallsFaster() {
        assertEquals(0.5f, FocusGlow.ramp(50, 100, 1f), 1e-6f);
        assertTrue(FocusGlow.ramp(50, 100, 2f) < FocusGlow.ramp(50, 100, 1f));
    }

    // ---- the mask cache: a mask in use is never dropped, the others are kept a dozen deep ---------------------

    @Test
    public void cacheDropsTheLeastRecentlyUsedPastItsCap() {
        FocusGlow.Cache<String> c = new FocusGlow.Cache<>(3);
        c.put(1, "a");
        c.put(2, "b");
        c.put(3, "c");
        assertEquals("a", c.get(1));   // 1 is now the most recent: 2 goes first
        c.put(4, "d");
        assertEquals(3, c.size());
        assertNull(c.get(2));
        assertEquals("a", c.get(1));
        assertEquals("c", c.get(3));
        assertEquals("d", c.get(4));
    }

    @Test
    public void aPinnedMaskIsNeverDropped() {
        FocusGlow.Cache<String> c = new FocusGlow.Cache<>(2);
        c.pin(7);                       // pinned before it is made (CategoryHome pins, then the worker makes it)
        c.put(7, "tile");
        for (long k = 100; k < 120; k++) c.put(k, "pill " + k);   // settings, menus, pills: many sizes
        assertEquals("tile", c.get(7));
        assertEquals(3, c.size());      // the pinned one plus two others: still bounded
    }

    @Test
    public void pinsAreCountedAndUnpinningLetsItGo() {
        FocusGlow.Cache<String> c = new FocusGlow.Cache<>(1);
        c.pin(7);
        c.pin(7);                       // a recreated Home pins before the old one lets go
        c.put(7, "tile");
        c.put(8, "pill");
        c.unpin(7);
        c.put(9, "row");
        assertTrue(c.contains(7));      // still pinned once (contains: reading it would make it the most recent)
        assertFalse(c.contains(8));
        c.unpin(7);                     // now an ordinary entry: over the cap, the least recent goes at once
        assertEquals(1, c.size());
        assertFalse(c.contains(7));
        c.unpin(7);                     // unpinning again, or a key never pinned, changes nothing
        c.unpin(12345);
        assertEquals(1, c.size());
    }

    @Test
    public void clearDropsMasksAndPins() {
        FocusGlow.Cache<String> c = new FocusGlow.Cache<>(1);
        c.pin(7);
        c.put(7, "tile");
        c.clear();
        assertEquals(0, c.size());
        c.put(7, "tile");
        c.put(8, "pill");               // the old pin is gone: 7 can be dropped now
        assertFalse(c.contains(7));
    }

    @Test
    public void keysTellSizesShapesAndFallOffsApart() {
        long tile = FocusGlow.key(413, 232, 17, 110, 1.5f);
        assertEquals(tile, FocusGlow.key(413, 232, 17, 110, 1.5f));
        assertTrue(tile > 0);
        long[] others = {
                FocusGlow.key(414, 232, 17, 110, 1.5f), FocusGlow.key(413, 233, 17, 110, 1.5f),
                FocusGlow.key(413, 232, 18, 110, 1.5f), FocusGlow.key(413, 232, 17, 111, 1.5f),
                FocusGlow.key(413, 232, 17, 110, 0f), FocusGlow.key(232, 413, 17, 110, 1.5f),
                FocusGlow.key(3840, 2160, 2160, 4000, 10.23f)};
        java.util.Set<Long> seen = new java.util.HashSet<>();
        seen.add(tile);
        for (long k : others) {
            assertTrue("never negative", k >= 0);
            assertTrue("distinct " + k, seen.add(k));
        }
    }

    @Test
    public void cornerIsAtMostHalfTheShortSide() {
        assertEquals(17, FocusGlow.radius(413, 232, 17.12f));
        assertEquals(10, FocusGlow.radius(100, 20, 42f));
    }
}
