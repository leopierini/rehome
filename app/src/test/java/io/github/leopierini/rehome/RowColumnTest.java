package io.github.leopierini.rehome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Plain JVM tests for keeping the column between rows (final review M3, M7). */
public class RowColumnTest {

    @Test
    public void columnIsCountedFromTheFirstWholeTile() {
        assertEquals(2, RowColumn.column(2, 0));
        assertEquals(1, RowColumn.column(5, 4));
        // No whole tile yet (RecyclerView's -1): from the row's start.
        assertEquals(2, RowColumn.column(2, -1));
        assertEquals(0, RowColumn.column(3, 5));
    }

    @Test
    public void targetIsClampedToTheRow() {
        assertEquals(2, RowColumn.target(0, 2, 8));
        assertEquals(6, RowColumn.target(4, 2, 8));
        assertEquals(0, RowColumn.target(0, 2, 1));     // a one-card row
        assertEquals(7, RowColumn.target(6, 3, 8));     // the row's end
        assertEquals(0, RowColumn.target(-1, 0, 3));
    }

    /** The integrator's walk: YouTube (column 2), Down onto the only card, Down to Spotify's row, Up, Up. */
    @Test
    public void aOneCardRowDoesNotLoseTheColumn() {
        RowColumn.Wanted w = new RowColumn.Wanted();
        // Media (row 0): YouTube at 2. Down to the Watch Next row (row 1, one card).
        int col = w.from(0, 2, RowColumn.column(2, 0));
        int land = RowColumn.target(0, col, 1);
        w.landed(col, 1, land);
        assertEquals(0, land);
        // Down again to Musica (row 2, 6 apps): the third column, not the first.
        col = w.from(1, 0, RowColumn.column(0, 0));
        land = RowColumn.target(0, col, 6);
        w.landed(col, 2, land);
        assertEquals(2, land);
        // Up to the card, Up to Media: YouTube again.
        col = w.from(2, 2, RowColumn.column(2, 0));
        land = RowColumn.target(0, col, 1);
        w.landed(col, 1, land);
        col = w.from(1, 0, RowColumn.column(0, 0));
        assertEquals(2, RowColumn.target(0, col, 8));
    }

    @Test
    public void leftOrRightStartsANewColumn() {
        RowColumn.Wanted w = new RowColumn.Wanted();
        w.landed(3, 1, 0);
        w.reset();   // Right/Left on the card
        assertEquals(0, w.from(1, 0, 0));
    }

    @Test
    public void aMoveFromAnotherTileUsesItsOwnColumn() {
        RowColumn.Wanted w = new RowColumn.Wanted();
        w.landed(3, 1, 0);
        // Focus went elsewhere without a vertical move (a rebuild, the header, a click): its own column counts.
        assertEquals(1, w.from(2, 1, 1));
        assertEquals(1, w.from(1, 1, 1));
        // Back on the tile the last move landed on: the wanted column again.
        assertEquals(3, w.from(1, 0, 0));
    }
}
