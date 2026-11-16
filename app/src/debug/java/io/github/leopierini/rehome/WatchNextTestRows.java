package io.github.leopierini.rehome;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.tv.TvContract;
import android.util.Log;

import java.util.List;

/**
 * TestHooks "rehome_test_watchnext test": the launcher writes a few Watch Next rows of its own, so the "Continua a
 * guardare" row can be seen on a TV where no app has published one, and the filter checked on the device (two of
 * them must not show: one 40 days old, one watched to 97%). Every title starts with "Prova". The provider lets an
 * app write only its own rows (WRITE_EPG_DATA, a normal permission), and they are deleted at the first read after
 * the hook is switched off. Posters are the launcher's own banner: nothing is fetched over the network.
 *
 * Debug builds only (src/debug, with WRITE_EPG_DATA in src/debug's manifest): the release build has a stub that
 * writes nothing, so the Home people use holds no write permission on the TV's guide. Three test rows carry the
 * posters a hostile or broken app could publish, for checking PosterCache on the TV: a file that never ends
 * (TestStallProvider), a 999 999 x 300 px strip (res/raw/strip.png, about 300 KB) and a host that does not exist.
 */
final class WatchNextTestRows {

    private static final String TAG = "Rehome.WatchNext";
    private static final String WRITE = "com.android.providers.tv.permission.WRITE_EPG_DATA";
    private static final long DAY = 24L * 60 * 60 * 1000;

    private WatchNextTestRows() {}

    /** Puts the test rows in (hook on, none there) or takes them out (hook off, some there). True when it wrote. */
    static boolean sync(Context ctx, List<WatchNext.Item> rows, boolean wanted) {
        String self = ctx.getPackageName();
        int own = 0;
        for (WatchNext.Item i : rows) if (self.equals(i.pkg)) own++;
        if (wanted && own > 0 && "test-resort".equals(TestHooks.watchNext(ctx))) return resort(ctx, rows);
        if (wanted == (own > 0)) return false;
        if (ctx.checkSelfPermission(WRITE) != PackageManager.PERMISSION_GRANTED) return false;
        ContentResolver r = ctx.getContentResolver();
        try {
            if (!wanted) {
                // By id, with no selection: the provider takes none from an app without ACCESS_ALL_EPG_DATA.
                for (WatchNext.Item i : rows) {
                    if (self.equals(i.pkg)) {
                        r.delete(ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, i.id), null, null);
                    }
                }
                Log.i(TAG, "test rows deleted: " + own);
                return true;
            }
            long now = System.currentTimeMillis();
            String poster = "android.resource://" + self + "/" + R.drawable.banner;
            String home = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.HOME;"
                    + "package=" + self + ";end";
            // Opens the Home itself: allowed, it is the publisher's own activity.
            insert(r, "Prova · Documentario", "1", "3", "La partenza", poster, home,
                    WatchNext.TYPE_CONTINUE, now - 3_600_000L, 40, TvContract.WatchNextPrograms.TYPE_TV_EPISODE);
            // A long slogan-like title and no poster: the text card. Its link names another app: refused, so the
            // launcher opens its own app (the Home) instead.
            insert(r, "Prova · Ogni giorno un nuovo episodio da non perdere", "2", "7", null,
                    "content://" + self + ".teststall/poster",
                    "intent:#Intent;action=android.intent.action.MAIN;package=it.rainet.androidtv;end",
                    WatchNext.TYPE_CONTINUE, now - 2 * 3_600_000L, 70, TvContract.WatchNextPrograms.TYPE_TV_EPISODE);
            // Next episode: no bar. A web link no activity of the publisher handles: refused too.
            insert(r, "Prova · Film della sera", null, null, null, "https://example.invalid/poster.jpg",
                    "https://example.invalid/film",
                    WatchNext.TYPE_NEXT, now - DAY, -1, TvContract.WatchNextPrograms.TYPE_MOVIE);
            // Must not show: too old, and finished.
            insert(r, "Prova · Troppo vecchio", null, null, null, null, home,
                    WatchNext.TYPE_CONTINUE, now - 40 * DAY, 20, TvContract.WatchNextPrograms.TYPE_MOVIE);
            insert(r, "Prova · Finito", null, null, null, null, home,
                    WatchNext.TYPE_CONTINUE, now - 3 * 3_600_000L, 97, TvContract.WatchNextPrograms.TYPE_MOVIE);
            // A strip: refused from its header, never decoded (a text card).
            insert(r, "Prova · Striscia", null, null, null, "android.resource://" + self + "/" + R.raw.strip, home,
                    WatchNext.TYPE_CONTINUE, now - 4 * 3_600_000L, 10, TvContract.WatchNextPrograms.TYPE_MOVIE);
            Log.i(TAG, "test rows inserted: 6");
            return true;
        } catch (RuntimeException e) {
            Log.i(TAG, "test rows not written: " + e);
            return false;
        }
    }

    /**
     * "test-resort": "Prova · Striscia" watched just now, so it moves to the front of the row while focus may be on it
     * (focus must follow the card, CategoryHome.onWatchNext). Once: a row engaged in the last minute is left alone.
     */
    private static boolean resort(Context ctx, List<WatchNext.Item> rows) {
        long now = System.currentTimeMillis();
        for (WatchNext.Item i : rows) {
            if (!ctx.getPackageName().equals(i.pkg) || !"Prova · Striscia".equals(i.title)) continue;
            if (now - i.lastEngagement < 60_000L) return false;
            ContentValues v = new ContentValues();
            v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, now);
            try {
                ctx.getContentResolver().update(
                        ContentUris.withAppendedId(TvContract.WatchNextPrograms.CONTENT_URI, i.id), v, null, null);
                Log.i(TAG, "test row re-sorted: " + i.id);
                return true;
            } catch (RuntimeException e) {
                Log.i(TAG, "test row not re-sorted: " + e);
                return false;
            }
        }
        return false;
    }

    private static void insert(ContentResolver r, String title, String season, String episode, String episodeTitle,
                               String poster, String intent, int watchNextType, long engaged, int percent, int type) {
        ContentValues v = new ContentValues();
        v.put(TvContract.WatchNextPrograms.COLUMN_TYPE, type);
        v.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, watchNextType);
        v.put(TvContract.WatchNextPrograms.COLUMN_TITLE, title);
        if (season != null) v.put(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, season);
        if (episode != null) v.put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, episode);
        if (episodeTitle != null) v.put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, episodeTitle);
        if (poster != null) v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, poster);
        v.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intent);
        v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, engaged);
        if (percent >= 0) {
            long duration = 45 * 60 * 1000L;
            v.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (int) duration);
            v.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (int) (duration * percent / 100));
        }
        r.insert(TvContract.WatchNextPrograms.CONTENT_URI, v);
    }
}
