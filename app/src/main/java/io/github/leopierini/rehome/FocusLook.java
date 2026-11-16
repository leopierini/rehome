package io.github.leopierini.rehome;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * One focus language for the whole Home: the tile's ring (3dp white with a 2dp dark line inside it, which is what
 * shows focus on a near-white banner) plus a light glow around it (FocusGlow), on a glass surface. Tiles, the
 * weather, the top-bar pills, the dropdown rows, the tile menu and the launcher's settings all wear it. The XML
 * selectors (pill_bg, pill_debug_bg, input_row_bg, settings_choice_bg) spell the ring and glass out; their glow is
 * {@code @color/focus_glow} (#47FFFFFF), softer than {@link #GLOW} here (the weather's). This class is for views
 * built in code.
 */
final class FocusLook {

    private FocusLook() {}

    static final int RING = 0xFFFFFFFF, RING_DARK = 0xFF0B0D10;
    static final float RING_DP = 3, RING_DARK_DP = 2;
    /**
     * The glow's colour for elements with no artwork (pills, weather, rows: a soft white), its alpha the strength at
     * the shape's edge before the blur halves it; the spread is sized for each kind of element. Tiles take their
     * banner's colour instead ({@link #tileGlow}), as the Play Store's focused cards do on this TV.
     */
    static final int GLOW = 0x80FFFFFF;
    /**
     * A tile glow reaches 32dp (64 px at 1080p) and falls off as (1 - d/spread)^1.5 from full at the tile's edge,
     * the Play Store's measured profile (FocusGlowTest). It reached 55dp, as far as the Play Store's (90-110 px),
     * until the owner found it too strong and approved the lighter one in the wireframe.
     */
    static final float TILE_GLOW_DP = 32, HEADER_GLOW_DP = 10;   // dropdown rows: 6dp, in input_row_bg
    static final float TILE_GLOW_POWER = 1.5f;
    /**
     * A tile glow's strength at the edge (alpha of its colour) and the brightness range its colour is brought into.
     * 0.45, a little above the Play Store's (about 0.35 on its near-black page), as the Home's glow lies over a photo.
     * It was 0.8 after a first "troppo leggero", then "troppo forte": the owner
     * approved this value in the wireframe; fine-tune on the TV, never back above it.
     * A near-white banner tops out lower (TILE_GLOW_WHITE_MAX): Netflix and YouTube get a soft grey-white halo, not a
     * white blaze around the tile.
     */
    static final int TILE_GLOW_ALPHA = 0x73, TILE_GLOW_MIN = 150, TILE_GLOW_MAX = 230, TILE_GLOW_WHITE_MAX = 170;
    /** The glass behind a focused element drawn over the photo (weather, header items): dark, so text stays sharp. */
    static final int GLASS_DARK = 0x59000000;

    /**
     * A tile's glow from its banner's average colour (RGB): the same hue, brought to a steady brightness, so a dark
     * banner still glows (grey, as the Play Store's DAZN card) and a bright one is not a white blaze. The less
     * colour a banner has, the lower its top brightness: a saturated one up to TILE_GLOW_MAX, a white one
     * TILE_GLOW_WHITE_MAX.
     */
    static int tileGlow(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int m = Math.max(r, Math.max(g, b));
        if (m == 0) {
            r = g = b = m = 1;
        }
        float sat = (m - Math.min(r, Math.min(g, b))) / (float) m;
        int top = Math.round(TILE_GLOW_WHITE_MAX + (TILE_GLOW_MAX - TILE_GLOW_WHITE_MAX) * Math.min(1f, sat / 0.5f));
        int target = Math.max(TILE_GLOW_MIN, Math.min(top, m));
        float k = target / (float) m;
        r = Math.min(255, Math.round(r * k));
        g = Math.min(255, Math.round(g * k));
        b = Math.min(255, Math.round(b * k));
        return (TILE_GLOW_ALPHA << 24) | (r << 16) | (g << 8) | b;
    }

    /** The average colour (RGB) of a drawable, by drawing it once into a tiny bitmap; mid grey when it cannot. */
    static int averageColor(Drawable d) {
        if (d == null) return 0x808080;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(16, 9, android.graphics.Bitmap.Config.ARGB_8888);
        Rect was = d.copyBounds();
        try {
            d.setBounds(0, 0, 16, 9);
            d.draw(new Canvas(bmp));
        } catch (RuntimeException e) {
            return 0x808080;
        } finally {
            d.setBounds(was);
        }
        long r = 0, g = 0, b = 0, w = 0;
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int p = bmp.getPixel(x, y), a = p >>> 24;
                r += (long) ((p >> 16) & 0xFF) * a;
                g += (long) ((p >> 8) & 0xFF) * a;
                b += (long) (p & 0xFF) * a;
                w += a;
            }
        }
        bmp.recycle();
        if (w == 0) return 0x808080;
        return (int) (r / w) << 16 | (int) (g / w) << 8 | (int) (b / w);
    }

    /** The ring alone, inside the bounds: the white line, then the dark one inside it. */
    static Drawable ring(Resources res, float cornerDp) {
        float d = res.getDisplayMetrics().density;
        int outer = Math.round(RING_DP * d), inner = Math.round(RING_DARK_DP * d);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(Math.round(cornerDp * d));
        ring.setStroke(outer, RING);
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(Math.max(0, Math.round(cornerDp * d) - outer));
        edge.setStroke(inner, RING_DARK);
        LayerDrawable both = new LayerDrawable(new Drawable[]{ring, edge});
        both.setLayerInset(1, outer, outer, outer, outer);
        return both;
    }

    /** The focused look: glow outside, glass fill, ring. The glow draws past the bounds (see {@link #unclip}). */
    static Drawable focused(Resources res, float cornerDp, float glowDp, int glass) {
        float d = res.getDisplayMetrics().density;
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(Math.round(cornerDp * d));
        fill.setColor(glass);
        return new LayerDrawable(new Drawable[]{
                new FocusGlow(cornerDp * d, glowDp * d, GLOW), fill, ring(res, cornerDp)});
    }

    /** A background that shows {@code rest} until focused, then the focused look. */
    static StateListDrawable background(Resources res, float cornerDp, float glowDp, int glass, Drawable rest) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused(res, cornerDp, glowDp, glass));
        bg.addState(new int[]{}, rest != null ? rest : new GradientDrawable());
        return bg;
    }

    /** The parents of {@code v}, up to and with {@code root}, draw it past their bounds: a glow is not cut. */
    static void unclip(View v, View root) {
        for (android.view.ViewParent p = v.getParent(); p instanceof ViewGroup; p = p.getParent()) {
            ((ViewGroup) p).setClipChildren(false);
            ((ViewGroup) p).setClipToPadding(false);
            if (p == root) break;
        }
    }

    /** Every group under {@code root} draws its children past its bounds (a settings screen of glowing rows). */
    static void unclipTree(View root) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) root;
        g.setClipChildren(false);
        g.setClipToPadding(false);
        for (int i = 0; i < g.getChildCount(); i++) unclipTree(g.getChildAt(i));
    }

    /**
     * The glows of the focused tiles, drawn as the background of the view that holds the rows, so the band's edge
     * and the rows' own clipping never cut them, and the neighbouring tiles cover them. At most two at a time (the
     * one fading out, the one fading in); each is placed where its tile is drawn now.
     */
    static final class Layer extends Drawable {
        private final List<FocusGlow> glows = new ArrayList<>();
        /**
         * Android 10+: each glow in its own small node, so a moving or fading glow repaints only its rectangle and not
         * every row under the holder (GlowNodes says why). Null below Android 10, or before the first hardware draw.
         */
        private GlowNodes nodes;

        void clear() {
            if (glows.isEmpty()) return;
            glows.clear();
            if (live()) nodes.clear();
            else invalidateSelf();
        }

        /** Called before frames (CategoryHome.placeGlows): copies by index, no iterator or array per call. */
        void set(List<FocusGlow> now) {
            glows.clear();
            for (int i = 0; i < now.size(); i++) glows.add(now.get(i));
            // Drawn through nodes the holder already records: only the changed glows' nodes are recorded again.
            if (live() && nodes.fits(glows.size())) nodes.update(glows);
            else invalidateSelf();
        }

        /**
         * Whether the glows are drawn through their own nodes: then a glow's change reaches the screen with any frame
         * (CategoryHome only has to ask for one with a small view's invalidate), and the holder is not recorded again.
         */
        boolean live() {
            return nodes != null && nodes.live();
        }

        /** Whether it draws exactly these glows, in this order. */
        boolean holds(List<FocusGlow> now) {
            if (now.size() != glows.size()) return false;
            for (int i = 0; i < now.size(); i++) if (now.get(i) != glows.get(i)) return false;
            return true;
        }

        @Override
        public void draw(Canvas canvas) {
            if (android.os.Build.VERSION.SDK_INT >= 29 && canvas instanceof android.graphics.RecordingCanvas) {
                if (nodes == null) nodes = new GlowNodes();
                nodes.drawInto((android.graphics.RecordingCanvas) canvas, glows);
                return;
            }
            for (int i = 0; i < glows.size(); i++) glows.get(i).draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public boolean getPadding(Rect padding) {
            padding.set(0, 0, 0, 0);
            return false;
        }
    }
}
