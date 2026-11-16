package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The wind on Home's third weather line: "Wind 14 km/h NE". */
public class WindNoteTest {

    private static final Words EN = TestWords.en(), IT = TestWords.it();

    @Test
    public void compassInEightPoints() {
        assertEquals("N", WindNote.compass(EN, 0));
        assertEquals("N", WindNote.compass(EN, 22.4));
        assertEquals("NE", WindNote.compass(EN, 22.5));
        assertEquals("E", WindNote.compass(EN, 90));
        assertEquals("SE", WindNote.compass(EN, 135));
        assertEquals("S", WindNote.compass(EN, 180));
        assertEquals("SW", WindNote.compass(EN, 225));
        assertEquals("W", WindNote.compass(EN, 270));
        assertEquals("NW", WindNote.compass(EN, 315));
        assertEquals("NW", WindNote.compass(EN, 337.4));
        assertEquals("N", WindNote.compass(EN, 337.5));
        assertEquals("N", WindNote.compass(EN, 360));
        assertEquals("E", WindNote.compass(EN, 450));
        assertEquals("NW", WindNote.compass(EN, -45));
    }

    @Test
    public void noteRoundsAndDropsTheDirectionWhenAlmostStill() {
        assertEquals("Wind 14 km/h NE", WindNote.note(EN, 13.6, 40));
        assertEquals("Wind 2 km/h S", WindNote.note(EN, 2.0, 180));
        assertEquals("Wind 1 km/h", WindNote.note(EN, 1.4, 200));
        assertEquals("Wind 0 km/h", WindNote.note(EN, 0, 0));
        // Unknown speed (a reading saved before the wind was asked): no wind words at all.
        assertEquals("", WindNote.note(EN, Double.NaN, 90));
        // Speed without a direction.
        assertEquals("Wind 9 km/h", WindNote.note(EN, 9, Double.NaN));
    }

    @Test
    public void italianSaysVentoAndOvestForWest() {
        assertEquals("SO", WindNote.compass(IT, 225));
        assertEquals("O", WindNote.compass(IT, 270));
        assertEquals("NO", WindNote.compass(IT, 315));
        assertEquals("Vento 14 km/h NE", WindNote.note(IT, 13.6, 40));
        assertEquals("Vento 1 km/h", WindNote.note(IT, 1.4, 200));
    }
}
