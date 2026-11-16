package io.github.leopierini.rehome;

import android.content.ComponentCallbacks2;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MemoryTrimPolicyTest {
    @Test
    public void ordinaryBackgroundTransitionKeepsWarmResources() {
        assertFalse(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, false));
        assertFalse(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, true));
    }

    @Test
    public void backgroundPressureReleasesResourcesOnlyWhenHomeIsStopped() {
        assertTrue(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, false));
        assertTrue(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, false));
        assertFalse(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, true));
        assertFalse(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, true));
    }

    /** Final fix I2: behind an app the bound listener's process hears RUNNING_MODERATE first, never BACKGROUND. */
    @Test
    public void moderatePressureOnABoundProcessReleasesOnlyWhenHomeIsStopped() {
        assertTrue(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, false));
        assertFalse(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, true));
    }

    @Test
    public void lowMemorySignalsReleaseResourcesWhenHomeIsStopped() {
        assertTrue(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, false));
        assertTrue(MemoryTrimPolicy.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, false));
    }
}
