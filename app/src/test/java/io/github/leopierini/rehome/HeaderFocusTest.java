package io.github.leopierini.rehome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Plain JVM tests for Up from the first row: the clock group for the first two columns, else the nearest pill. */
public class HeaderFocusTest {

    // The Home at 1920 px: weather after the clock, then the pills at the right edge.
    private static final HeaderFocus.Target WEATHER = new HeaderFocus.Target(420, 720, true);
    private static final HeaderFocus.Target DEBUG = new HeaderFocus.Target(1180, 1360, false);
    private static final HeaderFocus.Target INPUTS = new HeaderFocus.Target(1370, 1520, false);
    private static final HeaderFocus.Target WIFI = new HeaderFocus.Target(1530, 1700, false);
    private static final HeaderFocus.Target SETTINGS = new HeaderFocus.Target(1710, 1864, false);
    private static final List<HeaderFocus.Target> HEADER = Arrays.asList(WEATHER, INPUTS, WIFI, SETTINGS);

    @Test
    public void firstTwoColumnsGoToTheWeather() {
        assertEquals(0, HeaderFocus.pick(0, 250, HEADER));
        assertEquals(0, HeaderFocus.pick(1, 650, HEADER));
    }

    @Test
    public void otherColumnsGoToTheNearestPill() {
        assertEquals(1, HeaderFocus.pick(2, 1050, HEADER));   // left of every pill: the first one
        assertEquals(2, HeaderFocus.pick(3, 1600, HEADER));   // under Wi-Fi
        assertEquals(3, HeaderFocus.pick(3, 1800, HEADER));
    }

    @Test
    public void theDebugPillCountsAsAPill() {
        List<HeaderFocus.Target> h = Arrays.asList(WEATHER, DEBUG, INPUTS, WIFI, SETTINGS);
        assertEquals(1, HeaderFocus.pick(2, 1050, h));
        assertEquals(2, HeaderFocus.pick(3, 1450, h));
    }

    @Test
    public void noWeatherFallsBackToThePills() {
        List<HeaderFocus.Target> h = Arrays.asList(INPUTS, WIFI, SETTINGS);
        assertEquals(0, HeaderFocus.pick(0, 250, h));
    }

    @Test
    public void noPillsFallsBackToTheClockGroup() {
        assertEquals(0, HeaderFocus.pick(3, 1600, Collections.singletonList(WEATHER)));
    }

    @Test
    public void nothingFocusable() {
        assertEquals(-1, HeaderFocus.pick(0, 250, new ArrayList<>()));
    }

    @Test
    public void aNewClockSideItemJoinsByPosition() {
        // A data meter right of the weather: column 1 under it goes there, column 0 still to the weather.
        HeaderFocus.Target meter = new HeaderFocus.Target(760, 940, true);
        List<HeaderFocus.Target> h = Arrays.asList(WEATHER, meter, INPUTS, WIFI, SETTINGS);
        assertEquals(0, HeaderFocus.pick(0, 250, h));
        assertEquals(1, HeaderFocus.pick(1, 800, h));
        assertEquals(1, HeaderFocus.pick(1, 1000, h));
    }

    @Test
    public void tiesGoLeft() {
        HeaderFocus.Target a = new HeaderFocus.Target(100, 200, false);
        HeaderFocus.Target b = new HeaderFocus.Target(300, 400, false);
        assertEquals(1, HeaderFocus.pick(2, 250, Arrays.asList(b, a)));
    }

    // With heights (window pixels): the weather beside the clock spans three lines, the pills sit on the first, the
    // "In riproduzione" card on the second, under the pills and flush with the gear.
    private static final HeaderFocus.Target W = new HeaderFocus.Target(420, 720, 40, 190, true);
    private static final HeaderFocus.Target D = new HeaderFocus.Target(1180, 1360, 40, 100, false);
    private static final HeaderFocus.Target I = new HeaderFocus.Target(1370, 1520, 40, 100, false);
    private static final HeaderFocus.Target F = new HeaderFocus.Target(1530, 1700, 40, 100, false);
    private static final HeaderFocus.Target S = new HeaderFocus.Target(1710, 1864, 40, 100, false);

    @Test
    public void upPrefersTheCardOverThePillAboveIt() {
        // The card is nearer the rows: a tile under both the card and the Wi-Fi pill goes up to the card.
        HeaderFocus.Target card = new HeaderFocus.Target(1300, 1864, 122, 262, false);
        List<HeaderFocus.Target> h = Arrays.asList(W, I, F, S, card);
        assertEquals(4, HeaderFocus.pick(3, 1600, h));
        // A tile left of the card still reaches the nearest pill.
        assertEquals(1, HeaderFocus.pick(2, 1250, Arrays.asList(W, D, I, F, S, card)));
    }

    @Test
    public void rightFromTheWeatherIsTheLeftmostPillWithoutACard() {
        assertEquals(1, HeaderFocus.beside(W, 1, Arrays.asList(W, D, I, F, S)));
        assertEquals(1, HeaderFocus.beside(W, 1, Arrays.asList(W, I, F, S)));
    }

    @Test
    public void rightFromTheWeatherReachesALongCardFirst() {
        HeaderFocus.Target card = new HeaderFocus.Target(1100, 1864, 122, 262, false);
        assertEquals(5, HeaderFocus.beside(W, 1, Arrays.asList(W, D, I, F, S, card)));
    }

    @Test
    public void rightFromTheWeatherSkipsAShortCardFurtherRight() {
        HeaderFocus.Target card = new HeaderFocus.Target(1500, 1864, 122, 262, false);
        assertEquals(1, HeaderFocus.beside(W, 1, Arrays.asList(W, I, F, S, card)));
    }

    @Test
    public void leftAndRightStepAlongThePills() {
        List<HeaderFocus.Target> pills = Arrays.asList(D, I, F, S);
        assertEquals(1, HeaderFocus.beside(F, -1, pills));
        assertEquals(0, HeaderFocus.beside(I, -1, pills));
        assertEquals(-1, HeaderFocus.beside(D, -1, pills));
        assertEquals(3, HeaderFocus.beside(F, 1, pills));
        assertEquals(-1, HeaderFocus.beside(S, 1, pills));
    }

    @Test
    public void besideIgnoresItselfAndWhatIsBehind() {
        assertEquals(-1, HeaderFocus.beside(W, -1, Arrays.asList(W, I, F, S)));
        assertEquals(-1, HeaderFocus.beside(S, 1, Collections.singletonList(S)));
    }
}
