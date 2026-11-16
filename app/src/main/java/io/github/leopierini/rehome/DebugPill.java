package io.github.leopierini.rehome;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The red "Debugging on" pill in the top bar while ADB is on (Settings.Global.ADB_ENABLED, no permission needed):
 * network ADB on port 5555 is a plain-text shell for anyone on the hotspot, and the switch is easy to forget. OK
 * opens Developer options, where it is switched off. Read when Home comes to the front, and watched while Home is
 * in front, so it goes away as soon as debugging is switched off, without leaving Home.
 *
 * It sits at the bar's left end (the right end in the mirrored Hebrew bar), away from Settings. MainActivity's
 * pill keys find the bar's ends by what is shown, so it takes part in Left/Right like the others.
 *
 * For screenshots it can be hidden with the rehome_test_hide_debug hook (TestHooks.hideDebug): the header is then
 * exactly as with debugging off. That key is watched too, so setting or deleting it shows at once while Home is in
 * front, and at the next Home resume otherwise.
 */
final class DebugPill {

    /** Told when the pill appears or goes away, so the bar can make room (MainActivity compacts two pills). */
    interface Listener {
        void onDebugPillShown(boolean shown, boolean hadFocus);
    }

    private final Activity act;
    private final TextView pill;
    private final Listener listener;
    private final ContentObserver observer;
    private boolean watching;

    DebugPill(Activity act, ViewGroup bar, boolean mirroredBar, Handler handler, Listener listener) {
        this.act = act;
        this.listener = listener;
        pill = (TextView) LayoutInflater.from(act).inflate(R.layout.top_debug_pill, bar, false);
        bar.addView(pill, mirroredBar ? bar.getChildCount() : 0);
        pill.setOnClickListener(v -> openDeveloperOptions());
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                refresh();
            }
        };
    }

    View view() {
        return pill;
    }

    boolean shown() {
        return pill.getVisibility() == View.VISIBLE;
    }

    /** Only while Home is in front: nothing to watch for behind another app. */
    void onResume() {
        if (!watching) {
            ContentResolver cr = act.getContentResolver();
            cr.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(TestHooks.HIDE_DEBUG), false, observer);
            watching = true;
        }
        refresh();
    }

    void onPause() {
        if (!watching) return;
        act.getContentResolver().unregisterContentObserver(observer);
        watching = false;
    }

    /** Shown while ADB is on, unless the rehome_test_hide_debug hook hides it for a screenshot. */
    static boolean wanted(TestHooks.Source settings) {
        String adb;
        try {
            adb = settings.get(Settings.Global.ADB_ENABLED);
        } catch (RuntimeException e) {
            return false;
        }
        return TestHooks.adbOn(adb) && !TestHooks.hideDebug(settings);
    }

    private void refresh() {
        boolean show = wanted(TestHooks.source(act));
        if (show == shown()) return;
        boolean hadFocus = pill.hasFocus();
        pill.setVisibility(show ? View.VISIBLE : View.GONE);
        listener.onDebugPillShown(show, hadFocus);
    }

    /** Developer options, where USB debugging is; the settings' main screen where a firmware has no such page. */
    private void openDeveloperOptions() {
        PackageManager pm = act.getPackageManager();
        Intent dev = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        Intent target = pm.resolveActivity(dev, PackageManager.MATCH_DEFAULT_ONLY) != null
                ? dev : SystemSettings.intent(act, Settings.ACTION_SETTINGS);
        try {
            act.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            android.widget.Toast.makeText(act, R.string.not_available, android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
