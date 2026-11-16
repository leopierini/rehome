package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the data meter's text, its day and month windows, and the apps it names. */
public class DataUsageTest {

    private static final TimeZone ROME = TimeZone.getTimeZone("Europe/Rome");
    private static final Words EN = TestWords.en(), IT = TestWords.it();

    private static long at(TimeZone tz, int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    // ---- sizes ----

    @Test
    public void nothingShowsNothing() {
        assertEquals("", DataUsage.size(EN, 0));
        assertEquals("", DataUsage.size(EN, -1));
    }

    @Test
    public void underOneMegabyte() {
        assertEquals("< 1 MB", DataUsage.size(EN, 400_000));
        assertEquals("< 1 MB", DataUsage.size(EN, 499_999));
        assertEquals("1 MB", DataUsage.size(EN, 500_000));
    }

    @Test
    public void megabytesUnderOneGigabyte() {
        assertEquals("740 MB", DataUsage.size(EN, 740_000_000L));
        assertEquals("999 MB", DataUsage.size(EN, 999_400_000L));
    }

    @Test
    public void gigabytesWithOneDecimalAndAComma() {
        // 999,6 MB would round to "1000 MB": it is 1,0 GB.
        assertEquals("1.0 GB", DataUsage.size(EN, 999_600_000L));
        assertEquals("2.3 GB", DataUsage.size(EN, 2_300_000_000L));
        assertEquals("2.0 GB", DataUsage.size(EN, 1_950_000_000L));
        assertEquals("12.3 GB", DataUsage.size(EN, 12_345_000_000L));
        assertEquals("120.0 GB", DataUsage.size(EN, 120_000_000_000L));
    }

    @Test
    public void italianUsesADecimalCommaAndItsOwnWords() {
        assertEquals("2,3 GB", DataUsage.size(IT, 2_300_000_000L));
        assertEquals("740 MB", DataUsage.size(IT, 740_000_000L));
        assertEquals("Oggi 2,3 GB  ·  Netflix 1,9 GB",
                DataUsage.header(IT, 2_300_000_000L, "Netflix", 1_900_000_000L));
        assertEquals("Dall'ultimo riavvio 1,2 GB", DataUsage.sinceBoot(IT, 1_200_000_000L));
        assertEquals("Sistema Android", DataUsage.label(IT, DataUsage.SYSTEM));
        assertEquals("Altre reti", DataUsage.label(IT, DataUsage.OTHER_NETWORKS));
        assertEquals("Un momento…", DataUsage.appLines(IT, null, 3).get(0)[0]);
    }

    @Test
    public void groupsAndPlaceholdersHaveWordsOnScreen() {
        assertEquals("Android system", DataUsage.label(EN, DataUsage.SYSTEM));
        assertEquals("Uninstalled apps", DataUsage.label(EN, DataUsage.REMOVED));
        assertEquals("ADB debugging", DataUsage.label(EN, DataUsage.ADB));
        assertEquals("Other services", DataUsage.label(EN, DataUsage.OTHER));
        assertEquals("Unattributed", DataUsage.label(EN, DataUsage.UNATTRIBUTED));
        assertEquals("Other networks", DataUsage.label(EN, DataUsage.OTHER_NETWORKS));
        // An app or a network is shown as it is named.
        assertEquals("Netflix", DataUsage.label(EN, "Netflix"));
        Map<String, Long> named = new LinkedHashMap<>();
        named.put(DataUsage.SYSTEM, 300_000_000L);
        assertEquals("Android system", DataUsage.appLines(EN, named, 3).get(0)[0]);
    }

    @Test
    public void headerLine() {
        assertEquals("Today 2.3 GB  ·  Netflix 1.9 GB",
                DataUsage.header(EN, 2_300_000_000L, "Netflix", 1_900_000_000L));
        assertEquals("Today 740 MB", DataUsage.header(EN, 740_000_000L, null, 0));
        assertEquals("Today 740 MB", DataUsage.header(EN, 740_000_000L, "Netflix", 0));
        assertEquals("", DataUsage.header(EN, 0, "Netflix", 0));
    }

    @Test
    public void headerShortensALongAppName() {
        assertEquals("Today 12.3 GB  ·  A very long app… 10.1 GB",
                DataUsage.header(EN, 12_300_000_000L, "A very long app name here", 10_100_000_000L));
    }

    @Test
    public void sinceBoot() {
        // Since the last real boot, not "since switched on": standby from the remote is not a boot (review, Minor 3).
        assertEquals("Since last restart 1.2 GB", DataUsage.sinceBoot(EN, 1_200_000_000L));
        assertEquals("", DataUsage.sinceBoot(EN, 0));
    }

    @Test
    public void shortLabel() {
        assertEquals("Netflix", DataUsage.shortLabel("Netflix", 18));
        assertEquals("abcdefghijklmnopq…", DataUsage.shortLabel("abcdefghijklmnopqrstuvwxyz", 18));
        assertEquals("", DataUsage.shortLabel(null, 18));
    }

    // ---- windows ----

    @Test
    public void dayStartsAtLocalMidnight() {
        long now = at(ROME, 2026, 11, 15, 2, 5);
        assertEquals(at(ROME, 2026, 11, 15, 0, 0), DataUsage.dayStart(now, ROME));
        assertEquals(20261115, DataUsage.dayKey(now, ROME));
        assertEquals(202611, DataUsage.monthKey(now, ROME));
    }

    @Test
    public void dayFollowsTheTimeZoneNotUtc() {
        // 23:30 UTC on the 14th is already the 15th in Rome.
        long now = at(TimeZone.getTimeZone("UTC"), 2026, 11, 14, 23, 30);
        assertEquals(20261115, DataUsage.dayKey(now, ROME));
        assertEquals(20261114, DataUsage.dayKey(now, TimeZone.getTimeZone("UTC")));
    }

    @Test
    public void daylightSavingDaysAreStillOneDay() {
        // 29 March 2026: clocks go forward at 02:00, a 23-hour day.
        long spring = at(ROME, 2026, 3, 29, 12, 0);
        long springStart = DataUsage.dayStart(spring, ROME);
        assertEquals(at(ROME, 2026, 3, 29, 0, 0), springStart);
        assertEquals(23 * 3_600_000L, DataUsage.dayStart(at(ROME, 2026, 3, 30, 12, 0), ROME) - springStart);
        // 25 October 2026: clocks go back at 03:00, a 25-hour day.
        long autumn = at(ROME, 2026, 10, 25, 23, 59);
        long autumnStart = DataUsage.dayStart(autumn, ROME);
        assertEquals(at(ROME, 2026, 10, 25, 0, 0), autumnStart);
        assertEquals(25 * 3_600_000L, DataUsage.dayStart(at(ROME, 2026, 10, 26, 1, 0), ROME) - autumnStart);
    }

    @Test
    public void monthIsTheCalendarMonth() {
        assertEquals(at(ROME, 2026, 1, 1, 0, 0), DataUsage.monthStart(at(ROME, 2026, 1, 31, 23, 59), ROME));
        assertEquals(at(ROME, 2026, 2, 1, 0, 0), DataUsage.monthStart(at(ROME, 2026, 2, 28, 12, 0), ROME));
        assertEquals(at(ROME, 2028, 2, 1, 0, 0), DataUsage.monthStart(at(ROME, 2028, 2, 29, 12, 0), ROME));
        assertEquals(at(ROME, 2026, 3, 1, 0, 0), DataUsage.monthStart(at(ROME, 2026, 3, 1, 0, 0), ROME));
        assertEquals(202602, DataUsage.monthKey(at(ROME, 2026, 2, 28, 23, 59), ROME));
        assertEquals(202603, DataUsage.monthKey(at(ROME, 2026, 3, 1, 0, 0), ROME));
    }

    @Test
    public void shareOfAnIntervalAfterAMoment() {
        assertEquals(100, DataUsage.share(200, 0, 100, 50));
        assertEquals(200, DataUsage.share(200, 0, 100, 0));
        assertEquals(200, DataUsage.share(200, 10, 100, 0));
        assertEquals(0, DataUsage.share(200, 0, 100, 100));
        // A clock set back: no interval to share, all of it counts now.
        assertEquals(200, DataUsage.share(200, 100, 50, 75));
    }

    // ---- apps ----

    @Test
    public void systemUidsAreOneLine() {
        assertEquals(DataUsage.SYSTEM, DataUsage.group(0));
        assertEquals(DataUsage.SYSTEM, DataUsage.group(1000));
        assertEquals(DataUsage.SYSTEM, DataUsage.group(1013));
        assertEquals(DataUsage.SYSTEM, DataUsage.group(DataUsage.UID_TETHERING));
        assertEquals(DataUsage.REMOVED, DataUsage.group(DataUsage.UID_REMOVED));
        // The shell: ADB over Wi-Fi, named apart from Android itself.
        assertEquals(DataUsage.ADB, DataUsage.group(2000));
        assertNull(DataUsage.group(10123));
    }

    @Test
    public void sharedUidNamedByItsLaunchableApp() {
        assertEquals("com.b.tv", DataUsage.pickPackage(new String[]{"com.c.service", "com.b.tv", "com.a.lib"},
                new HashSet<>(Arrays.asList("com.b.tv"))));
        // None launchable: the same one every time, whatever order PackageManager lists them in.
        assertEquals("com.google.android.gms", DataUsage.pickPackage(
                new String[]{"com.google.android.gsf", "com.google.android.gms"}, new HashSet<>()));
        assertNull(DataUsage.pickPackage(null, new HashSet<>()));
        assertNull(DataUsage.pickPackage(new String[0], new HashSet<>()));
    }

    @Test
    public void topSumsByNameAndSortsBiggestFirst() {
        Map<String, Long> by = new LinkedHashMap<>();
        DataUsage.add(by, "YouTube", 300);
        DataUsage.add(by, "Netflix", 1000);
        DataUsage.add(by, DataUsage.SYSTEM, 150);
        DataUsage.add(by, "YouTube", 400);   // a second bucket (foreground, background) of the same app
        DataUsage.add(by, "Spotify", 0);
        DataUsage.add(by, "Zattoo", 200);
        DataUsage.add(by, "Arte", 200);
        List<Map.Entry<String, Long>> top = DataUsage.top(by, 3);
        assertEquals(3, top.size());
        assertEquals("Netflix", top.get(0).getKey());
        assertEquals(1000L, (long) top.get(0).getValue());
        assertEquals("YouTube", top.get(1).getKey());
        assertEquals(700L, (long) top.get(1).getValue());
        // A tie goes by name, so the list does not shuffle between refreshes.
        assertEquals("Arte", top.get(2).getKey());
    }

    @Test
    public void topLeavesOutNothing() {
        Map<String, Long> by = new LinkedHashMap<>();
        DataUsage.add(by, "Netflix", 0);
        assertEquals(0, DataUsage.top(by, 3).size());
    }

    @Test
    public void headerAppIsARealApp() {
        Map<String, Long> by = new LinkedHashMap<>();
        DataUsage.add(by, DataUsage.SYSTEM, 5_000_000_000L);
        DataUsage.add(by, DataUsage.REMOVED, 4_000_000_000L);
        DataUsage.add(by, DataUsage.OTHER, 3_000_000_000L);
        DataUsage.add(by, DataUsage.ADB, 2_500_000_000L);
        DataUsage.add(by, "Netflix", 1_900_000_000L);
        DataUsage.add(by, "YouTube", 100_000_000L);
        assertEquals("Netflix", DataUsage.topApp(by).getKey());
        Map<String, Long> small = new LinkedHashMap<>();
        DataUsage.add(small, "Netflix", 400_000L);
        assertNull(DataUsage.topApp(small));
    }

    /** Review Minor 9: one threshold for naming an app, in the header line and in topApp. */
    @Test
    public void headerNamesAnAppFromTheSameThresholdAsTopApp() {
        assertEquals("Today 2.0 GB", DataUsage.header(EN, 2_000_000_000L, "Netflix", 800_000L));
        Map<String, Long> by = new LinkedHashMap<>();
        DataUsage.add(by, "Netflix", 800_000L);
        assertNull(DataUsage.topApp(by));
        assertEquals("Today 2.0 GB  ·  Netflix 1 MB",
                DataUsage.header(EN, 2_000_000_000L, "Netflix", DataUsage.APP_MIN));
    }

    // ---- apps after midnight (review Minor 2) ----

    @Test
    public void noAppUsesMoreThanTheDay() {
        // 00:30: yesterday's 23:00-24:00 film sits in the UTC-aligned per-UID bucket that holds midnight.
        Map<String, Long> by = new LinkedHashMap<>();
        DataUsage.add(by, "Netflix", 800_000_000L);
        DataUsage.add(by, "YouTube", 2_000_000L);
        DataUsage.capAt(by, 6_000_000L);
        assertEquals(6_000_000L, (long) by.get("Netflix"));
        assertEquals(2_000_000L, (long) by.get("YouTube"));
        assertEquals("Netflix", DataUsage.top(by, 3).get(0).getKey());
    }

    @Test
    public void nothingTodayNamesNoApp() {
        Map<String, Long> by = new LinkedHashMap<>();
        DataUsage.add(by, "Netflix", 800_000_000L);
        DataUsage.capAt(by, 0);
        assertTrue(DataUsage.top(by, 3).isEmpty());
    }

    // ---- when to query again (review Minor 5) ----

    @Test
    public void readAgainOnlyWhenSomethingMoved() {
        int day = 20261115;
        assertTrue(DataUsage.needsRead(false, day, day, 1_000, 1_000));            // nothing full to keep
        assertFalse(DataUsage.needsRead(true, day, day, 1_000, 1_000));
        assertFalse(DataUsage.needsRead(true, day, day, 1_000, 1_000 + DataUsage.READ_STEP - 1));
        assertTrue(DataUsage.needsRead(true, day, day, 1_000, 1_000 + DataUsage.READ_STEP));
        assertTrue(DataUsage.needsRead(true, day, day + 1, 1_000, 1_000));         // midnight: a new "today"
        assertTrue(DataUsage.needsRead(true, day, day, 5_000_000, 1_000));         // reboot or reset
        assertTrue(DataUsage.needsRead(true, day, day, -1, -1));                   // no counter: cannot tell
    }

    // ---- per network (review Important 1, Minor 7) ----

    private static final String A = "Phone A", B = "Phone B", C = "Home", D = "Studio";

    private static Map<String, Long> map(Object... kv) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        return m;
    }

