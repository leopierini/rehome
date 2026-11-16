package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

/**
 * Left and Right on the player: a song walks ⏮ ⏯ ⏭ and past them to the item before or after; a
 * video or an app has one button, so they change the item at once; at the list's ends nothing moves.
 * Buttons: SONG 0 ⏮, 1 ⏯, 2 ⏭; VIDEO/APP 0 Resume; -1 is "the main one" (⏯ or Resume), where an item is entered.
 */
public class PlayerCardNavTest {

    private static final int SONG = PlayerPick.SONG, VIDEO = PlayerPick.VIDEO, APP = PlayerPick.APP;

    @Test
    public void songLeftWalksButtonsThenItems() {
        assertArrayEquals(new int[]{1, 0}, PlayerCard.Nav.left(1, -1, SONG, 3));   // from ⏯ (main) to ⏮
        assertArrayEquals(new int[]{1, 0}, PlayerCard.Nav.left(1, 1, SONG, 3));
        assertArrayEquals(new int[]{1, 1}, PlayerCard.Nav.left(1, 2, SONG, 3));
        assertArrayEquals(new int[]{0, -1}, PlayerCard.Nav.left(1, 0, SONG, 3));   // past ⏮: the item before
    }

    @Test
    public void songRightWalksButtonsThenItems() {
        assertArrayEquals(new int[]{1, 2}, PlayerCard.Nav.right(1, -1, SONG, 3));
        assertArrayEquals(new int[]{1, 1}, PlayerCard.Nav.right(1, 0, SONG, 3));
        assertArrayEquals(new int[]{2, -1}, PlayerCard.Nav.right(1, 2, SONG, 3));  // past ⏭: the item after
    }

    @Test
    public void videoLeftRightChangeItem() {
        assertArrayEquals(new int[]{0, -1}, PlayerCard.Nav.left(1, -1, VIDEO, 3));
        assertArrayEquals(new int[]{2, -1}, PlayerCard.Nav.right(1, 0, VIDEO, 3));
        assertArrayEquals(new int[]{2, -1}, PlayerCard.Nav.right(1, -1, APP, 3));
    }

    @Test
    public void edgesStay() {
        assertArrayEquals(new int[]{0, -1}, PlayerCard.Nav.left(0, -1, VIDEO, 3));
        assertArrayEquals(new int[]{2, -1}, PlayerCard.Nav.right(2, -1, VIDEO, 3));
        assertArrayEquals(new int[]{0, 0}, PlayerCard.Nav.left(0, 0, SONG, 3));
        assertArrayEquals(new int[]{2, 2}, PlayerCard.Nav.right(2, 2, SONG, 3));
    }

    @Test
    public void singleItemSongOnlyButtons() {
        assertArrayEquals(new int[]{0, 0}, PlayerCard.Nav.left(0, 0, SONG, 1));
        assertArrayEquals(new int[]{0, 2}, PlayerCard.Nav.right(0, 1, SONG, 1));
        assertArrayEquals(new int[]{0, 2}, PlayerCard.Nav.right(0, 2, SONG, 1));
    }

    @Test
    public void unfocusedShowsFirstFocusedKeepsItsItem() {
        // With focus on Media the card showed 4/4; it must show the newest (1/N) until entered.
        java.util.List<String> keys = java.util.Arrays.asList("a", "b", "c");
        org.junit.Assert.assertEquals(0, PlayerCard.Nav.shown(keys, "c", false));
        org.junit.Assert.assertEquals(2, PlayerCard.Nav.shown(keys, "c", true));
        org.junit.Assert.assertEquals(0, PlayerCard.Nav.shown(keys, "gone", true));
        org.junit.Assert.assertEquals(0, PlayerCard.Nav.shown(keys, null, true));
    }
}
