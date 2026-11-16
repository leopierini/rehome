package io.github.leopierini.rehome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LauncherLocaleTest {
    @Test
    public void plainTagsBecomeLocales() {
        assertEquals("vi", LauncherLocale.parse("vi").getLanguage());
        assertEquals("BR", LauncherLocale.parse("pt-BR").getCountry());
        assertEquals("fil", LauncherLocale.parse(" fil ").getLanguage());
    }

    @Test
    public void tagsWithScriptsAndExtensionsBecomeLocales() {
        assertEquals("Latn", LauncherLocale.parse("sr-Latn").getScript());
        assertEquals("ar", LauncherLocale.parse("ar-EG-u-nu-latn").getLanguage());
        assertEquals("latn", LauncherLocale.parse("ar-EG-u-nu-latn").getUnicodeLocaleType("nu"));
    }

    @Test
    public void emptyOrMalformedTagsFollowTheTv() {
        assertNull(LauncherLocale.parse(""));
        assertNull(LauncherLocale.parse(null));
        assertNull(LauncherLocale.parse("Vietnamese"));
        assertNull(LauncherLocale.parse("vi_VN"));
        assertNull(LauncherLocale.parse("../vi"));
        assertNull(LauncherLocale.parse("vi-"));
        assertNull(LauncherLocale.parse("vi--VN"));
    }

    @Test
    public void onlyAConfigReadThroughTheSameContextIsKeptForCreate() {
        Object base = new Object(), languageContext = new Object(), config = new Object();
        assertEquals(config, LauncherLocale.keptForCreate(base, base, config));
        // A locale override reads resources in another language: onCreate loads its own.
        assertNull(LauncherLocale.keptForCreate(languageContext, base, config));
    }

    @Test
    public void theHandOverIsNoLongerAStatic() throws Exception {
        // Speed review M5: a process-wide static that every activity's wrap set and only MainActivity read.
        String config = source("Config.java"), main = source("MainActivity.java");
        assertFalse(config.contains("static Config handed"));
        assertFalse(config.contains("loadForCreate"));
        assertTrue(main.contains("private Config createConfig;"));
        assertTrue(main.contains("LauncherLocale.keptForCreate(wrapped, base, config)"));
    }

    static String source(String name) throws java.io.IOException {
        java.io.File src = new java.io.File("src/main/java/io/github/leopierini/rehome/" + name);
        if (!src.isFile()) src = new java.io.File("app/src/main/java/io/github/leopierini/rehome/" + name);
        assertTrue(name + " not found from " + new java.io.File(".").getAbsolutePath(), src.isFile());
        return new String(java.nio.file.Files.readAllBytes(src.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }
}
