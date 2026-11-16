package io.github.leopierini.rehome;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Up on a pill dropdown's first row closes it, as Back does. */
public class DropdownKeysTest {

    @Test
    public void upOnTheFirstRowCloses() {
        assertTrue(DropdownKeys.closesOnUp(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, 0, false));
    }

    @Test
    public void upWithARowAboveMovesOn() {
        assertFalse(DropdownKeys.closesOnUp(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, 0, true));
    }

    @Test
    public void heldUpStopsOnTheFirstRow() {
        // Holding Up to run to the top must not close the dropdown when it gets there: only a new press does.
        assertFalse(DropdownKeys.closesOnUp(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, 3, false));
    }

    @Test
    public void otherKeysAndKeyUpDoNothing() {
        assertFalse(DropdownKeys.closesOnUp(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_UP, 0, false));
        assertFalse(DropdownKeys.closesOnUp(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, 0, false));
        assertFalse(DropdownKeys.closesOnUp(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, 0, false));
    }
}
