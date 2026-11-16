package io.github.leopierini.rehome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.Map;

/**
 * Hold OK on a tile of the category Home: a small menu under (or over) the tile, in the Inputs dropdown's glass
 * (popup_inputs, item_input), to move the app within its row or to another row, hide it, or open its App info.
 * Each choice closes the menu; CategoryHome saves the change and puts focus back on the same app in its new place,
 * so holding OK again keeps moving it. The menu itself remembers nothing.
 */
final class TileMenu {

    /** What the menu's rows do; CategoryHome carries them out. */
    interface Actions {
        void moveBy(int delta);

        void moveTo(String rowTitle);

        void hide();

        void info();
    }

    /** MainActivity's dropdown window, so its "a popup is open" state (and onPause closing it) covers this menu. */
    interface Popups {
        PopupWindow glassPopup(View panel, int width);
    }

    private final Activity act;
    private final float d;

    TileMenu(Activity act) {
        this.act = act;
        this.d = act.getResources().getDisplayMetrics().density;
    }

    /** {@code otherRows}: the rows it can move to, each row's title (its key) to the title on screen. */
    void show(Popups popups, View card, boolean canLeft, boolean canRight, Map<String, String> otherRows, Actions a) {
        View panel = act.getLayoutInflater().inflate(R.layout.popup_inputs, (ViewGroup) act.findViewById(R.id.root), false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int minW = Math.round(240 * d);
        PopupWindow popup = popups != null ? popups.glassPopup(panel, minW) : plainPopup(panel, minW);
        if (canLeft) addRow(rows, popup, act.getString(R.string.tile_move_left), null, () -> a.moveBy(-1));
        if (canRight) addRow(rows, popup, act.getString(R.string.tile_move_right), null, () -> a.moveBy(+1));
        if (!otherRows.isEmpty()) {
            // The rows to choose from replace this list in the same panel: one level, Back closes it all.
            addRow(rows, null, act.getString(R.string.tile_move_row), "›", () -> {
                rows.removeAllViews();
                for (Map.Entry<String, String> row : otherRows.entrySet()) {
                    String title = row.getKey();
                    addRow(rows, popup, row.getValue(), null, () -> a.moveTo(title));
                }
                rows.getChildAt(0).requestFocus();
            });
        }
        addRow(rows, popup, act.getString(R.string.tile_hide), null, a::hide);
        addRow(rows, popup, act.getString(R.string.tile_info), null, a::info);

        place(popup, panel, card, Math.round((otherRows.size() * 42 + 12) * d));
        rows.getChildAt(0).requestFocus();
    }

    /**
     * A menu of one action under (or over) {@code anchor}, in the same glass and rows: the player's "Remove from
     * player" (category_player).
     */
    void showOne(Popups popups, View anchor, String title, Runnable action) {
        View panel = act.getLayoutInflater().inflate(R.layout.popup_inputs, (ViewGroup) act.findViewById(R.id.root), false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int minW = Math.round(240 * d);
        PopupWindow popup = popups != null ? popups.glassPopup(panel, minW) : plainPopup(panel, minW);
        addRow(rows, popup, title, null, action);
        place(popup, panel, anchor, 0);
        rows.getChildAt(0).requestFocus();
    }

    /** Under the anchor, or over it when the menu (at its tallest) would not fit below; then it fades in. */
    private void place(PopupWindow popup, View panel, View card, int tallestList) {
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int minW = Math.round(240 * d);
        popup.setWidth(Math.max(minW, Math.min(panel.getMeasuredWidth(), Math.round(360 * d))));
        // Under the tile, or over it when the menu would not fit below (the second row sits near the bottom).
        // Tall enough for the longest list it can turn into (the rows to move to).
        int[] at = new int[2];
        card.getLocationInWindow(at);
        int cardH = Math.round(card.getHeight() * card.getScaleY());
        int gap = Math.round(10 * d), height = panel.getMeasuredHeight();
        int tallest = Math.max(height, tallestList);
        View root = act.getWindow().getDecorView();
        boolean below = at[1] + cardH + gap + tallest <= root.getHeight();
        panel.setAlpha(0f);
        panel.setPivotY(below ? 0 : height);
        panel.setScaleY(0.92f);
        panel.setTranslationY((below ? -8 : 8) * d);
        if (below) {
            popup.showAtLocation(root, Gravity.NO_GRAVITY, at[0], at[1] + cardH + gap);
        } else {
            // Grows upward from just over the tile as its list changes length.
            popup.showAtLocation(root, Gravity.LEFT | Gravity.BOTTOM, at[0], root.getHeight() - at[1] + gap);
        }
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    private PopupWindow plainPopup(View panel, int width) {
        PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setAnimationStyle(0);
        return popup;
    }

    /**
     * One row in item_input's style. {@code popup} null: the row keeps the menu open (it opens a sub-list). Only a
     * press that went down on the row counts: the OK that opened the menu is still held, and its UP lands here.
     */
    private void addRow(LinearLayout rows, PopupWindow popup, String title, String status, Runnable action) {
        View row = act.getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView st = row.findViewById(R.id.status);
        if (status != null) st.setText(status);
        else st.setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        Runnable run = () -> {
            if (popup != null) popup.dismiss();
            action.run();
        };
        row.setOnClickListener(v -> run.run());
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                    && code != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) run.run();
            return true;
        });
        rows.addView(row);
    }
}
