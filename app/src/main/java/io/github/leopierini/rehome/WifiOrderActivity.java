package io.github.leopierini.rehome;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Wi-Fi priority, with the remote: the networks in the order first ("1. Home", "2. Phone A"…), OK on one opens a
 * small menu (Move up, Move down, Remove from order); then "Add to order" with the saved networks not in it, where OK
 * puts one at the end. Focus follows the network that was moved or added. Each change is saved at once
 * (WifiOrderStore), and WifiLink applies it from the next scan.
 *
 * <p>The saved networks come from the companion app (WifiLink.askSaved): without it the page only says to install
 * it. Names are shown as the TV has them; the stored order keeps its own spelling (WifiOrderStore.merge). Ordered
 * networks the TV does not list just now are not shown, and an edit keeps them in the stored order.
 */
public final class WifiOrderActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout rows;
    /** The order as shown (only networks still saved), and the saved networks; null until the companion answers. */
    private List<String> order = new ArrayList<>();
    private List<String> saved;
    /** The row to focus once the menu that changed the list has closed (it hands focus back to a removed row). */
    private View pendingFocus;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wifi_order);
        rows = findViewById(R.id.wifiOrderRows);
        // The ScrollView makes itself focusable; with only lines in it, it would take focus with no ring.
        ((ScrollView) findViewById(R.id.wifiOrderScroll)).setFocusable(false);
        if (!WifiLink.available(this)) {
            line(R.string.wifi_order_no_helper);
            return;
        }
        line(R.string.wifi_order_reading);
        WifiLink.askSaved(this, handler, this::onSaved);
    }

    private void onSaved(List<String> ssids) {
        if (isFinishing() || isDestroyed()) return;
        rows.removeAllViews();
        if (ssids == null) {
            line(R.string.wifi_order_unreadable);
            return;
        }
        if (ssids.isEmpty()) {
            line(R.string.wifi_order_no_saved);
            return;
        }
        saved = ssids;
        order = WifiOrderStore.merge(WifiOrderStore.get(this), saved);
        View first = build(order.isEmpty() ? -1 : 0, 0);
        if (first != null) first.requestFocus();
    }

    /**
     * The two sections again. Returns the row to focus: entry {@code orderIndex} of the order, or with -1 entry
     * {@code addIndex} of "Add to order" (the nearest one that is there).
     */
    private View build(int orderIndex, int addIndex) {
        rows.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        View focus = null;

        heading(R.string.wifi_order_section);
        if (order.isEmpty()) {
            // Under the heading, lined up with the rows' text.
            float d = getResources().getDisplayMetrics().density;
            TextView empty = line(R.string.wifi_order_empty);
            empty.setPadding(Math.round(18 * d), 0, Math.round(18 * d), 0);
            ((LinearLayout.LayoutParams) empty.getLayoutParams()).topMargin = Math.round(10 * d);
        }
        for (int i = 0; i < order.size(); i++) {
            final int at = i;
            TextView row = (TextView) inflater.inflate(R.layout.item_wifi_order, rows, false);
            row.setText(getString(R.string.wifi_order_rank, i + 1, order.get(i)));
            row.setOnClickListener(v -> showMenu(at));
            rows.addView(row);
            if (i == orderIndex) focus = row;
        }

        List<String> rest = WifiOrderStore.notOrdered(order, saved);
        if (!rest.isEmpty()) heading(R.string.wifi_order_add_section);
        int addFocus = orderIndex < 0 ? Math.min(addIndex, rest.size() - 1) : -1;
        for (int i = 0; i < rest.size(); i++) {
            final String ssid = rest.get(i);
            TextView row = (TextView) inflater.inflate(R.layout.item_wifi_order, rows, false);
            row.setText(ssid);
            row.setOnClickListener(v -> {
                save(WifiOrderStore.added(order, ssid));
                // Focus goes with it, to the end of the order.
                View added = build(order.size() - 1, 0);
                if (added != null) added.requestFocus();
            });
            rows.addView(row);
            if (i == addFocus) focus = row;
        }
        // Focused rows glow past their edges (FocusLook): no group may cut them.
        FocusLook.unclipTree(findViewById(android.R.id.content));
        return focus;
    }

    /** Move up / Move down (when there is room) / Remove from order, for entry {@code i}. */
    private void showMenu(int i) {
        pendingFocus = null;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (i > 0) {
            labels.add(getString(R.string.wifi_order_move_up));
            actions.add(() -> move(i, -1));
        }
        if (i < order.size() - 1) {
            labels.add(getString(R.string.wifi_order_move_down));
            actions.add(() -> move(i, +1));
        }
        labels.add(getString(R.string.wifi_order_remove));
        actions.add(() -> {
            save(WifiOrderStore.removed(order, i));
            int next = WifiOrderStore.focusAfterRemove(i, order.size());
            focusLater(build(next, 0));
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(order.get(i))
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .create();
        dialog.setOnDismissListener(d -> {
            View v = pendingFocus;
            pendingFocus = null;
            if (v != null && v.isAttachedToWindow()) v.requestFocus();
        });
        dialog.show();
    }

    private void move(int i, int delta) {
        save(WifiOrderStore.moved(order, i, delta));
        focusLater(build(i + delta, 0));
    }

    /** From a menu choice: focus now, and again once the menu has closed (it hands focus back to a row now gone). */
    private void focusLater(View v) {
        if (v == null) return;
        v.requestFocus();
        pendingFocus = v;
    }

    /**
     * The page's list is only the ordered networks the TV listed as saved; ordered ones it did not list (a partial
     * answer right after a boot) are kept in the stored order, hidden here, in their place (WifiOrderStore.withUnseen).
     */
    private void save(List<String> next) {
        order = next;
        WifiOrderStore.set(this, WifiOrderStore.withUnseen(WifiOrderStore.get(this), next, saved));
        setResult(RESULT_OK);
    }

    private void heading(int text) {
        TextView h = (TextView) getLayoutInflater().inflate(R.layout.item_wifi_order_heading, rows, false);
        h.setText(text);
        rows.addView(h);
    }

    /** A plain line (not focusable), in the page's secondary colour. */
    private TextView line(int text) {
        TextView t = (TextView) getLayoutInflater().inflate(R.layout.item_wifi_order_heading, rows, false);
        t.setText(text);
        t.setTextColor(0xFFACB8C1);
        t.setTextSize(16);
        rows.addView(t);
        return t;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
