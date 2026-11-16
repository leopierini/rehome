package io.github.leopierini.rehome;

import android.content.Context;

import java.util.List;

/**
 * The release build's stand-in for src/debug's test rows (TestHooks "rehome_test_watchnext test"): it writes nothing,
 * and the release manifest has no WRITE_EPG_DATA. The read-only "dump" hook works in both builds.
 */
final class WatchNextTestRows {

    private WatchNextTestRows() {}

    static boolean sync(Context ctx, List<WatchNext.Item> rows, boolean wanted) {
        return false;
    }
}
