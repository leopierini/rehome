package io.github.leopierini.rehome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Wi-Fi pill's dropdown: nearby networks (strongest first, one row per SSID, a lock on secured ones, the
 * connected one marked and listed first) and a last row for the full Wi-Fi settings. Looks like the Inputs list:
 * same glass panel (popup_inputs) and row style (wifi_item is item_input plus a lock).
 *
 * <p>The networks scroll ({@link DropdownList}) over a footer that stays put: "More Wi-Fi settings" and the data
 * line. The list is only as tall as the screen allows under the pill ({@link DropdownFit}), so with many networks in
 * range the panel ends above the bottom edge instead of running the data line off screen; with a few it looks as it
 * did before. D-pad: the list moves row by row and scrolls with the focus (ScrollView); Down from its last row goes
 * to the footer; in the footer Up and Down are handled here ({@link #footerKey}), because the rows scrolled out of
 * view still count for Android's focus search and some lie behind or below the footer.
 *
 * <p>One press: "Turn on" switches Wi-Fi on in place (the list fills as the scan comes, focus on the first
 * network) and a saved network is joined at once, the list closing and the pill spinning until the TV is on it
 * ({@link WifiLink}, through the companion app io.github.leopierini.rehome.wifi). What follows is the fallback, for a network
 * with no saved entry or without the companion app.
 *
 * <p>Picking a network opens TvSettings' own connection flow, com.android.tv.settings/.connectivity
 * .WifiConnectionActivity (exported, no permission), with the extras its createIntent() sets: "wifi_ssid" (plain
 * SSID) and "wifi_security_name" (a security constant, see {@link #security}). That flow asks for the password
 * only for a network it does not find saved (or whose saved password was rejected); a saved one gets a single
 * "Connect" step. After connecting it shows "Connected successfully" for 3 s and closes by itself, back to Home.
 * On this firmware (PH1M Android 11 TvSettingsTPV) it only ever finds saved WPA2 (PSK), WEP and open entries:
 * see {@link #security}.
 * The Philips wizard (org.droidtv.networkwizard, what android.settings.WIFI_SETTINGS opens on PH1M firmware)
 * is avoided: it never looks at saved networks, so it asks for the password every time and ends on a
 * "connected" screen that waits for OK.
 *
 * <p>Needs ACCESS_FINE_LOCATION (granted with pm grant) to read scan results and the current SSID, and
 * CHANGE_WIFI_STATE for the fresh scan started on open; without it the list shows the last cached scan.
 * {@code <package android:name="com.android.tv.settings" />} in the manifest's queries lets it check that the
 * TvSettings activities exist; when they don't, it falls back to the standard Wi-Fi settings.
 */
final class WifiPopup {

    /** What the popup borrows from MainActivity so it looks and closes like the other dropdowns. */
    interface Host {
        /** MainActivity.glassPopup: a focusable PopupWindow that Back dismisses, tracked as the open popup. */
        PopupWindow glassPopup(View panel, int width);

        /** MainActivity.dropIn under the anchor pill, with the alignment the Inputs list uses. */
        void dropIn(PopupWindow popup, View panel, View anchor);

        /** MainActivity.open: starts the intent in a new task, a toast when nothing handles it. */
        void open(Intent intent);

        /** The standard Wi-Fi settings with the launcher's vendor fallback (SystemSettings). */
        void openWifiSettings();

        /**
         * WifiLink: switches Wi-Fi on with no screen. False when it cannot (no companion app): the caller opens
         * the settings. The fallback runs if the companion app fails later.
         */
        default boolean quickEnable(Runnable fallback) {
            return false;
        }

        /** WifiLink: joins the saved network with no screen; false or the fallback as for quickEnable. */
        default boolean quickConnect(String ssid, int security, Runnable fallback) {
            return false;
        }

        /** The SSID WifiLink is joining ("" switching on), or null. */
        default String connectingTo() {
            return null;
        }

        /** Whether the TV has that network saved (WifiLink, from the companion app). */
        default boolean isSaved(String ssid) {
            return false;
        }

        /**
         * Reads the saved networks again unless read less than {@code maxAgeMs} ago; {@code changed} runs each
         * time the list differs from what isSaved said, until called again with null.
         */
        default void watchSaved(Runnable changed, long maxAgeMs) {
        }

        /** The network's place in the owner's order (WifiOrderStore, else config.xml wifi_priority); MAX_VALUE when not in it. */
        default int rank(String ssid) {
            return Integer.MAX_VALUE;
        }

        /** The data meter's line for the bottom of the list (DataMeter.line), or null for none. */
        default CharSequence dataLine() {
            return null;
        }

        /** Whether OK on that line opens the meter's detail; otherwise the line is not a stop. */
        default boolean dataDetail() {
            return false;
        }

        /** OK on that line: the meter's detail, in place of this list. */
        default void openData() {
        }
    }

    private static final String TV_SETTINGS = "com.android.tv.settings";
    private static final String CONNECT_ACTIVITY = TV_SETTINGS + ".connectivity.WifiConnectionActivity";
    private static final String NETWORK_ACTIVITY = TV_SETTINGS + ".connectivity.NetworkActivity";
    private static final String EXTRA_SSID = "wifi_ssid";
    private static final String EXTRA_SECURITY = "wifi_security_name";

    // com.android.settingslib.wifi.AccessPoint security constants, the values of the wifi_security_name extra.
    static final int SECURITY_NONE = 0;
    static final int SECURITY_WEP = 1;
    static final int SECURITY_PSK = 2;
    static final int SECURITY_EAP = 3;
    static final int SECURITY_OWE = 4;
    static final int SECURITY_SAE = 5;

    /** Rows of networks at most: the strongest; the list scrolls past the rows the screen has room for. */
    private static final int MAX_NETWORKS = 8;
    /** Weaker than this a TV rarely holds a connection; the current network is listed whatever its level. */
    private static final int MIN_RSSI = -88;

    /** Tags of the rows that are not networks, so a refresh keeps focus on them too (an SSID is never empty). */
    private static final String TAG_MORE = "", TAG_ACTION = " ";
    private static final String TAG_DATA = "  ";

    private final Activity activity;
    private final ViewGroup parent;
    private final Host host;
    private final float density;
    private final WifiManager wifi;

    private PopupWindow popup;
    private View anchor;
    /** popup_inputs' box: the list over the footer. */
    private LinearLayout rows;
    /** The networks, or the one row that stands for them (Wi-Fi off, turning on, searching, no list). */
    private DropdownList scroll;
    private LinearLayout list;
    /** "More Wi-Fi settings" and the data line, always on screen under the list. */
    private LinearLayout footer;
    /** The rows' width in the panel (before the first layout too). */
    private int rowsWidth;
    /**
     * popup_inputs' padding (6 dp), as wide as a focused row's glow (input_row_bg's 6 dp spread): the list is laid
     * out that far into the padding on every side and pads its rows by as much, so the glow has room inside its clip.
     */
    private int inset;
    /** The tag of the list row the focus was last on: Up from the footer goes back to it while it is in view. */
    private String lastListTag;
    /** What the rows show now: a scan that changes nothing does not rebuild them. */
    private String shown;
    /** "Turn on" pressed: focus goes to the first network once the list has one. */
    private boolean focusNetworks;

    WifiPopup(Activity activity, ViewGroup parent, Host host) {
        this.activity = activity;
        this.parent = parent;
        this.host = host;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.wifi = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    /** One SSID as listed: its strongest access point. */
    static final class Network {
        final String ssid;
        final int rssi;
        final int security;
        final boolean current;
        /** A saved network: "Saved" in place of the lock, listed right after the connected one. */
        boolean saved;
        /** Place in the owner's order (WifiPriority): the saved networks are listed by it. */
        int rank = Integer.MAX_VALUE;

        Network(String ssid, int rssi, int security, boolean current) {
            this.ssid = ssid;
            this.rssi = rssi;
            this.security = security;
            this.current = current;
        }

        boolean secured() {
            return security != SECURITY_NONE && security != SECURITY_OWE;
        }
    }

    /** @param onData focus starts on the data line (back from its detail: Back goes up one level at a time). */
    void show(View anchor, boolean onData) {
        LayoutInflater inflater = activity.getLayoutInflater();
        View panel = inflater.inflate(R.layout.popup_inputs, parent, false);
        rows = panel.findViewById(R.id.rows);
        int width = Math.max(anchor.getWidth(), (int) (280 * density));
        this.anchor = anchor;
        rowsWidth = width - panel.getPaddingLeft() - panel.getPaddingRight();
        inset = panel.getPaddingTop();
        buildBoxes();
        popup = host.glassPopup(panel, width);
        shown = null;
        fill(null);

        // A fresh scan while the list is open; rows follow the results. Registered on the panel's lifetime so
        // MainActivity's own dismiss listener on the popup stays untouched.
        BroadcastReceiver scans = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (popup == null || !popup.isShowing()) return;
                // The saved list again, at most every 3 s: right after a boot Android can list only part of it,
                // and a network joined through TvSettings' password screen is saved from then on.
                host.watchSaved(WifiPopup.this::refill, 3_000);
                refill();
            }
        };
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
                // Wi-Fi switching on from the list, and the connected network changing under it.
                filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
                filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
                if (Build.VERSION.SDK_INT >= 33) {
                    activity.registerReceiver(scans, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    activity.registerReceiver(scans, filter);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                try {
                    activity.unregisterReceiver(scans);
                } catch (IllegalArgumentException ignored) {
                }
                host.watchSaved(null, 0);
                v.removeOnAttachStateChangeListener(this);
            }
        });
        startScan();
        // The saved networks, from the companion app: the rows are rebuilt if they differ from the last list.
        host.watchSaved(this::refill, 0);

        host.dropIn(popup, panel, anchor);
        focusFirst(onData ? TAG_DATA : null);
    }

    /**
     * The list (scrolling) and the footer under it, in popup_inputs' box. The list sits {@link #inset} out on
     * every side, into the panel's padding (negative margins), and pads its rows by as much: the rows stand where they
     * always stood, the separator under the list too, and a focused row's glow fits inside the list's clip.
     */
    private void buildBoxes() {
        scroll = new DropdownList(activity, inset, dp(DropdownFit.FADE_DP));
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        list.setClipToPadding(false);
        list.setPadding(inset, inset, inset, inset);
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(-inset, -inset, -inset, -inset);
        rows.addView(scroll, lp);
        footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setClipChildren(false);
        footer.setClipToPadding(false);
        rows.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * The list's height for what it holds now: all of it when it fits above the screen's safe bottom margin with the
     * footer under it, else as much as fits ({@link DropdownFit}). Measured here, before the panel's own layout, so
     * the popup never shows a frame too tall.
     */
    private void fit() {
        footer.measure(View.MeasureSpec.makeMeasureSpec(rowsWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        list.measure(View.MeasureSpec.makeMeasureSpec(rowsWidth + 2 * inset, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int contentH = list.getMeasuredHeight();
        int rowH = list.getChildCount() > 0 ? list.getChildAt(0).getMeasuredHeight() : 0;
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int top = at[1] + anchor.getHeight() + (int) (MainActivity.DROP_GAP_DP * density);   // as dropIn places it
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        // The panel is the list and the footer: its padding is taken back by the list's negative margins.
        int room = DropdownFit.room(screenH, top, dp(DropdownFit.BOTTOM_MARGIN_DP), footer.getMeasuredHeight());
        int h = DropdownFit.listHeight(room, contentH, inset, rowH);
        scroll.setMaxHeight(h < contentH ? h : 0);
    }

    /**
     * Up and Down in the footer. Down moves to the next stop and stops at the last; Up moves to the stop above, and
     * from the first one back into the list ({@link DropdownFit#upTarget}): the row the focus came from, or the last
     * one in view. Android's own search would also see the rows scrolled out of view, some of them behind the footer
     * or below it. With no row to go to Up is left to MainActivity, which closes the dropdown (DropdownKeys).
     */
    private boolean footerKey(View v, int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        boolean press = event.getAction() == KeyEvent.ACTION_DOWN;
        List<View> stops = stops(footer);
        int i = stops.indexOf(v);
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (press && i >= 0 && i + 1 < stops.size()) stops.get(i + 1).requestFocus(View.FOCUS_DOWN);
            return true;
        }
        if (i > 0) {
            if (press) stops.get(i - 1).requestFocus(View.FOCUS_UP);
            return true;
        }
        List<View> networks = stops(list);
        int[] tops = new int[networks.size()], bottoms = new int[networks.size()];
        int remembered = -1;
        for (int k = 0; k < networks.size(); k++) {
            View row = networks.get(k);
            tops[k] = row.getTop();
            bottoms[k] = row.getBottom();
            if (lastListTag != null && lastListTag.equals(row.getTag())) remembered = k;
        }
        int target = DropdownFit.upTarget(remembered, tops, bottoms, scroll.getScrollY(), scroll.getHeight());
        if (target < 0) return false;
        if (press) networks.get(target).requestFocus(View.FOCUS_UP);
        return true;
    }

    /** The focusable rows of a box, top to bottom. */
    private static List<View> stops(LinearLayout box) {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < box.getChildCount(); i++) {
            View child = box.getChildAt(i);
            if (child.isFocusable() && child.getVisibility() == View.VISIBLE) out.add(child);
        }
        return out;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    /** Rebuilds the rows keeping the focus where it is (a scan, the saved list coming in). */
    private void refill() {
        if (popup == null || !popup.isShowing()) return;
        View focused = rows.findFocus();
        Object key = focused != null ? focused.getTag() : null;
        fill(key instanceof String ? (String) key : null);
    }

    @SuppressWarnings("deprecation")
    private void startScan() {
        try {
            if (wifi != null && wifi.isWifiEnabled()) wifi.startScan();
        } catch (SecurityException ignored) {
            // No CHANGE_WIFI_STATE: the cached results are listed, and the system's own scans update them.
        }
    }

    /** (Re)builds the rows; keeps focus on the row whose SSID was focused. */
    @SuppressWarnings("deprecation")
    private void fill(String focusSsid) {
        boolean enabled = wifi != null && wifi.isWifiEnabled();
        List<Network> networks = enabled ? networks() : Collections.<Network>emptyList();
        boolean canList = !enabled || canListNetworks();
        boolean turningOn = !enabled && wifi != null && wifi.getWifiState() == WifiManager.WIFI_STATE_ENABLING;
        String joining = host.connectingTo();
        // Scan results arrive every few seconds while the list is open; the same networks at the same strength
        // keep the rows (and the focus) as they are.
        StringBuilder state = new StringBuilder().append(enabled).append(canList).append(turningOn).append('|')
                .append(joining);
        for (Network n : networks) {
            state.append('\n').append(n.ssid).append('|').append(WifiManager.calculateSignalLevel(n.rssi, 4))
                    .append('|').append(n.security).append('|').append(n.current).append('|').append(n.saved);
        }
        // The data row too: it comes with the meter's first reading and changes as the day's use grows.
        state.append('\n').append(host.dataLine()).append('|').append(host.dataDetail());
        if (state.toString().equals(shown)) return;
        shown = state.toString();
        list.removeAllViews();
        footer.removeAllViews();
        if (turningOn || (!enabled && "".equals(joining))) {
            // Switching on: the row keeps the focus until the networks come.
            View row = addRow(list, R.drawable.ic_wifi, activity.getString(R.string.wifi_turning_on), false, false, null,
                    0.5f);
            row.setTag(TAG_ACTION);
        } else if (!enabled) {
            View row = addRow(list, R.drawable.ic_wifi_off, activity.getString(R.string.wifi_off), false, false,
                    activity.getString(R.string.wifi_turn_on), 1f);
            row.setTag(TAG_ACTION);
            row.setOnClickListener(v -> {
                // One press, in place: the list stays open and fills with the networks.
                Runnable fallback = () -> {
                    if (popup.isShowing()) popup.dismiss();
                    openSettings();
                };
                if (host.quickEnable(fallback)) {
                    focusNetworks = true;
                    fill(TAG_ACTION);
                } else {
                    popup.dismiss();
                    openSettings();
                }
            });
        } else {
            if (networks.isEmpty() && !canList) {
                // Without the location permission (lost on a reinstall) or with location off, Android hides the
                // scan results from every app: say so instead of "searching" for ever.
                View row = addRow(list, R.drawable.ic_wifi_off, activity.getString(R.string.wifi_no_list), false, false,
                        activity.getString(R.string.wifi_open_settings), 1f);
                row.setTag(TAG_ACTION);
                row.setOnClickListener(v -> {
                    popup.dismiss();
                    openSettings();
                });
            } else if (networks.isEmpty()) {
                View row = addRow(list, R.drawable.ic_wifi, activity.getString(R.string.wifi_searching), false, false, null, 0.5f);
                if (focusNetworks) {
                    // Just switched on from here: the focus waits on this row for the first network, not on
                    // "Altre impostazioni" below, where a second OK would open the settings.
                    row.setTag(TAG_ACTION);
                } else {
                    row.setFocusable(false);
                    row.setClickable(false);
                }
            }
            for (Network n : networks) {
                int level = WifiManager.calculateSignalLevel(n.rssi, 4);   // 0..3
                boolean joiningThis = n.ssid.equals(joining);
                View row = addRow(list, R.drawable.ic_wifi, n.ssid, showsLock(n.secured(), n.current, n.saved), n.current,
                        status(Words.of(activity.getResources()), n.current, n.saved, joiningThis), 0.55f + 0.15f * level);
                row.setTag(n.ssid);
                row.setOnClickListener(v -> {
                    popup.dismiss();
                    // A saved network is joined with no screen (the pill spins); TvSettings' flow otherwise.
                    if (!n.current && !joiningThis && !host.quickConnect(n.ssid, n.security, () -> connect(n))) connect(n);
                });
            }
        }
        addSeparator(footer);
        View more = addRow(footer, R.drawable.ic_settings, activity.getString(R.string.wifi_more), false, false, null, 1f);
        more.setTag(TAG_MORE);
        more.setOnClickListener(v -> {
            popup.dismiss();
            openSettings();
        });
        // At the bottom, the hotspot's data use (DataMeter): a statistic for the curious, not a header item.
        CharSequence data = host.dataLine();
        if (data != null) {
            addSeparator(footer);
            View row = addRow(footer, R.drawable.ic_swap_vert, data.toString(), false, false, null, 0.7f);
            row.setTag(TAG_DATA);
            if (host.dataDetail()) {
                row.setOnClickListener(v -> {
                    popup.dismiss();
                    host.openData();
                });
            } else {
                row.setFocusable(false);
                row.setClickable(false);
            }
        }
        if (focusNetworks && !networks.isEmpty()) {
            focusNetworks = false;
            focusSsid = networks.get(0).ssid;
        }
        for (View stop : stops(list)) {
            stop.setOnFocusChangeListener((v, has) -> {
                if (has) lastListTag = (String) v.getTag();
            });
        }
        for (View stop : stops(footer)) stop.setOnKeyListener(this::footerKey);
        fit();
        if (popup.isShowing()) focusFirst(focusSsid);
    }

    /** Scan results and the network's name need the location permission and location switched on (Android 10+). */
    private boolean canListNetworks() {
        if (activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT < 28) return true;
        android.location.LocationManager lm =
                (android.location.LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
        return lm == null || lm.isLocationEnabled();
    }

    private void focusFirst(String ssid) {
        View first = null;
        List<View> all = stops(list);
        all.addAll(stops(footer));
        for (View child : all) {
            if (first == null) first = child;
            if (ssid != null && ssid.equals(child.getTag())) {
                child.requestFocus();
                return;
            }
        }
        if (first != null) first.requestFocus();
    }

    /** Nearby networks, one per SSID: the connected one first, then strongest first. */
    @SuppressWarnings("deprecation")
    private List<Network> networks() {
        String currentSsid = null;
        int currentRssi = -60;
        try {
            WifiInfo info = wifi.getConnectionInfo();
            if (info != null && info.getNetworkId() != -1) {
                currentSsid = unquote(info.getSSID());
                currentRssi = info.getRssi();
            }
        } catch (SecurityException ignored) {
        }
        List<ScanResult> results;
        try {
            results = wifi.getScanResults();
        } catch (SecurityException e) {
            results = Collections.emptyList();
        }
        Map<String, Network> bySsid = new HashMap<>();
        for (ScanResult r : results != null ? results : Collections.<ScanResult>emptyList()) {
            String ssid = r.SSID;
            if (TextUtils.isEmpty(ssid)) continue;   // hidden networks
            boolean current = ssid.equals(currentSsid);
            if (!current && r.level < MIN_RSSI) continue;
            Network old = bySsid.get(ssid);
            if (old == null || r.level > old.rssi) {
                bySsid.put(ssid, new Network(ssid, r.level, security(r.capabilities), current));
            }
        }
        List<Network> list = new ArrayList<>(bySsid.values());
        for (Network n : list) {
            n.saved = host.isSaved(n.ssid);
            n.rank = host.rank(n.ssid);
        }
        Collections.sort(list, WifiPopup::order);
        if (currentSsid != null && !currentSsid.isEmpty() && !bySsid.containsKey(currentSsid)) {
            list.add(0, new Network(currentSsid, currentRssi, SECURITY_PSK, true));
        }
        return list.size() > MAX_NETWORKS ? list.subList(0, MAX_NETWORKS) : list;
    }

    /**
     * The wifi_security_name to send for a scan result so that WifiConnectionActivity finds the saved entry.
     *
     * <p>TvSettingsTPV (PH1M, Android 11) looks the network up in WifiConfigHelper.getFromConfiguredNetworks: the
     * first WifiManager.getConfiguredNetworks() entry whose WifiInfo.sanitizeSsid(SSID) equals wifi_ssid AND whose
     * WifiSecurityUtil.getSecurity(WifiConfiguration) equals wifi_security_name. That method only knows WPA_PSK -> 2,
     * WPA_EAP/IEEE8021X -> 3, a WEP key -> 1, anything else -> 0, so a saved SAE (WPA3) or OWE entry reads as 0
     * and a 5 (SAE) or 4 (OWE) never matches anything. Hence:
     * <ul>
     * <li>WPA2/WPA3 transition APs (PSK and SAE in the capabilities) send PSK, not SAE as AccessPoint would:
     * it matches the saved WPA2 entry (iPhone hotspots are saved both ways), and where only a WPA3 entry exists
     * the password asked once creates a WPA2 entry that later switches find.</li>
     * <li>WPA3-only APs send SAE: TvSettings asks for the password and updates the saved SAE entry. Sending 0 would
     * match that entry but connect it as open (KeyMgmt NONE+SAE), which the framework rejects.</li>
     * <li>OWE transition APs send NONE, matching a saved open entry.</li>
     * </ul>
     */
    static int security(String caps) {
        if (caps == null) return SECURITY_NONE;
        if (caps.contains("WEP")) return SECURITY_WEP;
        if (caps.contains("PSK")) return SECURITY_PSK;   // WPA/WPA2, and WPA2/WPA3 transition mode
        if (caps.contains("SAE")) return SECURITY_SAE;   // WPA3-only
        if (caps.contains("EAP")) return SECURITY_EAP;   // includes EAP_SUITE_B_192
        if (caps.contains("OWE_TRANSITION")) return SECURITY_NONE;
        if (caps.contains("OWE")) return SECURITY_OWE;
        return SECURITY_NONE;
    }

    static String unquote(String ssid) {
        if (ssid == null) return null;
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) ssid = ssid.substring(1, ssid.length() - 1);
        // WifiManager.UNKNOWN_SSID without location access, "0x" for a non-UTF-8 one.
        return ssid.equals("<unknown ssid>") || ssid.equals("0x") ? null : ssid;
    }

    /** TvSettings' connection flow for that SSID; the standard Wi-Fi settings where it is missing. */
    private void connect(Network n) {
        Intent i = new Intent()
                .setComponent(new ComponentName(TV_SETTINGS, CONNECT_ACTIVITY))
                .putExtra(EXTRA_SSID, n.ssid)
                .putExtra(EXTRA_SECURITY, n.security)
                // A password screen left in TvSettings' task (Home pressed on it) came back after this one
                // closed, asking again for a network already joined. A fresh task each time.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (exists(i)) host.open(i);
        else host.openWifiSettings();
    }

    /**
     * Whether another app's activity is there to start. Not Intent.resolveActivity: for an intent that names its
     * component it answers with that component without looking, so the fallback never ran.
     */
    private boolean exists(Intent i) {
        android.content.pm.ResolveInfo found = activity.getPackageManager().resolveActivity(i, 0);
        return found != null && found.activityInfo != null && found.activityInfo.exported;
    }

    /** TvSettings' network page (it knows the saved networks), else the standard Wi-Fi settings. */
    private void openSettings() {
        Intent i = new Intent().setComponent(new ComponentName(TV_SETTINGS, NETWORK_ACTIVITY));
        if (exists(i)) host.open(i);
        else host.openWifiSettings();
    }

    /**
     * A lock on a secured network the TV is not on. Not on the one it is on: next to "Connected" it read as "locked,
     * needs a password". Not on a saved one either: "Saved" says it needs no password.
     */
    static boolean showsLock(boolean secured, boolean current, boolean saved) {
        return secured && !current && !saved;
    }

    /** The row's grey word: joining it, on it, saved, or nothing. */
    static String status(Words w, boolean current, boolean saved, boolean joining) {
        if (joining) return w.get(R.string.wifi_connecting);
        if (current) return w.get(R.string.wifi_connected);
        return saved ? w.get(R.string.wifi_saved) : null;
    }

    /**
     * The connected network first, then the saved ones in the owner's order (WifiOrderStore), then the rest;
     * strongest first otherwise.
     */
    static int order(Network a, Network b) {
        if (a.current != b.current) return a.current ? -1 : 1;
        if (a.saved != b.saved) return a.saved ? -1 : 1;
        if (a.saved && a.rank != b.rank) return Integer.compare(a.rank, b.rank);
        return Integer.compare(b.rssi, a.rssi);
    }

    private View addRow(LinearLayout into, int icon, String title, boolean lock, boolean current, String status,
            float iconAlpha) {
        View row = activity.getLayoutInflater().inflate(R.layout.wifi_item, into, false);
        ImageView iconView = row.findViewById(R.id.icon);
        iconView.setImageResource(icon);
        iconView.setAlpha(iconAlpha);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.lock).setVisibility(lock ? View.VISIBLE : View.GONE);
        row.findViewById(R.id.dot).setVisibility(current ? View.VISIBLE : View.GONE);
        TextView statusView = row.findViewById(R.id.status);
        if (status != null) statusView.setText(status);
        else statusView.setVisibility(View.GONE);
        into.addView(row);
        return row;
    }

    /** Same hairline as MainActivity.addSeparator. */
    private void addSeparator(LinearLayout into) {
        View line = new View(activity);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, (int) density));
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        into.addView(line, lp);
    }
}
