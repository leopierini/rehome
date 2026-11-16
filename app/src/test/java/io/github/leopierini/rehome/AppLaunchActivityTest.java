package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** market://launch?id=... never opens a TV's factory or service menu. */
public class AppLaunchActivityTest {

    @Test
    public void factoryAndServiceMenusAreRefused() {
        assertTrue(AppLaunchActivity.isServiceMenu("org.droidtv.factory"));
        assertTrue(AppLaunchActivity.isServiceMenu("com.cvte.tv.hkcfac"));
        assertTrue(AppLaunchActivity.isServiceMenu("mediatek.factorymenu.ui"));
        assertTrue(AppLaunchActivity.isServiceMenu("com.example.EngineerMode"));
    }

    @Test
    public void ordinaryAppsOpen() {
        assertFalse(AppLaunchActivity.isServiceMenu("com.netflix.ninja"));
        assertFalse(AppLaunchActivity.isServiceMenu("com.google.android.youtube.tv"));
        assertFalse(AppLaunchActivity.isServiceMenu("com.facebook.katana"));
        assertFalse(AppLaunchActivity.isServiceMenu("it.rainet.androidtv"));
    }
}
