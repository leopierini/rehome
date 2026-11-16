package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The player card's words, in English, with one Italian case per helper (the owner's wording). */
public class PlayerTextTest {

    private static final TimeZone ROME = TimeZone.getTimeZone("Europe/Rome");
    private static final Words EN = TestWords.en(), IT = TestWords.it();
    private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;

    /** November {@code day}, 2026 at hh:mm in Rome. */
    private static long at(int day, int hh, int mm) {
        Calendar c = Calendar.getInstance(ROME);
        c.clear();
        c.set(2026, Calendar.NOVEMBER, day, hh, mm, 0);
        return c.getTimeInMillis();
    }

    private static final long NOW = at(15, 11, 40);

    private static PlayerPick.Item item(int kind) {
        PlayerPick.Item i = new PlayerPick.Item();
        i.kind = kind;
        i.pkg = "p";
        i.lastPlayed = NOW - 5 * MIN;
        return i;
    }

    @Test
    public void whenLiveAndAgo() {
        PlayerPick.Item i = item(PlayerPick.SONG);
        i.live = true;
        i.playing = true;
        assertEquals("Playing", PlayerText.when(EN, i, NOW));
        i.playing = false;
        assertEquals("Paused", PlayerText.when(EN, i, NOW));
        i.live = false;
        assertEquals("5 min ago", PlayerText.when(EN, i, NOW));
        i.lastPlayed = NOW - 20 * 1000;
        assertEquals("just now", PlayerText.when(EN, i, NOW));
        i.lastPlayed = NOW - 3 * HOUR;
        assertEquals("3 hours ago", PlayerText.when(EN, i, NOW));
        i.lastPlayed = NOW - HOUR - MIN;
        assertEquals("1 hour ago", PlayerText.when(EN, i, NOW));
        i.lastPlayed = at(14, 21, 40);
        assertEquals("yesterday", PlayerText.when(EN, i, NOW));
        i.lastPlayed = at(13, 9, 0);
        assertEquals("2 days ago", PlayerText.when(EN, i, NOW));
    }

    @Test
    public void atTodayYesterdayDate() {
        assertEquals("today at 11:35", PlayerText.at(EN, at(15, 11, 35), NOW, ROME));
        assertEquals("yesterday at 21:40", PlayerText.at(EN, at(14, 21, 40), NOW, ROME));
        assertEquals("Nov 12 at 20:10", PlayerText.at(EN, at(12, 20, 10), NOW, ROME));
    }

    @Test
    public void appTitleBackTo() {
        assertEquals("Back to Netflix", PlayerText.title(EN, item(PlayerPick.APP), "Netflix"));
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.title = "Kestrel - Route 9";
        assertEquals("Kestrel - Route 9", PlayerText.title(EN, v, "YouTube"));
    }

    @Test
    public void appSubPosition() {
        PlayerPick.Item a = item(PlayerPick.APP);
        a.position = 31_000;
        assertEquals("Left off at 0:31", PlayerText.sub(EN, a));
        a.position = 3_725_000;
        assertEquals("Left off at 1:02:05", PlayerText.sub(EN, a));
        a.position = 0;
        assertEquals("", PlayerText.sub(EN, a));
    }

    @Test
    public void subArtistOrEpisode() {
        PlayerPick.Item s = item(PlayerPick.SONG);
        s.artist = "The Hollow Pines";
        assertEquals("The Hollow Pines", PlayerText.sub(EN, s));
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.next = new WatchNext.Item();
        v.next.season = "4";
        v.next.episode = "10";
        v.next.episodeTitle = "Bonaccia";
        assertEquals("Bonaccia", PlayerText.sub(EN, v));
    }

    @Test
    public void songTimesMinutesSeconds() {
        PlayerPick.Item s = item(PlayerPick.SONG);
        s.position = 107_000;
        s.duration = 238_000;
        assertArrayEquals(new String[]{"1:47", "3:58"}, PlayerText.times(EN, s));
        assertEquals(107f / 238f, PlayerText.progress(s), 0.001f);
    }

    @Test
    public void videoTimesRemaining() {
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.position = 19 * MIN;
        v.duration = 50 * MIN;
        assertArrayEquals(new String[]{"19\u00A0min", "31\u00A0min\u00A0left"}, PlayerText.times(EN, v));
        v.position = 20_000;
        v.duration = 4 * MIN;
        // Under a minute the left end says nothing ("0 min" read as a fault).
        assertArrayEquals(new String[]{"", "4\u00A0min\u00A0left"}, PlayerText.times(EN, v));
    }

