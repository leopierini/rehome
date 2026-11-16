package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Plain JVM tests for the weather's rain line: Open-Meteo's next hours turned into "Rain from 18:00". */
public class RainNoteTest {

    private static final Words EN = TestWords.en();

    private static final int DRY = 3;       // overcast
    private static final int RAIN = 61;     // light rain
    private static final int SHOWER = 80;
    private static final int STORM = 95;
    private static final int SNOW = 73;

    /** Six hours from 17:00 on 15 November, local time as the API writes it. */
    private static String[] hours(int firstHour) {
        String[] t = new String[6];
        for (int i = 0; i < 6; i++) {
            int h = firstHour + i;
            String day = h >= 24 ? "2026-11-16" : "2026-11-15";
            t[i] = String.format(java.util.Locale.ROOT, "%sT%02d:00", day, h % 24);
        }
        return t;
    }

    private static String note(int firstHour, int[] prob, int[] codes) {
        return RainNote.note(EN, hours(firstHour), prob, codes, String.format(java.util.Locale.ROOT,
                "2026-11-15T%02d", firstHour));
    }

    private static final int[] DRY_CODES = {DRY, DRY, DRY, DRY, DRY, DRY};

    @Test
    public void dryWindowSaysNothing() {
        assertEquals("", note(17, new int[]{0, 10, 20, 30, 40, 49}, DRY_CODES));
    }

    @Test
    public void rainLaterByProbability() {
        assertEquals("Rain from 18:00", note(17, new int[]{10, 60, 70, 80, 20, 0}, DRY_CODES));
    }

    @Test
    public void rainLaterByCodeAlone() {
        assertEquals("Rain from 19:00", note(17, new int[]{0, 0, 30, 30, 0, 0},
                new int[]{DRY, DRY, SHOWER, DRY, DRY, DRY}));
        assertEquals("Rain from 21:00", note(17, new int[]{0, 0, 0, 0, 0, 0},
                new int[]{DRY, DRY, DRY, DRY, STORM, DRY}));
    }

    @Test
    public void rainingNowAndStopping() {
        assertEquals("Rain until 20:00", note(17, new int[]{90, 80, 70, 10, 0, 0},
                new int[]{RAIN, RAIN, RAIN, DRY, DRY, DRY}));
    }

    @Test
    public void rainingNowStopsThenStartsAgain() {
        // Wet now and next hour, dry at 19, wet again from 20: the line says when this rain stops, not the next one.
        assertEquals("Rain until 19:00", note(17, new int[]{90, 80, 10, 70, 80, 0},
                new int[]{RAIN, RAIN, DRY, RAIN, RAIN, DRY}));
    }

    @Test
    public void rainThroughTheWholeWindow() {
        assertEquals("Rain", note(17, new int[]{90, 80, 70, 60, 55, 50}, DRY_CODES));
    }

    @Test
    public void thresholdIsFiftyPercent() {
        assertEquals("Rain from 22:00", note(17, new int[]{0, 0, 0, 0, 0, 50}, DRY_CODES));
        assertEquals("", note(17, new int[]{0, 0, 0, 0, 0, 49}, DRY_CODES));
    }

    @Test
    public void snowSaysSnow() {
        assertEquals("Snow from 19:00", note(17, new int[]{0, 0, 70, 70, 0, 0},
                new int[]{DRY, DRY, SNOW, SNOW, DRY, DRY}));
        assertEquals("Snow", note(17, new int[]{90, 90, 90, 90, 90, 90},
                new int[]{SNOW, SNOW, SNOW, SNOW, SNOW, SNOW}));
        assertEquals("Snow until 19:00", note(17, new int[]{90, 90, 0, 0, 0, 0},
                new int[]{SNOW, 85, DRY, DRY, DRY, DRY}));
    }

