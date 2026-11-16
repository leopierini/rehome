package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the offline Home's debounce: a hotspot that drops for a second does not flash the Home. */
public class OfflineGateTest {

    private static final long D = OfflineGate.DELAY_MS;

    @Test
    public void startsOnline() {
        OfflineGate g = new OfflineGate();
        assertFalse(g.offline());
        assertEquals(-1, g.msUntilOffline(0));
    }

    @Test
    public void briefLossIsIgnored() {
        OfflineGate g = new OfflineGate();
        assertFalse(g.onSignal(false, 1000));
        assertEquals(D, g.msUntilOffline(1000));
        assertFalse(g.onTick(2500));
        assertFalse(g.onSignal(true, 3000));     // back within two seconds: nothing ever changed on screen
        assertFalse(g.offline());
        assertEquals(-1, g.msUntilOffline(3000));
        assertFalse(g.onTick(1000 + D));          // the timer posted for the loss finds nothing to do
        assertFalse(g.offline());
    }

    @Test
    public void lastingLossShowsOfflineAfterTheDelay() {
        OfflineGate g = new OfflineGate();
        g.onSignal(false, 1000);
        assertFalse(g.onTick(1000 + D - 1));
        assertEquals(1, g.msUntilOffline(1000 + D - 1));
        assertTrue(g.onTick(1000 + D));
        assertTrue(g.offline());
        assertEquals(-1, g.msUntilOffline(1000 + D));
        assertFalse(g.onTick(1000 + 2 * D));     // shown once
    }

    @Test
    public void repeatedOfflineSignalsKeepTheFirstTime() {
        OfflineGate g = new OfflineGate();
        g.onSignal(false, 1000);
        g.onSignal(false, 1000 + D / 2);          // another callback while still down: does not restart the wait
        assertTrue(g.onSignal(false, 1000 + D));
        assertTrue(g.offline());
    }

    @Test
    public void comingBackIsImmediate() {
        OfflineGate g = new OfflineGate();
        g.onSignal(false, 0);
        g.onTick(D);
        assertTrue(g.offline());
        assertTrue(g.onSignal(true, D + 1));
        assertFalse(g.offline());
        assertFalse(g.onSignal(true, D + 2));     // already online: no change
    }

    @Test
    public void aClockThatGoesBackDoesNotStickOffline() {
        OfflineGate g = new OfflineGate();
        g.onSignal(false, 5000);
        assertEquals(D, g.msUntilOffline(4000)); // never more than the full delay
    }
}
