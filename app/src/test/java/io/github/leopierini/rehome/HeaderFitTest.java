package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The weather stays left of the top-bar pills (CategoryHome.keepWeatherClear). Widths in dp at 1px = 1dp, measured
 * with Outfit on the 960x540dp Home of a 1080p-UI TV (55OLED706): clock "23:59" 150, weather box from 256, 14dp
 * padding each side, 12dp gap before the first pill, pills 10dp apart.
 */
public class HeaderFitTest {

    private static final int SCREEN = 960, EDGE = 56, PILL_GAP = 10;
    private static final int WEATHER_LEFT = 256, PAD = 14, GAP = 12;
    /** Pills in Italian: Ingressi 119, the Wi-Fi pill at its 180dp cap, Impostazioni 154, the gear 42. */
    private static final int INGRESSI = 119, WIFI_MAX = 180, IMPOSTAZIONI = 154, GEAR = 42, DEBUG_ATTIVO = 154;

    private static int pillLeft(int... pills) {
        return SCREEN - EDGE - HeaderFit.rowWidth(pills, PILL_GAP);
    }

    private static int room(int pillLeft) {
        return HeaderFit.textRoom(WEATHER_LEFT, pillLeft, GAP, PAD, PAD);
    }

    @Test
    public void rowWidthCountsEachPillAndItsMargin() {
        assertEquals(0, HeaderFit.rowWidth(new int[0], 10));
        assertEquals(52, HeaderFit.rowWidth(new int[]{42}, 10));
        assertEquals(371, HeaderFit.rowWidth(new int[]{INGRESSI, WIFI_MAX, GEAR}, 10));
    }

    @Test
    public void textRoomIsUpToAGapBeforeThePillLessThePadding() {
        assertEquals(500 - 12 - 256 - 28, HeaderFit.textRoom(256, 500, 12, 14, 14));
        assertTrue(HeaderFit.textRoom(256, 250, 12, 14, 14) < 0);
    }

    /** The owner's case: ADB off, Italian, a long network name. A labelled Settings pill left the weather 124dp. */
    @Test
    public void theGearAloneGivesTheWeatherItsRoomBack() {
        int labelled = room(pillLeft(INGRESSI, WIFI_MAX, IMPOSTAZIONI));
        int gear = room(pillLeft(INGRESSI, WIFI_MAX, GEAR));
        assertEquals(IMPOSTAZIONI - GEAR, gear - labelled);
        assertTrue("room " + gear, gear >= 230);
        // The sky's longest Italian text, "Parzialmente nuvoloso" (159), whole; so is "12° / 8°  |  Pioggia dalle 18" (158).
        assertTrue(gear >= 159);
    }

    @Test
    public void aLongCityEllipsizesAfterTheTemperature() {
        int room = room(pillLeft(INGRESSI, WIFI_MAX, GEAR));
        // Symbol 24 + 8 + "-10°C" 70 + 12 before the place name.
        int cityRoom = HeaderFit.cityRoom(room, 114);
        assertTrue(cityRoom > 0 && cityRoom < 177);   // "San Giovanni in Persiceto" is cut
        assertTrue(HeaderFit.cityShown(cityRoom, 177, 48));
        assertTrue(cityRoom >= 42);                   // "Roma" whole
    }

    @Test
    public void aCityWithNoRealRoomIsLeftOutRatherThanShownAsAnEllipsis() {
        assertFalse(HeaderFit.cityShown(36, 177, 48));
        assertTrue(HeaderFit.cityShown(36, 30, 48));   // short enough to fit whole
        assertTrue(HeaderFit.cityShown(48, 177, 48));
        assertFalse(HeaderFit.cityShown(-5, 30, 48));
    }

    /** 10 per character, so a part's width is easy to count: "12° / 8°" is 80. */
    private static final HeaderFit.Measure TEN = t -> 10f * t.length();
    private static final float DIV = 21;

    private static String line(String range, String rain, String wind, float room) {
        return String.join(" | ", HeaderFit.thirdLine(range, rain, wind, TEN, DIV, room));
    }

    @Test
    public void everyPartWhenTheyFitOrTheRoomIsNotKnown() {
        // 80 + 21 + 120 + 21 + 150 = 392
        assertEquals("12° / 8° | Rain from 18 | Wind 14 km/h NE",
                line("12° / 8°", "Rain from 18", "Wind 14 km/h NE", 392));
        assertEquals("12° / 8° | Rain from 18 | Wind 14 km/h NE",
                line("12° / 8°", "Rain from 18", "Wind 14 km/h NE", -1));
    }

