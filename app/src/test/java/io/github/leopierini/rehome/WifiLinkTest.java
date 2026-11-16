package io.github.leopierini.rehome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.wifi.WifiManager;

import org.junit.Test;

public class WifiLinkTest {

    @Test
    public void spinsWhileARequestIsPending() {
        assertTrue(WifiLink.busy(true, WifiManager.WIFI_STATE_ENABLED, false));
        assertTrue(WifiLink.busy(true, WifiManager.WIFI_STATE_DISABLED, false));
    }

    @Test
    public void spinsWhileWifiSwitchesOn() {
        assertTrue(WifiLink.busy(false, WifiManager.WIFI_STATE_ENABLING, false));
    }

    @Test
    public void spinsWhileTheFrameworkJoinsANetworkByItself() {
        assertTrue(WifiLink.busy(false, WifiManager.WIFI_STATE_ENABLED, true));
    }

    @Test
    public void stillOtherwise() {
        assertFalse(WifiLink.busy(false, WifiManager.WIFI_STATE_ENABLED, false));
        assertFalse(WifiLink.busy(false, WifiManager.WIFI_STATE_DISABLED, false));
        assertFalse(WifiLink.busy(false, WifiManager.WIFI_STATE_UNKNOWN, false));
        // A link state left over from before Wi-Fi went off does not spin a switched-off pill.
        assertFalse(WifiLink.busy(false, WifiManager.WIFI_STATE_DISABLED, true));
        assertFalse(WifiLink.busy(false, WifiManager.WIFI_STATE_DISABLING, true));
    }

    @Test
    public void reachedAfterSwitchingOnIsAnyNetwork() {
        assertTrue(WifiLink.reached("", "Sam’s Phone"));
        assertFalse(WifiLink.reached("", null));
    }

    @Test
    public void reachedIsTheNetworkAskedFor() {
        assertTrue(WifiLink.reached("Phone B", "Phone B"));
        // Still on the old network while the switch is under way.
        assertFalse(WifiLink.reached("Phone B", "Sam’s Phone"));
        assertFalse(WifiLink.reached(null, "Sam’s Phone"));
    }
}
