package io.github.leopierini.rehome;

import android.content.Context;
import android.media.session.MediaController;
import android.os.Handler;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * category_player: the player's memory, one per process, shared by the one who records (MediaMemory, in
 * MediaListener: other apps' sessions, also while Home is hidden) and the one who shows (CategoryHome's PlayerCard).
 * Thread-safe; every method that may touch the disk is to be called off the main thread.
 *
 * <p>Files in filesDir: player_memory.txt (PlayerStore), player_removed.txt (the keys the owner took out with
 * "Remove from player": Watch Next rows the launcher cannot delete, one per line), player_art/.
 */
final class PlayerMemory {

    private static final String TAG = "Rehome.Memory";
    static final String REMOVED_FILE = "player_removed.txt";
    /** Keys taken out kept at most: Watch Next rows go stale within 30 days anyway. */
    private static final int REMOVED_MAX = 100;

    /** Art files younger than this are never deleted as orphans: their track may not be in the memory yet. */
    private static final long ART_GRACE_MS = 10 * 60_000L;

    private static PlayerMemory instance;

    /** The file writes, in save order (saves counts them, written is the last one on disk). */
    private final Object writing = new Object();
    private long saves, written;

    private final File dir, artDir;
    /** By key, newest first after each save (PlayerPick.evict). */
    private final LinkedHashMap<String, PlayerPick.Item> items = new LinkedHashMap<>();
    private final LinkedHashMap<String, Boolean> removed = new LinkedHashMap<>();
    /** The live sessions, by package (MediaMemory), and the key each one plays now, with whether it plays. */
    private final Map<String, MediaController> controllers = new HashMap<>();
    private final Map<String, String> liveKey = new HashMap<>();
    private final Map<String, Boolean> livePlaying = new HashMap<>();
    private final Map<Runnable, Handler> listeners = new LinkedHashMap<>();

    /** The memory, read from disk the first time: call off the main thread. */
    static synchronized PlayerMemory get(Context c) {
        if (instance == null) instance = new PlayerMemory(c.getApplicationContext().getFilesDir());
        return instance;
    }

    private PlayerMemory(File dir) {
        this.dir = dir;
        this.artDir = new File(dir, PlayerStore.ART_DIR);
        for (PlayerPick.Item i : PlayerStore.decode(read(new File(dir, PlayerStore.FILE)))) items.put(i.key, i);
        String r = read(new File(dir, REMOVED_FILE));
        if (r != null) for (String k : r.split("\n")) if (!k.isEmpty()) removed.put(k, true);
    }

    File artDir() {
        return artDir;
    }

    /** Copies of the remembered items, with their live state and (playing) their position now. */
    synchronized List<PlayerPick.Item> items() {
        List<PlayerPick.Item> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (PlayerPick.Item i : items.values()) {
            PlayerPick.Item c = i.copy();
            c.live = c.key.equals(liveKey.get(c.pkg));
            c.playing = c.live && Boolean.TRUE.equals(livePlaying.get(c.pkg));
            c.position = PlayerPick.positionNow(c, now);
            out.add(c);
        }
        return out;
    }

    synchronized PlayerPick.Item item(String key) {
        PlayerPick.Item i = items.get(key);
        return i == null ? null : i.copy();
    }

    synchronized Set<String> removed() {
        return new HashSet<>(removed.keySet());
    }

    /** The live session of an app, or null. Its calls are binder calls: make them off the main thread. */
    synchronized MediaController controller(String pkg) {
        return pkg == null ? null : controllers.get(pkg);
    }

    // ---- the recorder's side (MediaMemory's thread) ----

    /** The live sessions now; true when that changed what the Home shows (or controls). */
    synchronized boolean setLive(Map<String, MediaController> byPkg, Map<String, String> keys,
                                 Map<String, Boolean> playing) {
        if (controllers.equals(byPkg) && liveKey.equals(keys) && livePlaying.equals(playing)) return false;
        controllers.clear();
        controllers.putAll(byPkg);
        liveKey.clear();
        liveKey.putAll(keys);
        livePlaying.clear();
        livePlaying.putAll(playing);
        return true;
    }

