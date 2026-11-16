package io.github.leopierini.rehome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The weather city picked in Launcher settings (CityPickerActivity), kept with the other settings
 * (Config.SETTINGS_NAME) so a reinstall that keeps data keeps it. Three states: a city; off (the owner turned the
 * weather off); nothing saved, where Home falls back to config.xml's weather_* and, without those, asks for a city.
 * Every change moves the settings' change count, so Home rebuilds with it when it next resumes.
 */
final class CityStore {
    private static final String NAME = "weather_city_name";
    private static final String REGION = "weather_city_region";
    private static final String COUNTRY = "weather_city_country";
    // Kept as text (Double.toString): a float would round the fifth decimal.
    private static final String LAT = "weather_city_lat";
    private static final String LON = "weather_city_lon";
    private static final String OFF = "weather_off";

    /** CategoryHome's saved reading ("json", "at"): it belongs to the old place, so it goes with every change. */
    static final String READING_PREFS = "category_weather";

    private CityStore() {
    }

    static City get(Context c) {
        return get(prefs(c));
    }

    static boolean isOff(Context c) {
        return isOff(prefs(c));
    }

    static void set(Context c, City city) {
        save(prefs(c), city);
        forgetReading(c);
    }

    /** No city, and no weather on Home until one is picked again. */
    static void clear(Context c) {
        save(prefs(c), null);
        forgetReading(c);
    }

    /** The city Home shows the weather for: the one picked here, else config.xml's (null when off or neither). */
    static City home(Context c) {
        android.content.res.Resources r = c.getResources();
        return home(prefs(c), fromConfig(r.getString(R.string.weather_city), r.getString(R.string.weather_latitude),
                r.getString(R.string.weather_longitude)));
    }

    static City home(SharedPreferences p, City config) {
        City picked = get(p);
        if (picked != null) return picked;
        return isOff(p) ? null : config;
    }

    /**
     * Whether a forecast asked for {@code lat}/{@code lon} still belongs on Home: {@code now} (the city Home shows the
     * weather for at the answer, {@link #home}) is the same place. False after a city change or with the weather off,
     * so a slow answer for the old city is neither shown nor saved as the new one's reading.
     */
    static boolean readingFor(double lat, double lon, City now) {
        if (now == null || Double.isNaN(lat) || Double.isNaN(lon)) return false;
        return Math.abs(now.lat() - lat) < 1e-6 && Math.abs(now.lon() - lon) < 1e-6;
    }

    /** config.xml's weather_city/latitude/longitude as a City, or null without both coordinates. */
    static City fromConfig(String name, String lat, String lon) {
        try {
            double la = Double.parseDouble(lat.trim());
            double lo = Double.parseDouble(lon.trim());
            if (Double.isNaN(la) || Double.isNaN(lo)) return null;
            return new City(name.trim(), "", "", la, lo);
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    static City get(SharedPreferences p) {
        String name = p.getString(NAME, null);
        if (name == null || name.isEmpty()) return null;
        try {
            double lat = Double.parseDouble(p.getString(LAT, ""));
            double lon = Double.parseDouble(p.getString(LON, ""));
            if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
            return new City(name, p.getString(REGION, ""), p.getString(COUNTRY, ""), lat, lon);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static boolean isOff(SharedPreferences p) {
        return p.getBoolean(OFF, false);
    }

    /**
     * {@code city}, or off for null, with the change count moved. apply(): the in-memory values change at once, so
     * Home (same process) reads them when it resumes; the disk write follows.
     */
    static void save(SharedPreferences p, City city) {
        SharedPreferences.Editor e = p.edit();
        if (city == null) {
            e.remove(NAME).remove(REGION).remove(COUNTRY).remove(LAT).remove(LON).putBoolean(OFF, true);
        } else {
            e.putString(NAME, city.name()).putString(REGION, city.region()).putString(COUNTRY, city.country())
                    .putString(LAT, Double.toString(city.lat())).putString(LON, Double.toString(city.lon()))
                    .remove(OFF);
        }
        e.putLong(Config.SETTINGS_REVISION_KEY, p.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1);
        e.apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    private static void forgetReading(Context c) {
        c.getSharedPreferences(READING_PREFS, Context.MODE_PRIVATE).edit().remove("json").remove("at").apply();
    }
}
