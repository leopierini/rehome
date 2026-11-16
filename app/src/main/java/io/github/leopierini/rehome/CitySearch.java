package io.github.leopierini.rehome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The weather city by name: Open-Meteo's geocoding API (geocoding-api.open-meteo.com, no key, the forecast's own
 * provider). The request and the parsing are static and tested on the JVM; {@link #find} does the network call and
 * must run off the main thread (CityPickerActivity).
 */
final class CitySearch {
    private static final String BASE = "https://geocoding-api.open-meteo.com/v1/search?name=";
    /** Enough to tell the Romes apart, few enough for one screen. */
    private static final int COUNT = 8;

    private CitySearch() {
    }

    /** The request for the place name in {@code query} ({@link #name}), names in {@code lang} (CitySearch.language). */
    static String url(String query, String lang) {
        String q = name(query);
        String name;
        try {
            // URLEncoder writes a space as "+"; %20 is a space in every part of a URL.
            name = URLEncoder.encode(q, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);   // UTF-8 is always there
        }
        return BASE + name + "&count=" + COUNT + "&language=" + language(lang) + "&format=json";
    }

    /**
     * The place name in {@code query}: the text before the first comma. Open-Meteo matches names only, so
     * "Rome, Italy" as a whole finds nothing; the rest is used by {@link #rank}.
     */
    static String name(String query) {
        if (query == null) return "";
        int comma = query.indexOf(',');
        return (comma < 0 ? query : query.substring(0, comma)).trim();
    }

    /**
     * {@code found} with the places whose region or country matches the parts after the comma in {@code query}
     * first ("Rome, Italy", "Rome, Lazio, Italy", "Rome, ital"), the most matching parts highest; the answer's own
     * order otherwise. A part matches a region or country that starts with it, ignoring case and accents.
     */
    static List<City> rank(List<City> found, String query) {
        List<String> parts = new ArrayList<>();
        int comma = query == null ? -1 : query.indexOf(',');
        if (comma >= 0) {
            for (String p : query.substring(comma + 1).split(",")) {
                String k = fold(p);
                if (!k.isEmpty()) parts.add(k);
            }
        }
        if (parts.isEmpty() || found.size() < 2) return found;
        List<City> ranked = new ArrayList<>(found);
        // List.sort is stable: places with the same score keep Open-Meteo's order.
        ranked.sort((a, b) -> Integer.compare(score(b, parts), score(a, parts)));
        return ranked;
    }

    private static int score(City c, List<String> parts) {
        String region = fold(c.region());
        String country = fold(c.country());
        int n = 0;
        for (String p : parts) {
            if ((!region.isEmpty() && region.startsWith(p)) || (!country.isEmpty() && country.startsWith(p))) n++;
        }
        return n;
    }

    /** Lower case without accents, for comparing what was typed with Open-Meteo's names. */
    private static String fold(String s) {
        return Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /** The language for the names in the answer: the launcher's own, Italian or English (anything else). */
    static String language(String launcherLanguage) {
        return "it".equals(launcherLanguage) ? "it" : "en";
    }

    /**
     * The places in an answer, in its order; empty when it has none (Open-Meteo leaves "results" out then).
     * A place without a name or both coordinates is skipped.
     *
     * @throws IllegalArgumentException when the text is not JSON at all (a hotspot's sign-in page)
     */
    static List<City> parse(String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (JSONException e) {
            throw new IllegalArgumentException("not a geocoding answer", e);
        }
        JSONArray results = o.optJSONArray("results");
        if (results == null) return Collections.emptyList();
        List<City> found = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String name = r.optString("name", "").trim();
            double lat = r.optDouble("latitude", Double.NaN);
            double lon = r.optDouble("longitude", Double.NaN);
            if (name.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon)) continue;
            found.add(new City(name, r.optString("admin1", "").trim(), r.optString("country", "").trim(), lat, lon));
        }
        return found;
    }

    /**
     * The places for {@code query}, from the network (blocking: never on the main thread), ranked by {@link #rank}.
     *
     * @throws IOException no network, a timeout, or an answer that is not Open-Meteo's
     */
    static List<City> find(String query, String lang) throws IOException {
        if (name(query).isEmpty()) return Collections.emptyList();   // ", Italy": no name to look for
        HttpURLConnection c = (HttpURLConnection) new URL(url(query, lang)).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                // Eight places are about 4 KB; a sign-in page is not read to its end.
                while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
                return rank(parse(out.toString("UTF-8")), query);
            } catch (IllegalArgumentException e) {
                throw new IOException(e);
            }
        } finally {
            c.disconnect();
        }
    }
}
