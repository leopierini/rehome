package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** rehome_test_player's made-up items: one of each kind, a song that plays now, never a key of a real item. */
public class PlayerTestItemsTest {

    private static final long NOW = 1_794_740_000_000L;

    @Test
    public void allGivesOneOfEachKind() {
        List<PlayerPick.Item> all = PlayerTestItems.make("all", NOW);
        assertEquals(3, all.size());
        assertEquals(PlayerPick.SONG, all.get(0).kind);
        assertTrue(all.get(0).live && all.get(0).playing);
        assertEquals(PlayerPick.VIDEO, all.get(1).kind);
        assertEquals(PlayerPick.APP, all.get(2).kind);
        for (PlayerPick.Item i : all) assertTrue(i.key.startsWith("T|"));
    }

    @Test
    public void oneKind() {
        assertEquals(PlayerPick.VIDEO, PlayerTestItems.make("video", NOW).get(0).kind);
        assertEquals(1, PlayerTestItems.make("app", NOW).size());
        assertTrue(PlayerTestItems.make("boh", NOW).isEmpty());
        assertTrue(PlayerTestItems.make(null, NOW).isEmpty());
    }

    @Test
    public void theyAreRecent() {
        for (PlayerPick.Item i : PlayerTestItems.make("all", NOW)) assertTrue(NOW - i.lastPlayed < 60 * 60_000L);
    }
}
