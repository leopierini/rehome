package io.github.leopierini.rehome;

/**
 * The Home key's promise: the focus on the first app of the first row (Media), never on the player card or a pill,
 * until the owner presses a key. Pure, tested on the JVM; CategoryHome keeps it.
 *
 * <p>Home from an app (YouTube on the TV) once came back with the focus on the player's Resume, the button the app
 * had been opened from: the focus restore posted for the Home key ran, but the focus did not stay on the first tile
 * (a rows layout still pending when it ran, then the window's own focus handling as Home came back, which hands the
 * focus to the first focusable view of a group it re-lays out). No single moment is "after everything" on that path,
 * so the promise is kept instead: from the Home key until the first key press (or Home leaving the screen), wherever
 * the focus lands, or after any layout, it is brought back to the first tile. Bounded, so a tile that cannot take the
 * focus is not chased for ever.
 */
final class HomeFocus {

    /** Moves for one Home press at most. A move that works ends it; the rest only cover layouts still to come. */
    static final int MAX_MOVES = 8;

    private boolean owed;
    private int moves;

    /** The Home key: the focus is owed to the first tile from now on. */
    void onHome() {
        owed = true;
        moves = 0;
    }

    /** A key went down on Home, or Home left the screen: the focus goes where the owner takes it. */
    void release() {
        owed = false;
    }

    boolean owed() {
        return owed;
    }

    /**
     * Whether to move the focus to the first tile now (counts the move).
     *
     * @param noRows      no row to go to (the pills are where focus goes then)
     * @param onFirstTile the focus is already on the first row's first tile
     */
    boolean shouldMove(boolean noRows, boolean onFirstTile) {
        if (!owed || noRows || onFirstTile || moves >= MAX_MOVES) return false;
        moves++;
        return true;
    }
}
