package io.github.leopierini.rehome;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The weather city kept in the launcher's settings: picked, turned off, or never set. */
public class CityStoreTest {

    private static final City ROME = new City("Rome", "Lazio", "Italy", 41.89193, 12.51133);

    private static final City MILAN = new City("Milan", "Lombardy", "Italy", 45.46427, 9.18951);

    @Test
    public void aReadingFetchedForTheCityStillPickedIsKept() {
        FakePrefs p = new FakePrefs();
        CityStore.save(p, ROME);
        assertTrue(CityStore.readingFor(ROME.lat(), ROME.lon(), CityStore.home(p, null)));
    }

    @Test
    public void aReadingFetchedBeforeACityChangeIsDropped() {
        FakePrefs p = new FakePrefs();
        CityStore.save(p, ROME);
        double lat = ROME.lat(), lon = ROME.lon();   // the request starts for Rome
        CityStore.save(p, MILAN);                    // the owner picks Milan before it answers
        assertFalse(CityStore.readingFor(lat, lon, CityStore.home(p, null)));
    }

    @Test
    public void aReadingFetchedBeforeTheWeatherWasTurnedOffIsDropped() {
        FakePrefs p = new FakePrefs();
        CityStore.save(p, ROME);
        CityStore.save(p, null);
        assertFalse(CityStore.readingFor(ROME.lat(), ROME.lon(), CityStore.home(p, ROME)));
        assertFalse(CityStore.readingFor(Double.NaN, Double.NaN, null));
    }

    @Test
    public void nothingSavedIsNoCityAndNotOff() {
        FakePrefs p = new FakePrefs();
        assertNull(CityStore.get(p));
        assertFalse(CityStore.isOff(p));
    }

    @Test
    public void aSavedCityComesBackWhole() {
        FakePrefs p = new FakePrefs();
        CityStore.save(p, ROME);
        assertEquals(ROME, CityStore.get(p));
        assertEquals(41.89193, CityStore.get(p).lat(), 0);
        assertEquals(12.51133, CityStore.get(p).lon(), 0);
        assertFalse(CityStore.isOff(p));
    }

    @Test
    public void accentsAndApostrophesSurvive() {
        FakePrefs p = new FakePrefs();
        City aquila = new City("L'Aquila", "Abruzzo", "Italia", 42.35055, 13.39954);
        CityStore.save(p, aquila);
        assertEquals(aquila, CityStore.get(p));
        City forli = new City("Forlì", "Emilia-Romagna", "Italia", 44.22177, 12.04144);
        CityStore.save(p, forli);
        assertEquals(forli, CityStore.get(p));
    }

    @Test
    public void clearForgetsTheCityAndTurnsTheWeatherOff() {
        FakePrefs p = new FakePrefs();
        CityStore.save(p, ROME);
        CityStore.save(p, null);
        assertNull(CityStore.get(p));
        assertTrue(CityStore.isOff(p));
        // Picking a city again turns it back on.
        CityStore.save(p, ROME);
        assertEquals(ROME, CityStore.get(p));
        assertFalse(CityStore.isOff(p));
    }

    @Test
    public void everyChangeMovesTheSettingsRevision() {
        // Home rebuilds on its next resume when Launcher settings' change count moved (MainActivity).
        FakePrefs p = new FakePrefs();
        p.edit().putLong(Config.SETTINGS_REVISION_KEY, 4).commit();
        CityStore.save(p, ROME);
        assertEquals(5, p.getLong(Config.SETTINGS_REVISION_KEY, 0));
        CityStore.save(p, null);
        assertEquals(6, p.getLong(Config.SETTINGS_REVISION_KEY, 0));
    }

    @Test
    public void aDamagedEntryIsNoCity() {
        FakePrefs p = new FakePrefs();
        CityStore.save(p, ROME);
        p.edit().putString("weather_city_lat", "north").commit();
        assertNull(CityStore.get(p));
    }

    @Test
    public void configPlaceNeedsBothCoordinates() {
        assertEquals(new City("Rome", "", "", 41.8919, 12.5113), CityStore.fromConfig("Rome", "41.8919", " 12.5113 "));
        assertNull(CityStore.fromConfig("Rome", "", "12.5113"));
        assertNull(CityStore.fromConfig("Rome", "41.8919", "east"));
        assertNull(CityStore.fromConfig("", "", ""));
        // Coordinates without a name: the weather shows, with no place beside it (as before).
        assertEquals("", CityStore.fromConfig("", "41.8919", "12.5113").name());
    }

    @Test
    public void pickedCityWinsOverConfigAndOffSilencesBoth() {
        City config = CityStore.fromConfig("Forlì", "44.2218", "12.0414");
        FakePrefs p = new FakePrefs();
        assertEquals(config, CityStore.home(p, config));
        assertNull(CityStore.home(p, null));
        CityStore.save(p, ROME);
        assertEquals(ROME, CityStore.home(p, config));
        CityStore.save(p, null);
        assertNull(CityStore.home(p, config));
    }

    /** SharedPreferences in a map: enough for CityStore, no Android runtime needed. */
    static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String k, String d) { return values.containsKey(k) ? (String) values.get(k) : d; }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String k, Set<String> d) { return values.containsKey(k) ? (Set<String>) values.get(k) : d; }
        @Override public int getInt(String k, int d) { return values.containsKey(k) ? (Integer) values.get(k) : d; }
        @Override public long getLong(String k, long d) { return values.containsKey(k) ? (Long) values.get(k) : d; }
        @Override public float getFloat(String k, float d) { return values.containsKey(k) ? (Float) values.get(k) : d; }
        @Override public boolean getBoolean(String k, boolean d) { return values.containsKey(k) ? (Boolean) values.get(k) : d; }
        @Override public boolean contains(String k) { return values.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> put = new HashMap<>();
                final java.util.List<String> removed = new java.util.ArrayList<>();
                boolean clear;

                @Override public Editor putString(String k, String v) { put.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { put.put(k, v); return this; }
                @Override public Editor putInt(String k, int v) { put.put(k, v); return this; }
                @Override public Editor putLong(String k, long v) { put.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { put.put(k, v); return this; }
                @Override public Editor putBoolean(String k, boolean v) { put.put(k, v); return this; }
                @Override public Editor remove(String k) { removed.add(k); return this; }
                @Override public Editor clear() { clear = true; return this; }
                @Override public boolean commit() {
                    if (clear) values.clear();
                    for (String k : removed) values.remove(k);
                    values.putAll(put);
                    return true;
                }
                @Override public void apply() { commit(); }
            };
        }
    }
}
