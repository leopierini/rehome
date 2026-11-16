package io.github.leopierini.rehome;

/**
 * The wind on Home's third weather line, from Open-Meteo's current wind_speed_10m (km/h, its default
 * unit) and wind_direction_10m (degrees it blows from): "Wind 14 km/h NE" (in place of the source's
 * name). Pure, tested on the JVM.
 */
final class WindNote {

    /** Under this a direction says nothing: "Wind 1 km/h". */
    static final double STILL_KMH = 2;

    private WindNote() {}

    /**
     * The 8-point compass point of a direction in degrees (any turn), from wind_points (N, NE… NW; Italian SO, O, NO
     * for west): 337.5 to 22.5 is N.
     */
    static String compass(Words w, double degrees) {
        double d = ((degrees % 360) + 360) % 360;
        return w.array(R.array.wind_points)[(int) Math.floor((d + 22.5) / 45) % 8];
    }

    /** "Wind 14 km/h NE"; no direction when almost still or unknown; "" when the speed is unknown. */
    static String note(Words w, double kmh, double degrees) {
        if (Double.isNaN(kmh) || kmh < 0) return "";
        int speed = (int) Math.round(kmh);
        return kmh < STILL_KMH || Double.isNaN(degrees) ? w.get(R.string.wind, speed)
                : w.get(R.string.wind_from, speed, compass(w, degrees));
    }
}
