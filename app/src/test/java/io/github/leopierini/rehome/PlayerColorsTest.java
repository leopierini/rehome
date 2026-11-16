package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The player card's tint for a banner (its strongest colour, not the plain average) and its blur. */
public class PlayerColorsTest {

    private static int[] fill(int n, int argb) {
        int[] px = new int[n];
        Arrays.fill(px, argb);
        return px;
    }

    private static int hueOf(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max == min) return -1;
        float h;
        if (max == r) h = (g - b) / (max - min);
        else if (max == g) h = 2 + (b - r) / (max - min);
        else h = 4 + (r - g) / (max - min);
        return Math.round(((h * 60) + 360) % 360);
    }

    @Test
    public void whiteBannerWithARedLogoIsRed() {
        // YouTube: mostly white, a red logo. The average is a pale pink; the tint must be the red.
        int[] px = fill(100, 0xFFF2F2F2);
        for (int k = 0; k < 12; k++) px[k] = 0xFFFF0000;
        int c = PlayerColors.mostSaturated(px);
        assertTrue(((c >> 16) & 0xFF) > 200);
        assertTrue(((c >> 8) & 0xFF) < 60);
    }

    @Test
    public void darkBannerWithAGreenLogoIsGreen() {
        // Spotify: black with a green logo and white letters.
        int[] px = fill(100, 0xFF000000);
        for (int k = 0; k < 8; k++) px[k] = 0xFF1DB954;
        for (int k = 8; k < 20; k++) px[k] = 0xFFFFFFFF;
        int c = PlayerColors.mostSaturated(px);
        int h = hueOf(c);
        assertTrue("hue " + h, h > 120 && h < 160);
    }

    @Test
    public void greyBannerFallsBackToTheAverage() {
        int[] px = fill(10, 0xFF808080);
        for (int k = 0; k < 5; k++) px[k] = 0xFF404040;
        assertEquals(0x606060, PlayerColors.mostSaturated(px));
    }

    @Test
    public void transparentPixelsDoNotCount() {
        int[] px = fill(10, 0x00FF0000);
        px[0] = 0xFF0000FF;
        assertEquals(0x0000FF, PlayerColors.mostSaturated(px));
        assertEquals(0x808080, PlayerColors.mostSaturated(new int[0]));
    }

    @Test
    public void oneStrayPixelDoesNotWin() {
        // Prime Video: a blue field; a single orange pixel of noise must not decide the tint.
        int[] px = fill(200, 0xFF00A8E1);
        px[0] = 0xFFFF9900;
        int h = hueOf(PlayerColors.mostSaturated(px));
        assertTrue("hue " + h, h > 180 && h < 220);
    }

    @Test
    public void blurSpreadsAndKeepsAFlatPictureFlat() {
        int w = 9, h = 9;
        int[] flat = fill(w * h, 0xFF336699);
        int[] out = PlayerColors.boxBlur(flat, w, h, 2);
        for (int p : out) assertEquals(0xFF336699, p);
        int[] dot = fill(w * h, 0xFF000000);
        dot[4 * w + 4] = 0xFFFFFFFF;
        out = PlayerColors.boxBlur(dot, w, h, 2);
        // The bright pixel is spread out: lower at the centre, non-zero around it, no hard edge left.
        int centre = out[4 * w + 4] & 0xFF, near = out[4 * w + 5] & 0xFF, far = out[0] & 0xFF;
        assertTrue(centre < 255 && centre > 0);
        assertTrue(near > 0);
        assertEquals(0, far);
        assertFalse(Arrays.equals(dot, out));
    }

    @Test
    public void aBannerFillsTheSlotAnIconStaysSmall() {
        // An item without a picture shows its app's banner over the whole 16:9 slot, as the tiles do;
        // an app with only a square icon keeps it small in the middle (cropped to 16:9 it would lose its edges).
        assertTrue(PlayerColors.fillsSlot(320, 180));
        assertTrue(PlayerColors.fillsSlot(1280, 720));
        assertFalse(PlayerColors.fillsSlot(192, 192));
        assertFalse(PlayerColors.fillsSlot(200, 160));
        assertFalse(PlayerColors.fillsSlot(0, 0));
        assertFalse(PlayerColors.fillsSlot(-1, -1));
    }

    private static int[] iconWith(int w, int h, int bg, int ink, int x0, int y0, int x1, int y1) {
        int[] px = fill(w * h, bg);
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) px[y * w + x] = ink;
        return px;
    }

    @Test
    public void aWordmarkIconIsToldApartFromALogo() {
        // Netflix's only icons (app, activity, logo) are "NETFLIX" on white: at 18dp, unreadable.
        assertTrue(PlayerColors.isWordmark(iconWith(64, 64, 0xFFFFFFFF, 0xFFE50914, 6, 26, 58, 38), 64, 64));
        // A round or square logo (Spotify, YouTube) is not, nor is a full-bleed picture or an empty icon.
        assertFalse(PlayerColors.isWordmark(iconWith(64, 64, 0x00000000, 0xFF1DB954, 8, 8, 56, 56), 64, 64));
        assertFalse(PlayerColors.isWordmark(iconWith(64, 64, 0xFFFFFFFF, 0xFFFF0000, 10, 18, 54, 46), 64, 64));
        assertFalse(PlayerColors.isWordmark(fill(64 * 64, 0xFF336699), 64, 64));
        assertFalse(PlayerColors.isWordmark(fill(64 * 64, 0x00000000), 64, 64));
        // Transparent around a wide wordmark: still one.
        assertTrue(PlayerColors.isWordmark(iconWith(64, 64, 0x00000000, 0xFFFFFFFF, 4, 28, 60, 37), 64, 64));
    }

    @Test
    public void aWordmarksInitialIsASquareAtItsLeftEnd() {
        // Netflix on this TV has only "NETFLIX" on white for an icon: its "N" (a square as tall as the letters, from
        // their left edge, with a margin) stands in for the logo on the app line (never omit it).
        int[] px = iconWith(64, 64, 0xFFFFFFFF, 0xFFE50914, 6, 26, 58, 38);
        int[] r = PlayerColors.wordmarkInitial(px, 64, 64);
        // Letters 12 px tall from x=6: side 12 + 2 * 3 margin = 18, centred on the letters' height.
        assertEquals(3, r[0]);
        assertEquals(23, r[1]);
        assertEquals(18, r[2]);
        assertEquals(null, PlayerColors.wordmarkInitial(iconWith(64, 64, 0x00000000, 0xFF1DB954, 8, 8, 56, 56), 64, 64));
        // Letters with gaps between them: the square is centred on the first one ("N" of "NETFLIX", not "NE").
        int[] word = iconWith(64, 64, 0xFFFFFFFF, 0xFFE50914, 10, 26, 18, 38);
        for (int x = 20; x < 58; x += 6) for (int y = 26; y < 38; y++) for (int k = 0; k < 4; k++) word[y * 64 + x + k] = 0xFFE50914;
        r = PlayerColors.wordmarkInitial(word, 64, 64);
        // First letter 10..17 (centre 14), side 18: from 5.
        assertEquals(5, r[0]);
        assertEquals(18, r[2]);
        // The letter's own columns: what lies beside it in the square (the "E") is painted over with the ground.
        assertEquals(10, r[3]);
        assertEquals(17, r[4]);
        // Kept inside the picture at its edges.
        r = PlayerColors.wordmarkInitial(iconWith(64, 64, 0xFFFFFFFF, 0xFF000000, 0, 26, 64, 38), 64, 64);
        assertEquals(0, r[0]);
    }
}
