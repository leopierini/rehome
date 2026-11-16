package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Home key keeps the focus on the first Media app until a key is pressed (TV capture: focus on Resume). */
public class HomeFocusTest {

    @Test
    public void nothingIsOwedBeforeTheHomeKey() {
        HomeFocus f = new HomeFocus();
        assertFalse(f.owed());
        assertFalse(f.shouldMove(false, false));
    }

    @Test
    public void afterHomeFocusAnywhereElseGoesBackToTheFirstTile() {
        HomeFocus f = new HomeFocus();
        f.onHome();
        // On the player's Resume (or a pill, or nowhere): moved.
        assertTrue(f.shouldMove(false, false));
        // Already on the first tile: left alone, and still owed (a later steal is undone too).
        assertFalse(f.shouldMove(false, true));
        assertTrue(f.owed());
        assertTrue(f.shouldMove(false, false));
    }

    @Test
    public void aKeyPressEndsIt() {
        HomeFocus f = new HomeFocus();
        f.onHome();
        f.release();
        // Up from the first tile to the player is the owner's move: never undone.
        assertFalse(f.shouldMove(false, false));
    }

    @Test
    public void withNoRowsTheFocusIsLeftWhereItIs() {
        HomeFocus f = new HomeFocus();
        f.onHome();
        assertFalse(f.shouldMove(true, false));
    }

    @Test
    public void aTileThatNeverTakesTheFocusIsNotChasedForEver() {
        HomeFocus f = new HomeFocus();
        f.onHome();
        for (int i = 0; i < HomeFocus.MAX_MOVES; i++) assertTrue(f.shouldMove(false, false));
        assertFalse(f.shouldMove(false, false));
        // The next Home press starts again.
        f.onHome();
        assertTrue(f.shouldMove(false, false));
    }
}
