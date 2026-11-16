package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The weather beside the clock is a vertical LinearLayout as wide as its content (CategoryHome.buildWeather). A line
 * added to it without layout params gets the vertical default, MATCH_PARENT width, and a MATCH_PARENT child adds
 * nothing to a WRAP_CONTENT LinearLayout's width: it is then stretched to what the other lines need. With no city
 * set the other two lines are empty, so the temperature's line, holding "Set your city", was laid out 0px wide and
 * the prompt never showed (first seen on the 55OLED706). Pinned on the source, as LinearLayout does not measure on
 * the JVM.
 */
public class WeatherLinesTest {

    private static String buildWeather() throws Exception {
        String code = LauncherLocaleTest.source("CategoryHome.java");
        int start = code.indexOf("weather = new LinearLayout(act);");
        int end = code.indexOf("if (askCity) showSetCity();", start);
        assertTrue(start > 0 && end > start);
        return code.substring(start, end);
    }

    @Test
    public void everyWeatherLineIsAddedWithItsOwnWidth() throws Exception {
        String body = buildWeather();
        Matcher bare = Pattern.compile("weather\\.addView\\(\\s*\\w+\\s*\\)").matcher(body);
        boolean found = bare.find();
        assertFalse("a weather line added with the default MATCH_PARENT width: " + (found ? bare.group() : ""), found);
    }

    @Test
    public void theTemperatureLineWrapsItsContent() throws Exception {
        String body = buildWeather();
        Matcher add = Pattern.compile("weather\\.addView\\(top,\\s*(\\w+)\\)").matcher(body);
        assertTrue("the temperature's line is added with layout params", add.find());
        String params = add.group(1);
        Matcher decl = Pattern.compile("LinearLayout\\.LayoutParams " + params
                + " = new LinearLayout\\.LayoutParams\\(\\s*ViewGroup\\.LayoutParams\\.WRAP_CONTENT,").matcher(body);
        assertTrue("its width is WRAP_CONTENT", decl.find());
    }
}
