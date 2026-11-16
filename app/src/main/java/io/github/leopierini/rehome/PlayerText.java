package io.github.leopierini.rehome;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * category_player: the words on the player card (PlayerCard), short, from the string resources (Words). Pure,
 * tested on the JVM in English and Italian. The layout and the wording follow the approved wireframe.
 */
final class PlayerText {

    private static final long MIN = 60_000L, HOUR = 60 * MIN;
    /** A no-break space: "4 min", "1 h 05 min" never split across two lines (the resources' own use \u00A0 too). */
    private static final String NB = "\u00A0";

    private PlayerText() {}

    /**
     * The state chip beside the app's name: "Playing" / "Paused" while its session lives (capitalised: the chip
     * starts its own phrase), else how long ago ("5 min ago", "yesterday").
     */
    static String when(Words w, PlayerPick.Item i, long now) {
        return when(w, i, now, TimeZone.getDefault());
    }

    static String when(Words w, PlayerPick.Item i, long now, TimeZone tz) {
        if (i.live) return w.get(i.playing ? R.string.player_playing : R.string.player_paused);
        long ago = Math.max(0, now - i.lastPlayed);
        int days = daysBetween(i.lastPlayed, now, tz);
        if (days == 0 || ago < HOUR) {
            if (ago < MIN) return w.get(R.string.player_just_now);
            if (ago < HOUR) return w.get(R.string.player_minutes_ago, (int) (ago / MIN));
            int h = (int) (ago / HOUR);
            return w.plural(R.plurals.player_hours_ago, h, h);
        }
        return days == 1 ? w.get(R.string.player_yesterday) : w.plural(R.plurals.player_days_ago, days, days);
    }