    @Test
    public void midnightWrap() {
        // 22:00 to 03:00: rain from 01:00 the next day; midnight is a word.
        assertEquals("Rain from 1:00", note(22, new int[]{0, 0, 0, 80, 80, 80}, DRY_CODES));
        assertEquals("Rain from midnight", note(22, new int[]{0, 0, 80, 80, 80, 80}, DRY_CODES));
        assertEquals("Rain until midnight", note(22, new int[]{80, 80, 0, 0, 0, 0}, DRY_CODES));
        assertEquals("Rain until 1:00", note(22, new int[]{80, 80, 80, 0, 0, 0}, DRY_CODES));
        assertEquals("Rain from 2:00", note(22, new int[]{0, 0, 0, 0, 80, 0}, DRY_CODES));
    }

    @Test
    public void italianSaysTheHourItsOwnWay() {
        Words it = TestWords.it();
        int[] codes = DRY_CODES;
        assertEquals("Pioggia dalle 18", RainNote.note(it, hours(17), new int[]{10, 60, 70, 80, 20, 0}, codes,
                "2026-11-15T17"));
        assertEquals("Pioggia fino alle 20", RainNote.note(it, hours(17), new int[]{90, 80, 70, 10, 0, 0}, codes,
                "2026-11-15T17"));
        assertEquals("Pioggia dall'1", RainNote.note(it, hours(22), new int[]{0, 0, 0, 80, 80, 80}, codes,
                "2026-11-15T22"));
        assertEquals("Pioggia fino a mezzanotte", RainNote.note(it, hours(22), new int[]{80, 80, 0, 0, 0, 0}, codes,
                "2026-11-15T22"));
        assertEquals("Neve dalle 19", RainNote.note(it, hours(17), new int[]{0, 0, 70, 70, 0, 0},
                new int[]{DRY, DRY, SNOW, SNOW, DRY, DRY}, "2026-11-15T17"));
    }

    @Test
    public void hoursAlreadyPastAreSkipped() {
        // A reading saved at 17:00 shown again at 19:xx: 17 and 18 are gone, it rains from 19 on, so "now".
        String[] t = hours(17);
        int[] prob = {0, 0, 80, 80, 0, 0};
        assertEquals("Rain until 21:00", RainNote.note(EN, t, prob, DRY_CODES, "2026-11-15T19"));
        // Past the whole window: nothing to say.
        assertEquals("", RainNote.note(EN, t, prob, DRY_CODES, "2026-11-15T23"));
        // Across midnight the day decides, not the hour alone.
        assertEquals("Rain from 19:00", RainNote.note(EN, t, prob, DRY_CODES, "2026-11-14T23"));
    }

    @Test
    public void missingOrShortDataIsHandled() {
        assertEquals("", RainNote.note(EN, null, null, null, "2026-11-15T17"));
        assertEquals("", RainNote.note(EN, new String[0], new int[0], new int[0], "2026-11-15T17"));
        // No probabilities (-1) at all: the codes alone decide.
        assertEquals("Rain from 18:00", RainNote.note(EN, hours(17), null,
                new int[]{DRY, RAIN, DRY, DRY, DRY, DRY}, "2026-11-15T17"));
        // Arrays shorter than the times: only the hours with data count.
        assertEquals("Rain from 18:00", RainNote.note(EN, hours(17), new int[]{0, 90}, null, "2026-11-15T17"));
        // A garbled time is skipped.
        assertEquals("", RainNote.note(EN, new String[]{"x"}, new int[]{90}, new int[]{RAIN}, "2026-11-15T17"));
    }

    @Test
    public void hourKeyUsesTheApisOffset() {
        // 2026-11-15 16:30 UTC is 17:30 in Rome (UTC+1 in November).
        long ms = java.time.Instant.parse("2026-11-15T16:30:00Z").toEpochMilli();
        assertEquals("2026-11-15T17", RainNote.hourKey(ms, 3600));
        assertEquals("2026-11-16T00", RainNote.hourKey(java.time.Instant.parse("2026-11-15T23:10:00Z")
                .toEpochMilli(), 3600));
    }
}