    @Test
    public void raiPlayPercent() {
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.next = new WatchNext.Item();
        v.position = 38;
        v.duration = 100;
        assertArrayEquals(new String[]{"38%", ""}, PlayerText.times(EN, v));
        assertEquals(0.38f, PlayerText.progress(v), 0.001f);
    }

    @Test
    public void unknownDurationNoBar() {
        PlayerPick.Item a = item(PlayerPick.APP);
        a.position = 31_000;
        assertNull(PlayerText.times(EN, a));
        assertEquals(-1f, PlayerText.progress(a), 0f);
    }

    @Test
    public void songFactsSkipMissing() {
        PlayerPick.Item s = item(PlayerPick.SONG);
        s.album = "Ash & Ember";
        s.lastPlayed = at(15, 9, 35);
        List<String[]> f = PlayerText.facts(EN, s, NOW, ROME);
        assertEquals(2, f.size());
        assertArrayEquals(new String[]{"Album", "Ash & Ember", ""}, f.get(0));
        assertArrayEquals(new String[]{"Played", "today at 9:35", ""}, f.get(1));
        s.track = 3;
        s.tracks = 12;
        s.year = "2016";
        s.genre = "Folk";
        f = PlayerText.facts(EN, s, NOW, ROME);
        // The track number on its own line under the album, smaller; year and genre each a row.
        assertEquals(4, f.size());
        assertArrayEquals(new String[]{"Album", "Ash & Ember", "Track\u00A03\u00A0of\u00A012"}, f.get(0));
        assertArrayEquals(new String[]{"Year", "2016", ""}, f.get(1));
        assertArrayEquals(new String[]{"Genre", "Folk", ""}, f.get(2));
        assertArrayEquals(new String[]{"Played", "today at 9:35", ""}, f.get(3));
        // A genre without a year still has its own row.
        s.year = null;
        f = PlayerText.facts(EN, s, NOW, ROME);
        assertArrayEquals(new String[]{"Genre", "Folk", ""}, f.get(1));
    }

    @Test
    public void songLivePlayingHasNoListenedRow() {
        // "Playing" on the app line already says it: no "Played just now".
        PlayerPick.Item s = item(PlayerPick.SONG);
        s.album = "A";
        s.live = true;
        s.playing = true;
        List<String[]> f = PlayerText.facts(EN, s, NOW, ROME);
        assertEquals(1, f.size());
        assertEquals("Album", f.get(0)[0]);
    }

    @Test
    public void videoFacts() {
        // The channel is the subtitle already, so no "Channel" row; the length as a clock; when, as a clock time.
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.artist = "Kestrel";
        v.duration = 4 * MIN + 20_000;
        v.lastPlayed = at(15, 8, 38);
        List<String[]> f = PlayerText.facts(EN, v, NOW, ROME);
        assertEquals(2, f.size());
        assertArrayEquals(new String[]{"Length", "4:20", ""}, f.get(0));
        assertArrayEquals(new String[]{"Watched", "today at 8:38", ""}, f.get(1));
        // An hour or more: "1 h 05 min".
        v.duration = HOUR + 5 * MIN + 30_000;
        assertArrayEquals(new String[]{"Length", "1\u00A0h\u00A005\u00A0min", ""}, PlayerText.facts(EN, v, NOW, ROME).get(0));
    }

    @Test
    public void videoFactsOwnerRules() {
        // "Paper Lanterns" lost its column (Channel = subtitle, Length = bar, Watched = chip).
        // Now: the total length always, where it stopped only without a bar, when as a clock time with how long.
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.title = "Paper Lanterns";
        v.artist = "Mara Vale";
        v.position = 48_000;
        v.duration = 213_000;
        v.playedMs = 3 * MIN + 10_000;
        v.lastPlayed = at(15, 11, 20);
        List<String[]> f = PlayerText.facts(EN, v, NOW, ROME);
        assertEquals(2, f.size());
        assertArrayEquals(new String[]{"Length", "3:33", ""}, f.get(0));
        assertArrayEquals(new String[]{"Watched", "today at 11:20", "for\u00A03\u00A0min"}, f.get(1));
        assertTrue(PlayerText.showFacts(v, f));
        // A position but no known length: no bar, so where it stopped is a row.
        v.duration = 0;
        f = PlayerText.facts(EN, v, NOW, ROME);
        assertArrayEquals(new String[]{"Stopped at", "0:48", ""}, f.get(0));
        assertEquals("Watched", f.get(1)[0]);
        // Playing now: no "Watched" (the state says it); a short watch has no "per".
        v.duration = 213_000;
        v.live = true;
        v.playing = true;
        f = PlayerText.facts(EN, v, NOW, ROME);
        assertEquals(1, f.size());
        assertTrue(PlayerText.showFacts(v, f));
        v.live = v.playing = false;
        v.playedMs = 20_000;
        assertArrayEquals(new String[]{"Watched", "today at 11:20", ""}, PlayerText.facts(EN, v, NOW, ROME).get(1));
    }

