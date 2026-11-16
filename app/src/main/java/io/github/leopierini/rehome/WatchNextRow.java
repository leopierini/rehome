package io.github.leopierini.rehome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.tv.TvContract;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * category_watch_next: reads the TV provider's Watch Next rows for the player above "Media" (CategoryHome,
 * PlayerCard), on its own thread, and opens one safely.
 *
 * - Needs READ_TV_LISTINGS (runtime, granted with pm grant). Without it the player shows no Watch Next items.
 * - Queried with no selection (a caller without ACCESS_ALL_EPG_DATA gets a SecurityException for one) and with the
 *   columns named: this Philips fails on `split_id` with a null projection, as the TPM191E in the doc does.
 * - Read when Home comes to the front, and on the provider's change notices only while Home is in front.
 * - Posters through PosterCache: from disk at once, missing ones fetched afterwards and only online, on a thread of
 *   their own: a poster that never arrives costs that item its picture, never the player its updates.
 */
final class WatchNextRow {

    private static final String TAG = "Rehome.WatchNext";
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    /** A poster fetch past its deadline by this much never returns: its thread is left behind. */
    private static final long STUCK_MS = PosterCache.DEADLINE_MS + 5_000;

    private static final String[] PROJECTION = {
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_TYPE,
            // The plot, for the player's right column (category_player); RaiPlay fills it.
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
    };

    /** One Watch Next item for the player. */
    static final class Entry {
        WatchNext.Item item;
        /** The publishing app's name. */
        String app;
        /** The poster, decoded tile-sized, or null. */
        Bitmap poster;
    }

    interface Listener {
        /** On the main thread, only when what the row shows changed. */
        void onCards(List<Entry> entries);
    }

    private final Activity act;
    private final ContentResolver resolver;
    private final Listener listener;
    private final Supplier<Set<String>> blockedOnMain;
    private final PosterCache posters;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean queued = new AtomicBoolean();
    /** Posters only (see the class comment); replaced when a fetch on it never returns. */
    private volatile ExecutorService posterExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean postersQueued = new AtomicBoolean();
    /** Hidden apps (config.xml, RowEdits) as of the last refresh request: read on the worker thread. */
    private volatile Set<String> blocked = Collections.emptySet();
    private volatile boolean offline, destroyed;
    private boolean observing;
    /** Main thread: Home in front (onResume to onPause). Nothing is read or fetched while it is not. */
    private boolean resumed;
    /** Worker thread only: the posters of the items now, by URL, so a re-read does not decode them again. */
    private Map<String, Bitmap> inMemory = new HashMap<>();
    /** Main thread: what was delivered last ("" for nothing), and its items. */
    private String shownSignature = "";
    private List<Entry> shown = Collections.emptyList();
    /** Main thread: when the first change notice still waiting for its read came (0: none). */
    private long firstPending;

