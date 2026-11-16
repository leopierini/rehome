package io.github.leopierini.rehome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PosterCacheTest {

    @Test
    public void sampleSizeKeepsAtLeastTheTile() {
        assertEquals(1, PosterCache.sampleSize(360, 203, 361, 203));
        assertEquals(4, PosterCache.sampleSize(1920, 1080, 361, 203));
        assertEquals(2, PosterCache.sampleSize(800, 450, 361, 203));
        // A tall poster: limited by its width.
        assertEquals(1, PosterCache.sampleSize(500, 750, 361, 203));
        assertEquals(1, PosterCache.sampleSize(100, 100, 0, 0));
    }

    @Test
    public void aStripIsNeverDecodedAtFullSize() {
        // 4000 x 300: one short side used to keep the sample size at 1 (4.8 MB of pixels).
        int s = PosterCache.sampleSize(4000, 300, 361, 203);
        assertTrue((long) (4000 / s) * (300 / s) <= 8L * 361 * 203);
        // 999 999 x 300 (a 1.2 MB PNG): bounded too, though posterShaped refuses it before any decode.
        s = PosterCache.sampleSize(999_999, 300, 361, 203);
        assertTrue((long) (999_999 / s) * (300 / s) <= 8L * 361 * 203);
        // An ordinary poster is unchanged: at least the tile's size.
        assertEquals(4, PosterCache.sampleSize(1920, 1080, 361, 203));
    }

    @Test
    public void onlyPosterShapedImagesAreDecoded() {
        assertFalse(PosterCache.posterShaped(999_999, 300));
        assertFalse(PosterCache.posterShaped(300, 999_999));
        assertFalse(PosterCache.posterShaped(9000, 5000));
        assertFalse(PosterCache.posterShaped(4000, 300));
        assertFalse(PosterCache.posterShaped(-1, -1));   // not an image (SVG, text): bounds unknown
        assertTrue(PosterCache.posterShaped(320, 180));
        assertTrue(PosterCache.posterShaped(500, 750));
        assertTrue(PosterCache.posterShaped(1800, 300));   // 6:1, the widest kept
        // Re-review R3: at most a 4K frame of source pixels, whatever the sides (the decoder holds an interlaced PNG
        // or a progressive JPEG whole).
        assertFalse(PosterCache.posterShaped(8192, 4608));
        assertFalse(PosterCache.posterShaped(8192, 8192));
        assertTrue(PosterCache.posterShaped(3840, 2160));
        assertFalse(PosterCache.posterShaped(3841, 2160));
        assertTrue(PosterCache.posterShaped(2000, 3000));
    }

    @Test
    public void onlyADefiniteAnswerRefusesForGood() {
        assertTrue(PosterCache.refusedHttp(404));
        assertTrue(PosterCache.refusedHttp(403));
        assertTrue(PosterCache.refusedHttp(410));
        // Worth another try later: a server error, a timeout, too many requests, a captive portal's redirect.
        assertFalse(PosterCache.refusedHttp(500));
        assertFalse(PosterCache.refusedHttp(503));
        assertFalse(PosterCache.refusedHttp(408));
        assertFalse(PosterCache.refusedHttp(429));
        assertFalse(PosterCache.refusedHttp(302));
    }

    @Test
    public void passingFailuresRetryHourlyAtMostThreeTimes() {
        long now = 10_000_000L;
        assertTrue(PosterCache.retryNow(0, 0, now));
        // The hotspot dropped mid-download a minute ago: not yet.
        assertFalse(PosterCache.retryNow(1, now - 60_000, now));
        assertTrue(PosterCache.retryNow(1, now - PosterCache.RETRY_MS, now));
        // Back online (PosterCache.onOnline zeroes the time): at once.
        assertTrue(PosterCache.retryNow(2, 0, now));
        // A clock set back is no reason to wait.
        assertTrue(PosterCache.retryNow(1, now + 60_000, now));
        assertFalse(PosterCache.retryNow(PosterCache.MAX_TRIES, 0, now));
    }

    @Test
    public void refusedListKeepsTheNewest() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 70; i++) names.add("n" + i);
        List<String> kept = PosterCache.keepLast(names, 64);
        assertEquals(64, kept.size());
        assertEquals("n6", kept.get(0));
        assertEquals("n69", kept.get(63));
        assertEquals(Arrays.asList("a", "b"), PosterCache.keepLast(Arrays.asList("a", "b"), 64));
    }

    @Test
    public void downloadCapIsAMegabyte() {
        assertEquals(1024 * 1024, PosterCache.MAX_DOWNLOAD);
    }

    @Test
    public void centreCropWide() {
        // 2:1 into 16:9: the sides are cut.
        assertArrayEquals(new int[]{40, 0, 360, 180}, PosterCache.centreCrop(400, 180, 16, 9));
    }

    @Test
    public void centreCropTall() {
        // A 2:3 movie poster into 16:9: the middle band.
        assertArrayEquals(new int[]{0, 187, 400, 412}, PosterCache.centreCrop(400, 600, 16, 9));
    }

    @Test
    public void centreCropSameShape() {
        assertArrayEquals(new int[]{0, 0, 320, 180}, PosterCache.centreCrop(320, 180, 16, 9));
    }

    @Test
    public void evictOldestUntilUnderCap() {
        List<long[]> e = Arrays.asList(
                new long[]{100, 30, 0}, new long[]{100, 10, 1}, new long[]{100, 20, 2}, new long[]{100, 40, 3});
        assertEquals(Arrays.asList(1, 2), PosterCache.evict(e, 200));
        assertEquals(Collections.singletonList(1), PosterCache.evict(e, 350));
        assertTrue(PosterCache.evict(e, 400).isEmpty());
        assertTrue(PosterCache.evict(new ArrayList<>(), 0).isEmpty());
    }

    @Test
    public void stuckIsMeasuredOnTheMonotonicClock() {
        long ms = 1_000_000L;
        assertFalse(PosterCache.stuckFor(0, 20_000 * ms, 20_000));
        assertTrue(PosterCache.stuckFor(0, 20_001 * ms, 20_000));
        // nanoTime may be any number, negative too: only the difference counts.
        assertTrue(PosterCache.stuckFor(-5_000 * ms, 16_000 * ms, 20_000));
        assertFalse(PosterCache.stuckFor(Long.MAX_VALUE - 10 * ms, Long.MAX_VALUE, 20_000));
    }

    @Test
    public void aRefusalWithoutADefiniteAnswerEndsAfterADay() {
        long day = PosterCache.RETRY_REFUSED_MS;
        assertTrue(PosterCache.stillRefused(0, 5 * day));          // a 4xx, not an image: for good
        assertTrue(PosterCache.stillRefused(day, day + 1));        // the marker, a fetch given up: for a day
        assertTrue(PosterCache.stillRefused(day, 2 * day - 1));
        assertFalse(PosterCache.stillRefused(day, 2 * day));
        assertFalse(PosterCache.stillRefused(day, day - 1));       // the clock went back: no proof, try again
    }

    @Test
    public void theRefusedListKeepsTimesAndOldLines() {
        java.util.LinkedHashMap<String, Long> m = PosterCache.parseFailed(Arrays.asList(
                "aa.jpg", "", "bb.jpg 1700000000000", " cc.jpg  x ", "aa.jpg 5"));
        // An old line (name alone) is a refusal for good; a bad time too; a name given twice keeps its last line.
        assertEquals(Long.valueOf(1700000000000L), m.get("bb.jpg"));
        assertEquals(Long.valueOf(0), m.get("cc.jpg"));
        assertEquals(Long.valueOf(5), m.get("aa.jpg"));
        assertEquals(Arrays.asList("bb.jpg", "cc.jpg", "aa.jpg"), new java.util.ArrayList<>(m.keySet()));
        assertEquals(Arrays.asList("bb.jpg 1700000000000", "cc.jpg", "aa.jpg 5"), PosterCache.formatFailed(m));
        assertEquals(m, PosterCache.parseFailed(PosterCache.formatFailed(m)));
    }

    @Test
    public void aHungProviderIsKnownByItsAuthority() {
        assertEquals("com.example.provider", PosterCache.authority("content://com.example.provider/posters/1.jpg"));
        assertEquals("com.example.app", PosterCache.authority("android.resource://com.example.app/drawable/x"));
        assertEquals(null, PosterCache.authority("not a uri"));
    }
}