    @Test
    public void watchNextFactsDescriptionFirst() {
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.next = new WatchNext.Item();
        v.next.description = "Una capitana affronta un caso difficile.";
        v.duration = 100;
        v.lastPlayed = at(13, 21, 0);
        List<String[]> f = PlayerText.facts(EN, v, NOW, ROME);
        assertArrayEquals(new String[]{"", "Una capitana affronta un caso difficile.", ""}, f.get(0));
        assertArrayEquals(new String[]{"Watched", "Nov 13 at 21:00", ""}, f.get(1));
        assertEquals(2, f.size());   // RaiPlay's duration is a percent scale: no "Length 0 min"
    }

    @Test
    public void netflixSingleFactShown() {
        PlayerPick.Item a = item(PlayerPick.APP);
        a.lastPlayed = at(15, 9, 36);
        a.playedMs = 70_000;
        List<String[]> f = PlayerText.facts(EN, a, NOW, ROME);
        assertEquals(1, f.size());
        // How long under when, smaller.
        assertArrayEquals(new String[]{"Watched", "today at 9:36", "for\u00A01\u00A0min"}, f.get(0));
        assertTrue(PlayerText.showFacts(a, f));
    }

    @Test
    public void oneFactHidesColumnOnlyForASong() {
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.lastPlayed = at(15, 8, 38);
        List<String[]> f = PlayerText.facts(EN, v, NOW, ROME);
        assertEquals(1, f.size());
        assertTrue(PlayerText.showFacts(v, f));
        PlayerPick.Item s = item(PlayerPick.SONG);
        s.lastPlayed = at(15, 8, 38);
        f = PlayerText.facts(EN, s, NOW, ROME);
        assertEquals(1, f.size());
        assertFalse(PlayerText.showFacts(s, f));
        assertFalse(PlayerText.showFacts(v, new java.util.ArrayList<>()));
    }

    // ---- player fixes (TV check) ----

    @Test
    public void subDropsShowTitleAndEpisodeCodeFromEpisodeTitle() {
        // RaiPlay on the TV: "S4 E10 · Porto - Mare aperto - S4E10 - Bonaccia".
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.title = "Porto - Mare aperto";
        v.next = new WatchNext.Item();
        v.next.title = "Porto - Mare aperto";
        v.next.season = "4";
        v.next.episode = "10";
        v.next.episodeTitle = "Porto - Mare aperto - S4E10 - Bonaccia";
        assertEquals("Bonaccia", PlayerText.sub(EN, v));
        v.next.episodeTitle = "S04E10: Bonaccia";
        assertEquals("Bonaccia", PlayerText.sub(EN, v));
        v.next.episodeTitle = "Porto - Mare aperto - S4E10";
        assertEquals("Season 4, episode 10", PlayerText.sub(EN, v));
        v.next.episodeTitle = "Bonaccia";
        assertEquals("Bonaccia", PlayerText.sub(EN, v));
        // A title that only starts like the show's stays whole.
        v.next.episodeTitle = "Portofino";
        assertEquals("Portofino", PlayerText.sub(EN, v));
    }

