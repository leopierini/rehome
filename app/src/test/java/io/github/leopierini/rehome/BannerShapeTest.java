package io.github.leopierini.rehome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BannerShapeTest {
    @Test
    public void wideBannersAreKept() {
        assertTrue(TileArt.isBannerShaped(320, 180));
        assertTrue(TileArt.isBannerShaped(1920, 1080));
    }

    @Test
    public void squareAndTallPicturesAreNotBanners() {
        assertFalse(TileArt.isBannerShaped(64, 64));
        assertFalse(TileArt.isBannerShaped(256, 256));
        assertFalse(TileArt.isBannerShaped(100, 160));
    }

    @Test
    public void drawablesWithoutAnIntrinsicSizeStillFillTheTile() {
        assertTrue(TileArt.isBannerShaped(-1, -1));
        assertTrue(TileArt.isBannerShaped(0, 0));
    }
}
