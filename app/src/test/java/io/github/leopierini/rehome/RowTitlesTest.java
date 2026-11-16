package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The rows' titles: English keys in config.xml, shown in the UI's language. */
public class RowTitlesTest {

    @Test
    public void knownTitlesInEnglishAndItalian() {
        Words en = TestWords.en(), it = TestWords.it();
        assertEquals("Music", RowTitles.shown(en, "Music"));
        assertEquals("Other apps", RowTitles.shown(en, "Other apps"));
        assertEquals("Media", RowTitles.shown(it, "Media"));
        assertEquals("Musica", RowTitles.shown(it, "Music"));
        assertEquals("Giochi", RowTitles.shown(it, "Games"));
        assertEquals("Strumenti", RowTitles.shown(it, "Tools"));
        assertEquals("Altre app", RowTitles.shown(it, "Other apps"));
    }

    @Test
    public void anyOtherTitleShowsAsWritten() {
        assertEquals("Kids", RowTitles.shown(TestWords.it(), "Kids"));
        assertEquals("Musica", RowTitles.shown(TestWords.en(), "Musica"));
        assertEquals(0, RowTitles.res(null));
    }
}