    /** "today at 11:35", "yesterday at 21:40", "Nov 12 at 20:10" (Italian "oggi alle 11:35", "12/11 alle 20:10"). */
    static String at(Words w, long time, long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(time);
        String hm = String.format(Locale.ROOT, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        int days = daysBetween(time, now, tz);
        if (days == 0) return w.get(R.string.player_today_at, hm);
        if (days == 1) return w.get(R.string.player_yesterday_at, hm);
        java.text.SimpleDateFormat date = new java.text.SimpleDateFormat(w.get(R.string.player_date_pattern), w.locale());
        date.setTimeZone(tz);
        return w.get(R.string.player_date_at, date.format(new java.util.Date(time)), hm);
    }

    /**
     * The big line: the title, or "Back to Netflix" for an app that tells nothing of what it plays. YouTube titles
     * often start with the channel ("Mara Vale - Paper Lanterns"): the channel is the subtitle already, so
     * a whole leading copy of it and the dash after it go.
     */
    static String title(Words w, PlayerPick.Item i, String appLabel) {
        if (i.kind == PlayerPick.APP || PlayerPick.blank(i.title)) return w.get(R.string.player_back_to, appLabel);
        return dropTags(dropChannel(i.title, i.artist));
    }

    private static String dropChannel(String t, String artist) {
        if (PlayerPick.blank(artist)) return t;
        String a = artist.trim();
        String lead = t.trim();
        if (lead.length() <= a.length() || !lead.regionMatches(true, 0, a, 0, a.length())) return t;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s+[-–—]\\s+(.*\\S.*)$")
                .matcher(lead.substring(a.length()));
        return m.matches() ? m.group(1).trim() : t;
    }

    /** One trailing upload tag in () or []: "(Official Video)", "(4K Remaster)", "[HD]", "(Lyrics)", "(Audio)"… */
    private static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile(
            "\\s*[(\\[]\\s*(?:official\\s+)?(?:music\\s+)?(?:video|audio|lyrics?(?:\\s+video)?|visuali[sz]er|hd|hq|4k|"
                    + "4k\\s+remaster(?:ed)?|remaster(?:ed)?\\s+(?:in\\s+)?4k|video\\s+ufficiale|testo)\\s*[)\\]]\\s*$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Trailing upload tags dropped (the card cut "…(Official Video) (4K Remaster)" to "…(Official V…"),
     * one after another; never the whole title, never a group in the middle or one that says something ("(Live at
     * the Harbour)").
     */
    static String dropTags(String t) {
        String out = t;
        while (true) {
            java.util.regex.Matcher m = TAG.matcher(out);
            if (!m.find()) return out;
            String rest = out.substring(0, m.start()).trim();
            if (rest.isEmpty()) return out;
            out = rest;
        }
    }

    /** The main button of an item without ⏮ ⏯ ⏭: "Open Netflix" for an app that tells nothing, else "Resume". */
    static String button(Words w, PlayerPick.Item i, String appLabel) {
        return i.kind == PlayerPick.APP ? w.get(R.string.player_open, appLabel) : w.get(R.string.player_resume);
    }

    /** The line under it: artist or channel, a series' episode (seriesLines), or (an app) where it stopped. */
    static String sub(Words w, PlayerPick.Item i) {
        if (i.kind == PlayerPick.APP) return i.position > 0 ? w.get(R.string.player_left_off, clock(i.position)) : "";
        String[] series = seriesLines(w, i);
        if (series != null) return series[0];
        return i.artist == null ? "" : i.artist;
    }

    /**
     * A series (a Watch Next row with an episode) under its title: {the episode's name, "Season 4, episode 10"};
     * without a name the season line comes first and the second is "" (no "S4 E10 · …", no dots). Null
     * when the row tells nothing of an episode.
     */
    static String[] seriesLines(Words w, PlayerPick.Item i) {
        if (i.kind == PlayerPick.APP || i.next == null) return null;
        boolean s = !PlayerPick.blank(i.next.season), e = !PlayerPick.blank(i.next.episode);
        String code = s && e ? w.get(R.string.player_season_episode, i.next.season.trim(), i.next.episode.trim())
                : s ? w.get(R.string.player_season, i.next.season.trim())
                : e ? w.get(R.string.player_episode, i.next.episode.trim()) : "";
        String name = episodeName(i.next.title != null ? i.next.title : i.title, i.next.episodeTitle);
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) return code.isEmpty() ? null : new String[]{code, ""};
        return new String[]{name, code};
    }

    /**
     * An episode's own name, without what RaiPlay repeats in it: "Porto - Mare aperto - S4E10 - Bonaccia" under the
     * show "Porto - Mare aperto" is "Bonaccia" (the season and episode are on the line already). Only a whole leading
     * copy of the show's title goes ("Portofino" stays), then season/episode codes and the dashes around them.
     */
    static String episodeName(String show, String episode) {
        if (episode == null) return null;
        String e = episode.trim();
        String sh = show == null ? "" : show.trim();
        if (!sh.isEmpty() && e.length() > sh.length() && e.regionMatches(true, 0, sh, 0, sh.length())
                && !Character.isLetterOrDigit(e.charAt(sh.length()))) {
            e = e.substring(sh.length());
        } else if (!sh.isEmpty() && e.equalsIgnoreCase(sh)) {
            e = "";
        }
        String was;
        do {
            was = e;
            e = e.replaceFirst("^[\\s\\-–—:·|,.]+", "");
            e = e.replaceFirst("(?i)^s\\d{1,3}\\s*e\\d{1,4}\\b", "");
        } while (!e.equals(was));
        return e.replaceFirst("[\\s\\-–—:·|,]+$", "");
    }

    /** A song its session plays now, with a duration: its time and bar move every second while the Home shows it. */
    static boolean runs(PlayerPick.Item i) {
        return i != null && i.kind == PlayerPick.SONG && i.live && i.playing && i.duration > 0;
    }

    /**
     * Whether the times and the bar are hidden: a running song while the Home is idle. Its bar stops then (OLED: a
     * still picture lets the Philips screensaver start), and a time that stops while the song goes on is wrong
     * ("0:10" shown with the song at 0:38). The row keeps its place: nothing else moves.
     */
    static boolean timesHidden(PlayerPick.Item i, boolean idle) {
        return idle && runs(i);
    }

    /**
     * Whether there is a bar: a duration, and a start made (a row never begun is not "0%"); a song playing now has its
     * bar from 0:00, as it moves.
     */
    private static boolean hasBar(PlayerPick.Item i) {
        return i.duration > 0 && (i.position > 0 || (i.live && i.playing));
    }

    /** The bar's two ends, or null when there is no bar (no duration, or not begun). */
    static String[] times(Words w, PlayerPick.Item i) {
        if (!hasBar(i)) return null;
        if (percent(i)) return new String[]{Math.round(progress(i) * 100) + "%", ""};
        if (i.kind == PlayerPick.SONG) return new String[]{clock(i.position), clock(i.duration)};
        long left = Math.max(0, i.duration - i.position);
        // Under a minute the left end stays empty: "0 min" read as a fault.
        String done = i.position < MIN ? "" : (i.position / MIN) + NB + "min";
        return new String[]{done, w.get(R.string.player_minutes_left, (int) ((left + MIN - 1) / MIN))};
    }

    /** 0 to 1, or -1 when unknown. */
    static float progress(PlayerPick.Item i) {
        if (!hasBar(i)) return -1f;
        return Math.max(0f, Math.min(1f, i.position / (float) i.duration));
    }

    /** At most this many facts: the column is as tall as the picture. */
    static final int MAX_FACTS = 4;

    /**
     * The right column's rows, {label, value, secondary} (secondary: a smaller, greyer line under the value, or ""),
     * only those with a value and only what the centre does not say already: no "Channel" equal to
     * the subtitle, no album equal to the title, no "Track 1 of 1", no "Stopped at" when the bar tells it, and
     * "Played/Watched" as a clock time (the state after the app's name is relative), none while it plays. A
     * Watch Next plot comes first, with no label (PlayerCard shows it on up to three lines). The track
     * number goes under the album, year and genre are rows of their own, Netflix's "for N min" goes under when.
     */
    static List<String[]> facts(Words w, PlayerPick.Item i, long now, TimeZone tz) {
        List<String[]> out = new ArrayList<>();
        // When, as a clock time (the state after the app's name says "5 min ago"); nothing while it plays.
        String seen = (i.live && i.playing) || i.lastPlayed <= 0 ? null : at(w, i.lastPlayed, now, tz);
        String how = i.playedMs >= MIN
                ? w.get(R.string.player_for_minutes, (int) Math.round(i.playedMs / (double) MIN)) : "";
        String sub = norm(sub(w, i));
        String watched = w.get(R.string.player_watched);
        switch (i.kind) {
            case PlayerPick.SONG: {
                String a = PlayerPick.blank(i.album) || norm(i.album).equals(norm(i.title)) ? "" : i.album.trim();
                String track = i.track > 0 && i.tracks > 1 ? w.get(R.string.player_track, i.track, i.tracks) : "";
                if (!a.isEmpty()) out.add(fact(w.get(R.string.player_album), a, track));
                if (!PlayerPick.blank(i.year)) out.add(fact(w.get(R.string.player_year), i.year.trim(), ""));
                if (!PlayerPick.blank(i.genre)) out.add(fact(w.get(R.string.player_genre), i.genre.trim(), ""));
                if (seen != null) out.add(fact(w.get(R.string.player_played), seen, ""));
                break;
            }
            case PlayerPick.APP: {
                if (seen != null) out.add(fact(watched, seen, how));
                else if (!how.isEmpty()) out.add(fact(watched, how, ""));
                break;
            }
            default: {
                if (i.next != null && !PlayerPick.blank(i.next.description)) {
                    out.add(fact("", i.next.description.trim(), ""));
                }
                if (i.next == null && !PlayerPick.blank(i.artist) && !norm(i.artist).equals(sub)) {
                    out.add(fact(w.get(R.string.player_channel), i.artist.trim(), ""));
                }
                // The total length always (the bar shows progress), where it stopped only without a bar.
                if (i.duration > 0 && !percent(i)) {
                    out.add(fact(w.get(R.string.player_length), length(i.duration), ""));
                }
                if (i.position > 0 && !percent(i) && times(w, i) == null) {
                    out.add(fact(w.get(R.string.player_stopped_at), clock(i.position), ""));
                }
                if (seen != null) out.add(fact(watched, seen, how));
            }
        }
        return out.size() > MAX_FACTS ? new ArrayList<>(out.subList(0, MAX_FACTS)) : out;
    }

    private static String[] fact(String label, String value, String secondary) {
        return new String[]{label, value, secondary};
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * A song's column shows with two rows or more; a video's and an app's (Netflix) with one (the video
     * card without its column looked empty).
     */
    static boolean showFacts(PlayerPick.Item i, List<String[]> facts) {
        return facts.size() >= 2 || (i.kind != PlayerPick.SONG && !facts.isEmpty());
    }

    /** A video's length: "3:33", or from an hour "1 h 05 min". */
    static String length(long ms) {
        if (ms < HOUR) return clock(ms);
        long m = ms / MIN;
        return String.format(Locale.ROOT, "%d%sh%s%02d%smin", m / 60, NB, NB, m % 60, NB);
    }

    /** RaiPlay writes its position as a percent, with a duration of 100. */
    private static boolean percent(PlayerPick.Item i) {
        return i.next != null && i.duration == 100;
    }

    /** "1:47", "1:02:05". */
    static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** Calendar days from {@code from} to {@code to} in tz (0 = the same day). */
    private static int daysBetween(long from, long to, TimeZone tz) {
        Calendar a = Calendar.getInstance(tz), b = Calendar.getInstance(tz);
        a.setTimeInMillis(from);
        b.setTimeInMillis(to);
        int days = 0;
        // At most 30 days apart (older items never reach the card): a short walk, exact across DST changes.
        while (days < 400 && (a.get(Calendar.YEAR) != b.get(Calendar.YEAR)
                || a.get(Calendar.DAY_OF_YEAR) != b.get(Calendar.DAY_OF_YEAR))) {
            if (a.after(b)) return 0;
            a.add(Calendar.DAY_OF_YEAR, 1);
            days++;
        }
        return days;
    }
}