    private static long sumToday(List<DataUsage.Net> nets) {
        long t = 0;
        for (DataUsage.Net n : nets) if (!n.name.equals(DataUsage.UNATTRIBUTED)) t += n.today;
        return t;
    }

    @Test
    public void aNetworkNeverShowsMoreThanTodaysTotal() {
        // The ledger spread last night's 2 GB film over 21:00→08:00: 1,45 GB on "today", Android says 40 MB.
        List<DataUsage.Net> nets = DataUsage.networks(map(A, 1_450_000_000L), map(A, 3_000_000_000L),
                40_000_000L, 10_000_000_000L, 3);
        assertEquals(A, nets.get(0).name);
        assertEquals(40_000_000L, nets.get(0).today);
        assertEquals(3_000_000_000L, nets.get(0).month);
        // The month's rest is real news; today's is nothing.
        assertEquals(DataUsage.UNATTRIBUTED, nets.get(1).name);
        assertEquals(0, nets.get(1).today);
        assertEquals(7_000_000_000L, nets.get(1).month);
    }

    @Test
    public void networksOverTheTotalAreScaledTogether() {
        // The 1st of the month gets the previous evening's bytes: both today and the month are over Android's.
        List<DataUsage.Net> nets = DataUsage.networks(map(A, 300_000_000L, B, 100_000_000L),
                map(A, 300_000_000L, B, 100_000_000L), 200_000_000L, 200_000_000L, 3);
        assertEquals(2, nets.size());
        assertEquals(150_000_000L, nets.get(0).today);
        assertEquals(50_000_000L, nets.get(1).today);
        assertEquals(150_000_000L, nets.get(0).month);
        assertTrue(sumToday(nets) <= 200_000_000L);
    }

