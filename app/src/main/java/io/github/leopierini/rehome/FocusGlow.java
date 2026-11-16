package io.github.leopierini.rehome;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The soft light around a focused element (FocusLook), taken from the Play Store's focused card on this TV: a halo
 * that follows the element's rounded corners and fades out over {@code spread}, drawn only outside the shape, so it
 * never shows through a translucent glass fill or over a tile's banner. It draws past its own bounds by
 * {@code spread} on every side; the views around it must not clip it (clipChildren false).
 *
 * No blur runs while drawing: the halo is rendered once per size, as an alpha mask (one byte a pixel) shared by
 * every glow of that size, and drawn in the glow's colour (a tile's comes from its banner, as the Play Store's cards
 * do), so a glow costs one bitmap draw.
 *
 * Usable from XML (API 24+ inflates an app's own Drawable class by name), with the FocusGlow attributes:
 * <pre>{@code <io.github.leopierini.rehome.FocusGlow app:glowCorner="21dp" app:glowSpread="8dp" app:glowColor="#59FFFFFF"/>}</pre>
 */
public final class FocusGlow extends Drawable {

    /**
     * The masks made so far: the tiles' size is pinned while a Home shows them (CategoryHome), the others (pills,
     * menu and settings rows) are kept a dozen deep, the least recently used dropped first.
     */
    private static final Cache<Bitmap> MASKS = new Cache<>(12);

    private float corner, spread;
    /** 0: a blur (header items); above: the tiles' ramp, (1 - d/spread)^power (see {@link #setFalloff}). */
    private float power;
    private int color;
    private int alpha = 255;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private int maskW, maskH;
    /**
     * The mask this glow draws and the key of the size it is for. Looked up only when the size changes, never per
     * frame, and held here: the cache dropping it cannot make a glow on screen build it again in draw().
     */
    private Bitmap held;
    private long heldKey = -1;
    /** Bumped by every change to what draw() paints (GlowNodes records a glow's node again only when it moved). */
    private int version = 1;

    /** For XML inflation; the attributes set the look. */
    public FocusGlow() {
        this(0, 0, 0);
    }

    FocusGlow(float cornerPx, float spreadPx, int color) {
        this.corner = cornerPx;
        this.spread = spreadPx;
        this.color = color;
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Resources.Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);
        TypedArray a = theme != null ? theme.obtainStyledAttributes(attrs, R.styleable.FocusGlow, 0, 0)
                : r.obtainAttributes(attrs, R.styleable.FocusGlow);
        try {
            corner = a.getDimension(R.styleable.FocusGlow_glowCorner, corner);
            spread = a.getDimension(R.styleable.FocusGlow_glowSpread, spread);
            color = a.getColor(R.styleable.FocusGlow_glowColor, color);
        } finally {
            a.recycle();
        }
    }

    /** Its colour at the element's edge, alpha included (a tile's comes from its banner). */
    void setColor(int color) {
        if (this.color == color) return;
        this.color = color;
        version++;
        invalidateSelf();
    }

    /**
     * The Play Store's fall-off instead of a blur: full at the edge, (1 - d/spread)^power at d pixels out, nothing at
     * the spread. A blur is half strength at the edge and has faded long before its radius, so it never reached as
     * far, or showed as plainly, as the Play Store's halo.
     */
    void setFalloff(float power) {
        if (this.power == power) return;
        this.power = power;
        version++;
        invalidateSelf();
    }

    /** How far the halo reaches past the bounds, in pixels. */
    int spread() {
        return Math.round(spread);
    }

    /**
     * The size (without the halo) the mask is made for, when the bounds change size while it shows: a tile zooms
     * in as its glow fades in, and one mask for its zoomed size is stretched over every step instead of a new one
     * per frame. Zero: the bounds' own size.
     */
    void setMaskSize(int width, int height) {
        maskW = width;
        maskH = height;
        version++;
    }

    /** Changes with anything draw() depends on: bounds, alpha, colour, fall-off, mask size (GlowNodes). */
    int version() {
        return version;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        version++;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        int s = Math.round(spread);
        if (s <= 0 || alpha == 0 || (color >>> 24) == 0 || b.isEmpty()) return;
        int w = maskW > 0 ? maskW : b.width(), h = maskH > 0 ? maskH : b.height();
        Bitmap mask = held(w, h, s);
        // An alpha mask draws in the paint's colour: the glow's colour, its alpha times the fade's.
        paint.setColor(color);
        paint.setAlpha(Math.round((color >>> 24) * alpha / 255f));
        dst.set(b.left - s, b.top - s, b.right + s, b.bottom + s);
        canvas.drawBitmap(mask, null, dst, paint);
    }

    /** This glow's mask for a w x h shape: the one it holds, unless the size changed since. */
    private Bitmap held(int w, int h, int s) {
        int r = radius(w, h, corner);
        long key = key(w, h, r, s, power);
        if (key != heldKey || held == null) {
            held = mask(key, w, h, r, s, power);
            heldKey = key;
        }
        return held;
    }

    /**
     * Keeps this glow's mask (at its mask size, setMaskSize) in the cache until {@link #unpin}, and returns its key:
     * for a size that must be ready before it first shows. Cheap; {@link #prepare} makes the mask.
     */
    long pin() {
        long key = key(maskW, maskH, radius(maskW, maskH, corner), Math.round(spread), power);
        synchronized (FocusGlow.class) {
            MASKS.pin(key);
        }
        return key;
    }

    /** Makes this glow's mask (at its mask size) now, if it is not made yet: off the main thread, before focus. */
    void prepare() {
        int s = Math.round(spread);
        if (s > 0 && maskW > 0 && maskH > 0) held(maskW, maskH, s);
    }

    static synchronized void unpin(long key) {
        MASKS.unpin(key);
    }

    /** The radius of a w x h shape's corners: the glow's corner, at most half the short side. */
    static int radius(int w, int h, float corner) {
        return Math.round(Math.min(corner, Math.min(w, h) / 2f));
    }

    /**
     * One number for a mask's size and shape, so finding it allocates nothing: 14 bits each for w and h, 12 for r and
     * s, 10 for the power in hundredths (sizes on a 4K screen fit; never negative).
     */
    static long key(int w, int h, int r, int s, float power) {
        return ((long) (w & 0x3FFF) << 48) | ((long) (h & 0x3FFF) << 34) | ((long) (r & 0xFFF) << 22)
                | ((long) (s & 0xFFF) << 10) | (Math.round(power * 100) & 0x3FF);
    }

    /**
     * The halo around a w x h rounded rectangle, made once and cut out inside the shape: an alpha mask with
     * {@code s} pixels around the shape on every side, blurred in software or (power above 0) the ramp. A small
     * cache holds the few sizes on screen (a tile's, the pills', a menu's rows). Under the lock while it is made: a
     * glow that needs a mask being made off the main thread waits for it instead of making it twice.
     */
    private static synchronized Bitmap mask(long key, int w, int h, int r, int s, float power) {
        Bitmap bmp = MASKS.get(key);
        if (bmp != null) return bmp;
        bmp = Bitmap.createBitmap(w + 2 * s, h + 2 * s, Bitmap.Config.ALPHA_8);
        if (power > 0) {
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rampMask(w, h, r, s, power, bmp.getRowBytes())));
            MASKS.put(key, bmp);
            return bmp;
        }
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        // BlurMaskFilter's radius is about 1.7 sigma: the halo has faded to nothing at the spread.
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, s / 1.8f), BlurMaskFilter.Blur.NORMAL));
        RectF shape = new RectF(s, s, s + w, s + h);
        c.drawRoundRect(shape, r, r, p);
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawRoundRect(shape, r, r, hole);
        MASKS.put(key, bmp);
        return bmp;
    }

    /**
     * Lets go of the masks and the pins (Home trimmed under memory pressure); the next glow drawn makes its own again.
     * Nothing is recycled: a glow still showing keeps drawing the mask it holds.
     */
    static synchronized void clearMasks() {
        MASKS.clear();
    }

    /**
     * A small LRU of values by key, in plain Java (FocusGlowTest): at most {@code cap} unpinned values, the least
     * recently used dropped first; a pinned key (counted: pin twice, unpin twice) is never dropped. Not thread-safe:
     * FocusGlow holds its lock around it.
     */
    static final class Cache<V> {
        private final int cap;
        private final LinkedHashMap<Long, V> values = new LinkedHashMap<>(16, 0.75f, true);
        private final Map<Long, Integer> pins = new HashMap<>();

        Cache(int cap) {
            this.cap = cap;
        }

        V get(long key) {
            return values.get(key);
        }

        void put(long key, V value) {
            values.put(key, value);
            trim();
        }

        void pin(long key) {
            Integer n = pins.get(key);
            pins.put(key, n == null ? 1 : n + 1);
        }

        void unpin(long key) {
            Integer n = pins.get(key);
            if (n == null) return;
            if (n > 1) pins.put(key, n - 1);
            else pins.remove(key);
            trim();
        }

        boolean contains(long key) {
            return values.containsKey(key);
        }

        int size() {
            return values.size();
        }

        void clear() {
            values.clear();
            pins.clear();
        }

        private void trim() {
            int unpinned = 0;
            for (Long k : values.keySet()) if (!pins.containsKey(k)) unpinned++;
            for (Iterator<Long> it = values.keySet().iterator(); unpinned > cap && it.hasNext(); ) {
                if (!pins.containsKey(it.next())) {
                    it.remove();
                    unpinned--;
                }
            }
        }
    }

    /** The ramp at d pixels outside the shape: 1 at the edge, (1 - d/s)^power, 0 from s on. */
    static float ramp(float d, float s, float power) {
        if (d <= 0) return 1f;
        if (d >= s) return 0f;
        return (float) Math.pow(1 - d / s, power);
    }

    /**
     * The ramp's alpha mask around a w x h rectangle with corners of radius r, s pixels on every side, as rows of
     * {@code stride} bytes (a Bitmap's row may be padded): each pixel takes the ramp at its distance from the
     * rounded shape, so the halo follows the corners; inside the shape it is 0. One quarter is worked out and
     * mirrored, and the ramp comes from a table every 1/8 px, so a tile's mask takes a few milliseconds, once.
     */
    static byte[] rampMask(int w, int h, int r, int s, float power, int stride) {
        int bw = w + 2 * s, bh = h + 2 * s;
        byte[] out = new byte[stride * bh];
        byte[] table = new byte[s * 8 + 1];
        for (int i = 0; i < table.length; i++) table[i] = (byte) Math.round(255 * ramp(i / 8f, s, power));
        float cx = bw / 2f, cy = bh / 2f, hw = w / 2f - r, hh = h / 2f - r;
        for (int y = 0; y < (bh + 1) / 2; y++) {
            float dy = Math.max(0f, Math.abs(y + 0.5f - cy) - hh);
            for (int x = 0; x < (bw + 1) / 2; x++) {
                float dx = Math.max(0f, Math.abs(x + 0.5f - cx) - hw);
                float d = (float) Math.sqrt(dx * dx + dy * dy) - r;
                int v = 0;
                if (d > -0.5f) {
                    int i = Math.round(Math.max(0f, d) * 8);
                    v = i < table.length ? table[i] & 0xFF : 0;
                    // The pixel the edge runs through is partly inside: only its outside part glows.
                    if (d < 0.5f) v = Math.round(v * (d + 0.5f));
                }
                byte b = (byte) v;
                out[y * stride + x] = b;
                out[y * stride + bw - 1 - x] = b;
                out[(bh - 1 - y) * stride + x] = b;
                out[(bh - 1 - y) * stride + bw - 1 - x] = b;
            }
        }
        return out;
    }

    @Override
    public void setAlpha(int alpha) {
        if (this.alpha == alpha) return;
        this.alpha = alpha;
        version++;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        version++;
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /** Nothing to pad: the halo lies outside the bounds and must not push the view's content in. */
    @Override
    public boolean getPadding(Rect padding) {
        padding.set(0, 0, 0, 0);
        return false;
    }

    /** Each glow from XML is its own instance (its alpha is its own), built from the same three values. */
    @Override
    public ConstantState getConstantState() {
        final float c = corner, s = spread;
        final int col = color;
        return new ConstantState() {
            @Override
            public Drawable newDrawable() {
                return new FocusGlow(c, s, col);
            }

            @Override
            public int getChangingConfigurations() {
                return 0;
            }
        };
    }
}