    @Test
    public void onlyAPlayingLiveSongRunsAndHidesItsTimesWhenStill() {
        // While the Home is idle the bar stops (OLED: a still picture lets the Philips screensaver start); a running
        // time that stops is a wrong time ("0:10" with the song at 0:38), so it is hidden then.
        PlayerPick.Item i = new PlayerPick.Item();
        i.kind = PlayerPick.SONG;
        i.live = true;
        i.playing = true;
        i.duration = 120_000;
        i.position = 10_000;
        assertTrue(PlayerText.runs(i));
        assertTrue(PlayerText.timesHidden(i, true));
        assertFalse(PlayerText.timesHidden(i, false));
        i.playing = false;   // paused: its time is right, it stays
        assertFalse(PlayerText.runs(i));
        assertFalse(PlayerText.timesHidden(i, true));
        i.playing = true;
        i.kind = PlayerPick.VIDEO;
        assertFalse(PlayerText.runs(i));
        i.kind = PlayerPick.SONG;
        i.duration = 0;
        assertFalse(PlayerText.runs(i));
    }

    @Test
    public void notStartedHasNoBar() {
        // A Watch Next row at 0: no "0%" and no empty bar.
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.next = new WatchNext.Item();
        v.position = 0;
        v.duration = 100;
        assertNull(PlayerText.times(EN, v));
        assertEquals(-1f, PlayerText.progress(v), 0f);
        PlayerPick.Item m = item(PlayerPick.VIDEO);
        m.duration = 4 * MIN;
        assertNull(PlayerText.times(EN, m));
        // A song that has just started playing keeps its bar: it moves.
        PlayerPick.Item s = item(PlayerPick.SONG);
        s.duration = 200_000;
        s.live = true;
        s.playing = true;
        assertArrayEquals(new String[]{"0:00", "3:20"}, PlayerText.times(EN, s));
        assertEquals(0f, PlayerText.progress(s), 0f);
    }

    // ---- UX review: nothing said twice, no orphan words ----

    @Test
    public void factsDropWhatTheCentreAlreadySays() {
        // A single: its album is its title, "Track 1 of 1" says nothing.
        PlayerPick.Item s = item(PlayerPick.SONG);
        s.title = "Blue Hour";
        s.album = "blue hour ";
        s.track = 1;
        s.tracks = 1;
        s.year = "2025";
        s.lastPlayed = at(15, 9, 0);
        List<String[]> f = PlayerText.facts(EN, s, NOW, ROME);
        assertEquals(2, f.size());
        assertEquals("Year", f.get(0)[0]);
        assertEquals("Played", f.get(1)[0]);
        // "Track N of M" only with M > 1.
        s.album = "Another";
        f = PlayerText.facts(EN, s, NOW, ROME);
        assertArrayEquals(new String[]{"Album", "Another", ""}, f.get(0));
        // An album equal to the title takes its track line with it: no "Track" row alone.
        s.album = "Blue Hour";
        s.tracks = 12;
        f = PlayerText.facts(EN, s, NOW, ROME);
        assertEquals("Year", f.get(0)[0]);
    }

    @Test
    public void seenRowIsAClockTimeNotTheStatesRelativeTime() {
        // The state after the app's name says "5 min ago"; the column says when as a clock time instead
        // of dropping the row.
        PlayerPick.Item s = item(PlayerPick.SONG);
        s.album = "A";
        s.year = "2016";
        s.lastPlayed = NOW - 5 * MIN;
        List<String[]> f = PlayerText.facts(EN, s, NOW, ROME);
        assertEquals(3, f.size());
        assertArrayEquals(new String[]{"Played", "today at 11:35", ""}, f.get(2));
        // A session paused hours ago ("Paused") keeps when it last played.
        s.live = true;
        s.lastPlayed = at(15, 8, 5);
        f = PlayerText.facts(EN, s, NOW, ROME);
        assertArrayEquals(new String[]{"Played", "today at 8:05", ""}, f.get(2));
    }

    @Test
    public void netflixWithinTheHourSaysTheClockTimeToo() {
        PlayerPick.Item a = item(PlayerPick.APP);
        a.lastPlayed = NOW - 10 * MIN;
        a.playedMs = 70_000;
        List<String[]> f = PlayerText.facts(EN, a, NOW, ROME);
        assertEquals(1, f.size());
        assertArrayEquals(new String[]{"Watched", "today at 11:30", "for\u00A01\u00A0min"}, f.get(0));
        assertTrue(PlayerText.showFacts(a, f));
        // Less than a minute: no "for".
        a.playedMs = 20_000;
        f = PlayerText.facts(EN, a, NOW, ROME);
        assertArrayEquals(new String[]{"Watched", "today at 11:30", ""}, f.get(0));
    }