    private final Runnable refreshNow = () -> {
        firstPending = 0;
        refresh();
    };
    private final ContentObserver observer = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            long now = android.os.SystemClock.uptimeMillis();
            if (firstPending == 0) firstPending = now;
            main.removeCallbacks(refreshNow);
            main.postDelayed(refreshNow, WatchNext.settleDelay(firstPending, now));
        }
    };

    /** {@code w} x {@code h}: the size posters are decoded at (a tile's). */
    WatchNextRow(Activity act, int w, int h, Supplier<Set<String>> blocked, Listener listener) {
        this.act = act;
        this.resolver = act.getContentResolver();
        this.blockedOnMain = blocked;
        this.listener = listener;
        this.posters = new PosterCache(act.getCacheDir(), resolver, w, h);
    }

    private boolean permitted() {
        return act.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    void onResume() {
        if (destroyed) return;
        resumed = true;
        if (!observing && permitted()) {
            try {
                resolver.registerContentObserver(TvContract.WatchNextPrograms.CONTENT_URI, true, observer);
                observing = true;
            } catch (RuntimeException e) {
                Log.i(TAG, "no change notices: " + e);
            }
        }
        refresh();
    }

    void onPause() {
        resumed = false;
        main.removeCallbacks(refreshNow);
        firstPending = 0;
        if (!observing) return;
        resolver.unregisterContentObserver(observer);
        observing = false;
    }

    void destroy() {
        destroyed = true;
        onPause();
        exec.shutdownNow();
        posterExec.shutdownNow();
        posters.shutdown();
    }

    /**
     * CategoryHome lets go of its rows (memory pressure): the posters go, and the player keeps the items without them.
     * The next read delivers the posters.
     */
    List<Entry> release() {
        // Whatever the next read finds, even nothing, is delivered.
        shownSignature = "released";
        try {
            exec.execute(() -> inMemory = new HashMap<>());
        } catch (RuntimeException ignored) {
        }
        List<Entry> text = new ArrayList<>();
        for (Entry e : shown) text.add(entry(e.item, null, e.app));
        shown = text;
        return text;
    }

    /**
     * The items the last start showed (WatchNext.decode of what CategoryHome kept), without posters: the player has them
     * in the first frame (final review M2). Whatever the first read finds, even nothing, is delivered and corrects them.
     * Main thread, before the first read.
     */
    List<Entry> seed(List<WatchNext.Saved> saved) {
        List<Entry> text = new ArrayList<>();
        for (WatchNext.Saved sv : saved) text.add(entry(sv.item, null, sv.app));
        shownSignature = "seeded";
        shown = text;
        return text;
    }

    /**
     * Back online, the missing posters can be fetched: read again, but only with Home in front. The hotspot coming back
     * during a film fetches nothing (final review M1): onResume reads anyway.
     */
    void setOffline(boolean off) {
        boolean back = offline && !off;
        offline = off;
        if (back) {
            posters.onOnline();
            if (resumed) refresh();
        }
    }

    /** Reads the rows again (coalesced: one read waiting at most). */
    void refresh() {
        if (destroyed) return;
        if (!permitted()) {
            deliver("", Collections.emptyList());
            return;
        }
        blocked = new HashSet<>(blockedOnMain.get());
        if (queued.getAndSet(true)) return;
        try {
            exec.execute(() -> {
                queued.set(false);
                work();
            });
        } catch (RuntimeException e) {
            queued.set(false);
        }
    }

    private void deliver(String signature, List<Entry> entries) {
        if (destroyed || signature.equals(shownSignature)) return;
        shownSignature = signature;
        shown = entries;
        listener.onCards(entries);
    }

    /** One item, with its poster or none (null). */
    private static Entry entry(WatchNext.Item i, Bitmap poster, String app) {
        Entry e = new Entry();
        e.item = i;
        e.app = app;
        e.poster = poster;
        return e;
    }

    // ---- worker thread ----

    private void work() {
        try {
            read();
        } catch (RuntimeException | OutOfMemoryError e) {
            // On this thread an uncaught one would take the Home down with it: the row just stays as it is.
            Log.w(TAG, "watch next read failed: " + e);
        }
    }

    private void read() {
        List<WatchNext.Item> raw = query();
        String test = TestHooks.watchNext(act);
        // "test" and "test-resort" (debug builds: WatchNextTestRows).
        boolean testRows = test != null && test.startsWith("test");
        if (WatchNextTestRows.sync(act, raw, testRows)) raw = query();
        if (test != null) dump(raw);
        PackageManager pm = act.getPackageManager();
        Set<String> no = blocked;
        // TestHooks "test": the launcher's own test rows count (it is hidden from the rows otherwise).
        boolean ownRows = testRows;
        List<WatchNext.Item> items = WatchNext.filter(raw, System.currentTimeMillis(), installedAt(pm),
                pkg -> usable(pm, pkg, no, ownRows));

        Map<String, Bitmap> keep = new HashMap<>();
        List<Entry> entries = new ArrayList<>();
        List<Boolean> withPoster = new ArrayList<>();
        Map<String, String> labels = new HashMap<>();
        for (WatchNext.Item i : items) {
            Bitmap poster = null;
            if (i.poster != null) {
                poster = inMemory.get(i.poster);
                if (poster == null) poster = posters.cached(i.poster);
                if (poster != null) keep.put(i.poster, poster);
            }
            String app = labels.get(i.pkg);
            if (app == null) {
                app = label(pm, i.pkg);
                labels.put(i.pkg, app);
            }
            entries.add(entry(i, poster, app));
            withPoster.add(poster != null);
        }
        inMemory = keep;
        String signature = items.isEmpty() ? "" : WatchNext.signature(items, withPoster);
        if (!destroyed) main.post(() -> deliver(signature, entries));

        // Then the missing posters: the items are already delivered without them. On the hotspot only when it is online; posters
        // another app serves on the TV (content://) need no network.
        boolean online = !offline && networkUp();
        List<String> missing = new ArrayList<>();
        for (WatchNext.Item i : items) {
            String url = i.poster;
            if (url == null || keep.containsKey(url) || missing.contains(url) || !posters.wants(url)) continue;
            if (WatchNext.posterLocal(url) || (online && WatchNext.posterOverNetwork(url))) missing.add(url);
        }
        if (!missing.isEmpty()) fetchPosters(missing);
    }

    /**
     * The posters, on their own thread (one pass waiting at most). A fetch that is still not back long after its
     * deadline (an app whose file never ends: a blocked read cannot be interrupted) is given up for good, and its
     * thread left behind for a new one.
     */
    private void fetchPosters(List<String> urls) {
        String stuck = posters.stuck(STUCK_MS);
        if (stuck != null) {
            Log.w(TAG, "a poster never came back: given up");
            posters.giveUp(stuck);
            posterExec.shutdownNow();
            ExecutorService fresh = Executors.newSingleThreadExecutor();
            posterExec = fresh;
            // destroy() on the main thread may have shut the old one just now: this one goes too (final review M5).
            if (destroyed) fresh.shutdownNow();
            postersQueued.set(false);
        }
        if (destroyed || postersQueued.getAndSet(true)) return;
        // Should this pass hang, the items it does not reach get their posters at the read after it is given up.
        main.postDelayed(() -> {
            if (!destroyed && resumed && posters.stuck(STUCK_MS) != null) refresh();
        }, STUCK_MS + 1_000);
        try {
            posterExec.execute(() -> {
                postersQueued.set(false);
                boolean fetched = false;
                for (String url : urls) {
                    if (destroyed || Thread.currentThread().isInterrupted()) return;
                    try {
                        fetched |= posters.fetch(url);
                    } catch (Throwable t) {
                        // PosterCache catches its own; nothing here may reach the thread's default handler either.
                        Log.w(TAG, "poster failed: " + t.getClass().getSimpleName());
                    }
                }
                // Shown at once with Home in front; otherwise onResume's read finds them on disk.
                if (fetched && !destroyed) main.post(() -> {
                    if (resumed) refresh();
                });
            });
        } catch (RuntimeException e) {
            postersQueued.set(false);
        }
    }

    /** When the launcher was installed: the real clock is surely past it (WatchNext.filter's floor). */
    private long installedAt(PackageManager pm) {
        try {
            return pm.getPackageInfo(act.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return 0;
        }
    }

    private List<WatchNext.Item> query() {
        List<WatchNext.Item> out = new ArrayList<>();
        // No selection, the columns named (see the class comment).
        try (Cursor c = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, PROJECTION, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                WatchNext.Item i = new WatchNext.Item();
                i.id = c.getLong(0);
                i.pkg = c.getString(1);
                i.title = c.getString(2);
                i.episodeTitle = c.getString(3);
                i.season = c.getString(4);
                i.episode = c.getString(5);
                i.poster = c.getString(6);
                // No poster: the thumbnail is the next best picture.
                if (i.poster == null || i.poster.isEmpty()) i.poster = c.getString(7);
                if (i.poster != null && i.poster.isEmpty()) i.poster = null;
                i.intentUri = c.getString(8);
                i.watchNextType = c.isNull(9) ? WatchNext.TYPE_CONTINUE : c.getInt(9);
                i.lastEngagement = c.isNull(10) ? 0 : c.getLong(10);
                i.position = c.isNull(11) ? 0 : c.getLong(11);
                i.duration = c.isNull(12) ? 0 : c.getLong(12);
                i.description = c.getString(14);
                out.add(i);
            }
        } catch (RuntimeException e) {
            // SecurityException (permission taken back), SQLiteException (a firmware's provider): no row.
            Log.i(TAG, "watch next not readable: " + e);
        }
        return out;
    }

    /** TestHooks: every row as read, before filtering, for checking a firmware (logcat -s Rehome.WatchNext). */
    private static void dump(List<WatchNext.Item> raw) {
        long now = System.currentTimeMillis();
        Log.i(TAG, "dump: " + raw.size() + " rows");
        for (WatchNext.Item i : raw) {
            Log.i(TAG, "row " + i.id + " " + i.pkg + " type=" + i.watchNextType + " title=\"" + i.title
                    + "\" ep=\"" + WatchNext.episodeLine(i.season, i.episode, i.episodeTitle) + "\" ageH="
                    + (i.lastEngagement > 0 ? (now - i.lastEngagement) / 3_600_000L : -1) + " pos=" + i.position
                    + " dur=" + i.duration + " poster=" + head(i.poster, 48) + " intent=" + head(i.intentUri, 80));
        }
    }

    private static String head(String s, int n) {
        return s == null ? "null" : s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private boolean usable(PackageManager pm, String pkg, Set<String> no, boolean ownRows) {
        if (ownRows && pkg.equals(act.getPackageName())) return true;
        if (no.contains(pkg) || AppLaunchActivity.isServiceMenu(pkg)) return false;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return AppLabel.clean(String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))), pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pkg;
        }
    }

    private boolean networkUp() {
        ConnectivityManager cm = act.getApplicationContext().getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        try {
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- opening an item (main thread) ----

    /**
     * Opens the row's link in the app that published it (WatchNext.mayLaunch). False when it cannot: the caller then
     * opens the app itself. Parsed with Intent.URI_INTENT_SCHEME (intent:, android-app: and plain links); the
     * selector, clip data and any URI grant the link asks for are dropped (WatchNext.safeFlags), so the launcher
     * passes on none of its own access. The decisions are WatchNext's, tested there.
     */
    boolean open(WatchNext.Item i) {
        if (i == null || !WatchNext.launchableLink(i.intentUri)) return false;
        Intent in;
        try {
            in = Intent.parseUri(i.intentUri, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException | RuntimeException e) {
            Log.i(TAG, "bad link from " + i.pkg);
            return false;
        }
        in.setSelector(null);
        in.setClipData(null);
        in.setFlags(WatchNext.safeFlags(in.getFlags()));
        // A plain https link goes to the app that wrote it, not to a browser or a chooser.
        if (WatchNext.needsPackage(in.getComponent() != null, in.getPackage() != null)) in.setPackage(i.pkg);
        PackageManager pm = act.getPackageManager();
        ResolveInfo ri;
        try {
            ri = pm.resolveActivity(in, PackageManager.MATCH_DEFAULT_ONLY);
        } catch (RuntimeException e) {
            ri = null;
        }
        String to = ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
        if (!WatchNext.mayLaunch(i.pkg, to, to != null && ri.activityInfo.exported)) {
            Log.i(TAG, "link of " + i.pkg + " not opened (resolves to " + to + ")");
            return false;
        }
        try {
            act.startActivity(in);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.i(TAG, "link of " + i.pkg + " failed: " + e.getClass().getSimpleName());
            return false;
        }
    }
}
