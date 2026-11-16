package io.github.leopierini.rehome;

/**
 * How much of the weather fits left of the top-bar pills (CategoryHome.keepWeatherClear): its lines end a gap before
 * the first pill, a long place name ellipsizes after the temperature, and the third line drops the wind whole and
 * shortens the rain only between words. Plain arithmetic in px, so HeaderFitTest can check the 55" TV's worst case.
 */
final class HeaderFit {

    private HeaderFit() {}

    /** A row of pills, each with its start margin: what the top bar takes from the screen's right edge. */
    static int rowWidth(int[] pills, int margin) {
        int w = 0;
        for (int p : pills) w += p + margin;
        return w;
    }

    /** The weather's text width: from its box's left edge to {@code gap} before the first pill, less the box's padding. */
    static int textRoom(int weatherLeft, int pillLeft, int gap, int padLeft, int padRight) {
        return pillLeft - gap - weatherLeft - padLeft - padRight;
    }

    /** The place name's cap: the text room less what comes before it on the temperature's line (symbol, gaps, °C). */
    static int cityRoom(int textRoom, int beforeCity) {
        return textRoom - beforeCity;
    }

    /** Whether the place name shows: whole when it fits, ellipsized only with at least {@code minRoom} to show. */
    static boolean cityShown(int room, int cityWidth, int minRoom) {
        return room > 0 && (cityWidth <= room || room >= minRoom);
    }

    /** A text's width in px as the line would draw it (the weather's Paint; 10 per character in HeaderFitTest). */
    interface Measure {
        float width(String text);
    }

    /**
     * The weather's third line in {@code room} px (under 0: not known yet, every part): today's range, the rain to
     * come and the wind, parted by {@code divider}. Parts go in whole while they fit. The wind is never cut: it goes
     * whole. The rain, when it does not fit whole, is shortened by whole words ending in "…" ("Rain from…", at least
     * "Rain…"), never by letters ("Rain from 1…" reads as one o'clock), or goes whole when not even its first word
     * fits. The range always stays (the TextView ellipsizes it in a room too small even for it). Widths are rounded up
     * to whole pixels, so what is returned fits the room and the TextView never cuts it.
     */
    static java.util.List<String> thirdLine(String range, String rain, String wind, Measure measure, float divider,
                                            float room) {
        java.util.List<String> out = new java.util.ArrayList<>(3);
        String[] parts = {range, rain, wind};
        float used = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part == null || part.isEmpty()) continue;
            float gap = out.isEmpty() ? 0 : divider;
            float w = px(measure, part);
            if (room < 0 || used + gap + w <= room) {
                out.add(part);
                used += gap + w;
                continue;
            }
            if (i == 1) {
                String shorter = byWords(part, measure, room - used - gap);
                if (shorter != null) out.add(shorter);
            }
            if (i == 0) out.add(part);
            break;
        }
        return out;
    }

    /** The longest run of {@code text}'s first words, plus "…", that fits {@code room}; null when one word does not. */
    static String byWords(String text, Measure measure, float room) {
        String[] words = text.trim().split(" +");
        for (int n = words.length - 1; n >= 1; n--) {
            String s = String.join(" ", java.util.Arrays.asList(words).subList(0, n)) + "\u2026";
            if (px(measure, s) <= room) return s;
        }
        return null;
    }

    private static float px(Measure measure, String text) {
        return (float) Math.ceil(measure.width(text));
    }
}