    @Test
    public void aNetworksMonthIsNeverLessThanItsToday() {
        // Re-review N2: a gap from the 31st put 1,77 GB of the month on A; on the 2nd the owner uses 150 MB on B.
        // Android: today 150 MB, month 200 MB. B's month must hold its today; A gets what is left of the month.
        List<DataUsage.Net> nets = DataUsage.networks(map(B, 150_000_000L), map(A, 1_770_000_000L, B, 150_000_000L),
                150_000_000L, 200_000_000L, 3);
        for (DataUsage.Net net : nets) assertTrue(net.name, net.month >= net.today);
        DataUsage.Net b = nets.get(0).name.equals(B) ? nets.get(0) : nets.get(1);
        assertEquals(150_000_000L, b.today);
        assertEquals(150_000_000L, b.month);
        long months = 0;
        for (DataUsage.Net net : nets) if (!net.name.equals(DataUsage.UNATTRIBUTED)) months += net.month;
        assertTrue(months <= 200_000_000L);
    }

    @Test
    public void underTheTotalNothingIsScaled() {
        List<DataUsage.Net> nets = DataUsage.networks(map(A, 161_000_000L), map(A, 161_000_000L),
                704_000_000L, 18_800_000_000L, 3);
        assertEquals(161_000_000L, nets.get(0).today);
        assertEquals(DataUsage.UNATTRIBUTED, nets.get(1).name);
        assertEquals(543_000_000L, nets.get(1).today);
        assertEquals(18_639_000_000L, nets.get(1).month);
    }