    /**
     * Puts or replaces one item (a copy) and saves; a "Remove from player" is forgotten when it plays again. False when
     * the item was there already, unchanged (nothing written).
     */
    boolean put(PlayerPick.Item item, boolean log) {
        synchronized (this) {
            PlayerPick.Item c = item.copy();
            c.live = c.playing = false;
            c.next = null;
            PlayerPick.Item had = items.get(c.key);
            // Spotify sends a burst of states at each pause or play: an unchanged item is not written again.
            if (had != null && !removed.containsKey(c.key) && PlayerStore.encode(java.util.Collections.singletonList(had))
                    .equals(PlayerStore.encode(java.util.Collections.singletonList(c)))) {
                return false;
            }
            items.put(c.key, c);
            removed.remove(c.key);
        }
        save(log ? item : null);
        return true;
    }

    void changed() {
        Map<Runnable, Handler> copy;
        synchronized (this) {
            copy = new LinkedHashMap<>(listeners);
        }
        for (Map.Entry<Runnable, Handler> e : copy.entrySet()) {
            e.getValue().removeCallbacks(e.getKey());
            e.getValue().post(e.getKey());
        }
    }

    // ---- the Home's side ----

    synchronized void addListener(Runnable onChange, Handler on) {
        listeners.put(onChange, on);
    }

    synchronized void removeListener(Runnable onChange) {
        Handler h = listeners.remove(onChange);
        if (h != null) h.removeCallbacks(onChange);
    }

    /**
     * "Remove from player": a remembered item is forgotten (and its art), a Watch Next row hidden. The keys
     * come from PlayerPick.removeKeys: a remembered video merged with its row takes the row's key along.
     */
    void remove(List<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        String hidden = null;
        synchronized (this) {
            boolean more = false;
            for (String key : keys) {
                if (key == null || items.remove(key) != null) continue;
                removed.put(key, true);
                more = true;
            }
            while (removed.size() > REMOVED_MAX) removed.remove(removed.keySet().iterator().next());
            if (more) hidden = String.join("\n", removed.keySet());
        }
        if (hidden != null) {
            synchronized (writing) {
                write(new File(dir, REMOVED_FILE), hidden);
            }
        }
        save(null);
        changed();
    }

    /**
     * The memory to disk (the newest MEMORY_MAX), then the art files no item points to are deleted. The file is written
     * outside the memory's lock (items() on the Home's side never waits for an fsync), in order: a save never writes
     * an older text over a newer one.
     */
    private void save(PlayerPick.Item logged) {
        List<PlayerPick.Item> kept;
        String text;
        long seq;
        synchronized (this) {
            kept = PlayerPick.evict(new ArrayList<>(items.values()));
            items.clear();
            for (PlayerPick.Item i : kept) items.put(i.key, i);
            text = PlayerStore.encode(kept);
            seq = ++saves;
        }
        synchronized (writing) {
            if (seq > written) {
                write(new File(dir, PlayerStore.FILE), text);
                written = seq;
            }
        }
        String[] names = artDir.list();
        long young = System.currentTimeMillis() - ART_GRACE_MS;
        if (names != null) {
            for (String orphan : PlayerStore.orphanArt(kept, Arrays.asList(names))) {
                File f = new File(artDir, orphan);
                // A picture saved for a track not remembered yet (under MIN_PLAY_MS), or being written: not an orphan.
                if (f.lastModified() > young) continue;
                if (!f.delete()) Log.w(TAG, "art not deleted: " + orphan);
            }
        }
        if (logged != null) {
            Log.i(TAG, "saved " + logged.key + " kind=" + logged.kind + " title=\"" + logged.title + "\" played="
                    + logged.playedMs / 1000 + "s pos=" + logged.position / 1000 + "s art=" + (logged.art != null) + " artUrl=" + (logged.artUrl != null) + " link=" + logged.link);
        }
    }

    private static String read(File f) {
        try {
            return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Written to a temporary file and renamed: a crash mid-write leaves the old file, never half of one. */
    private static void write(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "not saved: " + f.getName() + ": " + e);
            return;
        }
        if (!tmp.renameTo(f)) Log.w(TAG, "not renamed: " + f.getName());
    }
}
