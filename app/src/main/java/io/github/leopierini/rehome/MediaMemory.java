package io.github.leopierini.rehome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * category_player: records what other apps play, for the player, while the notification listener
 * (MediaListener) is connected: that is, also while another app is in front, which is when YouTube still tells its
 * title (it forgets it once closed). Only listens: session callbacks, no polling. The one timed check runs while
 * something plays (a play reaching MIN_PLAY_MS, then a save every SAVE_EVERY_MS for the position).
 *
 * <p>One per process, on one thread that lives as long as the process: the listener can be disconnected and
 * connected again within milliseconds (an install, {@code cmd notification disallow_listener/allow_listener}), and the
 * first version, with a thread per connection sharing its fields, let the old thread's stop undo the new start (on
 * the TV: the active-sessions listener and the save timer gone, so nothing was recorded until a session
 * ended). Here start and stop are messages to the same thread, handled in the order they were sent.
 *
 * <p>What is remembered and as what: PlayerPick. Where: PlayerMemory.
 */
final class MediaMemory {

    private static final String TAG = "Rehome.Memory";
    /** Art kept at most this long on its long side (the player's image column, at 1080p, is narrower). */
    static final int ART_PX = 480;
    /** A picture fetched from a URI is read at most this big: a cover is a few hundred KB at most. */
    private static final int ART_BYTES = 2 * 1024 * 1024;
    private static final String AD_KEY = "android.media.metadata.ADVERTISEMENT";

    private static MediaMemory instance;

    private final Context app;
    private final ComponentName listener;
    private final Set<String> music, ignored;
    /** Network and file work (iTunes, covers by URI): never on the recording thread, so a slow answer holds nothing. */
    private final ExecutorService lookups = Executors.newSingleThreadExecutor();
    private final Handler bg;
    /** Read on the recording thread at the first start: its file is never read on the main thread from here. */
    private PlayerMemory memory;
    private SongLookup songs;
    private MediaSessionManager sessions;
    private boolean listening;
    /** The listener instance that started the recording: a late stop from an older one is ignored. */
    private Object owner;
    private final Map<MediaSession.Token, Watch> watches = new LinkedHashMap<>();
    /** Something was put into the memory since the Home was last told. */
    private boolean saved;
    /** https pictures fetched or being fetched in this process (recording thread): each is downloaded once. */
    private final Set<String> triedUrls = new HashSet<>();

    /** One thing a session plays: from its first event to another title, an ad, or the session's end. */
    private static final class Track {
        String key, title;
        PlayerPick.Item item;
        /** elapsedRealtime when it last started playing, -1 while it does not. */
        long since = -1;
        /** A new track under a song key already remembered: saved at once (MIN_PLAY_MS is for a memory's birth). */
        boolean update;
        /** iTunes' cover is on it: the session's 16:9 frame does not replace it. */
        boolean itunes;
        /** The art file name being fetched from a URI, so one fetch runs per picture. */
        String fetching;
        /** elapsedRealtime when the track began: its picture settles in PlayerPick.ART_SETTLE_MS. */
        long born = SystemClock.elapsedRealtime();
        /** Its picture's rank (PlayerPick.artRank); a file remembered from before this run: ART_STORED. */
        int artRank = PlayerPick.ART_STORED;
    }

    private final class Watch extends MediaController.Callback {
        final MediaController c;
        final String pkg;
        /** The app's name: a music app's "track" by the app itself is its ad (PlayerPick.remembers). */
        final String label;
        /** The last metadata and state the callbacks brought: no binder call (and no bitmap copy) per event. */
        MediaMetadata md;
        PlaybackState st;
        /** The session's picture, from the last metadata: a bitmap, or a URI to fetch; its signature names its file. */
        Bitmap art;
        String artUri, artSig;
        /** How good that picture is (PlayerPick.artRank): the app's logo never replaces the cover. */
        int artRank;
        /** The picture's https URI even when a bitmap came too (Spotify sends both), and the content's link. */
        String artHttps, link;
        Track t;
        /** elapsedRealtime of the last metadata: a state older than it carries the previous track's position. */
        long mdAt;
        /**
         * The state's update time when another session was heard after it: its "playing" is stale (YouTube and
         * Spotify keep saying "playing" after losing the audio focus) until it sends a new state.
         */
        long silenced = Long.MIN_VALUE;

