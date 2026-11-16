package io.github.leopierini.rehome;

/**
 * A place for the Home's weather, as Open-Meteo's geocoding names it (CitySearch) and Launcher settings keep it
 * (CityStore): the name shown beside the temperature, its region and country for telling places apart in the picker,
 * and the coordinates the forecast is asked for.
 */
record City(String name, String region, String country, double lat, double lon) {

    /** "Name, Region, Country" for the picker's rows: empty parts and a region named like the place left out. */
    String label() {
        StringBuilder b = new StringBuilder(name);
        if (!region.isEmpty() && !region.equals(name)) b.append(", ").append(region);
        if (!country.isEmpty() && !country.equals(region)) b.append(", ").append(country);
        return b.toString();
    }
}
