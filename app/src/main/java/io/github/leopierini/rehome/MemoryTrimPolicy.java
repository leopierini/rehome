package io.github.leopierini.rehome;

import android.content.ComponentCallbacks2;

/**
 * Memory that affects the picture is released only when Home is not on screen.
 *
 * RUNNING_MODERATE counts too while Home is stopped (final fix I2): the bound notification listener (MediaListener)
 * keeps the launcher a bound foreground service behind other apps (measured on the TV: adj 250, BFGS, against HOME
 * and adj 600 without it), and such a process never gets the BACKGROUND levels: the first sign of pressure it hears
 * is RUNNING_MODERATE.
 */
final class MemoryTrimPolicy {
    private MemoryTrimPolicy() {
    }

    static boolean shouldRelease(int level, boolean homeStarted) {
        if (homeStarted) return false;
        return level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
    }
}
