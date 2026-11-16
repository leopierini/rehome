package io.github.leopierini.rehome;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** The weather city's search: Open-Meteo's geocoding request and its answer (a recorded one for "Rome"). */
public class CitySearchTest {

    private static String fixture(String name) throws Exception {
        try (InputStream in = CitySearchTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull("missing test resource " + name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    @Test
    public void parseReadsTheRecordedRomeAnswer() throws Exception {
        List<City> found = CitySearch.parse(fixture("geocoding-rome.json"));
        assertEquals(3, found.size());
        City rome = found.get(0);
        assertEquals("Rome", rome.name());
        assertEquals("Lazio", rome.region());
        assertEquals("Italy", rome.country());
        assertEquals(41.89, rome.lat(), 0.01);
        assertEquals(12.51, rome.lon(), 0.01);
        // The others keep the answer's order.
        assertEquals("Georgia", found.get(1).region());
        assertEquals("United States", found.get(1).country());
    }

    @Test
    public void parseOfNoResultsIsEmpty() {
        assertTrue(CitySearch.parse("{}").isEmpty());
        // Open-Meteo's real answer for a name it does not know.
        assertTrue(CitySearch.parse("{\"generationtime_ms\":0.103354454}").isEmpty());
        assertTrue(CitySearch.parse("{\"results\":[]}").isEmpty());
    }

    @Test
    public void parseSkipsPlacesWithoutNameOrCoordinates() {
        String json = "{\"results\":["
                + "{\"name\":\"Rome\",\"latitude\":41.89193},"
                + "{\"latitude\":41.0,\"longitude\":12.0},"
                + "{\"name\":\"Forlì\",\"latitude\":44.22177,\"longitude\":12.04144,\"country\":\"Italia\"}"
                + "]}";
        List<City> found = CitySearch.parse(json);
        assertEquals(1, found.size());
        assertEquals("Forlì", found.get(0).name());
        // Missing parts are empty, not "null".
        assertEquals("", found.get(0).region());
        assertEquals("Italia", found.get(0).country());
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseOfSomethingElseThrows() {
        // A hotspot's sign-in page is not "no results": the picker says "No connection" for it.
        CitySearch.parse("<html><body>Sign in</body></html>");
    }

    @Test
    public void urlEncodesApostropheAndSpace() {
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name=L%27Aquila&count=8&language=en&format=json",
                CitySearch.url("L'Aquila", "en"));
        String spaced = CitySearch.url("L'Aquila centro", "en");
        assertTrue(spaced, spaced.contains("name=L%27Aquila%20centro&"));
    }

    @Test
    public void urlEncodesAccentsAsUtf8() {
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name=Forl%C3%AC&count=8&language=it&format=json",
                CitySearch.url("Forlì", "it"));
    }

    @Test
    public void urlTrimsTheQuery() {
        assertEquals(CitySearch.url("Rome", "en"), CitySearch.url("  Rome ", "en"));
    }

    @Test
    public void searchesTheNameBeforeTheFirstComma() {
        // Open-Meteo matches the place name only: "Rome, Italy" as a whole finds nothing.
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name=Rome&count=8&language=en&format=json",
                CitySearch.url("Rome, Italy", "en"));
        assertEquals(CitySearch.url("Rome", "en"), CitySearch.url(" Rome ,Lazio, Italy ", "en"));
        assertEquals(CitySearch.url("Rome", "en"), CitySearch.url("Rome,", "en"));
        assertEquals("Rome", CitySearch.name("Rome, Italy"));
        assertEquals("", CitySearch.name(", Italy"));
        assertEquals("", CitySearch.name(null));
    }

    private static List<City> romes() {
        return Arrays.asList(
                new City("Rome", "Georgia", "United States", 34.26, -85.16),
                new City("Rome", "Lazio", "Italy", 41.89, 12.51),
                new City("Rome", "New York", "United States", 43.21, -75.46),
                new City("Rome", "Lazio", "Italia", 41.89, 12.51));
    }

    private static List<String> regions(List<City> cities) {
        List<String> out = new ArrayList<>();
        for (City c : cities) out.add(c.region() + "/" + c.country());
        return out;
    }

    @Test
    public void rankPutsThePlacesInTheNamedCountryOrRegionFirst() {
        assertEquals(Arrays.asList("Lazio/Italy", "Georgia/United States", "New York/United States", "Lazio/Italia"),
                regions(CitySearch.rank(romes(), "Rome, Italy")));
        // Case, accents and a beginning of the word are enough; a region works like a country.
        assertEquals(Arrays.asList("Lazio/Italy", "Lazio/Italia", "Georgia/United States", "New York/United States"),
                regions(CitySearch.rank(romes(), "rome, ITAL")));
        assertEquals(Arrays.asList("New York/United States", "Georgia/United States", "Lazio/Italy", "Lazio/Italia"),
                regions(CitySearch.rank(romes(), "Rome, New York")));
        assertEquals("Lazio/Italia", regions(CitySearch.rank(romes(), "Rome, Itàlia")).get(0));
        // More named parts that match go higher: the picker's own "Rome, Lazio, Italy" picks that one.
        assertEquals("Lazio/Italy", regions(CitySearch.rank(romes(), "Rome, Lazio, Italy")).get(0));
        // Nothing after the comma, or nothing that matches: Open-Meteo's order is kept.
        assertEquals(regions(romes()), regions(CitySearch.rank(romes(), "Rome")));
        assertEquals(regions(romes()), regions(CitySearch.rank(romes(), "Rome, Narnia")));
    }

    @Test
    public void languageIsEnglishOrItalian() {
        assertEquals("it", CitySearch.language("it"));
        assertEquals("en", CitySearch.language("en"));
        assertEquals("en", CitySearch.language("fr"));
        assertEquals("en", CitySearch.language(""));
        assertEquals("en", CitySearch.language(null));
    }

    @Test
    public void labelIsNameRegionCountry() {
        assertEquals("Rome, Lazio, Italy", new City("Rome", "Lazio", "Italy", 41.89, 12.51).label());
        // Empty parts and a region named like the place are left out.
        assertEquals("Forlì, Italia", new City("Forlì", "", "Italia", 44.2, 12.0).label());
        assertEquals("Rome, Italy", new City("Rome", "Rome", "Italy", 41.89, 12.51).label());
        assertEquals("Rome", new City("Rome", "", "", 41.89, 12.51).label());
    }
}
