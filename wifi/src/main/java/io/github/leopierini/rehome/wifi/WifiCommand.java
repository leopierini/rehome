package io.github.leopierini.rehome.wifi;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.List;

/**
 * The two commands, as ordered broadcasts whose result code says what happened (the launcher reads it):
 * <ul>
 * <li>{@code ENABLE}: switches Wi-Fi on. The framework then joins a saved network in range by itself.</li>
 * <li>{@code CONNECT} with {@code ssid} (plain, unquoted) and {@code security} (WifiPopup.SECURITY_*): joins the
 * saved entry for that SSID, as TvSettings' "Connect" would, with no screen. {@link #NOT_SAVED} when there is none,
 * so the launcher can open TvSettings to ask for the password.</li>
 * <li>{@code SAVED}: the SSIDs of the saved networks, as the result extra {@code ssids} (String[]), so the dropdown can
 * mark them ("Saved" instead of the lock).</li>
 * </ul>
 * Both work only because this app targets API 28: from Android 10 the framework refuses them to apps that target
 * 29+ (and the launcher targets 34). Connecting to a network another app saved is allowed for a legacy app: the
 * framework logs "Allowing uid ... with insufficient permissions to connect" and connects.
 */
public final class WifiCommand extends BroadcastReceiver {

    static final String TAG = "Rehome.Wifi";
    public static final String ENABLE = "io.github.leopierini.rehome.wifi.ENABLE";
    public static final String CONNECT = "io.github.leopierini.rehome.wifi.CONNECT";
    public static final String SAVED = "io.github.leopierini.rehome.wifi.SAVED";

    /** Result codes. The launcher sets {@link #NO_HELPER} as the initial code: it stays when nothing answered. */
    public static final int OK = 1, NOT_SAVED = 2, REFUSED = 3, NO_HELPER = 0;

    // WifiPopup's security constants (com.android.settingslib.wifi.AccessPoint).
    private static final int SECURITY_NONE = 0, SECURITY_WEP = 1, SECURITY_PSK = 2, SECURITY_SAE = 5;

    @Override
    public void onReceive(Context context, Intent intent) {
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int result;
        try {
            if (wifi == null) result = REFUSED;
            else if (ENABLE.equals(intent.getAction())) result = wifi.setWifiEnabled(true) ? OK : REFUSED;
            else if (CONNECT.equals(intent.getAction())) result = connect(wifi, intent.getStringExtra("ssid"),
                    intent.getIntExtra("security", -1));
            else if (SAVED.equals(intent.getAction())) result = saved(wifi);
            else result = REFUSED;
        } catch (RuntimeException e) {   // SecurityException without the location grant, a dead system service
            Log.w(TAG, "command failed", e);
            result = REFUSED;
        }
        Log.i(TAG, intent.getAction() + " -> " + result);
        if (isOrderedBroadcast()) setResultCode(result);
    }

    // Without the location grant getConfiguredNetworks throws: onReceive answers REFUSED and the launcher falls back.
    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private int saved(WifiManager wifi) {
        List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
        if (saved == null) return REFUSED;
        java.util.LinkedHashSet<String> ssids = new java.util.LinkedHashSet<>();
        for (WifiConfiguration c : saved) {
            String ssid = c.SSID;
            if (ssid == null) continue;
            if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) ssid = ssid.substring(1, ssid.length() - 1);
            if (!ssid.isEmpty()) ssids.add(ssid);
        }
        Log.i(TAG, "saved networks: " + ssids.size() + " of " + saved.size());
        if (isOrderedBroadcast()) {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putStringArray("ssids", ssids.toArray(new String[0]));
            setResultExtras(extras);
        }
        return OK;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private static int connect(WifiManager wifi, String ssid, int security) {
        if (ssid == null || ssid.isEmpty()) return REFUSED;
        if (!wifi.isWifiEnabled()) wifi.setWifiEnabled(true);
        List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
        if (saved == null) return REFUSED;
        WifiConfiguration best = null;
        int bestScore = -1;
        String quoted = "\"" + ssid + "\"";
        for (WifiConfiguration c : saved) {
            if (!quoted.equals(c.SSID)) continue;
            int score = score(c, security);
            if (score > bestScore) {
                best = c;
                bestScore = score;
            }
        }
        if (best == null) return NOT_SAVED;
        // disableOthers=true is the "connect now" path (ClientModeImpl.connect); others stay saved and enabled.
        return wifi.enableNetwork(best.networkId, true) ? OK : REFUSED;
    }

    /**
     * Which saved entry fits the access point. An iPhone hotspot is saved twice (WPA2 and WPA3) and broadcasts
     * both: the WPA2 entry is the one TvSettings and the framework have been joining on this TV, so it comes
     * first unless the access point is WPA3-only.
     */
    @SuppressWarnings("deprecation")
    static int score(WifiConfiguration c, int security) {
        boolean psk = c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK);
        boolean sae = c.allowedKeyManagement.get(8 /* KeyMgmt.SAE, API 29 */);
        boolean open = c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) && c.wepKeys[0] == null;
        switch (security) {
            case SECURITY_SAE: return sae ? 3 : psk ? 1 : 0;
            case SECURITY_PSK: return psk ? 3 : sae ? 2 : 0;
            case SECURITY_NONE: return open ? 3 : 0;
            case SECURITY_WEP: return c.wepKeys[0] != null ? 3 : 0;
            default: return psk || sae ? 2 : 1;
        }
    }
}
