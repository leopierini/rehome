package io.github.leopierini.rehome;

/**
 * Up and Down between CategoryHome's rows keep the on-screen column (as Apple TV does), and keep it across a short
 * row: from YouTube (third column) Down onto a row's only tile, then Up, goes back to YouTube, not to Netflix (final
 * review M3). One formula for the places that map a column onto a row (moveToRow, focusHeaderAbove). Pure logic,
 * tested on the JVM.
 */
final class RowColumn {

    private RowColumn() {}

    /** The on-screen column of a row's tile at {@code pos}, the row's first whole tile being at {@code firstVisible}. */
    static int column(int pos, int firstVisible) {
        return Math.max(0, pos - Math.max(0, firstVisible));
    }

    /** The tile of a row of {@code size} tiles in that on-screen column, or its last tile when it is shorter. */
    static int target(int firstVisible, int column, int size) {
        return Math.max(0, Math.min(Math.max(0, firstVisible) + Math.max(0, column), size - 1));
    }

    /**
     * The column the user is moving along. Set by the first vertical move from a tile, kept while each vertical move
     * starts from the tile the last one landed on, forgotten on anything else (Left/Right, a rebuild, Home): a short
     * row that clamped the column does not lose it for the rows after it.
     */
    static final class Wanted {
        private int column = -1, row = -1, pos = -1;

        /** The column for a vertical move from ({@code row}, {@code pos}), whose own on-screen column is {@code here}. */
        int from(int row, int pos, int here) {
            return column >= 0 && row == this.row && pos == this.pos ? column : here;
        }

        /** A vertical move along {@code column} landed on ({@code row}, {@code pos}). */
        void landed(int column, int row, int pos) {
            this.column = column;
            this.row = row;
            this.pos = pos;
        }

        void reset() {
            column = row = pos = -1;
        }
    }
}
