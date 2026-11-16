package io.github.leopierini.rehome;

import java.util.ArrayList;
import java.util.List;

/**
 * TestHooks.player: made-up items for the player, to see on the TV the states that are hard to
 * produce on demand (a song playing with its year and genre, a video, Netflix's "Back to"). Never saved: added at
 * each refresh while the hook is set (ADB on only). Their keys start with "T|", so they never replace a real one.
 */
final class PlayerTestItems {

    private PlayerTestItems() {}

    static List<PlayerPick.Item> make(String which, long now) {
        List<PlayerPick.Item> out = new ArrayList<>();
        if (which == null) return out;
        boolean all = which.equals("all");
        if (all || which.equals("song")) {
            PlayerPick.Item s = item(PlayerPick.SONG, "com.spotify.tv.android", "Lantern's Bay", now - 60_000);
            s.artist = "The Hollow Pines";
            s.album = "Ash & Ember";
            s.year = "2016";
            s.genre = "Folk";
            s.track = 3;
            s.tracks = 12;
            s.position = 107_000;
            s.duration = 238_000;
            s.live = true;
            s.playing = true;
            out.add(s);
        }
        if (all || which.equals("video")) {
            PlayerPick.Item v = item(PlayerPick.VIDEO, "com.google.android.youtube.tv", "Kestrel - Route 9", now - 5 * 60_000);
            v.artist = "Kestrel";
            v.position = 48_000;
            v.duration = 252_000;
            out.add(v);
        }
        if (all || which.equals("app")) {
            PlayerPick.Item a = item(PlayerPick.APP, "com.netflix.ninja", null, now - 10 * 60_000);
            a.position = 31_000;
            a.playedMs = 70_000;
            out.add(a);
        }
        return out;
    }

    private static PlayerPick.Item item(int kind, String pkg, String title, long lastPlayed) {
        PlayerPick.Item i = new PlayerPick.Item();
        i.kind = kind;
        i.pkg = pkg;
        i.title = title;
        i.key = "T|" + PlayerPick.key(kind, pkg, title);
        i.lastPlayed = lastPlayed;
        i.playedMs = Math.max(i.playedMs, PlayerPick.MIN_PLAY_MS);
        return i;
    }
}
