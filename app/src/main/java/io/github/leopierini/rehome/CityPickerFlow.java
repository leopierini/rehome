package io.github.leopierini.rehome;

import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

/**
 * CityPickerActivity's decisions, kept apart so the JVM tests reach them: what a key or editor action from the TV's
 * keyboard means, whether a search has already been asked, and where focus goes when its answer arrives.
 */
final class CityPickerFlow {
    /** Not the picker's: TextView handles it as usual. */
    static final int PASS = 0;
    /** Search now (and close the keyboard). */
    static final int SEARCH = 1;
    /** Already handled (the ENTER release after its press): swallowed, so TextView does not move focus. */
    static final int CONSUME = 2;

    private CityPickerFlow() {
    }

    /**
     * The search field's editor action. The keyboard's Search (or Done/Go) key searches. A keyboard that sends ENTER
     * instead (IME_NULL with the key) searches on the press and has the release swallowed. Everything must be
     * consumed: an action left unhandled is replayed by TextView as an ENTER whose release moves focus down, off the
     * field and onto an emptied list.
     *
     * @param keyCode    the event's key, or -1 without an event
     * @param keyAction  the event's KeyEvent action, or -1 without an event
     */
    static int editorAction(int actionId, int keyCode, int keyAction) {
        if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_GO) {
            return SEARCH;
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return keyAction == KeyEvent.ACTION_DOWN ? SEARCH : CONSUME;
        }
        return PASS;
    }

    /**
     * Whether the query asked now is the one already asked and answered or under way, so it is not sent again (the
     * keyboard's Search right after the typing pause already searched it). A failed one ("No connection") is asked
     * again: that is how the owner retries.
     */
    static boolean alreadyAsked(String query, String lastQuery, boolean lastFailed) {
        return lastQuery != null && lastQuery.equals(query) && !lastFailed;
    }

    /**
     * Where focus goes when an answer arrives. {@code wanted}: the search came from the keyboard's Search key.
     * {@code onFieldOrNowhere}: focus is still on the search field (or was lost), not on a row the owner moved to.
     *
     * @return true to put focus on the first place found; with none, focus stays on (or goes back to) the field
     */
    static boolean focusFirstResult(boolean wanted, int results, boolean onFieldOrNowhere) {
        return wanted && results > 0 && onFieldOrNowhere;
    }
}
