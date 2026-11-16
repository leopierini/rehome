package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

/** Numbers and dates follow the words' language: English or Italian, never a third language inside English. */
public class WordsTest {

    @Test
    public void formattingLocaleIsEnglishOrItalianOnly() {
        assertEquals(Locale.ITALY, Words.formatLocale(Locale.ITALY));
        assertEquals(Locale.UK, Words.formatLocale(Locale.UK));
        assertEquals(Locale.ENGLISH, Words.formatLocale(Locale.GERMAN));
        assertEquals(Locale.ENGLISH, Words.formatLocale(Locale.FRANCE));
        assertEquals(Locale.ENGLISH, Words.formatLocale(null));
    }

    @Test
    public void germanTvGetsEnglishMonthsAndDecimalPoint() {
        Words de = TestWords.en(Locale.GERMAN);
        assertEquals("2.3 GB", DataUsage.size(de, 2_300_000_000L));
        TimeZone rome = TimeZone.getTimeZone("Europe/Rome");
        Calendar c = Calendar.getInstance(rome);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 12, 20, 10, 0);
        long then = c.getTimeInMillis();
        c.set(2026, Calendar.NOVEMBER, 15, 11, 40, 0);
        assertEquals("Nov 12 at 20:10", PlayerText.at(de, then, c.getTimeInMillis(), rome));
    }
}
