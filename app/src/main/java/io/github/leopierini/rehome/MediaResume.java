package io.github.leopierini.rehome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.util.Log;
import android.view.KeyEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * category_player: ⏯ on a song whose app is closed (its session gone) plays that app's last track again, as Android
 * 11's own media resumption does: the app's exported MediaBrowserService is connected with
 * {@link MediaBrowserService.BrowserRoot#EXTRA_RECENT}, and its session is told to play. The card then goes live by
 * itself (MediaMemory sees the session).
 *
 * <p>Checked on the TV (Spotify, the one music app here with such a service; Amazon Music, Radioplayer and RaiPlay Sound
 * have one too): connected at once, root "spotify_media_browser_root_empty", the session restored the last track
 * (title, artist, position) and played it from where it was. Spotify then brings its own screen to the front, and
 * when it was closed that screen's start paused the track ~5 s later: so the resume checks the state for a few
 * seconds and asks again while it does not play (PlayerPick.playAgain). Never played: the caller's fallback (the app
 * opens, as before).
 *
 * <p>One thread of its own; the browser is let go once the check is over (Spotify's playback runs in its own
 * foreground service).
 */
final class MediaResume {

    private static final String TAG = "Rehome.Memory";
    /** Connecting longer than this counts as a failure. */
    private static final long CONNECT_MS = 5_000;
    /** When the state is checked after the first play (ms); a try is made at each while it does not play. */
    private static final long[] CHECKS = {2_000, 4_000, 8_000, 12_000, 16_000};
    private static final long RELEASE_MS = 20_000;
    /** Not playing by now (two tries made): the app is opened instead, as before. */
    private static final long GIVE_UP_MS = 7_500;

    private static Handler bg;
    /** The apps a resume is running for (bg thread): a second ⏯ meanwhile does nothing. */
    private static final Set<String> busy = new HashSet<>();

    private MediaResume() {}

    private static synchronized Handler bg() {
        if (bg == null) {
            HandlerThread t = new HandlerThread("player-resume", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            bg = new Handler(t.getLooper());
        }
        return bg;
    }

    /** The app's exported media browser, or null. Package visibility: the manifest's MediaBrowserService query. */
    static ComponentName service(Context c, String pkg) {
        if (pkg == null) return null;
        try {
            Intent in = new Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg);
            List<ResolveInfo> l = c.getPackageManager().queryIntentServices(in, 0);
            if (l == null) return null;
            for (ResolveInfo r : l) {
                if (r.serviceInfo != null && r.serviceInfo.exported && pkg.equals(r.serviceInfo.packageName)
                        && r.serviceInfo.permission == null) {
                    return new ComponentName(r.serviceInfo.packageName, r.serviceInfo.name);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    /**
     * Plays {@code pkg}'s last track through its media browser. {@code onFail} runs on the main thread when the app
     * has no browser, refuses it, or never plays. {@code title}: the card's, only logged against what the app restored.
     */
    static void play(Context ctx, String pkg, String title, Runnable onFail) {
        final Context app = ctx.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final boolean log = TestHooks.memoryLog(app);
        bg().post(() -> {
            if (!busy.add(pkg)) return;
            ComponentName cn = service(app, pkg);
            if (cn == null) {
                done(pkg, null, main, onFail, log, "no media browser");
                return;
            }
            new Attempt(app, pkg, title, cn, main, onFail, log).start();
        });
    }

    private static void done(String pkg, MediaBrowser b, Handler main, Runnable onFail, boolean log, String why) {
        busy.remove(pkg);
        if (b != null) {
            try {
                b.disconnect();
            } catch (RuntimeException ignored) {
            }
        }
        if (log) Log.i(TAG, "resume " + pkg + ": " + why);
        if (onFail != null) main.post(onFail);
    }

    /** One resume: connect, play, check, let go. Every step on the bg thread. */
    private static final class Attempt extends MediaBrowser.ConnectionCallback {
        final Context app;
        final String pkg, title;
        final Handler main;
        final Runnable onFail;
        final boolean log;
        final MediaBrowser browser;
        MediaController c;
        int asked;
        boolean played, over;
        final long started = SystemClock.elapsedRealtime();

        Attempt(Context app, String pkg, String title, ComponentName cn, Handler main, Runnable onFail, boolean log) {
            this.app = app;
            this.pkg = pkg;
            this.title = title;
            this.main = main;
            this.onFail = onFail;
            this.log = log;
            Bundle hints = new Bundle();
            // What Android's own resumption asks: the root of the recent item, whose session can play it again.
            hints.putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT, true);
            browser = new MediaBrowser(app, cn, this, hints);
        }

        void start() {
            try {
                browser.connect();
            } catch (RuntimeException e) {
                fail("connect: " + e.getClass().getSimpleName());
                return;
            }
            bg().postDelayed(() -> {
                if (c == null && !over) fail("not connected in " + CONNECT_MS + " ms");
            }, CONNECT_MS);
        }

        @Override
        public void onConnected() {
            if (over) return;
            try {
                c = new MediaController(app, browser.getSessionToken());
                if (log) {
                    MediaMetadata md = c.getMetadata();
                    String has = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_TITLE);
                    Log.i(TAG, "resume " + pkg + ": connected, restored \"" + has + "\" (card \"" + title + "\", "
                            + (PlayerPick.norm(has).equals(PlayerPick.norm(title)) ? "same" : "other") + ")");
                }
                ask();
            } catch (RuntimeException e) {
                fail("session: " + e.getClass().getSimpleName());
                return;
            }
            for (long at : CHECKS) bg().postDelayed(this::check, at);
            bg().postDelayed(this::release, RELEASE_MS);
        }

        @Override
        public void onConnectionFailed() {
            fail("connection refused");
        }

        @Override
        public void onConnectionSuspended() {
            if (!over && !played) fail("connection lost");
        }

        /** The first try through the session's play(); later ones as a PLAY media button, which Spotify took cold. */
        private void ask() {
            asked++;
            if (asked == 1) {
                c.getTransportControls().play();
            } else {
                c.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
                c.dispatchMediaButtonEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
            }
            if (log) Log.i(TAG, "resume " + pkg + ": play asked (" + asked + ")");
        }

        private void check() {
            if (over || c == null) return;
            try {
                PlaybackState st = c.getPlaybackState();
                int state = st == null ? PlaybackState.STATE_NONE : st.getState();
                if (PlayerPick.plays(state)) played = true;
                if (log) {
                    Log.i(TAG, "resume " + pkg + ": +" + (SystemClock.elapsedRealtime() - started) + " ms state=" + state
                            + " pos=" + (st == null ? -1 : st.getPosition() / 1000) + "s");
                }
                // Never played after a few seconds: the app opens instead now, not at the end of the check.
                if (!played && SystemClock.elapsedRealtime() - started >= GIVE_UP_MS) {
                    fail("never played");
                    return;
                }
                if (PlayerPick.playAgain(state, asked)) ask();
            } catch (RuntimeException e) {
                if (log) Log.i(TAG, "resume " + pkg + ": check failed " + e.getClass().getSimpleName());
            }
        }

        /** The check is over: the browser is let go; the app opens instead if it never played. */
        private void release() {
            if (over) return;
            check();
            if (!played) {
                fail("never played");
                return;
            }
            over = true;
            busy.remove(pkg);
            try {
                browser.disconnect();
            } catch (RuntimeException ignored) {
            }
            if (log) Log.i(TAG, "resume " + pkg + ": playing, browser released");
        }

        private void fail(String why) {
            if (over) return;
            over = true;
            done(pkg, browser, main, onFail, log, why);
        }
    }
}