    @Test
    public void videoWithABarHasNoStopRowAndNoChannelRow() {
        // YouTube video begun: the bar says where it stopped, the subtitle the channel; the length and when stay.
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.title = "Paper Lanterns";
        v.artist = "Mara Vale";
        v.position = 48_000;
        v.duration = 252_000;
        List<String[]> f = PlayerText.facts(EN, v, NOW, ROME);
        assertEquals(2, f.size());
        assertEquals("Length", f.get(0)[0]);
        assertEquals("Watched", f.get(1)[0]);
    }

    @Test
    public void titleLosesTheChannelPrefix() {
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.artist = "Mara Vale";
        v.title = "Mara Vale - Paper Lanterns (Official Video)";
        assertEquals("Paper Lanterns", PlayerText.title(EN, v, "YouTube"));
        v.title = "mara vale – Silver Lines";
        assertEquals("Silver Lines", PlayerText.title(EN, v, "YouTube"));
        // Only a whole leading copy followed by a dash; the title alone stays.
        v.title = "Mara Valeish - X";
        assertEquals("Mara Valeish - X", PlayerText.title(EN, v, "YouTube"));
        v.title = "Mara Vale - ";
        assertEquals("Mara Vale - ", PlayerText.title(EN, v, "YouTube"));
        v.artist = null;
        v.title = "Kestrel - Route 9";
        assertEquals("Kestrel - Route 9", PlayerText.title(EN, v, "YouTube"));
    }

    @Test
    public void mainButtonWords() {
        assertEquals("Open Netflix", PlayerText.button(EN, item(PlayerPick.APP), "Netflix"));
        assertEquals("Resume", PlayerText.button(EN, item(PlayerPick.VIDEO), "YouTube"));
    }

    @Test
    public void noOrphanWordsInFacts() {
        PlayerPick.Item s = item(PlayerPick.SONG);
        s.album = "Ash & Ember";
        s.track = 3;
        s.tracks = 12;
        String[] f = PlayerText.facts(EN, s, NOW, ROME).get(0);
        assertEquals("Ash & Ember", f[1]);
        assertEquals("Track\u00A03\u00A0of\u00A012", f[2]);
    }

    @Test
    public void titleLosesTrailingUploadTags() {
        // "Paper Lanterns (Official Video) (4K Remaster)" was cut to "…(Official V…".
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.artist = "Mara Vale";
        v.title = "Paper Lanterns (Official Video) (4K Remaster)";
        assertEquals("Paper Lanterns", PlayerText.title(EN, v, "YouTube"));
        v.title = "Song [HD]";
        assertEquals("Song", PlayerText.title(EN, v, "YouTube"));
        v.title = "Song (Lyrics)";
        assertEquals("Song", PlayerText.title(EN, v, "YouTube"));
        v.title = "Song (OFFICIAL AUDIO)";
        assertEquals("Song", PlayerText.title(EN, v, "YouTube"));
        v.title = "Song (Official Music Video) [4K]";
        assertEquals("Song", PlayerText.title(EN, v, "YouTube"));
        v.title = "Song (Audio)";
        assertEquals("Song", PlayerText.title(EN, v, "YouTube"));
        // Only trailing tags: a title that is all tag, a group that is part of the title, a tag inside it, stay.
        v.title = "(Official Video)";
        assertEquals("(Official Video)", PlayerText.title(EN, v, "YouTube"));
        v.title = "Song (Live at the Harbour)";
        assertEquals("Song (Live at the Harbour)", PlayerText.title(EN, v, "YouTube"));
        v.title = "A (Official Video) B";
        assertEquals("A (Official Video) B", PlayerText.title(EN, v, "YouTube"));
        PlayerPick.Item s = item(PlayerPick.SONG);
        s.artist = "Kestrel";
        s.title = "Letters From Home (Audio)";
        assertEquals("Letters From Home", PlayerText.title(EN, s, "YouTube"));
    }

