package io.github.leopierini.rehome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class WatchNextTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 1_763_200_000_000L;
    private static final Predicate<String> ANY = p -> true;

    private static WatchNext.Item item(long id, String pkg, String title, long ageMs) {
        WatchNext.Item i = new WatchNext.Item();
        i.id = id;
        i.pkg = pkg;
        i.title = title;
        i.intentUri = "intent:#Intent;package=" + pkg + ";end";
        i.lastEngagement = NOW - ageMs;
        i.watchNextType = WatchNext.TYPE_CONTINUE;
        return i;
    }

    private static List<Long> ids(List<WatchNext.Item> items) {
        List<Long> out = new ArrayList<>();
        for (WatchNext.Item i : items) out.add(i.id);
        return out;
    }

    @Test
    public void newestFirst() {
        List<WatchNext.Item> in = Arrays.asList(
                item(1, "a", "Uno", 3 * DAY), item(2, "b", "Due", DAY), item(3, "a", "Tre", 2 * DAY));
        assertEquals(Arrays.asList(2L, 3L, 1L), ids(WatchNext.filter(in, NOW, ANY)));
    }

    @Test
    public void olderThanThirtyDaysDropped() {
        List<WatchNext.Item> in = Arrays.asList(
                item(1, "a", "Ieri", DAY), item(2, "a", "Vecchio", 31 * DAY), item(3, "a", "Al limite", 30 * DAY));
        assertEquals(Arrays.asList(1L, 3L), ids(WatchNext.filter(in, NOW, ANY)));
    }

    @Test
    public void noEngagementTimeDropped() {
        WatchNext.Item i = item(1, "a", "Senza data", 0);
        i.lastEngagement = 0;
        assertTrue(WatchNext.filter(Collections.singletonList(i), NOW, ANY).isEmpty());
    }

    @Test
    public void aClockSetBackKeepsTheRow() {
        // A row stamped a little in the future (the TV's clock behind the app's server) is not "older than 30 days".
        WatchNext.Item i = item(1, "a", "Domani", -DAY);
        assertEquals(1, WatchNext.filter(Collections.singletonList(i), NOW, ANY).size());
    }

    @Test
    public void farFutureRowsDroppedNotPinnedFirst() {
        // Seconds written as microseconds, or a TV clock far behind: dropped, not kept for ever at the front.
        WatchNext.Item wrong = item(1, "a", "Microsecondi", 0);
        wrong.lastEngagement = NOW * 1000;
        WatchNext.Item ahead = item(2, "a", "Due giorni avanti", -2 * DAY);
        WatchNext.Item today = item(3, "a", "Oggi", 3_600_000L);
        assertEquals(Collections.singletonList(3L), ids(WatchNext.filter(Arrays.asList(wrong, ahead, today), NOW, ANY)));
    }

    @Test
    public void aClockBehindTheInstallShowsNothing() {
        // Booted offline with no time yet: the clock reads a date before the launcher was even installed, so every
        // row "from after it" (the years-old ones too) would pass. Nothing is shown instead.
        long installed = NOW + 10 * DAY;
        WatchNext.Item old = item(1, "a", "Vecchio di anni", 0);
        old.lastEngagement = NOW - 2 * DAY;
        assertTrue(WatchNext.filter(Collections.singletonList(old), NOW, installed, ANY).isEmpty());
        assertEquals(1, WatchNext.filter(Collections.singletonList(old), NOW, NOW - DAY, ANY).size());
    }

    // ---- focus follows the card (CategoryHome.onWatchNext) ----

    @Test
    public void anItemWatchedAgainMovesToTheFront() {
        // [A, B, C], the user opens C and comes back: C is now the newest, so the reading is [C, A, B].
        WatchNext.Item a = item(1, "a", "A", 3 * DAY), b = item(2, "b", "B", 4 * DAY), c = item(3, "c", "C", 5 * DAY);
        List<WatchNext.Item> before = WatchNext.filter(Arrays.asList(a, b, c), NOW, ANY);
        assertEquals(Arrays.asList(1L, 2L, 3L), ids(before));
        WatchNext.Item c2 = item(3, "c", "C", 60_000L);
        List<WatchNext.Item> after = WatchNext.filter(Arrays.asList(a, b, c2), NOW, ANY);
        assertEquals(Arrays.asList(3L, 1L, 2L), ids(after));
    }

    // ---- change notices (WatchNextRow's observer) ----

    @Test
    public void noticesSettleButNeverPutTheReadOffPastTheMax() {
        assertEquals(WatchNext.SETTLE_MS, WatchNext.settleDelay(1000, 1000));
        // An app rewriting its row every second: each notice would push the read 1.5 s on; it still runs 5 s after
        // the first one (the notices before that read; the read then starts a new wait).
        long first = 1000;
        long due = 0;
        for (long t = first; t < first + WatchNext.SETTLE_MAX_MS; t += 1000) {
            due = t + WatchNext.settleDelay(first, t);
            assertTrue(due <= first + WatchNext.SETTLE_MAX_MS);
        }
        assertEquals(first + WatchNext.SETTLE_MAX_MS, due);
        assertEquals(0, WatchNext.settleDelay(first, first + 20_000));
    }

    @Test
    public void watchedNinetyFivePercentDropped() {
        WatchNext.Item done = item(1, "a", "Finito", DAY);
        done.position = 95_000;
        done.duration = 100_000;
        WatchNext.Item half = item(2, "a", "A metà", DAY);
        half.position = 50_000;
        half.duration = 100_000;
        WatchNext.Item almost = item(3, "a", "Quasi", DAY);
        almost.position = 94_900;
        almost.duration = 100_000;
        // Same engagement time: the newer row (higher id) first.
        assertEquals(Arrays.asList(3L, 2L), ids(WatchNext.filter(Arrays.asList(done, half, almost), NOW, ANY)));
    }

    @Test
    public void unknownDurationIsNotWatched() {
        WatchNext.Item i = item(1, "a", "Senza durata", DAY);
        i.position = 5_000_000;
        assertEquals(1, WatchNext.filter(Collections.singletonList(i), NOW, ANY).size());
    }

    @Test
    public void unusableAppsDropped() {
        Set<String> ok = new HashSet<>(Collections.singletonList("on"));
        List<WatchNext.Item> in = Arrays.asList(item(1, "on", "Sì", DAY), item(2, "off", "No", DAY));
        assertEquals(Collections.singletonList(1L), ids(WatchNext.filter(in, NOW, ok::contains)));
    }

    @Test
    public void duplicatesOfOneTitleKeepTheNewest() {
        List<WatchNext.Item> in = Arrays.asList(
                item(1, "rai", "Ogni giorno un nuovo episodio", 3 * DAY),
                item(2, "rai", "ogni giorno un nuovo episodio ", DAY),
                // The same title from another app is another show.
                item(3, "netflix", "Ogni giorno un nuovo episodio", 2 * DAY));
        assertEquals(Arrays.asList(2L, 3L), ids(WatchNext.filter(in, NOW, ANY)));
    }

    @Test
    public void atMostSix() {
        List<WatchNext.Item> in = new ArrayList<>();
        for (int i = 1; i <= 9; i++) in.add(item(i, "a", "T" + i, i * DAY));
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L), ids(WatchNext.filter(in, NOW, ANY)));
    }

    @Test
    public void rowsWithoutTitleOrLinkDropped() {
        WatchNext.Item noTitle = item(1, "a", "  ", DAY);
        WatchNext.Item noLink = item(2, "a", "Titolo", DAY);
        noLink.intentUri = null;
        WatchNext.Item noPkg = item(3, null, "Titolo", DAY);
        assertTrue(WatchNext.filter(Arrays.asList(noTitle, noLink, noPkg), NOW, ANY).isEmpty());
    }

    @Test
    public void watchlistIsNotContinueWatching() {
        WatchNext.Item list = item(1, "a", "Da vedere", DAY);
        list.watchNextType = WatchNext.TYPE_WATCHLIST;
        WatchNext.Item next = item(2, "a", "Prossimo", DAY);
        next.watchNextType = WatchNext.TYPE_NEXT;
        WatchNext.Item fresh = item(3, "a", "Nuovo", DAY);
        fresh.watchNextType = WatchNext.TYPE_NEW;
        assertEquals(Arrays.asList(3L, 2L), ids(WatchNext.filter(Arrays.asList(list, next, fresh), NOW, ANY)));
    }

    @Test
    public void sameTimeNewerRowFirst() {
        List<WatchNext.Item> in = Arrays.asList(item(4, "a", "A", DAY), item(9, "a", "B", DAY));
        assertEquals(Arrays.asList(9L, 4L), ids(WatchNext.filter(in, NOW, ANY)));
    }

    @Test
    public void nullsTolerated() {
        List<WatchNext.Item> in = new ArrayList<>(Arrays.asList(null, item(1, "a", "A", DAY)));
        assertEquals(Collections.singletonList(1L), ids(WatchNext.filter(in, NOW, ANY)));
        assertTrue(WatchNext.filter(null, NOW, ANY).isEmpty());
    }

    @Test
    public void progress() {
        WatchNext.Item i = item(1, "a", "A", DAY);
        assertEquals(-1f, WatchNext.progress(i), 0f);
        i.duration = 200_000;
        assertEquals(-1f, WatchNext.progress(i), 0f);   // not started: no bar (a "next episode" row)
        i.position = 50_000;
        assertEquals(0.25f, WatchNext.progress(i), 1e-6f);
        i.position = 400_000;
        assertEquals(1f, WatchNext.progress(i), 0f);
        i.duration = 0;
        assertEquals(-1f, WatchNext.progress(i), 0f);
    }

    @Test
    public void episodeLine() {
        assertEquals("S2 E5 · Il ritorno", WatchNext.episodeLine("2", "5", "Il ritorno"));
        assertEquals("S2 E5", WatchNext.episodeLine("2", "5", null));
        assertEquals("E12", WatchNext.episodeLine(null, "12", " "));
        assertEquals("S3", WatchNext.episodeLine("3", "", null));
        assertEquals("Pilota", WatchNext.episodeLine(null, null, "Pilota"));
        assertEquals("", WatchNext.episodeLine(null, null, null));
    }

    @Test
    public void signatureFollowsWhatIsShown() {
        WatchNext.Item a = item(1, "a", "A", DAY);
        String s = WatchNext.signature(Collections.singletonList(a), Collections.singletonList(false));
        assertEquals(s, WatchNext.signature(Collections.singletonList(a), Collections.singletonList(false)));
        assertFalse(s.equals(WatchNext.signature(Collections.singletonList(a), Collections.singletonList(true))));
        a.position = 10;
        a.duration = 100;
        assertFalse(s.equals(WatchNext.signature(Collections.singletonList(a), Collections.singletonList(false))));
    }

    // ---- launch guard ----

    @Test
    public void launchOnlyIntoThePublishingApp() {
        assertTrue(WatchNext.mayLaunch("com.netflix.ninja", "com.netflix.ninja", true));
        // Resolved to a browser, or to another app the row names: not with the launcher's hand.
        assertFalse(WatchNext.mayLaunch("com.netflix.ninja", "com.android.chrome", true));
        assertFalse(WatchNext.mayLaunch("com.netflix.ninja", null, true));
        assertFalse(WatchNext.mayLaunch(null, "com.netflix.ninja", true));
    }

    @Test
    public void neverAnActivityThePublisherDoesNotExport() {
        // intent:#Intent;component=com.netflix.ninja/.InternalDebugActivity;end resolves into the publisher's own
        // package, but to an activity only the launcher's identity could start: refused.
        assertFalse(WatchNext.mayLaunch("com.netflix.ninja", "com.netflix.ninja", false));
    }

    @Test
    public void neverAFactoryMenu() {
        assertFalse(WatchNext.mayLaunch("org.droidtv.factory", "org.droidtv.factory", true));
        assertFalse(WatchNext.mayLaunch("com.cvte.tv.hkcfac", "com.cvte.tv.hkcfac", true));
        assertFalse(WatchNext.mayLaunch("mediatek.factorymenu.ui", "mediatek.factorymenu.ui", true));
    }

    @Test
    public void grantFlagsAreAndroidsOwn() {
        assertEquals(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                WatchNext.GRANT_FLAGS);
        assertEquals(android.content.Intent.FLAG_ACTIVITY_NEW_TASK, WatchNext.NEW_TASK);
    }

    @Test
    public void linkFlagsLoseEveryUriGrant() {
        // intent:#Intent;launchFlags=0xc3;SEL;action=...;end asks for read, write, persistable and prefix grants on
        // whatever data it names (the launcher's theme provider, say): all four go, a task of its own is added.
        int asked = 0xc3;
        int safe = WatchNext.safeFlags(asked);
        assertEquals(0, safe & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, safe & android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertEquals(0, safe & android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        assertEquals(0, safe & android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        assertEquals(android.content.Intent.FLAG_ACTIVITY_NEW_TASK, safe & android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        // Other launch flags pass (the target can only be the publisher's own exported activity).
        int clearTop = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
        assertEquals(clearTop, WatchNext.safeFlags(clearTop | 0x1) & clearTop);
    }

    @Test
    public void aPackagelessLinkIsPinnedToItsPublisher() {
        // https://www.disneyplus.com/play/...: no package, no component, so it would open a browser or a chooser.
        assertTrue(WatchNext.needsPackage(false, false));
        // Already pinned (package= or component=): resolved as it is, and refused unless it is the publisher's.
        assertFalse(WatchNext.needsPackage(true, false));
        assertFalse(WatchNext.needsPackage(false, true));
        assertFalse(WatchNext.needsPackage(true, true));
    }

    @Test
    public void linkSchemes() {
        assertTrue(WatchNext.launchableLink("intent:#Intent;package=a;end"));
        assertTrue(WatchNext.launchableLink("https://www.netflix.com/title/1"));
        assertTrue(WatchNext.launchableLink("android-app://it.rainet.androidtv/https/raiplay.it/x"));
        assertTrue(WatchNext.launchableLink("raiplay://video/abc"));
        assertFalse(WatchNext.launchableLink("file:///sdcard/x"));
        assertFalse(WatchNext.launchableLink("content://io.github.leopierini.rehome.theme/x"));
        assertFalse(WatchNext.launchableLink("javascript:alert(1)"));
        assertFalse(WatchNext.launchableLink(""));
        assertFalse(WatchNext.launchableLink(null));
    }

    @Test
    public void posterFetchOnlyOverHttps() {
        assertTrue(WatchNext.posterOverNetwork("https://img.raiplay.it/a.jpg"));
        assertFalse(WatchNext.posterOverNetwork("http://img.raiplay.it/a.jpg"));
        assertFalse(WatchNext.posterOverNetwork("content://x/y"));
        assertFalse(WatchNext.posterOverNetwork(null));
        assertTrue(WatchNext.posterLocal("content://org.jellyfin/x"));
        assertTrue(WatchNext.posterLocal("android.resource://com.spotify/1"));
        assertFalse(WatchNext.posterLocal("file:///sdcard/a.jpg"));
        assertFalse(WatchNext.posterLocal("https://x/a.jpg"));
    }

    /**
     * Re-review R4: the two lines of WatchNextRow.open that drop a link's selector (which can name another app's
     * component) and its clip data are Android calls no JVM test can run. Pinned in the source, before the start, so a
     * later tidy cannot drop them with the suite still green.
     */
    @Test
    public void openDropsTheSelectorAndClipDataBeforeStarting() throws Exception {
        java.io.File src = new java.io.File("src/main/java/io/github/leopierini/rehome/WatchNextRow.java");
        if (!src.isFile()) src = new java.io.File("app/src/main/java/io/github/leopierini/rehome/WatchNextRow.java");
        assertTrue("WatchNextRow.java not found from " + new java.io.File(".").getAbsolutePath(), src.isFile());
        String code = new String(java.nio.file.Files.readAllBytes(src.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        int open = code.indexOf("boolean open(WatchNext.Item i)");
        assertTrue(open > 0);
        int start = code.indexOf("act.startActivity(in)", open);
        int parse = code.indexOf("Intent.parseUri(i.intentUri", open);
        int selector = code.indexOf("in.setSelector(null);", open);
        int clip = code.indexOf("in.setClipData(null);", open);
        int flags = code.indexOf("in.setFlags(WatchNext.safeFlags(in.getFlags()));", open);
        assertTrue(parse > 0 && start > 0);
        assertTrue("selector cleared after parsing, before the start", selector > parse && selector < start);
        assertTrue("clip data cleared after parsing, before the start", clip > parse && clip < start);
        assertTrue("grant flags dropped before the start", flags > parse && flags < start);
    }

    private static WatchNext.Item card(long id, String pkg, String title) {
        WatchNext.Item i = new WatchNext.Item();
        i.id = id;
        i.pkg = pkg;
        i.title = title;
        i.intentUri = "intent:#Intent;component=" + pkg + "/.Play;S.k=a\tb;end";
        i.poster = "https://img.example/" + id + ".jpg";
        i.watchNextType = WatchNext.TYPE_CONTINUE;
        i.lastEngagement = NOW - id;
        i.position = 600_000;
        i.duration = 3_600_000;
        return i;
    }

    /** Final review M2: the last cards come back whole at the next start, odd characters and nulls included. */
    @Test
    public void savedCardsComeBackAsTheyWere() {
        WatchNext.Item a = card(7, "it.rai.raiplay", "Il commissario \\ Montalbano");
        a.episodeTitle = "La forma\ndell'acqua";
        a.season = "1";
        a.episode = null;
        WatchNext.Item b = card(9, "com.example.tv", "Two\tcolumns\r");
        b.poster = null;
        b.intentUri = "\\0";   // a value that looks like the null mark stays a value
        List<WatchNext.Saved> back = WatchNext.decode(
                WatchNext.encode(Arrays.asList(a, b), Arrays.asList("RaiPlay", null)));
        assertEquals(2, back.size());
        WatchNext.Item x = back.get(0).item, y = back.get(1).item;
        assertEquals(7, x.id);
        assertEquals("it.rai.raiplay", x.pkg);
        assertEquals(a.title, x.title);
        assertEquals(a.episodeTitle, x.episodeTitle);
        assertEquals("1", x.season);
        assertEquals(null, x.episode);
        assertEquals(a.poster, x.poster);
        assertEquals(a.intentUri, x.intentUri);
        assertEquals(a.lastEngagement, x.lastEngagement);
        assertEquals(a.position, x.position);
        assertEquals(a.duration, x.duration);
        assertEquals("RaiPlay", back.get(0).app);
        assertEquals(b.title, y.title);
        assertEquals(null, y.poster);
        assertEquals("\\0", y.intentUri);
        assertEquals("com.example.tv", back.get(1).app);   // no app name kept: the package, as label() does
    }

    @Test
    public void damagedSavedCardsAreLeftOut() {
        String good = WatchNext.encode(Collections.singletonList(card(1, "a.b", "Ok")), Collections.singletonList("A"));
        assertEquals(1, WatchNext.decode("garbage\n1\t2\n" + good + "x\ta.b\tT\t\\0\t\\0\t\\0\t\\0\t\\0\t0\t0\t0\t0\tA\n")
                .size());
        assertTrue(WatchNext.decode(null).isEmpty());
        assertTrue(WatchNext.decode("").isEmpty());
        // A bad escape loses that card only.
        assertEquals(1, WatchNext.decode(good + good.replace("Ok", "O\\q")).size());
    }

    @Test
    public void atMostARowOfCardsIsKept() {
        List<WatchNext.Item> many = new ArrayList<>();
        List<String> apps = new ArrayList<>();
        for (int k = 0; k < 10; k++) {
            many.add(card(k, "p" + k, "T" + k));
            apps.add("A");
        }
        assertEquals(WatchNext.MAX_ITEMS, WatchNext.decode(WatchNext.encode(many, apps)).size());
    }
}
