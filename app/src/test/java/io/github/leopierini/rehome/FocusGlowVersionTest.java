package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Speed review M9: GlowNodes records a glow's node again only when FocusGlow.version() changed (or the holder records),
 * so every setter that changes what draw() paints must bump it. A Drawable cannot be made on the JVM, so the setters
 * are pinned on the source.
 */
public class FocusGlowVersionTest {

    private static String body(String code, String signature) {
        int at = code.indexOf(signature);
        assertTrue("missing: " + signature, at > 0);
        int end = code.indexOf("\n    }\n", at);
        return code.substring(at, end);
    }

    @Test
    public void everyDrawRelevantSetterBumpsTheVersion() throws Exception {
        String code = LauncherLocaleTest.source("FocusGlow.java");
        String[] setters = {
                "void setColor(int color)",
                "void setFalloff(float power)",
                "void setMaskSize(int width, int height)",
                "protected void onBoundsChange(Rect bounds)",
                "public void setAlpha(int alpha)",
                "public void setColorFilter(ColorFilter cf)",
        };
        for (String s : setters) assertTrue(s + " bumps version()", body(code, s).contains("version++;"));
    }
}