    @Test
    public void aFourthNetworkGoesUnderOtherNetworksNotLost() {
        List<DataUsage.Net> nets = DataUsage.networks(
                map(A, 40_000_000L, B, 30_000_000L, C, 20_000_000L, D, 10_000_000L),
                map(A, 400_000_000L, B, 300_000_000L, C, 200_000_000L, D, 100_000_000L),
                100_000_000L, 1_000_000_000L, 3);
        assertEquals(3, nets.size());   // two networks and "Altre reti": never more lines than before
        assertEquals(A, nets.get(0).name);
        assertEquals(B, nets.get(1).name);
        assertEquals(DataUsage.OTHER_NETWORKS, nets.get(2).name);
        assertEquals(30_000_000L, nets.get(2).today);
        assertEquals(300_000_000L, nets.get(2).month);
        // Everything Android counted is on some line: nothing for "Non attribuiti".
        assertEquals(100_000_000L, sumToday(nets));
    }

    @Test
    public void threeNetworksAreThreeLines() {
        List<DataUsage.Net> nets = DataUsage.networks(map(A, 1L, B, 1L, C, 1L), map(A, 3L, B, 2L, C, 1L), 3, 6, 3);
        assertEquals(3, nets.size());
        assertEquals(C, nets.get(2).name);
    }

