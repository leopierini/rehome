package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Speed review M4: CategoryHome makes its tiles on several threads (ParallelOrdered). A banner is the app's own, but an
 * app without an icon of its own gets the framework's default one, whose VectorDrawable state (and native cache) every
 * such app shares: its glow colour must not be drawn on the helpers. Pinned on the source, as the threading cannot run
 * on the JVM.
 */
public class TileGlowThreadTest {

    @Test
    public void onlyBannersAreSampledOnTheHelperThreads() throws Exception {
        String code = LauncherLocaleTest.source("CategoryHome.java");
        int tile = code.indexOf("private Tile tile(PackageManager pm, ResolveInfo ri, boolean leanback)");
        int end = code.indexOf("\n    }\n", tile);
        assertTrue(tile > 0 && end > tile);
        String body = code.substring(tile, end);
        assertTrue(body.contains("if (t.banner != null) t.glow = FocusLook.tileGlow(FocusLook.averageColor(t.banner));"));
        assertFalse("no icon drawn on a helper thread", body.contains("averageColor(t.icon)")
                || body.contains("t.banner != null ? t.banner : t.icon"));
    }

    @Test
    public void iconTilesAreSampledOnTheCallerAfterTheMap() throws Exception {
        String code = LauncherLocaleTest.source("CategoryHome.java");
        int map = code.indexOf("ParallelOrdered.map(index, i -> tile(");
        int icon = code.indexOf("if (t.banner == null) t.glow = FocusLook.tileGlow(FocusLook.averageColor(t.icon));");
        assertTrue(map > 0);
        assertTrue("after the parallel map", icon > map);
    }
}
