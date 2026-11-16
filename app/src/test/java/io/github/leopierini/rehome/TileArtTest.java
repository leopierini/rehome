package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

/** Which banners are kept smaller (TileArt), and at what size: the zoomed tile still covered, the shape kept. */
public class TileArtTest {

    // The Philips at 1080p: a tile 361x203, zoomed to 386x217 when focused.
    private static final int W = 386, H = 217;

    @Test
    public void theUsualBannersAreLeftAlone() {
        assertNull(TileArt.fitSize(320, 180, W, H));   // xhdpi banners: already smaller than the tile
        assertNull(TileArt.fitSize(512, 288, W, H));   // Disney+: only 1.3x, not worth a second copy
        assertNull(TileArt.fitSize(579, 325, W, H));   // just under 1.5x
    }

    @Test
    public void aFullHdBannerIsCutToTheZoomedTile() {
        assertArrayEquals(new int[]{386, 217}, TileArt.fitSize(1280, 720, W, H));
        assertArrayEquals(new int[]{386, 217}, TileArt.fitSize(1920, 1080, W, H));
    }

    @Test
    public void aWiderBannerKeepsItsShapeAndStillCoversTheTile() {
        // RaiPlay's 1024x500: CENTER_CROP crops its sides as before.
        assertArrayEquals(new int[]{444, 217}, TileArt.fitSize(1024, 500, W, H));
    }

    @Test
    public void aTallerBannerKeepsItsShape() {
        assertArrayEquals(new int[]{386, 241}, TileArt.fitSize(1280, 800, W, H));
    }

    @Test
    public void nothingForEmptySizes() {
        assertNull(TileArt.fitSize(0, 720, W, H));
        assertNull(TileArt.fitSize(1280, 720, 0, H));
    }

    @Test
    public void halvingsStopWhileTheResultStillCovers() {
        assertEquals(1, TileArt.halvings(1280, 720, 386, 217));   // 640x360
        assertEquals(1, TileArt.halvings(1024, 500, 444, 217));   // 512x250
        assertEquals(2, TileArt.halvings(1920, 1080, 386, 217));  // 960x540, 480x270
        for (int[] c : new int[][]{{1280, 720}, {1024, 500}, {1920, 1080}, {3840, 2160}, {1280, 800}}) {
            int[] size = TileArt.fitSize(c[0], c[1], W, H);
            int n = TileArt.halvings(c[0], c[1], size[0], size[1]);
            assertTrue(c[0] + "x" + c[1] + " still covers", (c[0] >> n) >= size[0] && (c[1] >> n) >= size[1]);
            assertTrue(c[0] + "x" + c[1] + " as far as it goes", (c[0] >> n) < size[0] * 2 || (c[1] >> n) < size[1] * 2);
        }
    }

    @Test
    public void fitHalvesOnlyBannersFourTimesTheTile() {
        // As fit() asks (twice the target): this TV's three big banners go in one bilinear step. Halving them first was
        // measured at +12-15 ms of every cold start (speed review M1, left out on purpose).
        assertEquals(0, TileArt.halvings(1280, 720, 386 * 2, 217 * 2));   // Red Bull TV, Cast Browser: 3.3x
        assertEquals(0, TileArt.halvings(1024, 500, 444 * 2, 217 * 2));   // RaiPlay: 2.3x
        assertEquals(1, TileArt.halvings(1920, 1080, 386 * 2, 217 * 2));  // 5x: 960x540 first, then 2.5x
        assertEquals(2, TileArt.halvings(3840, 2160, 386 * 2, 217 * 2));
    }

    @Test
    public void noHalvingWhenAlreadyClose() {
        assertEquals(0, TileArt.halvings(579, 325, 386, 217));
        assertEquals(0, TileArt.halvings(1280, 720, 0, 217));
    }

    // The player's app line (CategoryHome.playerIcon): an icon shown at 16dp, 32 px on the Philips; the wordmark check
    // draws it at 160 px, so a bitmap icon is decoded at no less than twice that.
    @Test
    public void aHugeAppIconIsDecodedSmallerAndKeptAtTheSizeShown() {
        assertEquals(4, PosterCache.sampleSize(2048, 2048, 320, 320));   // 512x512: 1 MiB instead of 16
        assertEquals(1, PosterCache.sampleSize(512, 512, 320, 320));     // ordinary icons load as before
        assertEquals(1, PosterCache.sampleSize(432, 432, 320, 320));
        assertArrayEquals(new int[]{32, 32}, TileArt.fitSize(512, 512, 32, 32));
        assertArrayEquals(new int[]{32, 32}, TileArt.fitSize(160, 160, 32, 32));   // a wordmark's first letter
        assertNull(TileArt.fitSize(40, 40, 32, 32));                               // already about that size
        assertEquals(3, TileArt.halvings(512, 512, 64, 64));                       // 64x64, then one 2x2 step
    }
}
