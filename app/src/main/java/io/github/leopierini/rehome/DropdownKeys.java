package io.github.leopierini.rehome;

import android.view.KeyEvent;

/**
 * A top-bar pill's dropdown (Wi-Fi, Inputs, …): Up on its first row closes it and the focus goes back to the pill,
 * as Back does. Pure, tested on the JVM; MainActivity.keepHomeKeys asks it.
 */
final class DropdownKeys {

    private DropdownKeys() {}

    /**
     * {@code focusAbove}: the dropdown has a row above the focused one. Only a new press closes: holding Up to run to
     * the top stops on the first row instead of closing the dropdown when it gets there.
     */
    static boolean closesOnUp(int keyCode, int action, int repeatCount, boolean focusAbove) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP && action == KeyEvent.ACTION_DOWN && repeatCount == 0 && !focusAbove;
    }
}