        Watch(MediaController c, String pkg) {
            this.c = c;
            this.pkg = pkg;
            label = appLabel(pkg);
            try {
                md = c.getMetadata();
                st = c.getPlaybackState();
            } catch (RuntimeException ignoredException) {
            }
            readArt();
        }

        void readArt() {
            // A cover (ART, ALBUM_ART, then their URI) before DISPLAY_ICON, which can be the app's logo (Spotify).
            Bitmap[] bs = bitmaps(md);
            int[] sizes = new int[6];
            for (int k = 0; k < 3; k++) {
                if (bs[k] == null) continue;
                sizes[2 * k] = bs[k].getWidth();
                sizes[2 * k + 1] = bs[k].getHeight();
            }
            String coverUri = artUri(md, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            String iconUri = artUri(md, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            int src = PlayerPick.pictureSource(sizes, coverUri != null, iconUri != null);
            artRank = PlayerPick.artRank(src);
            art = src == PlayerPick.SRC_ART ? bs[0] : src == PlayerPick.SRC_ALBUM_ART ? bs[1]
                    : src == PlayerPick.SRC_ICON ? bs[2] : null;
            artUri = src == PlayerPick.SRC_COVER_URI ? coverUri : src == PlayerPick.SRC_ICON_URI ? iconUri : null;
            artSig = art != null ? "b" + signature(art) : artUri != null ? "u" + artUri : null;
            artHttps = PlayerPick.artUrl(coverUri != null ? coverUri : iconUri, null);
            link = PlayerPick.link(string(md, MediaMetadata.METADATA_KEY_MEDIA_URI),
                    string(md, MediaMetadata.METADATA_KEY_MEDIA_ID));
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            md = metadata;
            mdAt = SystemClock.elapsedRealtime();
            readArt();
            update(this);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            st = state;
            update(this);
        }

        @Override
        public void onSessionDestroyed() {
            end(this);
        }
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::setControllers;
    private final Runnable tick = this::tick;

    static synchronized MediaMemory get(Context c) {
        if (instance == null) instance = new MediaMemory(c.getApplicationContext());
        return instance;
    }

    private MediaMemory(Context context) {
        app = context;
        listener = new ComponentName(app, MediaListener.class);
        music = new HashSet<>(Arrays.asList(app.getResources().getStringArray(R.array.player_music_apps)));
        ignored = new HashSet<>(Arrays.asList(app.getResources().getStringArray(R.array.player_ignore_apps)));
        HandlerThread thread = new HandlerThread("player-memory", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        bg = new Handler(thread.getLooper());
    }

    /** The listener connected: (re)start recording. {@code who} is the listener instance. */
    void start(Object who) {
        bg.post(() -> {
            owner = who;
            startNow();
        });
    }

    /** The listener disconnected: stop, unless a newer listener instance has started since. */
    void stop(Object who) {
        bg.post(() -> {
            if (owner != who) return;
            owner = null;
            stopNow();
        });
    }

    private void startNow() {
        if (listening) stopNow();
        if (memory == null) memory = PlayerMemory.get(app);
        if (songs == null) songs = new SongLookup(app.getFilesDir());
        sessions = app.getSystemService(MediaSessionManager.class);
        if (sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, bg);
            listening = true;
            setControllers(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {
            Log.i(TAG, "sessions refused: " + e.getMessage());
        }
    }

    private void stopNow() {
        bg.removeCallbacks(tick);
        if (listening && sessions != null) {
            try {
                sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
            } catch (RuntimeException ignoredException) {
            }
        }
        listening = false;
        for (Watch w : watches.values()) {
            finish(w);
            try {
                w.c.unregisterCallback(w);
            } catch (RuntimeException ignoredException) {
            }
        }
        watches.clear();
        if (memory != null) publishLive();
    }

    private void setControllers(List<MediaController> list) {
        if (!listening) return;
        Map<MediaSession.Token, Watch> next = new LinkedHashMap<>();
        if (list != null) {
            for (MediaController c : list) {
                String pkg;
                try {
                    pkg = c == null ? null : c.getPackageName();
                } catch (RuntimeException e) {
                    continue;
                }
                if (pkg == null || app.getPackageName().equals(pkg)) continue;
                Watch had = watches.remove(c.getSessionToken());
                if (had != null) {
                    next.put(c.getSessionToken(), had);
                    continue;
                }
                Watch w = new Watch(c, pkg);
                try {
                    c.registerCallback(w, bg);
                } catch (RuntimeException e) {
                    continue;
                }
                next.put(c.getSessionToken(), w);
            }
        }
        // A session no longer active (Spotify after losing the audio focus to another app goes inactive, its state
        // still saying "playing"): what it played is saved as it was and it is no longer live.
        for (Watch gone : watches.values()) {
            finish(gone);
            try {
                gone.c.unregisterCallback(gone);
            } catch (RuntimeException ignoredException) {
            }
        }
        watches.clear();
        watches.putAll(next);
        for (Watch w : watches.values()) update(w);
        publishLive();
    }

    /** A metadata or state change (or a first look) of one session. */
    private void update(Watch w) {
        if (!listening) return;
        MediaMetadata md = w.md;
        PlaybackState st = w.st;
        String pkg = w.pkg;
        long el = SystemClock.elapsedRealtime(), now = System.currentTimeMillis();
        // One app is heard at a time (PlayerPick.audible): the others still saying "playing" are paused in fact.
        Watch heard = audible();
        for (Watch o : watches.values()) if (o != heard && o != w && claims(o)) silence(o, el, now);
        boolean playing = heard == w;
        if (!playing && claims(w)) w.silenced = st.getLastPositionUpdateTime();
        String title = PlayerPick.first(text(md, MediaMetadata.METADATA_KEY_TITLE),
                text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
        String artist = PlayerPick.first(text(md, MediaMetadata.METADATA_KEY_ARTIST),
                text(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST), text(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
        // A stale "playing" state would move the position on forever: only a heard or a paused one tells it.
        boolean stale = !playing && st != null && st.getState() == PlaybackState.STATE_PLAYING;
        long pos = stale ? -1 : position(st, el);

        // METADATA_KEY_ADVERTISEMENT is hidden in the SDK; its key is what Spotify sends (logged on the TV).
        boolean ad = isAd(md);
        boolean remember = PlayerPick.remembers(app.getPackageName(), ignored, pkg, title, artist, playing, music, ad,
                w.label);
        String key = null;
        int kind = 0;
        if (remember) {
            Bitmap art = w.art;
            kind = PlayerPick.kind(pkg, title, artist, art == null ? 0 : art.getWidth(), art == null ? 0 : art.getHeight(),
                    music);
            key = PlayerPick.key(kind, pkg, title);
        }
        Track t = w.t;
        if (t != null && (key == null || !key.equals(t.key) || !PlayerPick.norm(title).equals(PlayerPick.norm(t.title)))) {
            // What it played has ended: another track, an ad, or (Netflix) a pause that empties the metadata, whose
            // state still carries where it stopped.
            if (t.item.kind == PlayerPick.APP && key == null && pos >= 0) t.item.position = pos;
            stopClock(t, el, now);
            save(t);
            w.t = t = null;
        }
        if (key == null) {
            reschedule();
            publishLive();
            return;
        }
        boolean fresh = t == null;
        if (fresh) {
            t = new Track();
            t.key = key;
            t.title = title;
            PlayerPick.Item had = memory.item(key);
            String expected = kind == PlayerPick.APP ? null : title;
            if (had != null && PlayerPick.norm(had.title).equals(PlayerPick.norm(expected))) {
                // The same video or track again: its played time and art go on. Netflix (an APP) only within one
                // sitting, so "per N min" is not the sum of every evening.
                t.item = had;
                if (kind == PlayerPick.APP && !PlayerPick.sameSitting(had, now)) t.item.playedMs = 0;
                t.itunes = had.art != null && had.art.endsWith(PlayerStore.artName(key, title, "itunes"));
            } else {
                t.item = new PlayerPick.Item();
                t.item.key = key;
                if (had != null && kind == PlayerPick.SONG) {
                    // Another track of a song card: shown at once, or the card says the old song plays (review 1).
                    t.update = true;
                    t.item.lastPlayed = had.lastPlayed;
                }
            }
            w.t = t;
            if (log()) {
                Log.i(TAG, "track " + key + " ad=" + ad + " keys=" + (md == null ? "-" : new java.util.TreeSet<>(md.keySet())) + " art="
                        + (w.art != null ? "bitmap " + w.art.getWidth() + "x" + w.art.getHeight()
                        : w.artUri != null ? w.artUri.substring(0, w.artUri.indexOf(':') + 1) : "none"));
            }
        }
        // The new track's metadata came before its state: the state's position is still the previous track's.
        if (fresh && st != null && w.mdAt > 0 && st.getLastPositionUpdateTime() < w.mdAt) pos = 0;
        PlayerPick.Item i = t.item;
        i.kind = kind;
        i.pkg = pkg;
        i.title = kind == PlayerPick.APP ? null : title;
        if (!artist.isEmpty()) i.artist = artist;
        String album = text(md, MediaMetadata.METADATA_KEY_ALBUM);
        if (!album.isEmpty()) i.album = album;
        long duration = md == null ? 0 : md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration > 0) i.duration = duration;
        if (pos >= 0) i.position = pos;
        boolean artChanged = takeArt(w, t);
        if (w.link != null) i.link = w.link;
        // The picture's https source, to fetch it again if its file is lost: set with a new picture, or once on an
        // item that has none yet (an item of an older build); iTunes' cover is not the session's.
        String url = PlayerPick.artUrl(w.artHttps, i.artUrl);
        if (!t.itunes && !java.util.Objects.equals(url, i.artUrl) && (i.artUrl == null || artChanged)) {
            i.artUrl = url;
            artChanged = true;
        }
        boolean clockChanged = false;
        if (playing) {
            i.lastPlayed = now;
            if (t.since < 0) {
                t.since = el;
                clockChanged = true;
                // Played again after a pause: the memory still holds the pause's position with the time it last
                // played before it, and PlayerPick.positionNow would count the pause as played (paused
                // at 0:57 for 15 s, the card said 1:15 on resume). Saved now with where it resumes and when.
                if (!fresh) save(t);
            }
        } else if (t.since >= 0) {
            stopClock(t, el, now);
            save(t);
            clockChanged = true;
        }
        if (fresh && t.update) save(t);
        else if (artChanged) save(t);
        if (clockChanged || fresh) reschedule();
        publishLive();
    }

    /**
     * The session's picture onto the track, when it is not the one there already: a bitmap saved now, a URI (Spotify
     * gives only a URI) fetched on the lookups thread. True when the item's art changed here.
     */
    private boolean takeArt(Watch w, Track t) {
        if (w.artSig == null || t.itunes) return false;
        String name = PlayerStore.artName(t.key, t.title, w.artSig);
        boolean has = t.item.art != null && new File(t.item.art).exists();
        if (has && name.equals(fileName(t.item.art))) return false;
        if (!PlayerPick.takesNewArt(t.artRank, w.artRank, has, SystemClock.elapsedRealtime() - t.born)) return false;
        if (w.art != null) {
            String path = saveArt(w.art, name);
            if (path == null) return false;
            if (log()) Log.i(TAG, "art " + name + " (" + w.artSig + ", rank " + w.artRank + ") was " + fileName(t.item.art));
            t.item.art = path;
            t.artRank = w.artRank;
            // A fetch still running for a lesser picture (the cover's URL) no longer lands on the track.
            t.fetching = null;
            return true;
        }
        // The URL stays on the item until its picture is in: fetched later from the memory if not now (fixArt).
        String keep = PlayerPick.keepUriArt(t.item.art, has, w.artUri);
        boolean changed = !java.util.Objects.equals(keep, t.item.art);
        t.item.art = keep;
        if (name.equals(t.fetching)) return changed;
        t.fetching = name;
        fetchArt(w, t, w.artUri, name, w.artRank);
        return changed;
    }

    /** A picture by URI (content:// through the resolver, https:// only online), decoded and saved; then onto the track. */
    private void fetchArt(Watch w, Track t, String uri, String name, int rank) {
        if (uri.startsWith("https://") && !triedUrls.add(uri)) return;
        lookups.execute(() -> {
            Bitmap b = null;
            try {
                byte[] bytes;
                if (uri.startsWith("https://")) {
                    if (!online()) {
                        // Not tried: the memory keeps the URL and the next Home refresh online fetches it.
                        bg.post(() -> triedUrls.remove(uri));
                        return;
                    }
                    bytes = SongLookup.fetch(uri, ART_BYTES);
                } else {
                    try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(uri))) {
                        bytes = SongLookup.read(in, ART_BYTES);
                    }
                }
                b = decode(bytes);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if (log()) Log.i(TAG, "art not read: " + uri.substring(0, Math.min(uri.length(), 24)) + "…: " + e);
            }
            if (b == null) return;
            final Bitmap art = b;
            bg.post(() -> {
                try {
                    if (w.t != t || !name.equals(t.fetching) || t.itunes) {
                        // The track ended before its picture came: onto the remembered item, if it still waits for it.
                        if (w.t != t) fillPending(t.key, uri, art);
                        return;
                    }
                    String path = saveArt(art, name);
                    if (path == null) return;
                    t.item.art = path;
                    t.artRank = rank;
                    save(t);
                    publishLive();
                } finally {
                    art.recycle();
                }
            });
        });
    }

    /**
     * Remembered items whose picture is still an https URL (a fetch that failed offline, or came after the track
     * ended; items of older builds): each fetched once, online only, size-capped, saved as a file. Called on each
     * refresh of the Home's player; cheap when there is nothing to fetch.
     */
    void fixArt() {
        bg.post(() -> {
            if (memory == null) memory = PlayerMemory.get(app);
            java.util.function.Predicate<String> exists = p -> new File(p).exists();
            for (PlayerPick.Item i : PlayerPick.artToFetch(memory.items(), triedUrls, exists)) {
                final String key = i.key, url = PlayerPick.fetchUrl(i, exists);
                triedUrls.add(url);
                lookups.execute(() -> {
                    if (!online()) {
                        bg.post(() -> triedUrls.remove(url));
                        return;
                    }
                    Bitmap b = null;
                    try {
                        b = decode(SongLookup.fetch(url, ART_BYTES));
                    } catch (IOException | RuntimeException | OutOfMemoryError e) {
                        if (log()) Log.i(TAG, "art not read: " + url.substring(0, Math.min(url.length(), 24)) + "…: " + e);
                    }
                    if (b == null) return;
                    final Bitmap art = b;
                    bg.post(() -> {
                        try {
                            fillPending(key, url, art);
                        } finally {
                            art.recycle();
                        }
                    });
                });
            }
        });
    }

    /**
     * A fetched picture onto the remembered item that still waits for that URL: its art is the URL, or its file is gone
     * and the URL is its artUrl (PlayerPick.fetchUrl); and onto its live track. bg thread.
     */
    private void fillPending(String key, String url, Bitmap art) {
        PlayerPick.Item i = memory.item(key);
        java.util.function.Predicate<String> exists = p -> new File(p).exists();
        if (i == null || !url.equals(PlayerPick.fetchUrl(i, exists))) return;
        String path = saveArt(art, PlayerStore.artName(key, i.title, "u" + url));
        if (path == null) return;
        String was = i.art;
        i.art = path;
        for (Watch w : watches.values()) {
            if (w.t != null && key.equals(w.t.key) && java.util.Objects.equals(was, w.t.item.art)) w.t.item.art = path;
        }
        if (log()) Log.i(TAG, "art fetched for " + key);
        if (memory.put(i, log())) memory.changed();
    }

    private String appLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = app.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    /** The metadata's ADVERTISEMENT flag (a hidden key for the SDK, so lint does not know it). */
    @android.annotation.SuppressLint("WrongConstant")
    private static boolean isAd(MediaMetadata md) {
        try {
            return md != null && md.getLong(AD_KEY) != 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether a session says it plays and that is not known to be stale. */
    private static boolean claims(Watch w) {
        return w.st != null && w.st.getState() == PlaybackState.STATE_PLAYING
                && w.st.getLastPositionUpdateTime() != w.silenced;
    }

    /** The session heard now (PlayerPick.audible over the ones that claim to play), or null. */
    private Watch audible() {
        Watch[] ws = watches.values().toArray(new Watch[0]);
        boolean[] plays = new boolean[ws.length];
        long[] since = new long[ws.length];
        for (int k = 0; k < ws.length; k++) {
            plays[k] = claims(ws[k]);
            since[k] = ws[k].st == null ? 0 : ws[k].st.getLastPositionUpdateTime();
        }
        int k = PlayerPick.audible(plays, since);
        return k < 0 ? null : ws[k];
    }

    /** Another session is heard: this one's "playing" is stale. Its clock stops where it was then. */
    private void silence(Watch o, long el, long now) {
        o.silenced = o.st.getLastPositionUpdateTime();
        Track t = o.t;
        if (t == null || t.since < 0) return;
        long pos = position(o.st, el);
        if (pos >= 0) t.item.position = t.item.duration > 0 ? Math.min(pos, t.item.duration) : pos;
        stopClock(t, el, now);
        save(t);
    }

    /** The session is gone: what it played is saved as it was. */
    private void end(Watch w) {
        finish(w);
        reschedule();
        publishLive();
    }

    private void finish(Watch w) {
        Track t = w.t;
        if (t != null) {
            stopClock(t, SystemClock.elapsedRealtime(), System.currentTimeMillis());
            save(t);
            w.t = null;
        }
    }

    /** While something plays: its played time and position saved, the first time at MIN_PLAY_MS. */
    private void tick() {
        long el = SystemClock.elapsedRealtime(), now = System.currentTimeMillis();
        for (Watch w : watches.values()) {
            Track t = w.t;
            if (t == null || t.since < 0) continue;
            t.item.playedMs += el - t.since;
            t.since = el;
            t.item.lastPlayed = now;
            long pos = position(w.st, el);
            if (pos >= 0) t.item.position = pos;
            save(t);
        }
        reschedule();
        publishLive();
    }

    /** The save timer set to the first track due (PlayerPick.nextSave), or off when nothing plays. */
    private void reschedule() {
        bg.removeCallbacks(tick);
        int n = 0;
        long[] since = new long[watches.size()], played = new long[watches.size()];
        for (Watch w : watches.values()) {
            since[n] = w.t == null ? -1 : w.t.since;
            played[n] = w.t == null ? 0 : w.t.item.playedMs;
            n++;
        }
        long el = SystemClock.elapsedRealtime();
        long at = PlayerPick.nextSave(el, since, played);
        if (at != Long.MAX_VALUE) bg.postDelayed(tick, Math.max(0, at - el));
    }

    private static void stopClock(Track t, long el, long now) {
        if (t.since < 0) return;
        t.item.playedMs += el - t.since;
        t.item.lastPlayed = now;
        t.since = -1;
    }

    /** Into the memory once it has played MIN_PLAY_MS (a preview or an ad never is), or at once as a song's update. */
    private void save(Track t) {
        if (t.item.playedMs < PlayerPick.MIN_PLAY_MS && !t.update) return;
        PlayerPick.Item had = memory.item(t.key);
        // A song key is one per app: a new track under it is a new song to look up, as a key never seen.
        boolean newSong = t.item.kind == PlayerPick.SONG
                && (had == null || !PlayerPick.norm(had.title).equals(PlayerPick.norm(t.item.title)));
        if (memory.put(t.item, log())) saved = true;
        if (newSong && SongLookup.on(app)) lookUp(t.item.copy());
    }

    /** Read at each save (ADB on only, TestHooks): a hook set after the listener connected still logs. */
    private boolean log() {
        return TestHooks.memoryLog(app);
    }

    /**
     * A song just entered the memory: its album, year, genre and track number from the cache or from iTunes (online
     * only), and iTunes' square cover when the session gave none or a video's frame (YouTube Music). On a thread of
     * its own: a slow answer never holds the recording up.
     */
    private void lookUp(PlayerPick.Item song) {
        lookups.execute(() -> {
            if (!SongLookup.on(app)) return;   // turned off in Launcher settings since the song was queued
            SongLookup.Query q = SongLookup.clean(song.artist, song.title);
            if (q.artist.isEmpty() || q.title.isEmpty()) return;
            SongLookup.Hit h = songs.cached(q);
            if (h == null) {
                if (!online()) return;
                h = songs.find(q);
            }
            if (h == null || h == SongLookup.NOT_FOUND) return;
            Bitmap cover = null;
            if (h.artUrl != null && !squareFile(song.art) && online()) {
                try {
                    cover = decode(SongLookup.fetch(h.artUrl, 512 * 1024));
                } catch (IOException | RuntimeException | OutOfMemoryError ignoredException) {
                }
            }
            final SongLookup.Hit hit = h;
            final Bitmap art = cover;
            bg.post(() -> apply(song.key, song.title, hit, art));
        });
    }

    /** The answer onto the song, if it is still the one under its key: in the memory and on the track that plays. */
    private void apply(String key, String title, SongLookup.Hit hit, Bitmap cover) {
        String art = cover != null ? saveArt(cover, PlayerStore.artName(key, title, "itunes")) : null;
        if (cover != null) cover.recycle();
        PlayerPick.Item saved = memory.item(key);
        if (saved == null || !PlayerPick.norm(saved.title).equals(PlayerPick.norm(title))) return;
        fill(saved, hit, art);
        memory.put(saved, log());
        for (Watch w : watches.values()) {
            if (w.t != null && key.equals(w.t.key) && PlayerPick.norm(title).equals(PlayerPick.norm(w.t.title))) {
                fill(w.t.item, hit, art);
                if (art != null) w.t.itunes = true;
            }
        }
        memory.changed();
    }

    private static void fill(PlayerPick.Item i, SongLookup.Hit h, String art) {
        if (PlayerPick.blank(i.album)) i.album = h.album;
        i.year = h.year;
        i.genre = h.genre;
        i.track = h.track;
        i.tracks = h.tracks;
        if (art != null) i.art = art;
    }

    /** Whether a remembered art file is a square cover (a song's), not a video's frame. */
    private static boolean squareFile(String path) {
        if (path == null || path.startsWith("https://")) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        return o.outWidth > 0 && Math.abs(o.outWidth - o.outHeight) <= Math.max(o.outWidth, o.outHeight) / 10;
    }

    /** A validated network, and not the offline test hook: the lookup is never tried into a dead hotspot. */
    private boolean online() {
        if (TestHooks.offline(app)) return false;
        try {
            android.net.ConnectivityManager cm = app.getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Which session each app plays now, for the player's live state and its ⏮ ⏯ ⏭. The Home is told only when that
     * changed or something was saved: a playing session sends several events a second, and each notice used to
     * redraw the card (the focus jumped while music played).
     */
    private void publishLive() {
        Map<String, MediaController> byPkg = new HashMap<>();
        Map<String, String> keys = new HashMap<>();
        Map<String, Boolean> playing = new HashMap<>();
        Watch heard = audible();
        for (Watch w : watches.values()) {
            String pkg = w.pkg;
            boolean plays = w == heard;
            // An app with several sessions: the one that plays something remembered wins.
            if (byPkg.containsKey(pkg) && w.t == null) continue;
            byPkg.put(pkg, w.c);
            if (w.t != null) keys.put(pkg, w.t.key);
            playing.put(pkg, plays);
        }
        boolean changed = memory.setLive(byPkg, keys, playing);
        if (changed || saved) {
            saved = false;
            memory.changed();
        }
    }

    /** Where it is now: the last reported position moved on by the time since, while it plays; -1 unknown. */
    private static long position(PlaybackState st, long el) {
        if (st == null || st.getPosition() < 0) return -1;
        long p = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            p += (long) ((el - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        return Math.max(0, p);
    }

    private static String text(MediaMetadata md, String key) {
        if (md == null) return "";
        try {
            return PlayerPick.clean(md.getText(key));
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String string(MediaMetadata md, String key) {
        try {
            return md == null ? null : md.getString(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The session's ART, ALBUM_ART and DISPLAY_ICON bitmaps, each null when absent or empty. */
    private static Bitmap[] bitmaps(MediaMetadata md) {
        String[] keys = {MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON};
        Bitmap[] out = new Bitmap[keys.length];
        if (md == null) return out;
        for (int k = 0; k < keys.length; k++) {
            try {
                Bitmap b = md.getBitmap(keys[k]);
                if (b != null && b.getWidth() > 0 && b.getHeight() > 0) out[k] = b;
            } catch (RuntimeException ignoredException) {
            }
        }
        return out;
    }

    /** The first of these keys holding a picture URI, https or content (fetched by fetchArt), or null. */
    private static String artUri(MediaMetadata md, String... keys) {
        if (md == null) return null;
        for (String k : keys) {
            String s;
            try {
                s = md.getString(k);
            } catch (RuntimeException e) {
                continue;
            }
            if (s != null && (s.startsWith("https://") || s.startsWith("content://"))) return s;
        }
        return null;
    }

    /**
     * Tells covers apart without keeping them: size and a few pixels. Each event's metadata is a new parcel, so
     * identity says nothing; a new cover for the same title (an app that sends the title first) shows up here.
     */
    private static String signature(Bitmap b) {
        long h = b.getWidth() * 31L + b.getHeight();
        try {
            for (int y = 1; y <= 4; y++) {
                for (int x = 1; x <= 4; x++) {
                    h = h * 31 + b.getPixel(b.getWidth() * x / 5, b.getHeight() * y / 5);
                }
            }
        } catch (RuntimeException ignoredException) {
            // A hardware bitmap cannot be read: its size tells enough.
        }
        return Long.toHexString(h);
    }

    private static String fileName(String path) {
        if (path == null) return null;
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Decoded near ART_PX: a 3000 px cover is never held whole. */
    private static Bitmap decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= ART_PX) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
    }

    /**
     * The picture, at most ART_PX on its long side, as JPEG in player_art under {@code name}; its path, or null.
     * Written to a temporary file and renamed: the Home decoding it never reads half a file.
     */
    private String saveArt(Bitmap src, String name) {
        File dir = memory.artDir();
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File f = new File(dir, name), tmp = new File(dir, name + ".tmp");
        Bitmap scaled = null;
        try {
            float k = Math.min(1f, ART_PX / (float) Math.max(src.getWidth(), src.getHeight()));
            scaled = k < 1f ? Bitmap.createScaledBitmap(src, Math.max(1, Math.round(src.getWidth() * k)),
                    Math.max(1, Math.round(src.getHeight() * k)), true) : src;
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)) return null;
            }
            if (!tmp.renameTo(f)) return null;
            return f.getPath();
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "art not saved: " + e);
            return null;
        } finally {
            // Never recycle the session's own bitmap: it belongs to the parcel.
            if (scaled != null && scaled != src) scaled.recycle();
        }
    }
}
