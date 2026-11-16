package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for a tile glow's colour: the banner's hue at a steady brightness. */
public class FocusLookTest {

    private static int max(int argb) {
        return Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF));
    }

    /** The tile glow was too strong; the wireframe's lighter one was approved. */
    @Test
    public void tileGlowIsLighter() {
        assertEquals(0x73, FocusLook.tileGlow(0xE50914) >>> 24);
        assertEquals(32f, FocusLook.TILE_GLOW_DP, 0f);
    }

    @Test
    public void keepsTheHue() {
        int teal = FocusLook.tileGlow(0x0A7384);   // Disney+'s banner on this TV
        assertEquals(FocusLook.TILE_GLOW_MIN, max(teal));
        assertTrue(((teal >> 8) & 0xFF) > ((teal >> 16) & 0xFF));   // still more green than red
        assertEquals(FocusLook.TILE_GLOW_ALPHA, teal >>> 24);
    }

    @Test
    public void aDarkBannerStillGlowsGrey() {
        int black = FocusLook.tileGlow(0x000000);
        assertEquals(FocusLook.TILE_GLOW_MIN, max(black));
        assertEquals((black >> 16) & 0xFF, black & 0xFF);
    }

    @Test
    public void aWhiteBannerIsCappedLower() {
        // Netflix and YouTube: a soft grey-white halo, not a white blaze over the photo.
        int white = FocusLook.tileGlow(0xFFFFFF);
        assertEquals(FocusLook.TILE_GLOW_WHITE_MAX, max(white));
        int netflix = FocusLook.tileGlow(0xEBCDCF);   // white with red letters, averaged
        assertTrue(max(netflix) <= FocusLook.TILE_GLOW_WHITE_MAX + 20);
    }

    @Test
    public void aBrightSaturatedBannerKeepsItsBrightness() {
        int prime = FocusLook.tileGlow(0x1A97FF);   // Prime Video's blue
        assertEquals(FocusLook.TILE_GLOW_MAX, max(prime));
        assertEquals(FocusLook.TILE_GLOW_ALPHA, prime >>> 24);
    }

    @Test
    public void aMidColourIsKept() {
        int orange = FocusLook.tileGlow(0xC86414);
        assertEquals(0xC8, max(orange));
        assertEquals(0x64, (orange >> 8) & 0xFF);
    }
}