    @Test
    public void noNamedNetworkNoSplit() {
        assertTrue(DataUsage.networks(map(DataLedger.UNKNOWN, 500_000_000L), map(DataLedger.UNKNOWN, 500_000_000L),
                800_000_000L, 800_000_000L, 3).isEmpty());
    }

    @Test
    public void todayOnlyNetworkAfterTheMonths() {
        List<DataUsage.Net> nets = DataUsage.networks(map(D, 5_000_000L), map(A, 400_000_000L),
                5_000_000L, 405_000_000L, 3);
        assertEquals(A, nets.get(0).name);
        assertEquals(D, nets.get(1).name);
        assertEquals(5_000_000L, nets.get(1).today);
    }

    @Test
    public void theDetailShowsAWaitLineUntilTheAppsAreNamed() {
        // Speed review M3: opened before the names are ready, the detail shows its totals at once and this line.
        List<String[]> lines = DataUsage.appLines(EN, null, 3);
        assertEquals(1, lines.size());
        assertEquals("One moment…", lines.get(0)[0]);
        assertEquals("", lines.get(0)[1]);
    }

    @Test
    public void theNamedAppsReplaceItBiggestFirst() {
        Map<String, Long> named = new LinkedHashMap<>();
        named.put("YouTube", 300_000_000L);
        named.put("Netflix", 900_000_000L);
        named.put("Spotify", 20_000_000L);
        named.put("RaiPlay", 50_000_000L);
        named.put("Idle", 0L);
        List<String[]> lines = DataUsage.appLines(EN, named, 3);
        assertEquals(3, lines.size());
        assertEquals("Netflix", lines.get(0)[0]);
        assertEquals(DataUsage.size(EN, 900_000_000L), lines.get(0)[1]);
        assertEquals("YouTube", lines.get(1)[0]);
        assertEquals("RaiPlay", lines.get(2)[0]);
    }

    @Test
    public void noAppLinesWhenNoAppUsedData() {
        assertTrue(DataUsage.appLines(EN, new LinkedHashMap<>(), 3).isEmpty());
    }
}
