package io.github.leopierini.rehome;

/**
 * The player card's colour work on plain pixel arrays (ARGB ints), pure and tested on the JVM (PlayerColorsTest).
 * The host runs it off the main thread.
 */
final class PlayerColors {

    private PlayerColors() {}

    /**
     * Whether an app's picture, for an item without one of its own, fills the 16:9 slot (CENTER_CROP, as the tiles show
     * banners): a banner does, wider than 4:3; a square icon stays small in the middle.
     */
    static boolean fillsSlot(int w, int h) {
        return w > 0 && h > 0 && w * 3 > h * 4;
    }

    private static final int BUCKETS = 12;
    /** Below this share of strong colour the banner is grey/white/black: its plain average is the honest tint. */
    private static final double MIN_CHROMA_SHARE = 0.04;

    /**
     * A banner's strongest colour: YouTube's white banner averages to a pale pink and Spotify's black
     * one to a dark grey, which tint the card with nothing. The hues are binned (12 buckets) and weighted by chroma, so
     * one stray pixel never wins; the winning bucket's chroma-weighted mean is the colour. Grey pictures fall back to
     * the plain average. Transparent pixels (alpha < 128) do not count. RGB, no alpha; 0x808080 when empty.
     */
    static int mostSaturated(int[] argb) {
        double[] w = new double[BUCKETS], r = new double[BUCKETS], g = new double[BUCKETS], b = new double[BUCKETS];
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        double chromaAll = 0;
        for (int p : argb) {
            if ((p >>> 24) < 128) continue;
            int pr = (p >> 16) & 0xFF, pg = (p >> 8) & 0xFF, pb = p & 0xFF;
            sr += pr;
            sg += pg;
            sb += pb;
            n++;
            int max = Math.max(pr, Math.max(pg, pb)), min = Math.min(pr, Math.min(pg, pb));
            int chroma = max - min;
            if (chroma < 24) continue;   // greys, white letters, black backgrounds
            double c = chroma / 255.0;
            int bucket = (int) (hue(pr, pg, pb, max, min) / 360.0 * BUCKETS) % BUCKETS;
            double weight = c * c;
            w[bucket] += weight;
            r[bucket] += pr * weight;
            g[bucket] += pg * weight;
            b[bucket] += pb * weight;
            chromaAll += c;
        }
        if (n == 0) return 0x808080;
        int best = 0;
        for (int k = 1; k < BUCKETS; k++) if (w[k] > w[best]) best = k;
        if (w[best] == 0 || chromaAll / n < MIN_CHROMA_SHARE * 0.5) {
            return (int) (sr / n) << 16 | (int) (sg / n) << 8 | (int) (sb / n);
        }
        return (int) Math.round(r[best] / w[best]) << 16 | (int) Math.round(g[best] / w[best]) << 8
                | (int) Math.round(b[best] / w[best]);
    }

    private static double hue(int r, int g, int b, int max, int min) {
        double d = max - min, h;
        if (max == r) h = (g - b) / d;
        else if (max == g) h = 2 + (b - r) / d;
        else h = 4 + (r - g) / d;
        h *= 60;
        return h < 0 ? h + 360 : h;
    }

    /**
     * A box blur of radius {@code r}, run horizontally then vertically (edges clamped). Three passes come close to a
     * Gaussian; the card uses it on a small copy so the cover's backdrop has no visible blocks (the scaled-only blur
     * showed a mosaic on the TV). Alpha is kept opaque.
     */
    static int[] boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length], out = new int[px.length];
        pass(px, tmp, w, h, r, true);
        pass(tmp, out, w, h, r, false);
        return out;
    }

    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        int span = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0;
            for (int k = -r; k <= r; k++) {
                int p = in[at(line, clamp(k, len), w, horizontal)];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                out[at(line, i, w, horizontal)] = 0xFF000000 | (sr / span) << 16 | (sg / span) << 8 | (sb / span);
                int add = in[at(line, clamp(i + r + 1, len), w, horizontal)];
                int sub = in[at(line, clamp(i - r, len), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int at(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }

    /**
     * Whether an app icon is a wordmark (a line of letters on a plain ground, like Netflix's "NETFLIX" on white): what
     * differs from the ground at the corner spans at least 2.5 times as wide as it is tall. At 18dp on the app line such
     * an icon is unreadable, and the app's name stands next to it anyway.
     */
    static boolean isWordmark(int[] argb, int w, int h) {
        return inkBox(argb, w, h) != null;
    }

    /**
     * A wordmark's first letter as a square {x, y, side, letterLeft, letterRight} of the picture (whatever else falls in
     * the square, outside the letter's columns, is to be painted over with the ground): as tall as the letters plus a quarter of it
     * on each side, centred on the first letter, kept inside the picture; null when the icon is no wordmark. Netflix's
     * "NETFLIX" gives its "N" (the app line keeps an icon).
     */
    static int[] wordmarkInitial(int[] argb, int w, int h) {
        int[] box = inkBox(argb, w, h);
        if (box == null) return null;
        int bh = box[3] - box[1] + 1, margin = Math.max(1, bh / 4);
        // The first letter ends at the first column without ink (the gap before the second); none: a square's worth.
        int bg = argb[0], end = -1;
        for (int x = box[0]; x <= box[2] && end < 0; x++) {
            boolean ink = false;
            for (int y = box[1]; y <= box[3] && !ink; y++) ink = differs(argb[y * w + x], bg);
            if (!ink) end = x - 1;
        }
        if (end < 0) end = Math.min(box[2], box[0] + bh - 1);
        int side = Math.min(Math.min(w, h), Math.max(end - box[0] + 1, bh) + 2 * margin);
        int x = Math.max(0, Math.min(w - side, (box[0] + end + 1) / 2 - side / 2));
        int y = Math.max(0, Math.min(h - side, box[1] - margin));
        return new int[]{x, y, side, box[0], end};
    }

    /** The wordmark's ink box {left, top, right, bottom}, or null when the icon is no wordmark. */
    private static int[] inkBox(int[] argb, int w, int h) {
        if (argb == null || w <= 0 || h <= 0 || argb.length < w * h) return null;
        int bg = argb[0];
        int left = w, right = -1, top = h, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!differs(argb[y * w + x], bg)) continue;
                left = Math.min(left, x);
                right = Math.max(right, x);
                top = Math.min(top, y);
                bottom = Math.max(bottom, y);
            }
        }
        if (right < 0) return null;
        int bw = right - left + 1, bh = bottom - top + 1;
        return bw * 2 >= w && bw >= 2.5 * bh ? new int[]{left, top, right, bottom} : null;
    }

    /** Ink against the ground: a visible pixel on a clear ground, or a clearly different colour. */
    private static boolean differs(int p, int bg) {
        int pa = p >>> 24, ba = bg >>> 24;
        if (pa < 128) return false;
        if (ba < 128) return true;
        int d = Math.abs(((p >> 16) & 0xFF) - ((bg >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((bg >> 8) & 0xFF))
                + Math.abs((p & 0xFF) - (bg & 0xFF));
        return d > 90;
    }
}
