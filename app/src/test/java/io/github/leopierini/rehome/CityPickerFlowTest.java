package io.github.leopierini.rehome;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The city picker's keyboard and focus rules: one search per Search key, focus on the first place found. */
public class CityPickerFlowTest {

    @Test
    public void searchKeySearchesOnce() {
        assertEquals(CityPickerFlow.SEARCH, CityPickerFlow.editorAction(EditorInfo.IME_ACTION_SEARCH, -1, -1));
        assertEquals(CityPickerFlow.SEARCH, CityPickerFlow.editorAction(EditorInfo.IME_ACTION_DONE, -1, -1));
        assertEquals(CityPickerFlow.SEARCH, CityPickerFlow.editorAction(EditorInfo.IME_ACTION_GO, -1, -1));
    }

    @Test
    public void enterFromAKeyboardSearchesOnThePressAndSwallowsTheRelease() {
        assertEquals(CityPickerFlow.SEARCH, CityPickerFlow.editorAction(EditorInfo.IME_NULL,
                KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN));
        // The release must be consumed too: TextView would move focus down off the field with it.
        assertEquals(CityPickerFlow.CONSUME, CityPickerFlow.editorAction(EditorInfo.IME_NULL,
                KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP));
        assertEquals(CityPickerFlow.SEARCH, CityPickerFlow.editorAction(EditorInfo.IME_NULL,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.ACTION_DOWN));
    }

    @Test
    public void otherActionsAreLeftToTheField() {
        assertEquals(CityPickerFlow.PASS, CityPickerFlow.editorAction(EditorInfo.IME_ACTION_NEXT, -1, -1));
        assertEquals(CityPickerFlow.PASS, CityPickerFlow.editorAction(EditorInfo.IME_NULL, -1, -1));
        assertEquals(CityPickerFlow.PASS, CityPickerFlow.editorAction(EditorInfo.IME_NULL,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN));
    }

    @Test
    public void theSameQueryIsNotSentTwice() {
        // Typing pause searched "Rome"; Search right after asks nothing new.
        assertTrue(CityPickerFlow.alreadyAsked("Rome", "Rome", false));
        assertFalse(CityPickerFlow.alreadyAsked("Rome", "Rom", false));
        assertFalse(CityPickerFlow.alreadyAsked("Rome", null, false));
        // "No connection": Search again retries.
        assertFalse(CityPickerFlow.alreadyAsked("Rome", "Rome", true));
    }

    @Test
    public void focusGoesToTheFirstPlaceOnlyAfterTheSearchKey() {
        assertTrue(CityPickerFlow.focusFirstResult(true, 3, true));
        // Typing: focus stays in the field.
        assertFalse(CityPickerFlow.focusFirstResult(false, 3, true));
        // No results / No connection: nothing to go to, focus stays on the field.
        assertFalse(CityPickerFlow.focusFirstResult(true, 0, true));
        // The owner already moved to a row (or "Turn weather off"): left there.
        assertFalse(CityPickerFlow.focusFirstResult(true, 3, false));
    }
}
