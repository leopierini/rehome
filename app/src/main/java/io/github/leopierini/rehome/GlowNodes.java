package io.github.leopierini.rehome;

import android.annotation.TargetApi;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The tile glows of {@link FocusLook.Layer}, each recorded in its own small RenderNode inside the rows' holder
 * (Android 10 and later).
 *
 * Why (Task 7, measured on the Philips 55OLED706, Mali-G52): the Layer is the background of the view that holds every
 * row. Redrawing it on each step of the focus motion re-recorded that view, so every frame repainted the whole lower
 * part of the screen (wallpaper, bands, all the tiles) instead of the glow's rectangle: the buffer queue filled up and
 * frames reached the screen 20-45 ms late (gfxinfo: ~40% "janky", 440 frames for 40 keys against 370 without the
 * glow). Now a glow that moves or fades re-records only its own node, and a frame repaints only that rectangle.
 *
 * The pixels are the same: a node is placed at the glow's own rectangle and FocusGlow.draw paints into it exactly
 * what it painted into the holder, under the same clip and fading edge (the nodes are drawn by the holder's
 * background, where the glows always were). Between two recordings of the holder only the changed glows' nodes are
 * recorded again.
 *
 * <p>Every recording of the holder records every node again (speedfix, C1). When Home is stopped (an app in front)
 * the window drops all its display lists, the nodes' included: back on Home the holder records itself again, and a
 * node skipped because "its glow did not change" drew nothing, so the focused tile came back without its glow until
 * focus moved. The holder records rarely (start, return, rebuild); three small recordings then cost nothing.
 */
@TargetApi(29)
final class GlowNodes {
    /** Nodes the holder's recording draws; two glows show at a time (one fading in, one out), a third is spare. */
    private static final int SLOTS = 3;

    private final List<RenderNode> nodes = new ArrayList<>();
    /** What each node shows now: the glow and its FocusGlow.version() when recorded (null: empty). */
    private final List<FocusGlow> shown = new ArrayList<>();
    private int[] versions = new int[0];
    /** How many nodes the holder's last recording draws; 0 until it has drawn through a RecordingCanvas. */
    private int recorded;

    /** Whether the holder's recording draws these nodes, so changes reach the screen without recording it again. */
    boolean live() {
        return recorded > 0;
    }

    /** Whether n glows fit in the nodes the holder already draws. */
    boolean fits(int n) {
        return n <= recorded;
    }

    /** From the holder's draw: brings the nodes up to date and draws them (empty ones draw nothing). */
    void drawInto(RecordingCanvas canvas, List<FocusGlow> glows) {
        int want = Math.max(SLOTS, glows.size());
        while (nodes.size() < want) {
            nodes.add(new RenderNode("rehome-glow"));
            shown.add(null);
        }
        if (versions.length < nodes.size()) {
            int[] grown = new int[nodes.size()];
            System.arraycopy(versions, 0, grown, 0, versions.length);
            versions = grown;
        }
        recorded = nodes.size();
        // The holder is recording: its old display list, and possibly the nodes' (a stop drops them), are gone.
        update(glows, true);
        for (int i = 0; i < recorded; i++) canvas.drawRenderNode(nodes.get(i));
    }

    /** Records again only the nodes whose glow changed (placed, faded, recoloured) since they were recorded. */
    void update(List<FocusGlow> glows) {
        update(glows, false);
    }

    private void update(List<FocusGlow> glows, boolean holderRecording) {
        for (int i = 0; i < recorded; i++) {
            FocusGlow g = i < glows.size() ? glows.get(i) : null;
            int v = g == null ? 0 : g.version();
            RenderNode node = nodes.get(i);
            if (!recordAgain(holderRecording, shown.get(i), versions[i], g, v, g != null && node.hasDisplayList())) {
                continue;
            }
            record(node, g);
            shown.set(i, g);
            versions[i] = v;
        }
    }

    /**
     * Whether a node must be recorded again: always while the holder records (its nodes may have been dropped with the
     * window's display lists), when its glow or that glow's version changed, and when it should show a glow but has
     * no display list (dropped while Home was stopped, before the holder recorded again). Plain logic, JVM-tested.
     */
    static boolean recordAgain(boolean holderRecording, Object shown, int shownVersion, Object now, int version,
            boolean nodeHasList) {
        if (holderRecording) return true;
        if (shown != now || shownVersion != version) return true;
        return now != null && !nodeHasList;
    }

    /** Drops what the nodes show (Home released or destroyed): no display list keeps a mask alive. */
    void clear() {
        for (int i = 0; i < nodes.size(); i++) {
            if (shown.get(i) == null) continue;
            record(nodes.get(i), null);
            shown.set(i, null);
            versions[i] = 0;
        }
    }

    private static void record(RenderNode node, FocusGlow g) {
        Rect b = g == null ? null : g.getBounds();
        if (b == null || b.isEmpty()) {
            // Empty, at the same place: the frame repaints where the glow was, so it disappears.
            node.beginRecording();
            node.endRecording();
            return;
        }
        int s = g.spread();
        int left = b.left - s, top = b.top - s;
        node.setPosition(left, top, b.right + s, b.bottom + s);
        RecordingCanvas c = node.beginRecording(b.width() + 2 * s, b.height() + 2 * s);
        try {
            c.translate(-left, -top);
            g.draw(c);
        } finally {
            node.endRecording();
        }
    }
}
