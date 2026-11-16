package io.github.leopierini.rehome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The weather city, picked with the remote: OK on the search field opens the TV's keyboard; the places Open-Meteo
 * knows by that name (CitySearch) come up as rows while typing (after a pause) or at the keyboard's Search key, and
 * OK on one keeps it (CityStore) and closes. "Turn weather off" at the bottom clears it. Opened from Launcher
 * settings and from the Home's weather spot while no city is set; Home rebuilds with the change when it resumes.
 */
public final class CityPickerActivity extends Activity {
    /** A pause in typing this long searches what is there, so the keyboard's Search key is not a must. */
    private static final long TYPING_PAUSE_MS = 700;
    /** Fewer letters find hundreds of places, none of them likely the one meant. */
    private static final int MIN_LETTERS = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** One request at a time, off the main thread; a newer search makes an older answer moot (seq). */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int seq;
    private boolean focusFirstResult;
    /** The query last sent and whether its answer was "No connection" (CityPickerFlow.alreadyAsked). */
    private String lastQuery;
    private boolean lastFailed;

    private EditText query;
    private TextView status;
    private LinearLayout results;
    private ClipScrollView scroll;
    private City current;

    private final Runnable typed = () -> search(false);

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_city_picker);
        query = findViewById(R.id.cityQuery);
        status = findViewById(R.id.cityStatus);
        results = findViewById(R.id.cityResults);
        scroll = findViewById(R.id.cityScroll);
        // A ScrollView makes itself focusable in its constructor (after the XML attributes), and with no rows in it
        // Down from the field landed on it, with no ring. Only real rows take focus.
        scroll.setFocusable(false);
        // A focused place scrolls into view with its glow (settings_choice_bg's 8dp spread), inside the list's clip.
        scroll.setGlow(Math.round(8 * getResources().getDisplayMetrics().density));
        current = CityStore.get(this);

        TextView now = findViewById(R.id.cityNow);
        if (current != null) {
            now.setText(getString(R.string.weather_city_now, current.label()));
            now.setVisibility(View.VISIBLE);
        }
        TextView off = findViewById(R.id.cityOff);
        off.setSelected(CityStore.isOff(this));
        off.setOnClickListener(v -> {
            CityStore.clear(this);
            setResult(RESULT_OK);
            finish();
        });

        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(typed);
                handler.postDelayed(typed, TYPING_PAUSE_MS);
            }
        });
        query.setOnEditorActionListener((v, actionId, event) -> {
            int what = CityPickerFlow.editorAction(actionId, event == null ? -1 : event.getKeyCode(),
                    event == null ? -1 : event.getAction());
            if (what == CityPickerFlow.PASS) return false;
            if (what == CityPickerFlow.SEARCH) {
                handler.removeCallbacks(typed);
                hideKeyboard();
                search(true);
            }
            // Consumed either way: left unhandled, TextView replays it as an ENTER whose release moves focus off
            // the field, onto the list this search just emptied.
            return true;
        });

        // Focused rows glow past their edges (FocusLook): no group may cut them.
        FocusLook.unclipTree(findViewById(android.R.id.content));
        query.requestFocus();
    }

    /** What the field holds, now; {@code fromKeyboard}: the owner is done typing, so the first place gets focus. */
    private void search(boolean fromKeyboard) {
        String q = query.getText().toString().trim();
        if (q.length() >= MIN_LETTERS && CityPickerFlow.alreadyAsked(q, lastQuery, lastFailed)) {
            // Searched at the typing pause: the answer is here (on to it) or on its way (it moves focus then).
            if (fromKeyboard) {
                focusFirstResult = true;
                if (results.getChildCount() > 0) focusAfterAnswer();
            }
            return;
        }
        final int mine = ++seq;
        results.removeAllViews();
        lastQuery = null;
        if (q.length() < MIN_LETTERS) {
            showStatus(0);
            return;
        }
        lastQuery = q;
        lastFailed = false;
        focusFirstResult = fromKeyboard;
        showStatus(R.string.city_searching);
        final String lang = CitySearch.language(getResources().getConfiguration().getLocales().get(0).getLanguage());
        io.execute(() -> {
            List<City> found;
            try {
                found = CitySearch.find(q, lang);
            } catch (IOException | RuntimeException e) {
                found = null;
            }
            final List<City> answer = found;
            handler.post(() -> {
                if (mine != seq || isFinishing() || isDestroyed()) return;
                show(answer);
            });
        });
    }

    /** The places found, or why there are none (null: no answer from the network). */
    private void show(List<City> found) {
        results.removeAllViews();
        lastFailed = found == null;
        if (found == null || found.isEmpty()) {
            showStatus(found == null ? R.string.city_no_connection : R.string.city_no_results);
            focusAfterAnswer();
            return;
        }
        showStatus(0);
        LayoutInflater inflater = getLayoutInflater();
        for (City city : found) {
            TextView row = (TextView) inflater.inflate(R.layout.item_city, results, false);
            row.setText(city.label());
            row.setSelected(city.equals(current));
            row.setOnClickListener(v -> {
                CityStore.set(this, city);
                setResult(RESULT_OK);
                finish();
            });
            results.addView(row);
        }
        FocusLook.unclipTree(results);
        focusAfterAnswer();
    }

    /**
     * After the keyboard's Search key: the first place found, unless the owner has moved on to a row meanwhile. With
     * nothing found, focus stays on (or comes back to) the field, so the next try is one OK away.
     */
    private void focusAfterAnswer() {
        View now = getCurrentFocus();
        boolean onField = now == null || now == query || now == scroll;
        if (CityPickerFlow.focusFirstResult(focusFirstResult, results.getChildCount(), onField)) {
            results.getChildAt(0).requestFocus();
        } else if (focusFirstResult && onField && now != query) {
            query.requestFocus();
        }
        focusFirstResult = false;
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    private void showStatus(int text) {
        if (text == 0) {
            status.setVisibility(View.GONE);
        } else {
            status.setText(text);
            status.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }
}
