package io.github.leopierini.rehome;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the TV used of the hotspot today, as the last row of the Wi-Fi dropdown ("Today 2.3 GB", the
 * owner's call: it is no longer a header item), and on OK a dropdown with today, this month, today's top three apps
 * and the split per hotspot.
 *
 * <p>Sources. With the usage-stats app-op (over ADB: {@code appops set io.github.leopierini.rehome GET_USAGE_STATS
 * allow}): NetworkStatsManager's Wi-Fi totals for today and the calendar month, and today's per-UID summary for the
 * apps. Without it: only TrafficStats' total since the last real boot ("Since last restart 1.2 GB"), and no
 * dropdown. The split per hotspot is the launcher's own estimate (DataLedger, saved in {@value #FILE_NAME}): the
 * public API on Android 11 cannot split by Wi-Fi name. Whatever it could not attribute shows as "Unattributed".
 *
 * <p>Cost: no network traffic of its own. Every query runs on its own background thread; the numbers are read on
 * Home resume and every {@link #REFRESH_MS} while Home stays in front, never while it is paused, and only when the TV
 * used about a megabyte since the last reading (DataUsage.needsRead): each query makes the system persist netstats.
 * The ledger also samples (two counters and the Wi-Fi name, no query) at each network change, when Home goes, and when
 * the screen goes off or on: standby from inside a streaming app is then a sample boundary, and the night's gap does
 * not carry the evening's film into the morning.
 */
final class DataMeter {

    private static final String TAG = "Rehome.DataMeter";
    static final String FILE_NAME = "data_ledger.txt";
    static final long REFRESH_MS = 5 * 60 * 1000L;
    private static final int TOP_APPS = 3;
    private static final int MAX_NETWORK_ROWS = 3;

    /** One reading, built on the background thread and shown on the main one. */
    private static final class Reading {
        boolean full;
        long sinceBoot, today, month;
        /** TestHooks.meter: the dropdown row's text instead of the real one, read off the main thread. */
        String test;
        /** Today's bytes per UID; named into {@link #apps} only when the dropdown that shows them may open. */
        final Map<Integer, Long> uids = new HashMap<>();
        /**
         * Today's bytes per app name (DataUsage.add, capAt), or null until {@link #nameApps} ran. Naming loads each
         * app's resources for its label: about 20 APKs mapped (+30 MB clean, +10 MB native heap for a few seconds)
         * and their parsing on the IO thread at every Home start (Task 7). Only the detail shows names, so they are
         * made when the Wi-Fi dropdown with the meter's line opens, one OK before the detail.
         */
        volatile Map<String, Long> apps;
        final LinkedHashMap<String, Long> netToday = new LinkedHashMap<>(), netMonth = new LinkedHashMap<>();
    }

    private final Activity act;
    private final Context app;
    private final float d;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /**
     * One thread and one ledger for the whole process: a recreate() makes a new Home while the old one's last sample
     * may still be queued, and two ledgers on one file would lose an update (AtomicFile read during a write).
     */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, TAG));
    private final File file;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (destroyed) return;
            // Kept open (goAsync) until the sample is saved: the broadcast's hold on suspend lasts that long, so a TV
            // put to sleep from inside an app still records the evening on the evening (re-review N1), not at 08:00.
            PendingResult done = goAsync();
            try {
                IO.execute(() -> {
                    try {
                        sample();
                    } finally {
                        done.finish();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                done.finish();
            }
        }
    };

    // Background thread only.
    private static DataLedger ledger;
    private final Map<Integer, String> names = new HashMap<>();
    /** The last reading that asked NetworkStatsManager, and the counter and day it was taken at. */
    private Reading lastRead;
    private long lastReadCounter = -1;
    private int lastReadDay;

    // Main thread.
    private Reading shown;
    private boolean resumed;
    private volatile boolean destroyed;

    DataMeter(Activity act) {
        this.act = act;
        this.app = act.getApplicationContext();
        d = act.getResources().getDisplayMetrics().density;
        file = new File(act.getFilesDir(), FILE_NAME);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
            else app.registerReceiver(screen, f);
        } catch (RuntimeException e) {
            Log.w(TAG, "screen receiver: " + e);
        }
    }

    /**
     * (Owner's call, Task 5) The meter's line for the bottom of the Wi-Fi dropdown, where it lives instead of the
     * header: only "Today 843 MB" (the top apps and the rest are one OK away, in the detail), "Since last restart
     * 1.0 GB" without the app-op, or null when there is nothing to show yet.
     */
    CharSequence line() {
        Reading r = shown;
        if (r == null) return null;
        // The dropdown with this line is opening: name today's apps now, so the detail (one OK away) has them.
        if (r.full && r.apps == null) run(() -> nameApps(r));
        String text = r.test != null ? r.test
                : r.full ? DataUsage.header(words(), r.today, null, 0) : DataUsage.sinceBoot(words(), r.sinceBoot);
        return text.isEmpty() ? null : text;
    }

    /** Whether OK on that line has a dropdown to open (only the full reading has one). */
    boolean hasDetail() {
        return shown != null && shown.full && resumed;
    }

    void onResume() {
        resumed = true;
        refresh();
    }

    void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        // Leaving Home, usually for an app that streams: the ledger notes the network it starts on.
        run(this::sample);
    }

    /** A network came or went (MainActivity's network callback): what came before goes to the old one. */
    void onNetworkChanged() {
        run(this::sample);
    }

    void onDestroy() {
        destroyed = true;
        resumed = false;
        handler.removeCallbacksAndMessages(null);
        try {
            app.unregisterReceiver(screen);
        } catch (RuntimeException ignored) {
        }
        // The shared thread stays: samples already queued (this Home's last pause) still run and save.
    }

    private final Runnable tick = this::refresh;

    private void refresh() {
        handler.removeCallbacks(tick);
        if (!resumed || destroyed) return;
        run(() -> {
            sample();
            Reading r = readOrKeep();
            if (!destroyed) handler.post(() -> show(r));
        });
        handler.postDelayed(tick, REFRESH_MS);
    }

    private void run(Runnable job) {
        if (destroyed) return;
        try {
            IO.execute(job);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    // ---- background ----------------------------------------------------------------------------------

    private static long counter() {
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        return rx < 0 || tx < 0 ? -1 : rx + tx;
    }

    /** Background thread: today's apps by name for a reading that has none yet (Reading.apps says why it waits). */
    private void nameApps(Reading r) {
        if (r.apps != null) return;
        Map<String, Long> apps = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> e : r.uids.entrySet()) DataUsage.add(apps, name(e.getKey()), e.getValue());
        // Per-UID buckets are 2 h and UTC-aligned: just after midnight an app could read more than the day.
        DataUsage.capAt(apps, r.today);
        r.apps = apps;
    }

    /** The ledger's next sample, saved when it changed. */
    private void sample() {
        try {
            if (ledger == null) ledger = load();
            long boot = Settings.Global.getLong(app.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            if (ledger.sample(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot, counter(), ssid(),
                    TimeZone.getDefault())) {
                save(ledger);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "sample: " + e);
        }
    }

    /** The Wi-Fi network now, or null (not on Wi-Fi, or the name hidden without the location permission). */
    private String ssid() {
        WifiManager wm = app.getSystemService(WifiManager.class);
        WifiInfo info = wm == null ? null : wm.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1) return null;
        String s = info.getSSID();
        if (s == null) return null;
        s = s.replace("\"", "").trim();
        return s.isEmpty() || s.contains("unknown ssid") || s.equals("0x") ? null : s;
    }

    /** A new reading, or the last full one with today's ledger when the TV used under a megabyte since. */
    private Reading readOrKeep() {
        long c = counter();
        int day = DataUsage.dayKey(System.currentTimeMillis(), TimeZone.getDefault());
        Reading last = lastRead;
        if (last != null && usageAllowed()
                && !DataUsage.needsRead(last.full, lastReadDay, day, lastReadCounter, c)) {
            Reading r = base(c);
            r.full = true;
            r.today = last.today;
            r.month = last.month;
            r.uids.putAll(last.uids);
            r.apps = last.apps;
            return r;
        }
        Reading r = read(c);
        lastRead = r.full ? r : null;
        lastReadCounter = c;
        lastReadDay = day;
        return r;
    }

    /** What every reading has: the counter since boot, the ledger's split, the test hook. */
    private Reading base(long counter) {
        Reading r = new Reading();
        r.sinceBoot = Math.max(0, counter);
        if (ledger != null) {
            r.netToday.putAll(ledger.today);
            r.netMonth.putAll(ledger.month);
        }
        r.test = TestHooks.meter(app);
        return r;
    }

    private Reading read(long counter) {
        Reading r = base(counter);
        if (!usageAllowed()) return r;
        try {
            NetworkStatsManager nsm = app.getSystemService(NetworkStatsManager.class);
            if (nsm == null) return r;
            long now = System.currentTimeMillis();
            TimeZone tz = TimeZone.getDefault();
            long dayStart = DataUsage.dayStart(now, tz), monthStart = DataUsage.monthStart(now, tz);
            r.today = bytes(nsm.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, null, dayStart, now));
            r.month = bytes(nsm.querySummaryForDevice(ConnectivityManager.TYPE_WIFI, null, monthStart, now));
            Map<Integer, Long> byUid = new HashMap<>();
            NetworkStats stats = nsm.querySummary(ConnectivityManager.TYPE_WIFI, null, dayStart, now);
            try {
                NetworkStats.Bucket b = new NetworkStats.Bucket();
                while (stats.hasNextBucket() && stats.getNextBucket(b)) {
                    // Foreground and background, metered or not, come as separate buckets of one UID.
                    Long was = byUid.get(b.getUid());
                    byUid.put(b.getUid(), (was == null ? 0 : was) + b.getRxBytes() + b.getTxBytes());
                }
            } finally {
                stats.close();
            }
            r.uids.putAll(byUid);
            r.full = true;
        } catch (SecurityException | android.os.RemoteException e) {
            Log.w(TAG, "network stats: " + e);
        } catch (RuntimeException e) {
            Log.w(TAG, "network stats: " + e);
        }
        return r;
    }

    private static long bytes(NetworkStats.Bucket b) {
        return b == null ? 0 : Math.max(0, b.getRxBytes() + b.getTxBytes());
    }

    /** The app-op set over ADB (PACKAGE_USAGE_STATS is an app-op permission, never granted otherwise). */
    @SuppressWarnings("deprecation")
    private boolean usageAllowed() {
        try {
            AppOpsManager ops = app.getSystemService(AppOpsManager.class);
            if (ops == null) return false;
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return app.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The name a UID's traffic shows under: Android itself as one line, else the app's label as its tile shows it
     * (AppLabel.clean). A UID several packages share goes under the one with a tile. Kept for the life of Home.
     */
    private String name(int uid) {
        String known = names.get(uid);
        if (known != null) return known;
        String n = DataUsage.group(uid);
        if (n == null) {
            PackageManager pm = app.getPackageManager();
            String[] pkgs = pm.getPackagesForUid(uid);
            Set<String> launchable = new HashSet<>();
            if (pkgs != null && pkgs.length > 1) {
                for (String p : pkgs) {
                    if (pm.getLeanbackLaunchIntentForPackage(p) != null || pm.getLaunchIntentForPackage(p) != null) {
                        launchable.add(p);
                    }
                }
            }
            String pkg = DataUsage.pickPackage(pkgs, launchable);
            n = DataUsage.OTHER;
            if (pkg != null) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    n = AppLabel.clean(String.valueOf(pm.getApplicationLabel(ai)), pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        names.put(uid, n);
        return n;
    }

    private DataLedger load() {
        if (file.exists()) {
            try {
                DataLedger l = DataLedger.decode(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
                if (l != null) return l;
                Log.w(TAG, FILE_NAME + " is not valid; starting a new one");
            } catch (IOException e) {
                Log.w(TAG, "cannot read " + FILE_NAME + ": " + e);
            }
        }
        return new DataLedger();
    }

    private void save(DataLedger l) {
        AtomicFile af = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            out.write(l.encode().getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + FILE_NAME + ": " + e);
            if (out != null) af.failWrite(out);
        }
    }

    // ---- on screen -----------------------------------------------------------------------------------

    /** The Wi-Fi dropdown reads it (line, hasDetail) the next time it fills. */
    private void show(Reading r) {
        if (destroyed) return;
        shown = r;
    }

    /**
     * The dropdown under {@code anchor} (the Wi-Fi pill whose list carries the meter's line): today, this month,
     * today's top apps, then per hotspot (an estimate). Closing it gives focus back to what had it.
     */
    void showDetail(View anchor, int gravity, Runnable back) {
        Reading r = shown;
        if (r == null || !r.full || !(act instanceof MainActivity) || !resumed) return;
        MainActivity host = (MainActivity) act;
        View panel = act.getLayoutInflater().inflate(R.layout.popup_inputs, (ViewGroup) anchor.getRootView(), false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = host.glassPopup(panel, Math.round(300 * d));

        Words w = words();
        row(rows, w.get(R.string.data_today_label), orDash(w, r.today), true);
        row(rows, w.get(R.string.data_month_label), orDash(w, r.month), true);
        // Not named yet (line() asked for it on the IO thread when the Wi-Fi list opened): the detail opens now with
        // its totals and a "One moment…" line, which the names replace when they are ready (fillApps).
        Map<String, Long> named = r.apps;
        List<String[]> apps = DataUsage.appLines(w, named, TOP_APPS);
        View appsCaption = null, naming = null;
        if (!apps.isEmpty()) {
            appsCaption = caption(rows, w.get(R.string.data_top_apps));
            for (String[] a : apps) naming = row(rows, a[0], a[1], false);
        }
        List<DataUsage.Net> nets = DataUsage.networks(r.netToday, r.netMonth, r.today, r.month, MAX_NETWORK_ROWS);
        if (!nets.isEmpty()) {
            caption(rows, w.get(R.string.data_by_network));
            for (DataUsage.Net n : nets) {
                row(rows, DataUsage.label(w, n.name), orDash(w, n.today) + "  ·  " + orDash(w, n.month), false);
            }
        }

        // Read-only: no row takes focus, the panel does. OK, Back and Up close it, back where it was opened: the
        // Wi-Fi dropdown, on its data line (back). Not when Home itself goes (onPause closes every popup).
        panel.setFocusable(true);
        panel.setFocusableInTouchMode(true);
        panel.setOnKeyListener((v, keyCode, event) -> {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (event.getAction() == KeyEvent.ACTION_UP) popup.dismiss();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return false;
            }
        });
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(Math.round(300 * d), Math.min(panel.getMeasuredWidth(), Math.round(440 * d))));
        // Under the pill, aligned as the Wi-Fi list it replaces was.
        host.dropIn(popup, panel, anchor, gravity);
        if (back != null) {
            host.alsoOnClose(popup, () -> {
                if (resumed && !destroyed) handler.post(back);
            });
        }
        panel.requestFocus();
        if (named == null) {
            View caption = appsCaption, wait = naming;
            run(() -> {
                nameApps(r);
                if (!destroyed) handler.post(() -> fillApps(popup, rows, caption, wait, r));
            });
        }
    }

    /** The detail's app names, in place of its "One moment…" line; nothing if it was closed meanwhile. */
    private void fillApps(PopupWindow popup, LinearLayout rows, View caption, View wait, Reading r) {
        if (destroyed || !popup.isShowing() || wait == null || wait.getParent() != rows) return;
        int at = rows.indexOfChild(wait);
        rows.removeView(wait);
        List<String[]> apps = DataUsage.appLines(words(), r.apps, TOP_APPS);
        if (apps.isEmpty()) {
            rows.removeView(caption);
            return;
        }
        for (String[] a : apps) {
            View line = row(rows, a[0], a[1], false);
            rows.removeView(line);
            rows.addView(line, at++);
        }
    }

    /** The words in the UI's language (the activity's resources: LauncherLocale applies). */
    private Words words() {
        return Words.of(act.getResources());
    }

    private static String orDash(Words w, long bytes) {
        String s = DataUsage.size(w, bytes);
        return s.isEmpty() ? "–" : s;
    }

    /** A line of the dropdown in the Inputs list's style (item_input), not focusable, with no icon. */
    private View row(LinearLayout rows, String title, String value, boolean strong) {
        View row = act.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setFocusable(false);
        row.setClickable(false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView t = row.findViewById(R.id.title);
        t.setText(title);
        ((ViewGroup.MarginLayoutParams) t.getLayoutParams()).setMarginStart(0);
        if (!strong) t.setTextColor(0xD9FFFFFF);
        TextView s = row.findViewById(R.id.status);
        s.setText(value);
        s.setTextColor(strong ? 0xF2FFFFFF : 0xCCFFFFFF);
        s.setVisibility(View.VISIBLE);
        // Tighter than the Inputs list's 42dp: the longest dropdown (three apps, three networks and the rest) still
        // ends above the screen's bottom edge.
        row.getLayoutParams().height = Math.round(32 * d);
        rows.addView(row);
        return row;
    }

    /** A section's dim title, like the row titles' secondary lines. */
    private View caption(LinearLayout rows, String text) {
        TextView c = new TextView(act);
        c.setText(text);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        c.setTextColor(0x8CFFFFFF);
        try {
            c.setTypeface(UiFont.get(act, R.string.font_regular));
        } catch (RuntimeException ignored) {
        }
        c.setSingleLine(true);
        c.setPadding(Math.round(12 * d), Math.round(6 * d), Math.round(12 * d), Math.round(2 * d));
        rows.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }
}
