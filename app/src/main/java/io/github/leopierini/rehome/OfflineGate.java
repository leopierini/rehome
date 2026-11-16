package io.github.leopierini.rehome;

/**
 * Whether the Home shows itself offline (the hint in the weather, dimmed tiles). The TV is online
 * only through a phone's hotspot, which drops for a second or two now and then: going offline is shown only after
 * the network has stayed away for {@link #DELAY_MS}, while coming back is shown at once. Pure logic over the
 * caller's clock (SystemClock.elapsedRealtime), so it is tested on the JVM.
 */
final class OfflineGate {

    /** Long enough for a hotspot that drops and joins again, including Android's validation of it. */
    static final long DELAY_MS = 10_000L;

    private boolean shownOffline;
    /** When the network went away, or -1 while it is up. */
    private long offlineSince = -1;

    /** The Home is shown offline now. */
    boolean offline() {
        return shownOffline;
    }

    /** A fresh reading of the network. True when what the Home shows must change now. */
    boolean onSignal(boolean online, long now) {
        if (online) {
            offlineSince = -1;
            if (!shownOffline) return false;
            shownOffline = false;
            return true;
        }
        if (offlineSince < 0) offlineSince = now;
        return onTick(now);
    }

    /** The timer the caller posted for {@link #msUntilOffline}. True when the Home turns offline now. */
    boolean onTick(long now) {
        if (offlineSince < 0 || shownOffline || now - offlineSince < DELAY_MS) return false;
        shownOffline = true;
        return true;
    }

    /** How long until the Home turns offline if nothing changes, or -1 when nothing is pending. */
    long msUntilOffline(long now) {
        if (offlineSince < 0 || shownOffline) return -1;
        return Math.max(0, Math.min(DELAY_MS, DELAY_MS - (now - offlineSince)));
    }
}
