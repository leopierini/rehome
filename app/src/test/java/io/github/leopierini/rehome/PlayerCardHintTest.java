package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The long-OK hint shows only when the facts column ends above it. */
public class PlayerCardHintTest {

    @Test
    public void hintShowsWhenFactsEndAboveIt() {
        assertTrue(PlayerCard.hintFits(100, 130, 8));
        assertTrue(PlayerCard.hintFits(122, 130, 8));   // exactly the gap
    }

    @Test
    public void hintHidesWhenFactsReachOrOverlapIt() {
        assertFalse(PlayerCard.hintFits(123, 130, 8));
        assertFalse(PlayerCard.hintFits(131, 130, 0));
        assertFalse(PlayerCard.hintFits(150, 130, 8));
    }
}
