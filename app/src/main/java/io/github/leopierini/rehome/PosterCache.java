package io.github.leopierini.rehome;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The posters of the Watch Next cards (player and row), on a metered hotspot: each poster URL is fetched once, cut to the
 * tile's size and kept on disk as a small JPEG (tens of KB), the folder capped at {@link #MAX_BYTES}. A download is
 * cut off past {@link #MAX_DOWNLOAD} or {@link #DEADLINE_MS}. Every method here does disk or network work: call it off
 * the main thread (WatchNextRow's poster thread).
 *
 * What other apps publish is not trusted: an image's size is read from its header before anything is decoded, and
 * a strip-shaped or huge one is refused; anything thrown while one poster is fetched only loses that poster. A
 * poster refused for good (a 4xx, too big, not an image) is remembered on disk, so a restart does not fetch it again.
 * One refused without a definite answer (a local one the launcher died on, see {@link #MARKER}, or a fetch that never
 * came back) is remembered with its time and tried again a day later ({@link #RETRY_REFUSED_MS}). One that failed for a
 * passing reason (the hotspot dropping, a 5xx, a timeout) is tried again an hour later or when the network comes back,
 * {@link #MAX_TRIES} times.
 */
final class PosterCache {

    private static final String TAG = "Rehome.Posters";
    static final long MAX_BYTES = 4L * 1024 * 1024;
    /** Real posters are tile sized (RaiPlay's resizegd/320x-, tens of KB); an app pointing at originals costs no more. */
    static final int MAX_DOWNLOAD = 1024 * 1024;
    /** One poster, from opening to the last byte. */
    static final long DEADLINE_MS = 15_000;
    /** A side longer than this, or a shape further from square than MAX_ASPECT: not a poster, never decoded. */
    static final int MAX_SIDE = 8192;
    static final int MAX_ASPECT = 6;
    /**
     * Pixels in the source, at most (a 4K frame): an interlaced PNG or a progressive JPEG is held whole by the decoder
     * whatever the sample size, and a one-colour 8192x8192 image fits in the download cap (re-review R3).
     */
    static final long MAX_PIXELS = 3840L * 2160;
    /** A refusal without a definite answer (the marker, a fetch given up) is tried again after this. */
    static final long RETRY_REFUSED_MS = 24 * 60 * 60 * 1000L;
    static final int MAX_TRIES = 3;
    static final long RETRY_MS = 60 * 60 * 1000L;
    /** Names (hash of the URL) refused for good, one per line; and the one being fetched now (see the class doc). */
    private static final String FAILED = "failed.txt", MARKER = "fetching.txt";
    private static final int MAX_FAILED = 64;

    /** What one attempt came to. */
    enum Outcome { OK, PASSING, REFUSED }

    private final File dir;
    private final ContentResolver resolver;
    private final int w, h;
    /** Passing failures in this process: URL to {tries, time of the last}. */
    private final Map<String, long[]> tries = Collections.synchronizedMap(new HashMap<>());
    /** Refused (names, as on disk) with when: 0 for good, else the time of a refusal tried again after a day. */
    private Map<String, Long> failed;
    /** Authorities of local posters whose fetch never came back: not asked again in this process (re-review R2). */
    private final Set<String> hung = Collections.synchronizedSet(new java.util.HashSet<>());
    /** Cuts a fetch off at its deadline: closes its connection or file (see fetchNow). */
    private final ScheduledThreadPoolExecutor watchdog = new ScheduledThreadPoolExecutor(1);
    /** The URL being fetched now, or null: WatchNextRow gives up on one that never returns. */
    private final java.util.concurrent.atomic.AtomicReference<String> current =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** System.nanoTime: it stands still while the TV sleeps, as the socket's own timeouts do (re-review R1 b). */
    private volatile long currentSince;

    PosterCache(File cacheDir, ContentResolver resolver, int w, int h) {
        this.dir = new File(cacheDir, "watchnext");
        this.resolver = resolver;
        this.w = w;
        this.h = h;
        watchdog.setKeepAliveTime(10, TimeUnit.SECONDS);
        watchdog.allowCoreThreadTimeOut(true);
        watchdog.setRemoveOnCancelPolicy(true);
    }

    /** The poster from the disk cache, or null. */
    Bitmap cached(String url) {
        File f = file(url);
        if (f == null || !f.isFile()) return null;
        Bitmap b;
        try {
            b = BitmapFactory.decodeFile(f.getPath());
        } catch (RuntimeException | OutOfMemoryError e) {
            b = null;
        }
        if (b == null) {
            // Not one this cache wrote whole: gone, so it is fetched again rather than failing on every read.
            if (!f.delete()) Log.d(TAG, "could not drop a bad poster");
            return null;
        }
        // Kept longest when used most recently: the cap drops the posters gone longest.
        if (!f.setLastModified(System.currentTimeMillis())) Log.d(TAG, "touch failed");
        return b;
    }

    /** Whether {@link #fetch} would try this URL now (not cached, not refused, not tried too often or too lately). */
    synchronized boolean wants(String url) {
        File f = file(url);
        if (f == null || f.isFile() || refusedNow(f.getName())) return false;
        if (WatchNext.posterLocal(url) && hung.contains(String.valueOf(authority(url)))) return false;
        long[] t = tries.get(url);
        return t == null || retryNow((int) t[0], t[1], System.currentTimeMillis());
    }

    /** Back online: the posters that failed for a passing reason may be tried again now, not in an hour. */
    void onOnline() {
        synchronized (tries) {
            for (long[] t : tries.values()) t[1] = 0;
        }
    }

    /** The URL being fetched since {@code olderThanMs} or longer, or null. */
    String stuck(long olderThanMs) {
        String u = current.get();
        return u != null && stuckFor(currentSince, System.nanoTime(), olderThanMs) ? u : null;
    }

    /**
     * A fetch that never came back (WatchNextRow left its thread): not tried again for a day, and no other local
     * poster from the same app in this process, so a deadlocked provider costs one thread, not one per poster.
     */
    void giveUp(String url) {
        refuse(url, System.currentTimeMillis());
        if (WatchNext.posterLocal(url)) hung.add(String.valueOf(authority(url)));
        current.compareAndSet(url, null);
    }

    /**
     * The Home is going. The watchdog is only shut, not stopped: a cut already scheduled still closes a read that hangs
     * (final review M5), then its thread ends.
     */
    void shutdown() {
        watchdog.shutdown();
    }

    /**
     * Fetches, cuts to size and stores one poster; true when it is now in the cache. https only when online, which
     * the caller checks; a content:// or android.resource:// poster is read from the app that serves it.
     */
    boolean fetch(String url) {
        File f = file(url);
        if (f == null || !wants(url)) return false;
        // Written before and removed after: found at the next start, it means the launcher died on this poster. Only
        // for posters another app serves on the TV: an https one is the same capped bytes, and a process killed while
        // it downloads (the low-memory killer as a film starts, a reinstall) says nothing about it (re-review R1 a).
        // With this process's id: a Home recreated mid-fetch finds its own process's fetch, not a crash (M5).
        File marker = WatchNext.posterLocal(url) ? new File(dir, MARKER) : null;
        String mark = f.getName() + " " + android.os.Process.myPid();
        if (marker != null) write(marker, mark);
        current.set(url);
        currentSince = System.nanoTime();
        Outcome o;
        try {
            o = fetchNow(url, f);
        } catch (Throwable t) {
            // An Error from decoding (out of memory) or anything else: this poster only, never the Home. The Home
            // going away under the fetch (the watchdog shut) is no answer about the poster.
            Log.i(TAG, "poster refused: " + t.getClass().getSimpleName());
            o = watchdog.isShutdown() ? Outcome.PASSING : Outcome.REFUSED;
        } finally {
            // A fetch given up long ago that returns at last leaves the newer fetch's state alone (re-review R2).
            current.compareAndSet(url, null);
            if (marker != null && mark.equals(readQuietly(marker)) && !marker.delete()) Log.d(TAG, "marker stays");
        }
        if (o == Outcome.REFUSED) {
            refuse(url, 0);
        } else if (o == Outcome.PASSING) {
            synchronized (tries) {
                long[] t = tries.get(url);
                if (t == null) tries.put(url, t = new long[2]);
                t[0]++;
                t[1] = System.currentTimeMillis();
            }
        }
        return o == Outcome.OK;
    }

    private Outcome fetchNow(String url, File f) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MS);
        byte[] bytes;
        if (WatchNext.posterOverNetwork(url)) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            // Per-read timeouts do not bound a server that sends a byte every few seconds: the watchdog cuts it.
            ScheduledFuture<?> cut = watchdog.schedule(c::disconnect, DEADLINE_MS, TimeUnit.MILLISECONDS);
            try {
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(true);
                int code;
                try {
                    code = c.getResponseCode();
                } catch (IOException e) {
                    return Outcome.PASSING;
                }
                if (code != 200) return refusedHttp(code) ? Outcome.REFUSED : Outcome.PASSING;
                // A huge original is not read at all.
                if (c.getContentLengthLong() > MAX_DOWNLOAD) return Outcome.REFUSED;
                try (InputStream in = c.getInputStream()) {
                    bytes = readCapped(in, deadline);
                } catch (IOException e) {
                    // Cut off, or the hotspot went: maybe better later.
                    return Outcome.PASSING;
                }
            } finally {
                cut.cancel(false);
                c.disconnect();
            }
        } else if (WatchNext.posterLocal(url)) {
            // Another app on the TV serves it: one that never answers is cut off too (the open cancelled, the file
            // closed), and is not asked again.
            CancellationSignal signal = new CancellationSignal();
            AssetFileDescriptor[] afd = new AssetFileDescriptor[1];
            ScheduledFuture<?> cut = watchdog.schedule(() -> {
                signal.cancel();
                closeQuietly(afd[0]);
            }, DEADLINE_MS, TimeUnit.MILLISECONDS);
            try {
                afd[0] = resolver.openAssetFileDescriptor(Uri.parse(url), "r", signal);
                if (afd[0] == null) return Outcome.REFUSED;
                try (InputStream in = afd[0].createInputStream()) {
                    bytes = readCapped(in, deadline);
                }
            } catch (java.io.FileNotFoundException e) {
                // A compressed raw resource has no file descriptor (openRawResourceFd): read as a stream, as before
                // (re-review R5 b). From the app's APK in this process: no provider to hang on.
                if (!url.startsWith(ContentResolver.SCHEME_ANDROID_RESOURCE + ":")) return Outcome.REFUSED;
                try (InputStream in = resolver.openInputStream(Uri.parse(url))) {
                    if (in == null) return Outcome.REFUSED;
                    bytes = readCapped(in, deadline);
                } catch (IOException | RuntimeException e2) {
                    return Outcome.REFUSED;
                }
            } catch (IOException | RuntimeException e) {
                return Outcome.REFUSED;
            } finally {
                cut.cancel(false);
                closeQuietly(afd[0]);
            }
        } else {
            return Outcome.REFUSED;
        }
        if (bytes == null) return Outcome.REFUSED;
        Bitmap tile = decodeToTile(bytes);
        if (tile == null) return Outcome.REFUSED;
        if (!dir.isDirectory() && !dir.mkdirs()) return Outcome.PASSING;
        File tmp = new File(dir, f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            tile.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            if (!tmp.delete()) Log.d(TAG, "tmp stays");
            return Outcome.PASSING;
        } finally {
            tile.recycle();
        }
        if (!tmp.renameTo(f)) {
            if (!tmp.delete()) Log.d(TAG, "tmp stays");
            return Outcome.PASSING;
        }
        trim();
        return Outcome.OK;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** Null when it is more than MAX_DOWNLOAD or still coming at the deadline. */
    private static byte[] readCapped(InputStream in, long deadlineNanos) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_DOWNLOAD) return null;
            if (System.nanoTime() > deadlineNanos) throw new IOException("too slow");
        }
        return out.toByteArray();
    }

    /**
     * Decoded no larger than needed (inSampleSize), then cut to the tile's shape and size, centred. The size comes from
     * the header first: an image that is not poster shaped costs nothing.
     */
    private Bitmap decodeToTile(byte[] bytes) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (!posterShaped(o.outWidth, o.outHeight)) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, w, h);
        Bitmap src = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (src == null) return null;
        Bitmap tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] crop = centreCrop(src.getWidth(), src.getHeight(), w, h);
        new Canvas(tile).drawBitmap(src, new Rect(crop[0], crop[1], crop[2], crop[3]), new Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return tile;
    }

    // ---- refused for good, on disk ----

    private synchronized Map<String, Long> failed() {
        if (failed != null) return failed;
        failed = new java.util.LinkedHashMap<>();
        try {
            File list = new File(dir, FAILED);
            if (list.isFile()) failed = parseFailed(Files.readAllLines(list.toPath(), StandardCharsets.UTF_8));
            // The launcher died while this one was being fetched or decoded: not again for a day. A marker of this
            // very process is a fetch still running for a Home that was recreated: no crash, left to that fetch.
            File marker = new File(dir, MARKER);
            String mark = marker.isFile() ? readQuietly(marker) : null;
            if (mark != null) {
                String[] parts = mark.split(" ");
                boolean ours = parts.length > 1 && parts[1].equals(String.valueOf(android.os.Process.myPid()));
                if (!ours) {
                    if (!marker.delete()) Log.d(TAG, "marker stays");
                    if (!parts[0].isEmpty() && !failed.containsKey(parts[0])) {
                        failed.put(parts[0], System.currentTimeMillis());
                        saveFailed();
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "refused list unread: " + e);
        }
        return failed;
    }

    /** Whether the poster with this file name is refused now (for good, or within a day of a timed refusal). */
    private synchronized boolean refusedNow(String name) {
        Long at = failed().get(name);
        return at != null && stillRefused(at, System.currentTimeMillis());
    }

    /** @param at 0 for good, else now: tried again after RETRY_REFUSED_MS */
    private synchronized void refuse(String url, long at) {
        File f = file(url);
        if (f == null) return;
        Map<String, Long> m = failed();
        Long was = m.get(f.getName());
        if (was != null && (was == 0 || was == at)) return;
        // Last in the list: the cap drops the oldest refusals first.
        m.remove(f.getName());
        m.put(f.getName(), at);
        saveFailed();
    }

    private void saveFailed() {
        List<String> lines = keepLast(formatFailed(failed), MAX_FAILED);
        failed = parseFailed(lines);
        write(new File(dir, FAILED), String.join("\n", lines));
    }

    private static String readQuietly(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void write(File f, String text) {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            Log.d(TAG, "not written: " + f.getName());
        }
    }

    /** Oldest-used posters out until the folder is under the cap. */
    private void trim() {
        // Posters only: the refused list and the marker are not cache.
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (files == null) return;
        List<long[]> entries = new ArrayList<>();
        for (int i = 0; i < files.length; i++) entries.add(new long[]{files[i].length(), files[i].lastModified(), i});
        for (int i : evict(entries, MAX_BYTES)) {
            if (!files[i].delete()) Log.d(TAG, "could not drop a poster");
        }
    }

    private File file(String url) {
        if (url == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(url.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return new File(dir, sb.append(".jpg").toString());
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    // ---- pure helpers (PosterCacheTest) ----

    /**
     * The largest power of two that still decodes at least the tile's size, and in any case no more than eight tiles'
     * worth of pixels: one short side alone would otherwise keep a long strip at full size. (A 2:3 movie poster
     * needs about three tiles' worth to fill the tile's width.)
     */
    static int sampleSize(int srcW, int srcH, int reqW, int reqH) {
        int s = 1;
        if (reqW <= 0 || reqH <= 0) return s;
        while (srcW / (s * 2) >= reqW && srcH / (s * 2) >= reqH) s *= 2;
        long budget = 8L * reqW * reqH;
        while ((long) (srcW / s) * (srcH / s) > budget && s < (1 << 20)) s *= 2;
        return s;
    }

    /** A size worth decoding: known, no side over MAX_SIDE, at most MAX_PIXELS, not a strip (MAX_ASPECT). */
    static boolean posterShaped(int srcW, int srcH) {
        if (srcW <= 0 || srcH <= 0 || srcW > MAX_SIDE || srcH > MAX_SIDE) return false;
        if ((long) srcW * srcH > MAX_PIXELS) return false;
        return Math.max(srcW, srcH) <= (long) MAX_ASPECT * Math.min(srcW, srcH);
    }

    /** A fetch started at {@code sinceNanos} (System.nanoTime) is past {@code olderThanMs} at {@code nowNanos}. */
    static boolean stuckFor(long sinceNanos, long nowNanos, long olderThanMs) {
        return nowNanos - sinceNanos > TimeUnit.MILLISECONDS.toNanos(olderThanMs);
    }

    /** A refusal from {@code at} (0: for good) still holds at {@code now}; a clock set back before it ends it. */
    static boolean stillRefused(long at, long now) {
        if (at <= 0) return true;
        return now >= at && now - at < RETRY_REFUSED_MS;
    }

    /** failed.txt: a name per line, then a space and the time for a refusal that is tried again after a day. */
    static java.util.LinkedHashMap<String, Long> parseFailed(List<String> lines) {
        java.util.LinkedHashMap<String, Long> m = new java.util.LinkedHashMap<>();
        for (String line : lines) {
            String t = line == null ? "" : line.trim();
            if (t.isEmpty()) continue;
            int sp = t.indexOf(' ');
            long at = 0;
            if (sp > 0) {
                try {
                    at = Long.parseLong(t.substring(sp + 1).trim());
                } catch (NumberFormatException e) {
                    at = 0;
                }
                t = t.substring(0, sp);
            }
            m.remove(t);
            m.put(t, Math.max(0, at));
        }
        return m;
    }

    static List<String> formatFailed(Map<String, Long> m) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : m.entrySet()) {
            long at = e.getValue() == null ? 0 : e.getValue();
            out.add(at > 0 ? e.getKey() + " " + at : e.getKey());
        }
        return out;
    }

    /** A content:// or android.resource:// poster's authority (the serving app), or null. */
    static String authority(String url) {
        try {
            return new java.net.URI(url).getAuthority();
        } catch (java.net.URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** An answer that will not change by asking again: a 4xx other than timeout and too-many-requests. */
    static boolean refusedHttp(int code) {
        return code >= 400 && code < 500 && code != 408 && code != 429;
    }

    /** Tried {@code tries} times, the last at {@code last}: again now? At most MAX_TRIES, RETRY_MS apart. */
    static boolean retryNow(int tries, long last, long now) {
        return tries < MAX_TRIES && (last <= 0 || now - last >= RETRY_MS || now < last);
    }

    /** The last {@code max} of a list (the oldest refusals go first). */
    static List<String> keepLast(List<String> names, int max) {
        return names.size() <= max ? names : new ArrayList<>(names.subList(names.size() - max, names.size()));
    }

    /** {left, top, right, bottom} of the largest centred part of the source with the tile's shape. */
    static int[] centreCrop(int srcW, int srcH, int dstW, int dstH) {
        if ((long) srcW * dstH > (long) srcH * dstW) {
            int cw = (int) ((long) srcH * dstW / dstH);
            int l = (srcW - cw) / 2;
            return new int[]{l, 0, l + cw, srcH};
        }
        int ch = (int) ((long) srcW * dstH / dstW);
        int t = (srcH - ch) / 2;
        return new int[]{0, t, srcW, t + ch};
    }

    /**
     * Which files go so the rest fit under {@code cap}: entries are {size, lastModified, index}; the least recently
     * used go first. Returns their indexes.
     */
    static List<Integer> evict(List<long[]> entries, long cap) {
        List<long[]> byAge = new ArrayList<>(entries);
        byAge.sort((a, b) -> Long.compare(a[1], b[1]));
        long total = 0;
        for (long[] e : byAge) total += e[0];
        List<Integer> out = new ArrayList<>();
        for (long[] e : byAge) {
            if (total <= cap) break;
            total -= e[0];
            out.add((int) e[2]);
        }
        return out;
    }
}