    @Test
    public void theWindGoesWholeBeforeTheRainIsShortened() {
        assertEquals("12° / 8° | Rain from 18", line("12° / 8°", "Rain from 18", "Wind 14 km/h NE", 391));
        assertEquals("12° / 8° | Rain from 18", line("12° / 8°", "Rain from 18", "Wind 14 km/h NE", 221));
        // No rain to come: the wind follows the range, and goes whole too.
        assertEquals("12° / 8° | Wind 14 km/h NE", line("12° / 8°", "", "Wind 14 km/h NE", 251));
        assertEquals("12° / 8°", line("12° / 8°", "", "Wind 14 km/h NE", 250));
    }

    /** "Rain from 1…" would read as one o'clock: the rain is cut only between words, and never below "Rain…". */
    @Test
    public void theRainIsShortenedByWholeWordsOnly() {
        // 80 + 21 = 101 before the rain. "Rain from…" is 100, "Rain…" 50.
        assertEquals("12° / 8° | Rain from…", line("12° / 8°", "Rain from 18", "Wind 14 km/h NE", 220));
        assertEquals("12° / 8° | Rain from…", line("12° / 8°", "Rain from 18", "", 201));
        assertEquals("12° / 8° | Rain…", line("12° / 8°", "Rain from 18", "", 200));
        assertEquals("12° / 8° | Rain…", line("12° / 8°", "Rain from 18", "", 151));
        // Not even "Rain…": the rain goes whole, the range stays.
        assertEquals("12° / 8°", line("12° / 8°", "Rain from 18", "", 150));
        assertEquals("12° / 8° | Pioggia dalle…", line("12° / 8°", "Pioggia dalle 18", "", 241));
        assertEquals("12° / 8° | Pioggia…", line("12° / 8°", "Pioggia dalle 18", "", 240));
        assertEquals("12° / 8° | Pioviggine fino a…", line("12° / 8°", "Pioviggine fino a mezzanotte", "", 281));
    }

    @Test
    public void noShortenedTextEndsInADigitOrAPieceOfAWord() {
        String rain = "Pioviggine fino a mezzanotte";
        for (int room = 0; room < 400; room++) {
            for (String part : HeaderFit.thirdLine("12° / 8°", rain, "Vento 14 km/h NO", TEN, DIV, room)) {
                if (!part.endsWith("…")) continue;
                String kept = part.substring(0, part.length() - 1);
                assertTrue(part, rain.startsWith(kept));
                assertTrue(part, rain.length() > kept.length() && rain.charAt(kept.length()) == ' ');
            }
        }
    }

    @Test
    public void theRangeAloneStaysEvenWithNoRoom() {
        assertEquals("12° / 8°", line("12° / 8°", "Rain from 18", "Wind 14 km/h NE", 10));
        assertEquals("", line("", "", "", 10));
        assertEquals("Rain…", line("", "Rain from 18", "", 60));
        // Without a range, nothing rather than a rain or wind cut by letters.
        assertEquals("", line("", "Rain from 18", "", 40));
        assertEquals("", line("", "", "Wind 14 km/h NE", 40));
    }

    @Test
    public void widthsAreRoundedUpSoAnAlmostFitIsNotTakenForAFit() {
        HeaderFit.Measure almost = t -> 10f * t.length() + 0.4f;
        // 81 + 21 + 121 (each width rounded up): the whole rain needs 223 pixels.
        assertEquals("12° / 8° | Rain from…", String.join(" | ",
                HeaderFit.thirdLine("12° / 8°", "Rain from 18", "", almost, DIV, 222)));
        assertEquals("12° / 8° | Rain from 18", String.join(" | ",
                HeaderFit.thirdLine("12° / 8°", "Rain from 18", "", almost, DIV, 223)));
    }

    /** With ADB on the debug pill takes Inputs' label, and the room is still positive for the lines to ellipsize in. */
    @Test
    public void withTheDebugPillThereIsStillRoom() {
        int room = room(pillLeft(DEBUG_ATTIVO, GEAR, WIFI_MAX, GEAR));
        assertTrue("room " + room, room > 100);
    }
}
