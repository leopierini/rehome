package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Wi-Fi dropdown's rows. */
public class WifiPopupTest {

    @Test
    public void connectedNetworkHasNoLock() {
        // A lock on the network the TV is on reads as "locked, needs a password".
        assertFalse(WifiPopup.showsLock(true, true, false));
        assertTrue(WifiPopup.showsLock(true, false, false));
        assertFalse(WifiPopup.showsLock(false, false, false));
        assertFalse(WifiPopup.showsLock(false, true, false));
    }

    @Test
    public void savedNetworkSaysSoInsteadOfTheLock() {
        // The saved hotspots must stand out from the strangers' networks.
        assertFalse(WifiPopup.showsLock(true, false, true));
        Words en = TestWords.en(), it = TestWords.it();
        assertEquals("Saved", WifiPopup.status(en, false, true, false));
        assertEquals("Connected", WifiPopup.status(en, true, true, false));
        assertEquals("Connecting…", WifiPopup.status(en, false, true, true));
        assertNull(WifiPopup.status(en, false, false, false));
        assertEquals("Salvata", WifiPopup.status(it, false, true, false));
        assertEquals("Connessa", WifiPopup.status(it, true, true, false));
    }

    @Test
    public void connectedThenSavedThenStrongest() {
        WifiPopup.Network strong = net("Vodafone", -40, false, false);
        WifiPopup.Network saved = net("Phone B", -80, false, true);
        WifiPopup.Network current = net("Sam’s Phone", -70, true, true);
        WifiPopup.Network weak = net("TP-LINK", -85, false, false);
        List<WifiPopup.Network> list = new ArrayList<>(Arrays.asList(weak, strong, saved, current));
        Collections.sort(list, WifiPopup::order);
        assertEquals(Arrays.asList(current, saved, strong, weak), list);
    }

    @Test
    public void savedNetworksFollowTheOwnersOrder() {
        WifiPopup.Network sam = net("Sam’s Phone", -40, false, true);
        sam.rank = 2;
        WifiPopup.Network home = net("Home", -80, false, true);
        home.rank = 1;
        WifiPopup.Network phoneB = net("Phone B", -85, false, true);
        phoneB.rank = 0;
        WifiPopup.Network other = net("Vodafone", -30, false, false);
        List<WifiPopup.Network> list = new ArrayList<>(Arrays.asList(other, sam, home, phoneB));
        Collections.sort(list, WifiPopup::order);
        assertEquals(Arrays.asList(phoneB, home, sam, other), list);
    }

    private static WifiPopup.Network net(String ssid, int rssi, boolean current, boolean saved) {
        WifiPopup.Network n = new WifiPopup.Network(ssid, rssi, WifiPopup.SECURITY_PSK, current);
        n.saved = saved;
        return n;
    }
}