    @Test
    public void seriesLinesEpisodeThenSeason() {
        // A series shows its title, then the episode's name, then "Season 4, episode 10" (no dots).
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.title = "Porto - Mare aperto";
        v.next = new WatchNext.Item();
        v.next.season = "4";
        v.next.episode = "10";
        v.next.episodeTitle = "Porto - Mare aperto - S4E10 - Bonaccia";
        assertArrayEquals(new String[]{"Bonaccia", "Season 4, episode 10"}, PlayerText.seriesLines(EN, v));
        assertEquals("Porto - Mare aperto", PlayerText.title(EN, v, "RaiPlay"));
        // No episode name: the season line takes the episode's place, no third line.
        v.next.episodeTitle = null;
        assertArrayEquals(new String[]{"Season 4, episode 10", ""}, PlayerText.seriesLines(EN, v));
        // Only the episode number, or only the season.
        v.next.season = null;
        assertArrayEquals(new String[]{"Episode 10", ""}, PlayerText.seriesLines(EN, v));
        v.next.season = "2";
        v.next.episode = " ";
        assertArrayEquals(new String[]{"Season 2", ""}, PlayerText.seriesLines(EN, v));
        // A name and nothing else.
        v.next.season = null;
        v.next.episode = null;
        v.next.episodeTitle = "Bonaccia";
        assertArrayEquals(new String[]{"Bonaccia", ""}, PlayerText.seriesLines(EN, v));
        // Nothing of an episode, or not from Watch Next: not a series.
        v.next.episodeTitle = null;
        assertNull(PlayerText.seriesLines(EN, v));
        PlayerPick.Item m = item(PlayerPick.VIDEO);
        m.artist = "Mara Vale";
        assertNull(PlayerText.seriesLines(EN, m));
        assertEquals("Mara Vale", PlayerText.sub(EN, m));
    }

    @Test
    public void italianWording() {
        PlayerPick.Item i = item(PlayerPick.SONG);
        assertEquals("5 min fa", PlayerText.when(IT, i, NOW, ROME));
        i.lastPlayed = NOW - HOUR - MIN;
        assertEquals("1 ora fa", PlayerText.when(IT, i, NOW, ROME));
        i.lastPlayed = NOW - 3 * HOUR;
        assertEquals("3 ore fa", PlayerText.when(IT, i, NOW, ROME));
        i.lastPlayed = at(14, 21, 40);
        assertEquals("ieri", PlayerText.when(IT, i, NOW, ROME));
        i.lastPlayed = at(13, 9, 0);
        assertEquals("2 giorni fa", PlayerText.when(IT, i, NOW, ROME));
        i.live = true;
        assertEquals("In pausa", PlayerText.when(IT, i, NOW, ROME));
        assertEquals("oggi alle 11:35", PlayerText.at(IT, at(15, 11, 35), NOW, ROME));
        assertEquals("12/11 alle 20:10", PlayerText.at(IT, at(12, 20, 10), NOW, ROME));
        PlayerPick.Item a = item(PlayerPick.APP);
        a.position = 31_000;
        assertEquals("Torna su Netflix", PlayerText.title(IT, a, "Netflix"));
        assertEquals("Apri Netflix", PlayerText.button(IT, a, "Netflix"));
        assertEquals("Eri a 0:31", PlayerText.sub(IT, a));
        assertEquals("Riprendi", PlayerText.button(IT, item(PlayerPick.VIDEO), "YouTube"));
        PlayerPick.Item v = item(PlayerPick.VIDEO);
        v.position = 19 * MIN;
        v.duration = 50 * MIN;
        assertArrayEquals(new String[]{"19\u00A0min", "mancano\u00A031\u00A0min"}, PlayerText.times(IT, v));
        v.next = new WatchNext.Item();
        v.next.season = "4";
        v.next.episode = "10";
        assertArrayEquals(new String[]{"Stagione 4, episodio 10", ""}, PlayerText.seriesLines(IT, v));
        PlayerPick.Item s = item(PlayerPick.SONG);
        s.album = "Ash & Ember";
        s.track = 3;
        s.tracks = 12;
        s.year = "2016";
        s.lastPlayed = at(15, 9, 35);
        List<String[]> f = PlayerText.facts(IT, s, NOW, ROME);
        assertArrayEquals(new String[]{"Album", "Ash & Ember", "Brano\u00A03\u00A0di\u00A012"}, f.get(0));
        assertArrayEquals(new String[]{"Anno", "2016", ""}, f.get(1));
        assertArrayEquals(new String[]{"Ascoltato", "oggi alle 9:35", ""}, f.get(2));
        PlayerPick.Item n = item(PlayerPick.APP);
        n.lastPlayed = at(15, 9, 36);
        n.playedMs = 70_000;
        assertArrayEquals(new String[]{"Guardato", "oggi alle 9:36", "per\u00A01\u00A0min"},
                PlayerText.facts(IT, n, NOW, ROME).get(0));
    }
}
