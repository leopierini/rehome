package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the per-hotspot ledger: TrafficStats deltas put on the network they were used on. */
public class DataLedgerTest {

    private static final TimeZone ROME = TimeZone.getTimeZone("Europe/Rome");
    private static final String A = "Phone A", B = "Phone B";

    private static long at(int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(ROME);
        c.clear();
        c.set(2026, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    private static long day(DataLedger l, String ssid) {
        Long v = l.today.get(ssid);
        return v == null ? 0 : v;
    }

    private static long month(DataLedger l, String ssid) {
        Long v = l.month.get(ssid);
        return v == null ? 0 : v;
    }

    /** elapsedRealtime grows with the wall clock inside one boot. */
    private static final long BOOT = at(11, 15, 0, 0);

    private static boolean sample(DataLedger l, long now, long counter, String ssid) {
        return l.sample(now, now - BOOT, 7, counter, ssid, ROME);
    }

    @Test
    public void firstSampleIsOnlyAStartingPoint() {
        DataLedger l = new DataLedger();
        assertTrue(sample(l, at(11, 15, 10, 0), 5_000, A));
        assertEquals(0, l.todayTotal());
        assertEquals(0, l.monthTotal());
    }

    @Test
    public void bytesGoToTheNetworkTheyWereUsedOn() {
        DataLedger l = new DataLedger();
        sample(l, at(11, 15, 10, 0), 1_000, A);
        sample(l, at(11, 15, 11, 0), 1_200, A);
        // The hotspot changed: what came before the change was A's.
        sample(l, at(11, 15, 12, 0), 1_500, B);
        sample(l, at(11, 15, 13, 0), 1_550, B);
        assertEquals(500, day(l, A));
        assertEquals(50, day(l, B));
        assertEquals(550, l.todayTotal());
        assertEquals(550, l.monthTotal());
    }

    @Test
    public void offlineTimeHasNoNetworkName() {
        DataLedger l = new DataLedger();
        sample(l, at(11, 15, 10, 0), 1_000, null);
        sample(l, at(11, 15, 11, 0), 1_010, A);
        assertEquals(10, day(l, DataLedger.UNKNOWN));
        assertEquals(0, day(l, A));
    }

    @Test
    public void nothingNewIsNotAChange() {
        DataLedger l = new DataLedger();
        sample(l, at(11, 15, 10, 0), 1_000, A);
        assertFalse(sample(l, at(11, 15, 10, 5), 1_000, A));
        assertTrue(sample(l, at(11, 15, 10, 6), 1_000, B));
        assertTrue(sample(l, at(11, 15, 10, 7), 1_001, B));
    }

    @Test
    public void rebootStartsTheCountersFromZero() {
        DataLedger l = new DataLedger();
        sample(l, at(11, 15, 10, 0), 9_000, A);
        // Rebooted at 11:00 (elapsedRealtime is small again), on B: everything since boot was B's.
        long now = at(11, 15, 11, 30);
        assertTrue(l.sample(now, 30 * 60_000L, 7, 400, B, ROME));
        assertEquals(400, day(l, B));
        assertEquals(0, day(l, A));
        // And then on as usual.
        l.sample(at(11, 15, 12, 0), 60 * 60_000L, 7, 600, B, ROME);
        assertEquals(600, day(l, B));
    }

    @Test
    public void rebootSeenByTheBootCount() {
        DataLedger l = new DataLedger();
        l.sample(at(11, 15, 10, 0), 1_000_000L, 7, 9_000, A, ROME);
        // A longer uptime than before, yet another boot: the counter says 12 000 since that boot.
        l.sample(at(11, 15, 22, 0), 2_000_000L, 8, 12_000, A, ROME);
        assertEquals(12_000, day(l, A));
    }

    @Test
    public void countersThatGoBackStartAgain() {
        DataLedger l = new DataLedger();
        sample(l, at(11, 15, 10, 0), 9_000, A);
        sample(l, at(11, 15, 11, 0), 300, A);
        assertEquals(300, day(l, A));
    }

    @Test
    public void unsupportedCounterIsIgnored() {
        DataLedger l = new DataLedger();
        sample(l, at(11, 15, 10, 0), 1_000, A);
        assertFalse(sample(l, at(11, 15, 11, 0), -1, A));
        sample(l, at(11, 15, 12, 0), 1_100, A);
        assertEquals(100, day(l, A));
    }

    @Test
    public void midnightSplitsByTime() {
        DataLedger l = new DataLedger();
        sample(l, at(11, 14, 23, 0), 0, A);
        // Two hours without a sample across midnight: half of it was yesterday, gone from today, still this month.
        sample(l, at(11, 15, 1, 0), 200, A);
        assertEquals(100, day(l, A));
        assertEquals(200, month(l, A));
    }

    /**
     * Task 3 review, Important 1: Home → Netflix at 21:00, 2 GB until 23:00, standby from the remote, Home again at
     * 08:00. With only the pause and the morning samples, the even split puts 8/11 of the film on the next day; that
     * is what DataUsage.networks clamps against Android's total. With the screen-off sample DataMeter now takes,
     * the film stays on the evening and the morning gets only the night's few megabytes.
     */
    @Test
    public void standbyFromAnAppWithoutAScreenOffSampleSpillsIntoTheMorning() {
        DataLedger l = new DataLedger();
        sample(l, at(11, 15, 21, 0), 0, A);
        sample(l, at(11, 16, 8, 0), 2_000_000_000L, A);
        assertEquals(Math.round(2_000_000_000L * 8 / 11.0), day(l, A));
    }

    @Test
    public void screenOffSampleKeepsTheEveningOnTheEvening() {
        DataLedger l = new DataLedger();
        sample(l, at(11, 15, 21, 0), 0, A);
        // Screen off (standby) at 23:00: a sample boundary.
        sample(l, at(11, 15, 23, 0), 2_000_000_000L, A);
        assertEquals(2_000_000_000L, day(l, A));
        // Networked standby uses 9 MB overnight; screen on at 08:00.
        sample(l, at(11, 16, 8, 0), 2_009_000_000L, A);
        assertTrue(day(l, A) <= 9_000_000L);
        assertEquals(8_000_000L, day(l, A));
        assertEquals(2_009_000_000L, month(l, A));
    }

    @Test
    public void newMonthStartsEmpty() {
        DataLedger l = new DataLedger();
        sample(l, at(10, 31, 20, 0), 0, A);
        sample(l, at(10, 31, 23, 0), 1_000, A);
        assertEquals(1_000, month(l, A));
        sample(l, at(11, 1, 1, 0), 1_200, A);
        assertEquals(100, day(l, A));
        assertEquals(100, month(l, A));
        assertEquals(20261101, l.dayKey);
        assertEquals(202611, l.monthKey);
    }

    @Test
    public void aNewDayWithoutTrafficStillClearsToday() {
        DataLedger l = new DataLedger();
        sample(l, at(11, 14, 20, 0), 0, A);
        sample(l, at(11, 14, 21, 0), 500, A);
        sample(l, at(11, 15, 9, 0), 500, A);
        assertEquals(0, l.todayTotal());
        assertEquals(500, l.monthTotal());
    }

    @Test
    public void manyNetworksFoldIntoUnknown() {
        DataLedger l = new DataLedger();
        long now = at(11, 15, 10, 0);
        long counter = 0;
        sample(l, now, counter, "net0");
        for (int i = 0; i < DataLedger.MAX_NETWORKS + 3; i++) {
            counter += 100 + i;
            now += 60_000L;
            sample(l, now, counter, "net" + (i + 1));
        }
        assertTrue(l.today.size() <= DataLedger.MAX_NETWORKS);
        assertEquals(counter, l.todayTotal());
    }

    @Test
    public void survivesAWriteAndARead() {
        DataLedger l = new DataLedger();
        String odd = "Wi\\Fi\nof \"Bob\" ·";
        sample(l, at(11, 15, 10, 0), 1_000, A);
        sample(l, at(11, 15, 11, 0), 3_000, odd);
        sample(l, at(11, 15, 12, 0), 3_500, odd);
        DataLedger back = DataLedger.decode(l.encode());
        assertNotNull(back);
        assertEquals(2_000, day(back, A));
        assertEquals(500, day(back, odd));
        assertEquals(2_500, back.monthTotal());
        assertEquals(odd, back.ssid);
        assertEquals(l.counter, back.counter);
        assertEquals(l.at, back.at);
        assertEquals(l.elapsed, back.elapsed);
        assertEquals(l.boot, back.boot);
        assertEquals(l.dayKey, back.dayKey);
        // And carries on where it left off.
        sample(back, at(11, 15, 13, 0), 3_600, A);
        assertEquals(600, day(back, odd));
    }

    @Test
    public void noNetworkSurvivesTooAsNoName() {
        DataLedger l = new DataLedger();
        sample(l, at(11, 15, 10, 0), 1_000, null);
        DataLedger back = DataLedger.decode(l.encode());
        assertNotNull(back);
        assertNull(back.ssid);
    }

    @Test
    public void garbageIsNotALedger() {
        assertNull(DataLedger.decode(""));
        assertNull(DataLedger.decode("hello"));
        assertNull(DataLedger.decode("rehome-data 1\nlast x y z"));
    }
}
