package io.github.leopierini.rehome;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-press Wi-Fi: "Turn on" switches Wi-Fi on and a saved network in the dropdown is joined straight away,
 * with no TvSettings screens; meanwhile the Wi-Fi pill shows a spinner instead of the fan.
 * Also the owner's order for the saved networks ({@link WifiPriority}: the one set in Launcher settings,
 * {@link WifiOrderStore}, else config.xml wifi_priority), applied after each scan, and the list of saved networks the
 * dropdown marks "Saved".
 *
 * <p>Android 10+ refuses WifiManager.setWifiEnabled and enableNetwork to apps that target API 29 or later, and
 * getConfiguredNetworks returns nothing to them, so the work is done by the companion app io.github.leopierini.rehome.wifi
 * (the {@code wifi} module, targetSdk 28), reached with ordered broadcasts guarded by the signature permission
 * io.github.leopierini.rehome.permission.WIFI_COMMAND. Its result code says what happened; when it is missing, has no saved
 * entry for the SSID or is refused, the caller's fallback runs (TvSettings' own flow, which asks for the password).
 *
 * <p>"Connecting" ({@link #busy}) is the request in flight (until the TV is on that network, or {@link #PATIENCE_MS})
 * and also whatever the framework is doing by itself: Wi-Fi switching on, or joining a saved network (the
 * NETWORK_STATE_CHANGED detailed state), so an automatic reconnection spins the pill too.
 *
 * <p>The order: after a scan, when a better-ranked network is in range, the TV moves to it (the pill spins). A network
 * reached that way with no internet 40 s later is set aside (15 min, doubling while it keeps failing), as is one
 * whose password is refused or that cannot be joined, and the order is applied again without it. A network the owner
 * picks in the dropdown holds: the order waits while the TV stays on it. The order is read again each time it is
 * applied, so one changed in Launcher settings counts from the next scan; what is set aside stays set aside.
 */
final class WifiLink {

    static final String HELPER = "io.github.leopierini.rehome.wifi";
    private static final String COMMAND = HELPER + ".WifiCommand";
    static final String ENABLE = HELPER + ".ENABLE";
    static final String CONNECT = HELPER + ".CONNECT";
    static final String SAVED = HELPER + ".SAVED";
    /** WifiCommand's result codes; NO_HELPER is the initial code, left when nothing answered. */
    static final int NO_HELPER = 0, OK = 1, NOT_SAVED = 2, REFUSED = 3;

    /** A request that has not got the TV onto the network by then is over (the pill shows what there is). */
    static final long PATIENCE_MS = 30_000;
    /** Wi-Fi switched on: the framework joins a saved network in range within seconds, or there is none. */
    static final long ENABLE_PATIENCE_MS = 20_000;
    /** After a move made by the order: no internet by then and the network is set aside. */
    private static final long VALIDATION_MS = 40_000;
    /** At most one move made by the order per minute, so two networks can never ping-pong. */
    private static final long MOVE_SPACING_MS = 60_000;
    /** A network the order could not join (not saved, refused, timed out) waits this long. */
    private static final long FAILED_PAUSE_MS = 10 * 60_000;
    /** After a scan the saved list is read again if older than this (at boot Android can list only part of it). */
    private static final long SAVED_MAX_AGE_MS = 20_000;

    private final Context context;
    private final Handler handler;
    private final Runnable onChange;
    private final WifiManager wifi;
    /** Where the order set in Launcher settings lives (WifiOrderStore), and config.xml wifi_priority under it. */
    private final SharedPreferences settings;
    private final List<String> configOrder;

    /** The SSID asked for, "" after "Turn on" (any network), null when nothing is pending. */
    private String target;
    /** The pending request was made by the order, not by the owner: no fallback screens, no toasts. */
    private boolean byOrder;
    private long deadline;
    /** What the framework last said about the Wi-Fi link (NETWORK_STATE_CHANGED), and the radio (WIFI_STATE). */
    private NetworkInfo.DetailedState detailed;
    private int radio = WifiManager.WIFI_STATE_UNKNOWN;
    /** Runs when the saved password is refused for the network asked for: TvSettings asks for it again. */
    private Runnable onRefused;
    /** The saved networks' SSIDs as the companion app last listed them (empty until then, or without it). */
    private Set<String> saved = Collections.emptySet();
    private long savedAt = -SAVED_MAX_AGE_MS;
    /** Runs when the saved list changes (the open dropdown rebuilds its rows); null when nothing shows it. */
    private Runnable savedListener;
    /** The network the owner picked in the dropdown: the order leaves it alone while the TV is on it. */
    private String chosen;
    /** Networks set aside by the order until the given elapsedRealtime, and no-internet failures in a row. */
    private final Map<String, Long> asideUntil = new HashMap<>();
    private final Map<String, Integer> noInternet = new HashMap<>();
    private long lastMove = -MOVE_SPACING_MS;
    /** The network a move by the order reached, checked for internet VALIDATION_MS later. */
    private String checking;

    private final Runnable expire = this::expire;
    private final Runnable checkInternet = this::checkInternet;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                radio = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                detailed = info != null ? info.getDetailedState() : null;
                if (detailed == NetworkInfo.DetailedState.CONNECTED) {
                    if (target != null && reached()) {
                        boolean moved = byOrder;
                        String was = target;
                        finish();
                        if (moved) checkLater(was);
                    }
                    // A network joined through TvSettings' password screen is saved from now on.
                    loadSaved(0);
                }
            } else if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)) {
                int error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0);
                if (error == WifiManager.ERROR_AUTHENTICATING && target != null && !target.isEmpty()) refused();
                return;
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                loadSaved(SAVED_MAX_AGE_MS);
                applyOrder();
                return;
            }
            onChange.run();
        }
    };

    WifiLink(Context context, Handler handler, Runnable onChange) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.onChange = onChange;
        this.wifi = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        this.settings = this.context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        this.configOrder = WifiOrderStore.config(this.context);
    }

    /** The order in force now, most wanted first; empty: none is applied. */
    private List<String> order() {
        return WifiOrderStore.get(settings, configOrder);
    }

    /** For the activity's whole life (MainActivity.onCreate / onDestroy). */
    void register() {
        IntentFilter filter = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, filter, null, handler);
        loadSaved(0);
    }

    void unregister() {
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        handler.removeCallbacks(expire);
        handler.removeCallbacks(checkInternet);
    }

    /** What runs when the saved list changes (the open dropdown); null to stop. */
    void setSavedListener(Runnable listener) {
        savedListener = listener;
    }

    /**
     * Asks the companion app for the saved networks unless the list is younger than {@code maxAgeMs}; the saved
     * listener runs on the handler when the answer differs from the list held.
     */
    void loadSaved(long maxAgeMs) {
        long now = SystemClock.elapsedRealtime();
        if (now - savedAt < maxAgeMs || !available()) return;
        savedAt = now;
        askSaved(context, handler, ssids -> {
            if (ssids == null) return;
            Set<String> list = new HashSet<>(ssids);
            if (list.equals(saved)) return;
            saved = list;
            if (savedListener != null) savedListener.run();
        });
    }

    /** What {@link #askSaved} hands back: the saved SSIDs as the companion app listed them, or null. */
    interface SavedAnswer {
        void saved(List<String> ssids);
    }

    /**
     * Asks the companion app for the saved networks' SSIDs; {@code answer} runs on {@code handler} with them, or with
     * null when it is missing or could not read them (no location grant). Also for WifiOrderActivity.
     */
    static void askSaved(Context context, Handler handler, SavedAnswer answer) {
        Intent intent = new Intent(SAVED).setComponent(new ComponentName(HELPER, COMMAND))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Bundle extras = getResultExtras(false);
                String[] ssids = extras != null ? extras.getStringArray("ssids") : null;
                answer.saved(getResultCode() == OK && ssids != null ? Arrays.asList(ssids) : null);
            }
        };
        try {
            context.sendOrderedBroadcast(intent, null, receiver, handler, NO_HELPER, null, null);
        } catch (RuntimeException e) {
            handler.post(() -> answer.saved(null));
        }
    }

    /** Whether {@code ssid} is a saved network, as of the last {@link #loadSaved}. */
    boolean isSaved(String ssid) {
        return ssid != null && saved.contains(ssid);
    }

    /** Position in the owner's order (the dropdown lists the saved networks by it); MAX_VALUE when not in it. */
    int rank(String ssid) {
        return WifiPriority.rank(order(), ssid);
    }

    /** Whether the companion app is installed (and visible: the manifest's queries name it). */
    boolean available() {
        return available(context);
    }

    static boolean available(Context context) {
        try {
            context.getPackageManager().getPackageInfo(HELPER, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Switches Wi-Fi on. @return false when the helper is not installed (the caller opens the settings instead);
     * the fallback also runs later when the helper answers that it could not.
     */
    boolean enable(Runnable fallback) {
        if (!available()) return false;
        start("", ENABLE_PATIENCE_MS, null, false);
        send(new Intent(ENABLE), fallback);
        return true;
    }

    /**
     * Joins the saved network {@code ssid}, picked by the owner (the order then leaves it alone). @return false when
     * the helper is not installed; the fallback (TvSettings' flow, which asks for the password) also runs when it has
     * no saved entry for it or is refused, and when the saved password is rejected.
     */
    boolean connect(String ssid, int security, Runnable fallback) {
        if (!available()) return false;
        chosen = ssid;
        start(ssid, PATIENCE_MS, fallback, false);
        send(new Intent(CONNECT).putExtra("ssid", ssid).putExtra("security", security), fallback);
        return true;
    }

    /** The SSID being joined (for the pill and the dropdown), "" while switching on, null when nothing is asked. */
    String target() {
        return target;
    }

    /** Whether the pill shows the spinner: a request in flight, or the framework switching on or connecting. */
    boolean busy() {
        return busy(target != null, radio, linking(detailed));
    }

    /** A detailed state between "trying this network" and "on it". */
    private static boolean linking(NetworkInfo.DetailedState d) {
        if (d == null) return false;
        switch (d) {
            case CONNECTING:
            case AUTHENTICATING:
            case OBTAINING_IPADDR:
            case VERIFYING_POOR_LINK:
            case CAPTIVE_PORTAL_CHECK:
                return true;
            default:
                return false;
        }
    }

    static boolean busy(boolean pending, int radio, boolean linking) {
        if (pending) return true;
        if (radio == WifiManager.WIFI_STATE_ENABLING) return true;
        return radio == WifiManager.WIFI_STATE_ENABLED && linking;
    }

    /** Whether the TV is now on what was asked for: any network after "Turn on", else that SSID. */
    static boolean reached(String target, String currentSsid) {
        if (target == null || currentSsid == null) return false;
        return target.isEmpty() || target.equals(currentSsid);
    }

    private boolean reached() {
        return reached(target, currentSsid());
    }

    @SuppressWarnings("deprecation")
    private String currentSsid() {
        try {
            WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
            if (info == null || info.getNetworkId() == -1 || info.getSupplicantState() != SupplicantState.COMPLETED) return null;
            return WifiPopup.unquote(info.getSSID());
        } catch (SecurityException e) {
            return null;
        }
    }

    /** The network the framework is joining by itself (known from association on), or null. */
    @SuppressWarnings("deprecation")
    String joining() {
        try {
            WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
            String ssid = info != null ? WifiPopup.unquote(info.getSSID()) : null;
            return ssid == null || ssid.isEmpty() ? null : ssid;
        } catch (SecurityException e) {
            return null;
        }
    }

    // ---- the owner's order ----------------------------------------------------------------------------

    /** After a scan: moves to the best-ranked saved network in range, unless something is already under way. */
    @SuppressWarnings("deprecation")
    private void applyOrder() {
        List<String> order = order();
        if (order.isEmpty() || target != null || wifi == null || !wifi.isWifiEnabled() || !available()) return;
        String current = currentSsid();
        if (chosen != null && !chosen.equals(current)) chosen = null;   // the owner's pick holds only while on it
        if (chosen != null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastMove < MOVE_SPACING_MS) return;
        List<ScanResult> results;
        try {
            results = wifi.getScanResults();
        } catch (SecurityException e) {
            return;
        }
        // Keyed by the order's entries (its spelling may use ' where the TV's has ’); onTv: the TV's own name.
        Map<String, Integer> inRange = new HashMap<>();
        Map<String, Integer> security = new HashMap<>();
        Map<String, String> onTv = new HashMap<>();
        for (ScanResult r : results != null ? results : Collections.<ScanResult>emptyList()) {
            String entry = WifiPriority.entry(order, r.SSID);
            if (entry == null) continue;
            Integer had = inRange.get(entry);
            if (had == null || r.level > had) {
                inRange.put(entry, r.level);
                security.put(entry, WifiPopup.security(r.capabilities));
                onTv.put(entry, r.SSID);
            }
        }
        Set<String> aside = new HashSet<>();
        for (Map.Entry<String, Long> e : asideUntil.entrySet()) {
            if (e.getValue() <= now) continue;
            String entry = WifiPriority.entry(order, e.getKey());
            aside.add(entry != null ? entry : e.getKey());
        }
        String here = WifiPriority.entry(order, current);
        String pick = WifiPriority.pick(order, here != null ? here : current, inRange, aside);
        if (pick == null) return;
        String ssid = onTv.get(pick);
        if (ssid == null) return;
        lastMove = now;
        start(ssid, PATIENCE_MS, null, true);
        Integer sec = security.get(pick);
        send(new Intent(CONNECT).putExtra("ssid", ssid).putExtra("security", sec != null ? sec : -1),
                () -> setAside(ssid, FAILED_PAUSE_MS));
    }

    private void setAside(String ssid, long pause) {
        asideUntil.put(ssid, SystemClock.elapsedRealtime() + pause);
    }

    private void checkLater(String ssid) {
        checking = ssid;
        handler.removeCallbacks(checkInternet);
        handler.postDelayed(checkInternet, VALIDATION_MS);
    }

    /** A move made by the order, 40 s on: no internet there, so set it aside and apply the order without it. */
    private void checkInternet() {
        String ssid = checking;
        checking = null;
        if (ssid == null || !ssid.equals(currentSsid())) return;
        if (wifiValidated()) {
            noInternet.remove(ssid);
            return;
        }
        Integer n = noInternet.get(ssid);
        int times = n == null ? 1 : n + 1;
        noInternet.put(ssid, times);
        setAside(ssid, WifiPriority.noInternetPause(times));
        lastMove = -MOVE_SPACING_MS;
        applyOrder();
    }

    private boolean wifiValidated() {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return true;
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return true;
        }
        return false;
    }

    // ---- requests -------------------------------------------------------------------------------------

    private void start(String ssid, long patience, Runnable refusedFallback, boolean fromOrder) {
        target = ssid;
        byOrder = fromOrder;
        onRefused = refusedFallback;
        deadline = SystemClock.elapsedRealtime() + patience;
        handler.removeCallbacks(expire);
        handler.postDelayed(expire, patience);
        onChange.run();
    }

    private void finish() {
        target = null;
        byOrder = false;
        onRefused = null;
        handler.removeCallbacks(expire);
    }

    private void expire() {
        if (target == null) return;
        if (SystemClock.elapsedRealtime() < deadline) {
            handler.postDelayed(expire, deadline - SystemClock.elapsedRealtime());
            return;
        }
        String was = target;
        boolean fromOrder = byOrder;
        boolean ok = reached();
        finish();
        if (!ok && fromOrder) setAside(was, FAILED_PAUSE_MS);
        else if (!ok && !was.isEmpty()) toast(context.getString(R.string.wifi_cant_connect, was));
        onChange.run();
    }

    /** The saved password did not work: TvSettings' flow asks for it (it sees the entry as rejected). */
    private void refused() {
        Runnable fallback = onRefused;
        String was = target;
        if (byOrder) setAside(was, FAILED_PAUSE_MS);
        finish();
        toast(context.getString(R.string.wifi_password_refused, was));
        if (fallback != null) fallback.run();
    }

    private void send(Intent intent, Runnable fallback) {
        intent.setComponent(new ComponentName(HELPER, COMMAND)).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        String asked = target;
        BroadcastReceiver answer = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (getResultCode() == OK) return;
                // Not this request any more (another was made since): leave that one alone.
                if (asked != null && asked.equals(target)) finish();
                onChange.run();
                if (fallback != null) fallback.run();
            }
        };
        try {
            context.sendOrderedBroadcast(intent, null, answer, handler, NO_HELPER, null, null);
        } catch (RuntimeException e) {
            finish();
            onChange.run();
            if (fallback != null) fallback.run();
        }
    }

    private void toast(String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
