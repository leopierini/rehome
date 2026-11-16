package io.github.leopierini.rehome;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * Banners far bigger than a tile, kept at the tile's size (Task 7). Most TV banners are 320x180, but a few apps ship
 * theirs at 1280x720 or 1024x500 (Red Bull TV, Cast Browser, RaiPlay on the Philips): 2-3.6 MB each in the launcher's
 * memory and again as a GPU texture, for a tile drawn at 361x203. Those are scaled once to the zoomed tile's size;
 * on screen the GPU then draws it at about 1:1 instead of sampling a 1280-wide picture every 3.5 pixels.
 *
 * <p>The scale is one bilinear step for the banners this TV has (2.3-3.3x); halving steps (each a 2x2 average) come
 * first only for a banner 4x the tile or more. Halving these three too (speed review M1) was measured and left out: it
 * cost 12-15 ms of every Home cold start (speedfix A/B, 21 against 14 starts) for a tile no eye tells apart (RaiPlay
 * mean difference 1/255), the same sampling the GPU did on the full banner before Task 7.
 */
final class TileArt {

    private TileArt() {}

    /** A tile's design size in dp: 16:9 within a pixel at 180 wide. Home's rows scale it to their own width. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * Some apps declare a small square image as their banner (Rockchip's Media Center ships a 64 px one). Cropped
     * over a 16:9 tile it is a blur. A drawable with no intrinsic size (a colour, a shape) still fills the tile.
     */
    static boolean isBannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }

    /** Only banners at least this many times the zoomed tile's covering size are copied smaller. */
    static final float SHRINK_ABOVE = 1.5f;

    /**
     * The size to keep a w x h banner at for a tile of tw x th (zoom included): the smallest that still covers it
     * with the banner's own shape (CENTER_CROP crops it as before), or null to keep the banner as it is.
     */
    static int[] fitSize(int w, int h, int tw, int th) {
        if (w <= 0 || h <= 0 || tw <= 0 || th <= 0) return null;
        float cover = Math.max(tw / (float) w, th / (float) h);
        if (cover * SHRINK_ABOVE > 1f) return null;
        return new int[]{Math.max(tw, Math.round(w * cover)), Math.max(th, Math.round(h * cover))};
    }

    /**
     * How many halvings (each a 2x2 average) take w x h down while the result still covers tw x th. {@link #fit} asks
     * for twice the target, so its last bilinear step shrinks by 2-4x and only a banner 4x the tile is halved first.
     */
    static int halvings(int w, int h, int tw, int th) {
        if (tw <= 0 || th <= 0) return 0;
        int n = 0;
        while (w / 2 >= tw && h / 2 >= th) {
            w /= 2;
            h /= 2;
            n++;
        }
        return n;
    }

    /**
     * The banner, or a smaller copy of it when {@link #fitSize} says so. Any thread (CategoryHome makes its tiles on
     * several, ParallelOrdered).
     *
     * <p>Tried and left out (Task 7): copying every banner to a HARDWARE bitmap here. It saved the software copy
     * (about 5.5 MB) and 22 ms of Home's first frame, but the uploads run one at a time and the tiles took 35 ms longer
     * to make: the cold start came out 13 ms slower.
     */
    static Drawable fit(Resources res, Drawable banner, int tw, int th) {
        if (!(banner instanceof BitmapDrawable)) return banner;
        Bitmap src = ((BitmapDrawable) banner).getBitmap();
        if (src == null || src.getConfig() == Bitmap.Config.HARDWARE) return banner;
        int[] size = fitSize(src.getWidth(), src.getHeight(), tw, th);
        if (size == null) return banner;
        try {
            Bitmap b = src;
            // Halving steps only for a banner 4x the tile or more (the class comment says why not for less).
            for (int n = halvings(src.getWidth(), src.getHeight(), size[0] * 2, size[1] * 2); n > 0; n--) {
                Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
                if (b != src) b.recycle();
                b = half;
            }
            Bitmap out = Bitmap.createScaledBitmap(b, size[0], size[1], true);
            if (b != src && b != out) b.recycle();
            // The source stays as it is: it may be the app's Resources cache's copy, not ours to recycle.
            BitmapDrawable d = new BitmapDrawable(res, out);
            d.setTargetDensity(out.getDensity());
            return d;
        } catch (RuntimeException | OutOfMemoryError e) {
            return banner;
        }
    }
}
