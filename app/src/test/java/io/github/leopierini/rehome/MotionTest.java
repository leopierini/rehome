package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** Reduced motion: every animation short. */
public class MotionTest {

    @Test
    public void reducedAnimationsLastAtMost100ms() {
        assertTrue(Motion.REDUCED_MS <= 100);
    }
}
