package io.github.leopierini.rehome;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The weather's rain line for the next hours: "Rain from 18:00", "Rain until 20:00", "Rain", or nothing when
 * dry; "Snow" the same way when the first wet hour is snow (Italian: "Pioggia dalle 18"). Pure logic over
 * Open-Meteo's hourly arrays, so it is tested on the JVM.
 *
 * An hour counts as wet when its precipitation probability is at least 50% or its WMO code is rain, drizzle,
 * showers, snow or a thunderstorm. Hours come as the API writes them in the place's own time
 * ("2026-11-15T18:00", timezone=auto); hours before {@code nowHour} are skipped, so a saved reading shown again
 * an hour later talks about the hours still ahead. Midnight needs nothing special: the hour is read from each
 * entry's own text, and the full date-and-hour text sorts in time order.
 */
final class RainNote {

    static final int WET_PERCENT = 50;

    private RainNote() {}

    /** "yyyy-MM-ddTHH" of {@code epochMs} in the place's time, from the API's utc_offset_seconds. */
    static String hourKey(long epochMs, int utcOffsetSeconds) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH")
                .format(Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.ofTotalSeconds(utcOffsetSeconds)));
    }

    /**
     * @param times       hourly.time, local "yyyy-MM-ddTHH:mm"
     * @param probability hourly.precipitation_probability in %, -1 (or a short/null array) where unknown
     * @param codes       hourly.weather_code, -1 (or a short/null array) where unknown
     * @param nowHour     {@link #hourKey} of now: earlier hours are left out
     */
    static String note(Words w, String[] times, int[] probability, int[] codes, String nowHour) {
        if (times == null) return "";
        // The hours still ahead, with whether each is wet and whether it is snow.
        int n = 0;
        int[] hour = new int[times.length];
        boolean[] wet = new boolean[times.length];
        boolean[] snow = new boolean[times.length];
        for (int i = 0; i < times.length; i++) {
            String t = times[i];
            if (t == null || t.length() < 13 || t.charAt(10) != 'T') continue;
            int h;
            try {
                h = Integer.parseInt(t.substring(11, 13));
            } catch (NumberFormatException e) {
                continue;
            }
            if (nowHour != null && t.substring(0, 13).compareTo(nowHour) < 0) continue;
            int p = probability != null && i < probability.length ? probability[i] : -1;
            int c = codes != null && i < codes.length ? codes[i] : -1;
            hour[n] = h;
            snow[n] = isSnow(c);
            wet[n] = p >= WET_PERCENT || isRain(c) || snow[n];
            n++;
        }
        int firstWet = -1;
        for (int i = 0; i < n && firstWet < 0; i++) if (wet[i]) firstWet = i;
        if (firstWet < 0) return "";
        String kind = w.get(snow[firstWet] ? R.string.weather_snow : R.string.weather_rain);
        if (firstWet > 0) return from(w, kind, hour[firstWet]);
        for (int i = 1; i < n; i++) if (!wet[i]) return until(w, kind, hour[i]);
        return kind;
    }

    /** Drizzle, rain, freezing rain, showers, thunderstorms. */
    static boolean isRain(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || (code >= 95 && code <= 99);
    }

    static boolean isSnow(int code) {
        return (code >= 71 && code <= 77) || code == 85 || code == 86;
    }

    /** "Rain from 18:00", "from 1:00", "from midnight" (Italian: "dalle 18", "dall'1", "da mezzanotte"). */
    private static String from(Words w, String kind, int h) {
        if (h == 0) return w.get(R.string.rain_from_midnight, kind);
        if (h == 1) return w.get(R.string.rain_from_one, kind);
        return w.get(R.string.rain_from, kind, h);
    }

    /** "Rain until 20:00", "until 1:00", "until midnight" (Italian: "fino alle 20", "fino all'1"…). */
    private static String until(Words w, String kind, int h) {
        if (h == 0) return w.get(R.string.rain_until_midnight, kind);
        if (h == 1) return w.get(R.string.rain_until_one, kind);
        return w.get(R.string.rain_until, kind, h);
    }
}
