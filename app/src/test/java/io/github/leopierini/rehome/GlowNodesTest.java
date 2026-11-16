package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * When a glow's node is recorded again (GlowNodes.recordAgain). C1 of the speed review: back on Home after an app the
 * window had dropped every display list, the glow nodes' too, and the focused tile's node was skipped because its glow
 * had not changed: ring and zoom, no glow, until focus moved.
 */
public class GlowNodesTest {

    private final Object glow = new Object(), other = new Object();

    @Test
    public void anUnchangedGlowWithItsDisplayListIsLeftAlone() {
        // A frame of a fade on another tile: this node's glow is the same, at the same version.
        assertFalse(GlowNodes.recordAgain(false, glow, 7, glow, 7, true));
        assertFalse(GlowNodes.recordAgain(false, null, 0, null, 0, false));
    }

    @Test
    public void aChangedGlowIsRecorded() {
        assertTrue(GlowNodes.recordAgain(false, glow, 7, glow, 8, true));    // faded, moved, recoloured
        assertTrue(GlowNodes.recordAgain(false, glow, 7, other, 7, true));   // another tile's glow in this slot
        assertTrue(GlowNodes.recordAgain(false, glow, 7, null, 0, true));    // the glow went: emptied
        assertTrue(GlowNodes.recordAgain(false, null, 0, glow, 1, false));
    }

    @Test
    public void theHolderRecordingRecordsEveryNode() {
        // Return to Home: the same glow at the same version, but the holder records again because the stop dropped its
        // display list, and with it the nodes'. This is the case that drew nothing.
        assertTrue(GlowNodes.recordAgain(true, glow, 7, glow, 7, true));
        assertTrue(GlowNodes.recordAgain(true, glow, 7, glow, 7, false));
        assertTrue(GlowNodes.recordAgain(true, null, 0, null, 0, true));
    }

    @Test
    public void aGlowWhoseNodeLostItsDisplayListIsRecordedEvenIfUnchanged() {
        // The belt: a frame placed before the holder records again (placeGlows runs before every draw).
        assertTrue(GlowNodes.recordAgain(false, glow, 7, glow, 7, false));
    }
}
