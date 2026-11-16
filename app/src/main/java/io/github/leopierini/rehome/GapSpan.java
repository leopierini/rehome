package io.github.leopierini.rehome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.style.ReplacementSpan;

/**
 * A gap between two parts of one line of text, instead of a " · " (the dots read as machine-made):
 * empty, or with a thin dim vertical bar in its middle ("Rome | Cloudy"). Put on one placeholder character.
 */
final class GapSpan extends ReplacementSpan {

    private final int gap, line, color;

    /** {@code gap}: px on each side of the bar (or half the empty gap); {@code line}: the bar's width, 0 for none. */
    GapSpan(int gap, int line, int color) {
        this.gap = gap;
        this.line = line;
        this.color = color;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        return 2 * gap + line;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                     Paint paint) {
        if (line <= 0) return;
        // About 60% of the letters' height, centred on the capitals (baseline to cap height), not the whole line box.
        Paint.FontMetrics m = paint.getFontMetrics();
        float cap = -m.ascent * 0.72f, h = (m.descent - m.ascent) * 0.6f;
        float mid = y - cap / 2f;
        int was = paint.getColor();
        Paint.Style style = paint.getStyle();
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(x + gap, mid - h / 2f, x + gap + line, mid + h / 2f, paint);
        paint.setColor(was);
        paint.setStyle(style);
    }
}
