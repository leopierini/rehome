package io.github.leopierini.rehome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Home as titled rows, one per category. Each row is a frosted band of tiles that scrolls
 * sideways; an app's name shows under its tile while it has focus (category_names_on_focus; an icon tile keeps its
 * name). Beside the clock, over the wallpaper, the weather for one
 * place, which opens the weather app; the top-bar pills keep the right edge. MainActivity hands over focus and
 * package changes.
 */
final class CategoryHome {

    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;
    /** A reading older than this is not shown as the weather now. */
    private static final long WEATHER_STALE_MS = 3 * 60 * 60 * 1000L;
    /** After a failed fetch: again in a minute, then twice as long each time, up to a quarter of an hour. */
    private static final long WEATHER_RETRY_MIN_MS = 60 * 1000L;
    private static final long WEATHER_RETRY_MAX_MS = 15 * 60 * 1000L;
    /** Four scans in two minutes is all Android 11 gives an app in front; this leaves one for the Wi-Fi list. */
    private static final long WIFI_NUDGE_EVERY_MS = 45 * 1000L;
    /** Tiles across a band: four in full, and a sliver of the fifth at the right edge shows there is more that way. */
    private static final float VISIBLE_TILES = 4.4f;
    /** Offline: the veil over a tile of an app that needs the internet (still focusable; the focused one in full). */
    private static final int OFFLINE_VEIL = 0xA6000000;
    /** The focused tile's zoom. */
    private static final float FOCUS_ZOOM = 1.07f;
    private static final int NAME_COLOR = 0xE6FFFFFF, NAME_FOCUSED = 0xFFFFFFFF, NAME_OFFLINE = 0x80FFFFFF;

    private static final class Tile {
        String pkg, label;
        ComponentName component;
        Drawable banner, icon;
        boolean leanback;
        /** Its focus glow's colour, from its artwork (FocusLook.tileGlow). */
        int glow;
    }

    private static final class Row {
        String title;
        final List<Tile> tiles = new ArrayList<>();
        View view;
        RecyclerView rv;
        int lastPos;
    }

    private final Activity act;
    private final FrameLayout root;
    private final float d;
    private final boolean animate;
    private final View btnInputs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** findNetApps' binder calls, once per rebuild: a thread that ends when idle. */
    private final ExecutorService netIo = idleThread();
    /**
     * The weather's request, on a thread of its own (that ends when idle): with the hotspot going it sits up to 16 s
     * in timeouts, and on io it held the player's ⏮ ⏯ ⏭ and its refresh behind it for as long.
     */
    private final ExecutorService weatherIo = idleThread();

    private final List<Row> rows = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();
    private final LinkedHashMap<String, List<String>> categories = new LinkedHashMap<>();
    private final String otherTitle;
    /** The UI's words for the pure text helpers (RainNote, WindNote, RowTitles). */
    private final Words words;

    private final int screenW, screenH, side, pad, gap, tileW, tileH, headH, nameH, bandMaxW, rowsTop, rowH, fadeLen;

    private ScrollView scroll;
    private LinearLayout rowsBox;

    private LinearLayout weather;
    private TextView wIcon, wTemp, wPlace, wMore;
    /** The place, on the temperature's line after it: wPlace then holds the sky alone. */
    private TextView wCity;
    private final String weatherApp, weatherCity;
    private final double weatherLat, weatherLon;
    /**
     * No city picked, none in config.xml, and the weather not turned off: the weather's spot says "Set your city"
     * and OK on it opens the picker (CityPickerActivity). Nothing is fetched.
     */
    private final boolean askCity;
    /** The data meter: it samples and reads here; its line is in the Wi-Fi dropdown (dataLine). */
    private DataMeter meter;

    private int focusedRow = 0;
    /** The column Up/Down keep across a short row (RowColumn). */
    private final RowColumn.Wanted wanted = new RowColumn.Wanted();
    /** Which apps the rows show, as PackageManager listed them: an unchanged list is not built again. */
    private String lastSignature;
    /** The focused app when the rows were let go under memory pressure. */
    private String keptPkg;
    private final Typeface nameRegular, nameMedium;

    /** The weather app was opened from the widget: Back from it returns to the widget, not to a tile. */
    private boolean backToWeather;
    private boolean resumed;
    private volatile boolean destroyed;
    /** The Home shows itself offline (MainActivity's OfflineGate): the hint in the weather, net apps dimmed. */
    private boolean offline;
    /** Apps on the rows that are no use without the internet (NeedsInternet), found off the main thread. */
    private Set<String> netApps = new HashSet<>();
    private final AtomicBoolean fetching = new AtomicBoolean();

    /** category_names_on_focus: a banner tile's name shows only while it has focus (an icon tile's always). */
    private final boolean namesOnFocus;
    /** The owner's own order and hidden apps (RowEdits), over the rows config.xml and AutoCategory give. */
    private RowEdits edits;
    /** The last reload's apps, and its rows before the owner's edits: an edit re-arranges these without reloading. */
    private Map<String, Tile> tiles = new LinkedHashMap<>();
    private LinkedHashMap<String, List<String>> defaults = new LinkedHashMap<>();
    private TileMenu.Popups popups;
    /** The focused tile's glow (and the one fading out), drawn behind the rows (FocusLook.Layer). */
    private final FocusLook.Layer glowLayer = new FocusLook.Layer();
    private final List<TileHolder> lit = new ArrayList<>();
    private final ViewTreeObserver.OnPreDrawListener placeGlows = () -> {
        placeGlows();
        return true;
    };
    /** home_number_keys and home_color_keys (HomeKeys); the key taken on its way down, whose UP is eaten too. */
    private final boolean numberKeys;
    private final Map<String, String> colorKeys;
    private final HomeKeys.Gate homeKeys = new HomeKeys.Gate();
    /** Keys pressed on Home (counted by onHomeKey): a focus restore posted before one is stale. */
    private int keyDowns;
    /** The Home key's focus on the first tile, kept until a key is pressed (TV capture: Home from YouTube). */
    private final HomeFocus homeFocus = new HomeFocus();
    /** After any layout and any focus move while HOME's focus is owed: put it back if it went elsewhere. */
    private final ViewTreeObserver.OnGlobalLayoutListener homeLayout = () -> {
        if (homeFocus.owed()) keepHomeFocus();
    };
    private final ViewTreeObserver.OnGlobalFocusChangeListener homeFocusMoved = (was, now) -> {
        // Posted: never move the focus from inside a focus change.
        if (homeFocus.owed()) handler.post(this::keepHomeFocus);
    };
    private long weatherRetryMs = WEATHER_RETRY_MIN_MS;
    /** The reader of the Watch Next rows behind the player (null when the player is off). */
    private WatchNextRow watchNext;
    /** The Watch Next items as last saved (WatchNext.encode), for the player's first frame at the next start. */
    private static final String WATCH_CARDS = "watch_cards";
    private String savedCards = "";
    /**
     * category_watch_next and category_player: the player above "Media" (PlayerCard), fed by
     * the player's memory (PlayerMemory, recorded by MediaListener) and the Watch Next rows (WatchNextRow).
     */
    private final boolean playerOn;
    private PlayerCard player;
    private PlayerMemory memory;
    private int playerH;
    private long playerGlowKey;
    /** The last Watch Next read (with its posters), for the player. Main thread. */
    private List<WatchNextRow.Entry> watchEntries = new ArrayList<>();
    /**
     * The player's pictures, by key, art and title: io thread only. A refresh decodes only what is new. The six items
     * the player shows and two more: a song's cover is up to 0.9 MiB, and with music playing every new track left
     * one behind (it kept sixteen before a memory pass).
     */
    private final LinkedHashMap<String, PlayerCard.Art> playerArt = new LinkedHashMap<String, PlayerCard.Art>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PlayerCard.Art> e) {
            return size() > PLAYER_ART_KEPT;
        }
    };
    private static final int PLAYER_ART_KEPT = 8;
    /**
     * The app line's icons by package (null: the app has none), io thread only: a new track of the same app does not
     * load its icon and look for a wordmark in it again. Emptied with the rows' rebuild (an app updated) and release.
     */
    private final Map<String, Drawable> playerIcons = new java.util.HashMap<>();
    private final Map<String, String> appLabels = new java.util.HashMap<>();
    /** The memory's change notices, coalesced: a playing session can send several a second. */
    private final Runnable playerRefresh = this::refreshPlayer;
    /** A minute without a key on Home: the player's bar stops, so the Philips screensaver can start (OLED). */
    private static final long PLAYER_IDLE_MS = 60_000L;
    private final Runnable playerIdle = () -> {
        if (player != null) player.setIdle(true);
    };
    /** The header element focused last, for Up from the player; the Wi-Fi pill before any. */
    private View lastHeader;
    private final Runnable playerChanged = () -> {
        handler.removeCallbacks(playerRefresh);
        handler.postDelayed(playerRefresh, 250);
    };

    CategoryHome(Activity act, FrameLayout root, Set<String> hiddenApps, boolean animate) {
        this.act = act;
        this.root = root;
        this.animate = animate;
        Resources res = act.getResources();
        words = Words.of(res);
        DisplayMetrics dm = res.getDisplayMetrics();
        d = dm.density;
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        btnInputs = act.findViewById(R.id.btnInputs);

        for (String entry : res.getStringArray(R.array.category_rows_list)) {
            int bar = entry.indexOf('|');
            if (bar <= 0) continue;
            List<String> pkgs = new ArrayList<>();
            for (String p : entry.substring(bar + 1).split(",")) if (!p.trim().isEmpty()) pkgs.add(p.trim());
            categories.put(entry.substring(0, bar).trim(), pkgs);
        }
        // config.xml's hidden_apps and a theme's, as MainActivity read them.
        hidden.addAll(hiddenApps != null ? hiddenApps : Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        nameRegular = font(R.string.font_regular);
        nameMedium = font(R.string.font_medium);
        otherTitle = res.getString(R.string.category_other_title);
        weatherApp = res.getString(R.string.weather_app);
        // The city picked in Launcher settings (CityStore), else config.xml's; a change there rebuilds Home.
        City city = CityStore.home(act);
        weatherCity = city != null ? city.name() : "";
        weatherLat = city != null ? city.lat() : Double.NaN;
        weatherLon = city != null ? city.lon() : Double.NaN;
        askCity = city == null && !CityStore.isOff(act);
        if (!weatherApp.isEmpty()) hidden.add(weatherApp);
        hidden.add(act.getPackageName());
        namesOnFocus = res.getBoolean(R.bool.category_names_on_focus);
        numberKeys = res.getBoolean(R.bool.home_number_keys);
        colorKeys = HomeKeys.parseColors(res.getStringArray(R.array.home_color_keys));
        edits = RowEdits.load(RowEdits.file(act));

        // The header text, the row titles and the tiles share one left edge (side); each band starts its padding
        // further out, so the band's frame sits just outside the tiles.
        side = dp(56);
        pad = dp(8);
        gap = dp(16);
        headH = dp(30);
        nameH = dp(30);
        bandMaxW = screenW - 2 * (side - pad);
        tileW = Math.round((bandMaxW - 2 * pad - (VISIBLE_TILES - 1) * gap) / VISIBLE_TILES);
        tileH = Math.round(tileW * TileArt.DESIGN_H_DP / TileArt.DESIGN_W_DP);
        fadeLen = dp(14);
        // Title, band, the names under it and the space before the next title.
        rowH = headH + tileH + 2 * pad + nameH + dp(10);
        // Two whole rows fit under the header, the second one's names just above the bottom edge.
        rowsTop = screenH - 2 * rowH - fadeLen + dp(4);

        addVignette();
        styleHeader();
        buildRows();
        if (city != null || askCity) buildWeather();
        alignHeader();
        playerOn = res.getBoolean(R.bool.category_watch_next) && res.getBoolean(R.bool.category_player);
        if (playerOn) {
            watchNext = new WatchNextRow(act, tileW, tileH, this::blockedApps, this::onWatchNext);
            // The items the last start showed, without their posters, so the first frame already has them (final
            // review M2): the first read corrects them in place. Kept with the weather, whose file this start reads
            // anyway.
            if (act.checkSelfPermission(WatchNextRow.PERMISSION) == PackageManager.PERMISSION_GRANTED) {
                savedCards = weatherPrefs().getString(WATCH_CARDS, "");
                watchEntries = watchNext.seed(WatchNext.decode(savedCards));
            }
            buildPlayer();
        }
        meter = new DataMeter(act);
    }

    private static ExecutorService idleThread() {
        return new java.util.concurrent.ThreadPoolExecutor(0, 1, 10, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>());
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    /** The launcher's own typeface (the header's), not the system one: font_* names a bundled font file. */
    private Typeface font(int name) {
        try {
            return UiFont.get(act, name);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    private TextView text(float sp, int color, int font) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(font));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    // ---- header --------------------------------------------------------------------------------------

    /**
     * Darkens the photo's edges and corners, and its lower part most, so the header and the rows read over any
     * wallpaper. One small bitmap drawn once and stretched over the screen, like MainActivity's scrim: no shader runs
     * per frame. It sits right above the wallpaper, under everything else.
     */
    private void addVignette() {
        int w = 160, h = 90;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint();
        // Corners and edges: an ellipse a little wider than the screen, clear in the middle.
        Matrix oval = new Matrix();
        oval.setScale(1f, (float) h / w, w / 2f, h / 2f);
        RadialGradient edges = new RadialGradient(w / 2f, h / 2f, w * 0.62f,
                new int[]{0x00000000, 0x14000000, 0xA6000000, 0xF2000000}, new float[]{0f, 0.35f, 0.75f, 1f},
                Shader.TileMode.CLAMP);
        edges.setLocalMatrix(oval);
        paint.setShader(edges);
        canvas.drawRect(0, 0, w, h, paint);
        // Bottom: deeper, under the rows; a lighter veil along the top, under the header.
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0x8C000000, 0x26000000, 0x26000000, 0x8C000000, 0xD9000000},
                new float[]{0f, 0.3f, 0.45f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        BitmapDrawable shade = new BitmapDrawable(act.getResources(), bmp);
        shade.setGravity(Gravity.FILL);
        View vignette = new View(act);
        vignette.setBackground(shade);
        vignette.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(vignette, root.indexOfChild(act.findViewById(R.id.homeColumn)), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * A small greeting in sentence case, the clock and the date under it in plain lower case, over the photo. Light
     * shadows only: the vignette does most of the work.
     */
    private void styleHeader() {
        TextView greeting = act.findViewById(R.id.greeting);
        greeting.setAllCaps(false);
        greeting.setLetterSpacing(0.08f);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        greeting.setTextColor(0xD9FFFFFF);
        // Under the clock's left edge like the date and the row titles, not 4dp in.
        if (greeting.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) greeting.getLayoutParams()).setMarginStart(0);
        }
        greeting.setShadowLayer(dp(4), 0, dp(1), 0x59000000);
        TextView clock = act.findViewById(R.id.clock);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        clock.setLetterSpacing(-0.02f);
        clock.setShadowLayer(dp(6), 0, dp(1), 0x4D000000);
        TextView date = act.findViewById(R.id.date);
        date.setAllCaps(false);
        date.setLetterSpacing(0.01f);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        date.setTextColor(0xE6FFFFFF);
        date.setShadowLayer(dp(4), 0, dp(1), 0x59000000);
    }

    /**
     * One block across the header: the clock and the date, a thin line as tall as the two, then the weather in the
     * same height, its temperature's digits level with the clock's and its last line on the date's baseline. The
     * top-bar pills keep the right edge, centred on the clock's digits. All follow the clock, whose width changes
     * with the time. Up from the first row reaches the pills, Up again (or Right from the weather back) the
     * weather. The pills change only their place, not their order or behaviour.
     */
    private void alignHeader() {
        View bar = act.findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) bar.getLayoutParams();
        lp.removeRule(RelativeLayout.ALIGN_BOTTOM);
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.topMargin = 0;
        lp.bottomMargin = 0;
        bar.setLayoutParams(lp);
        TextView clock = act.findViewById(R.id.clock);
        TextView date = act.findViewById(R.id.date);
        View divider = weather == null ? null : new View(act);
        if (divider != null) {
            divider.setBackgroundColor(0x40FFFFFF);
            root.addView(divider, root.indexOfChild(weather), new FrameLayout.LayoutParams(
                    Math.max(1, dp(1)), 0, Gravity.TOP | Gravity.START));
        }
        View.OnLayoutChangeListener align = (v, l, t, r, b, ol, ot, or, ob) -> {
            float line = digitsCentre(clock);
            bar.setTranslationY(bar.getTranslationY() + line - (windowY(bar) + bar.getHeight() / 2f));
            if (weather == null) return;
            float top = inkTop(clock), base = baseline(date);
            int[] c = new int[2];
            clock.getLocationInWindow(c);
            float lineX = c[0] + clock.getWidth() + dp(32);
            divider.setTranslationX(lineX);
            divider.setTranslationY(top);
            weather.setTranslationX(lineX + dp(32) - weather.getPaddingLeft());
            weather.setTranslationY(weather.getTranslationY() + top - inkTop(wTemp));
            // The last line on the date's baseline, the middle one with even gaps above and below it.
            float place = (baseline(wTemp) + base + capHeight(wPlace) - capHeight(wMore)) / 2f;
            int nudgePlace = Math.round(place - baseline(wPlace));
            int nudgeMore = Math.round(base - baseline(wMore)) - nudgePlace;
            int height = Math.round(base - top);
            // A pixel either way is rounding: settle rather than chase it.
            if (Math.abs(nudgePlace) <= 1 && Math.abs(nudgeMore) <= 1
                    && Math.abs(divider.getLayoutParams().height - height) <= 1) return;
            // Not inside this layout pass: margins and sizes change on the next frame, then this runs once more.
            weather.post(() -> {
                ((ViewGroup.MarginLayoutParams) wPlace.getLayoutParams()).topMargin += nudgePlace;
                ((ViewGroup.MarginLayoutParams) wMore.getLayoutParams()).topMargin += nudgeMore;
                divider.getLayoutParams().height = height;
                wPlace.requestLayout();
                divider.requestLayout();
            });
        };
        clock.addOnLayoutChangeListener(align);
        bar.addOnLayoutChangeListener(align);
        if (weather != null) weather.addOnLayoutChangeListener(align);
        if (weather != null) {
            // Not inside the layout pass (a width limit set now would stop at the weather, mid-layout). The clock too:
            // align moves the weather by a translation, which no layout of the weather reports, and a 12-hour clock
            // grows a digit at 10:00 and loses it at 1:00 (about 33dp). Posted, so it runs after align has moved it.
            View.OnLayoutChangeListener clear = (v, l, t, r, b, ol, ot, or, ob) -> weather.post(this::keepWeatherClear);
            clock.addOnLayoutChangeListener(clear);
            bar.addOnLayoutChangeListener(clear);
            weather.addOnLayoutChangeListener(clear);
        }
    }

    /**
     * The weather's box (its focus ring and glass) stays a gap left of the first top-bar pill: with "Debugging on"
     * shown and a rain note on the last line, the focused box reached over the pill, and with a labelled Settings
     * pill a long place name ran under the pills. Every line is capped there (HeaderFit): the place name ellipsizes
     * after the temperature (or is left out when only an ellipsis would show), the sky ellipsizes, and the last line
     * drops the wind whole before its rain is cut. With room to spare nothing changes.
     */
    private void keepWeatherClear() {
        if (weather == null || destroyed) return;
        ViewGroup bar = act.findViewById(R.id.topButtons);
        int pillLeft = Integer.MAX_VALUE;
        int[] at = new int[2];
        for (int i = 0; bar != null && i < bar.getChildCount(); i++) {
            View p = bar.getChildAt(i);
            if (p.getVisibility() != View.VISIBLE || p.getWidth() == 0) continue;
            p.getLocationInWindow(at);
            pillLeft = Math.min(pillLeft, at[0]);
        }
        if (pillLeft == Integer.MAX_VALUE) return;
        weather.getLocationInWindow(at);
        int max = HeaderFit.textRoom(at[0], pillLeft, dp(12), weather.getPaddingLeft(), weather.getPaddingRight());
        // Even when little room is left: an ellipsis is better than the box over a pill (final review M4).
        if (max <= 0) return;
        for (TextView t : new TextView[]{wPlace, wMore}) {
            if (t != null && t.getMaxWidth() != max) t.setMaxWidth(max);
        }
        // "Set your city" is in the temperature's 28sp line, alone on it (showSetCity): capped the same way, so the
        // longer Italian "Imposta la tua città" ellipsizes before a pill. A temperature is never capped.
        if (askCity && wTemp != null && wTemp.getMaxWidth() != max) wTemp.setMaxWidth(max);
        if (!askCity) fitCity(max);
        if (moreRoom != max) {
            moreRoom = max;
            fitMore();
        }
    }

    /** The place name after the temperature, in what the line leaves of the weather's room (keepWeatherClear). */
    private void fitCity(int max) {
        if (wCity == null || !(wCity.getParent() instanceof ViewGroup)) return;
        ViewGroup line = (ViewGroup) wCity.getParent();
        int before = 0;
        for (int i = 0; i < line.getChildCount(); i++) {
            View c = line.getChildAt(i);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) c.getLayoutParams();
            if (c == wCity) {
                before += lp.leftMargin;
                break;
            }
            if (c.getVisibility() != View.GONE) before += lp.leftMargin + c.getWidth() + lp.rightMargin;
        }
        int room = HeaderFit.cityRoom(max, before);
        CharSequence name = wCity.getText();
        int full = name == null ? 0 : (int) Math.ceil(wCity.getPaint().measureText(name, 0, name.length()));
        boolean shown = HeaderFit.cityShown(room, full, dp(48));
        if (shown && wCity.getMaxWidth() != room) wCity.setMaxWidth(room);
        int visibility = shown ? View.VISIBLE : View.GONE;
        if (wCity.getVisibility() != visibility) wCity.setVisibility(visibility);
    }

    private static int windowY(View v) {
        int[] at = new int[2];
        v.getLocationInWindow(at);
        return at[1];
    }

    /** Where a line of digits looks centred, in window coordinates: halfway up a figure from the baseline. */
    private static float digitsCentre(TextView t) {
        return (inkTop(t) + baseline(t)) / 2f;
    }

    /** The top of a figure ("0") in this text, in window coordinates. */
    private static float inkTop(TextView t) {
        return baseline(t) - capHeight(t);
    }

    private static float baseline(TextView t) {
        return windowY(t) + t.getBaseline();
    }

    private static float capHeight(TextView t) {
        Rect figure = new Rect();
        t.getPaint().getTextBounds("0", 0, 1, figure);
        return -figure.top;
    }

    // ---- rows ----------------------------------------------------------------------------------------

    private void buildRows() {
        // Focus never scrolls the rows by itself: onTileFocused puts the focused row at the top, smoothly (and, Task 7,
        // faster than ScrollView's own move: RowsScrollView).
        scroll = new RowsScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setFocusable(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(fadeLen);
        rowsBox = new LinearLayout(act);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setClipChildren(false);
        rowsBox.setClipToPadding(false);
        // The top padding keeps the focused row's title clear of the fading edge. None at the bottom: the last row
        // stops in the second slot, under the one before it, with no empty screen below.
        rowsBox.setPadding(0, fadeLen, 0, 0);
        // The focused tile's glow, under the bands and the other tiles, never cut at a band's edge.
        rowsBox.setBackground(glowLayer);
        rowsBox.getViewTreeObserver().addOnPreDrawListener(placeGlows);
        scroll.addView(rowsBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, screenH - rowsTop);
        lp.gravity = Gravity.TOP;
        lp.topMargin = rowsTop;
        // Right over the header column, under the weather.
        root.addView(scroll, root.indexOfChild(act.findViewById(R.id.homeColumn)) + 1, lp);
        root.getViewTreeObserver().addOnGlobalLayoutListener(homeLayout);
        root.getViewTreeObserver().addOnGlobalFocusChangeListener(homeFocusMoved);
    }

    /** Rebuilds every row from the TV apps (and the launcher-only apps a category names). */
    void reload(List<ResolveInfo> leanback) {
        PackageManager pm = act.getPackageManager();
        final List<ResolveInfo> launcher = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        Set<String> named = new HashSet<>();
        for (List<String> pkgs : categories.values()) named.addAll(pkgs);
        // Most package broadcasts (an update of a system component, a toggled component) change nothing shown
        // here: the same apps from the same APKs keep the rows, their artwork and the focus as they are.
        String signature = signature(leanback, launcher, named);
        if (signature.equals(lastSignature) && !rows.isEmpty()) return;
        lastSignature = signature;
        String keepPkg = focusedPackage();
        if (keepPkg == null) keepPkg = keptPkg;
        keptPkg = null;
        // An updated app may bring a new icon.
        if (player != null) io.execute(playerIcons::clear);
        Map<String, ResolveInfo> wanted = new LinkedHashMap<>();
        for (ResolveInfo ri : leanback) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            String pkg = ri.activityInfo.packageName;
            if (hidden.contains(pkg) || wanted.containsKey(pkg)) continue;
            wanted.put(pkg, ri);
        }
        int leanbackCount = wanted.size();
        // Apps with only a phone launcher entry (CHILI): shown when a category asks for them by name.
        for (ResolveInfo ri : launcher) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (named.contains(pkg) && !hidden.contains(pkg) && !wanted.containsKey(pkg)) {
                wanted.put(pkg, ri);
            }
        }
        // Each app's label, banner and glow colour on four threads (ParallelOrdered: ~185 ms of a cold start on one),
        // into the same order as before.
        List<ResolveInfo> infos = new ArrayList<>(wanted.values());
        List<Integer> index = new ArrayList<>();
        for (int i = 0; i < infos.size(); i++) index.add(i);
        List<Tile> built = ParallelOrdered.map(index, i -> tile(pm, infos.get(i), i < leanbackCount), 4);
        Map<String, Tile> byPkg = new LinkedHashMap<>();
        for (Tile t : built) {
            // An icon tile's glow colour here, one at a time: apps without an icon of their own share the framework's
            // default one (one VectorDrawable state and cache), which two threads must not draw at once (review M4).
            if (t.banner == null) t.glow = FocusLook.tileGlow(FocusLook.averageColor(t.icon));
            byPkg.put(t.pkg, t);
        }
        LinkedHashMap<String, Row> byTitle = new LinkedHashMap<>();
        Set<String> placed = new HashSet<>();
        for (Map.Entry<String, List<String>> cat : categories.entrySet()) {
            Row row = new Row();
            row.title = cat.getKey();
            byTitle.put(row.title, row);
            for (String pkg : cat.getValue()) {
                Tile t = byPkg.get(pkg);
                if (t != null && placed.add(pkg)) row.tiles.add(t);
            }
        }
        // Apps no row names (new installs) go to the row that fits them, after the named ones.
        List<Tile> rest = new ArrayList<>();
        for (Tile t : byPkg.values()) if (!placed.contains(t.pkg)) rest.add(t);
        rest.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        Row other = new Row();
        other.title = otherTitle;
        for (Tile t : rest) {
            String title = classify(pm, t);
            Row row = title != null ? byTitle.get(title) : null;
            (row != null ? row : other).tiles.add(t);
        }
        // Every row, empty ones too: the owner may have moved apps into a row config.xml leaves empty.
        defaults = new LinkedHashMap<>();
        for (Row r : byTitle.values()) defaults.put(r.title, packages(r));
        defaults.put(other.title, packages(other));
        tiles = byPkg;
        showRows(keepPkg);
        findNetApps();
        // An app disabled or removed: its items leave the player too.
        if (watchNext != null && resumed) watchNext.refresh();
    }

    private static List<String> packages(Row r) {
        List<String> pkgs = new ArrayList<>();
        for (Tile t : r.tiles) pkgs.add(t.pkg);
        return pkgs;
    }

    /** The rows from the last reload's apps with the owner's edits over them, focus kept on keepPkg's tile. */
    private void showRows(String keepPkg) {
        // Each row keeps its place (remembered tile and scroll) across the rebuild: an edit in one row does not send
        // every other row back to its start.
        Map<String, Integer> lastPos = new java.util.HashMap<>();
        Map<String, android.os.Parcelable> scrolled = new java.util.HashMap<>();
        for (Row r : rows) {
            lastPos.put(r.title, r.lastPos);
            if (r.rv != null && r.rv.getLayoutManager() != null) {
                scrolled.put(r.title, r.rv.getLayoutManager().onSaveInstanceState());
            }
        }
        rows.clear();
        wanted.reset();
        for (Map.Entry<String, List<String>> e : RowEdits.arrange(defaults, edits).entrySet()) {
            Row r = new Row();
            r.title = e.getKey();
            for (String pkg : e.getValue()) r.tiles.add(tiles.get(pkg));
            rows.add(r);
        }
        lit.clear();
        glowLayer.clear();
        prepareTileGlow();
        rowsBox.removeAllViews();
        if (player != null) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(bandMaxW, playerH);
            plp.leftMargin = side - pad;
            rowsBox.addView(player, plp);
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Integer was = lastPos.get(r.title);
            if (was != null) r.lastPos = Math.max(0, Math.min(was, r.tiles.size() - 1));
            rowsBox.addView(rowView(r, i));
            android.os.Parcelable state = scrolled.get(r.title);
            if (state != null && r.rv.getLayoutManager() != null) r.rv.getLayoutManager().onRestoreInstanceState(state);
        }
        // Keep focus on the same app across a rebuild, or on the same row.
        focusedRow = Math.min(focusedRow, Math.max(0, rows.size() - 1));
        if (keepPkg != null) {
            for (int i = 0; i < rows.size(); i++) {
                for (int j = 0; j < rows.get(i).tiles.size(); j++) {
                    if (rows.get(i).tiles.get(j).pkg.equals(keepPkg)) {
                        focusedRow = i;
                        rows.get(i).lastPos = j;
                    }
                }
            }
        }
    }

    /** Every app the rows can show with the APK it comes from (a new path after each update), and the language. */
    private String signature(List<ResolveInfo> leanback, List<ResolveInfo> launcher, Set<String> named) {
        StringBuilder sb = new StringBuilder(Locale.getDefault().toLanguageTag());
        for (ResolveInfo ri : leanback) {
            if (ri.activityInfo == null || !ri.activityInfo.exported || hidden.contains(ri.activityInfo.packageName)) continue;
            sign(sb.append("\nT "), ri);
        }
        for (ResolveInfo ri : launcher) {
            if (ri.activityInfo == null || !named.contains(ri.activityInfo.packageName)
                    || hidden.contains(ri.activityInfo.packageName)) continue;
            sign(sb.append("\nL "), ri);
        }
        return sb.toString();
    }

    private static void sign(StringBuilder sb, ResolveInfo ri) {
        sb.append(ri.activityInfo.packageName).append('/').append(ri.activityInfo.name);
        if (ri.activityInfo.applicationInfo != null) sb.append(' ').append(ri.activityInfo.applicationInfo.sourceDir);
    }

    /**
     * Which apps on the rows need the internet (NeedsInternet), for the offline Home. A permission lookup per app and
     * the TV input list are binder calls, so they run on the background thread once per rebuild; the tiles are
     * dimmed (when offline) as soon as the answer is back.
     */
    private void findNetApps() {
        if (destroyed) return;
        String tools = AutoCategory.toolsRow(new ArrayList<>(categories.keySet()));
        // From the rows before the owner's edits: a move never changes what dims (NeedsInternet.inToolsRow), so an
        // edit needs no new pass.
        final Map<String, Boolean> inTools = NeedsInternet.inToolsRow(defaults, tools);
        final PackageManager pm = act.getPackageManager();
        final android.media.tv.TvInputManager tim =
                (android.media.tv.TvInputManager) act.getSystemService(Context.TV_INPUT_SERVICE);
        // Not on io: a weather fetch there can sit 16 s in timeouts, just when the network is going (offline).
        netIo.execute(() -> {
            Set<String> inputs = new HashSet<>();
            try {
                if (tim != null) {
                    for (android.media.tv.TvInputInfo info : tim.getTvInputList()) {
                        if (info.getServiceInfo() == null) continue;
                        if (!NeedsInternet.inputPlaysOffline(info.isPassthroughInput(), info.getParentId() != null)) continue;
                        inputs.add(info.getServiceInfo().packageName);
                    }
                }
            } catch (RuntimeException ignored) {
            }
            Set<String> need = new HashSet<>();
            for (Map.Entry<String, Boolean> e : inTools.entrySet()) {
                boolean internet = false;
                try {
                    internet = NeedsInternet.asksNetwork(
                            pm.getPackageInfo(e.getKey(), PackageManager.GET_PERMISSIONS).requestedPermissions);
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                }
                if (NeedsInternet.needs(e.getKey(), internet, e.getValue(), inputs.contains(e.getKey()))) {
                    need.add(e.getKey());
                }
            }
            if (!destroyed) handler.post(() -> {
                netApps = need;
                if (player != null) player.setOffline(offline, need);
                if (offline) refreshTiles();
            });
        });
    }

    /** The offline Home (MainActivity, after OfflineGate): the hint instead of the weather, and net apps dimmed. */
    void setOffline(boolean off) {
        if (offline == off) return;
        offline = off;
        // "Set your city" stays: it is not a reading, and the pills already say the network is gone.
        if (weather != null && !askCity) {
            if (off) {
                showOfflineHint();
            } else {
                // Back online: the saved reading while it is fresh, until the one onOnline asks for arrives.
                showNoWeather();
                showCachedWeather();
            }
        }
        refreshTiles();
        if (watchNext != null) watchNext.setOffline(off);
        if (player != null) player.setOffline(off, netApps);
    }

    /**
     * Every tile drawn again for the offline state. Through the adapter, not the children: a RecyclerView keeps tiles
     * scrolled just off screen bound but detached (its view cache, prefetch), and puts them back without binding
     * them, so they kept the old veil. The payload rebinds only the focus/offline look (NET_STATE).
     */
    private void refreshTiles() {
        for (Row r : rows) {
            if (r.rv == null || r.rv.getAdapter() == null) continue;
            r.rv.getAdapter().notifyItemRangeChanged(0, r.tiles.size(), NET_STATE);
        }
    }

    /** refreshTiles' payload: the network state changed, nothing else about the tile. */
    private static final Object NET_STATE = new Object();

    /** Whether focus is on a tile (not on the weather or a top-bar pill). */
    boolean hasFocus() {
        return scroll.hasFocus();
    }

    /** Memory pressure with Home in the background: let go of the tiles and their artwork; reload() builds them again. */
    void release() {
        keptPkg = focusedPackage();
        rows.clear();
        rowsBox.removeAllViews();
        lit.clear();
        glowLayer.clear();
        FocusGlow.clearMasks();
        tileGlowKey = 0;
        tiles = new LinkedHashMap<>();
        lastSignature = null;
        // The player's copy of the last read would hold every poster through the memory pressure: keep the items only.
        if (watchNext != null) watchEntries = watchNext.release();
        if (player != null) {
            player.dropArt();
            io.execute(() -> {
                playerArt.clear();
                playerIcons.clear();
            });
        }
    }

    /** The Home key: every row back at its start, and focus (when it comes) on the first tile of the first row. */
    void resetToFirst() {
        focusedRow = 0;
        keptPkg = null;
        backToWeather = false;
        wanted.reset();
        for (Row r : rows) {
            r.lastPos = 0;
            r.rv.scrollToPosition(0);
        }
        scroll.scrollTo(0, 0);
        // The focus is owed to the first tile until a key (HomeFocus); taken off the player now, before the refresh
        // that follows Home's return sees the card focused and keeps its item.
        homeFocus.onHome();
        keepHomeFocus();
    }

    /** HOME's focus, kept until the first key (HomeFocus): wherever the focus is, back to the first row's first tile. */
    private void keepHomeFocus() {
        if (destroyed || !homeFocus.owed()) return;
        View focused = root.findFocus();
        if (!homeFocus.shouldMove(rows.isEmpty(), onFirstTile(focused))) return;
        focusTile(0, 0);
    }

    /** Whether {@code v} is (inside) the tile at the first row's first position. */
    private boolean onFirstTile(View v) {
        if (v == null || rows.isEmpty() || rows.get(0).rv == null) return false;
        RecyclerView rv = rows.get(0).rv;
        while (v != null && v.getParent() != rv) {
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return v != null && rv.getChildAdapterPosition(v) == 0;
    }

    /** The top bar from the rows: the Inputs pill, or the first pill shown when a TV has none. */
    private void focusTopBar() {
        focusTopBar(false);
    }

    /**
     * @param leftmost Right from the weather: the pill next to it, whatever it is (the debug pill sits before Inputs),
     *                 so Right never skips one.
     */
    private void focusTopBar(boolean leftmost) {
        if (!leftmost && btnInputs != null && btnInputs.getVisibility() == View.VISIBLE && btnInputs.isFocusable()) {
            btnInputs.requestFocus();
            return;
        }
        ViewGroup bar = act.findViewById(R.id.topButtons);
        for (int i = 0; bar != null && i < bar.getChildCount(); i++) {
            View pill = bar.getChildAt(i);
            // This fallback never picks "Debugging on" (one OK there opens Developer options); Right from the weather
            // does, as the pill next to it. Up from a tile can still reach it through HeaderFocus.pick, from the
            // column right under it (HeaderFocusTest pins that).
            if (!leftmost && pill.getId() == R.id.btnDebug) continue;
            if (pill.getVisibility() == View.VISIBLE && pill.isFocusable()) {
                pill.requestFocus();
                return;
            }
        }
    }

    /** The row for an app no row names, or null for the catch-all row. */
    private String classify(PackageManager pm, Tile t) {
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(t.pkg, 0);
            boolean game = (ai.flags & android.content.pm.ApplicationInfo.FLAG_IS_GAME) != 0;
            return AutoCategory.classify(t.pkg, t.label, ai.category, game, new ArrayList<>(categories.keySet()));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String focusedPackage() {
        if (focusedRow < 0 || focusedRow >= rows.size()) return null;
        Row r = rows.get(focusedRow);
        return r.lastPos >= 0 && r.lastPos < r.tiles.size() ? r.tiles.get(r.lastPos).pkg : null;
    }

    private Tile tile(PackageManager pm, ResolveInfo ri, boolean leanback) {
        Tile t = new Tile();
        t.pkg = ri.activityInfo.packageName;
        t.component = new ComponentName(t.pkg, ri.activityInfo.name);
        t.label = AppLabel.clean(String.valueOf(ri.loadLabel(pm)), t.pkg);
        t.leanback = leanback;
        t.banner = loadBanner(pm, ri);
        if (t.banner == null) t.icon = ri.loadIcon(pm);
        // A banner is the app's own: its glow colour is safe on this thread. An icon's is made by reload, on main.
        if (t.banner != null) t.glow = FocusLook.tileGlow(FocusLook.averageColor(t.banner));
        // After the glow's colour, which stays the full banner's: a 1280x720 banner kept at the zoomed tile's size.
        if (t.banner != null) {
            t.banner = TileArt.fit(act.getResources(), t.banner, Math.round(tileW * FOCUS_ZOOM), Math.round(tileH * FOCUS_ZOOM));
        }
        return t;
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources r = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, act.getResources().getDisplayMetrics().densityDpi);
                banner = r.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (banner == null) banner = ri.activityInfo.loadBanner(pm);
        return banner != null && TileArt.isBannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight())
                ? banner : null;
    }

    /** The frosted band behind a row's tiles: a light veil with a hairline edge, over the wallpaper. */
    private Drawable band() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(0x1FFFFFFF);
        g.setStroke(Math.max(1, dp(0.5f)), 0x3DFFFFFF);
        // Only behind the tiles: the names sit under the band, on the photo.
        return new InsetDrawable(g, 0, 0, 0, nameH);
    }

    private View rowView(Row row, int index) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        box.setPadding(side - pad, 0, 0, dp(10));

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        head.setPadding(pad, 0, 0, dp(8));
        TextView title = text(18, 0xF2FFFFFF, R.string.font_medium);
        title.setText(RowTitles.shown(words, row.title));
        title.setShadowLayer(dp(4), 0, dp(1), 0x59000000);
        head.addView(title);
        box.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headH));

        RecyclerView rv = new RecyclerView(act) {
            // Focus moving past the row's edge scrolls it here (LayoutManager.requestChildRectangleOnScreen): the
            // same curve as RecyclerView's, in about half its time (RowScroll).
            @Override
            public void smoothScrollBy(int dx, int dy) {
                smoothScrollBy(dx, dy, null, RowScroll.horizontalMs(dx, getWidth()));
            }
        };
        rv.setLayoutManager(new LinearLayoutManager(act, RecyclerView.HORIZONTAL, false));
        // The band first: a background with insets (InsetDrawable) replaces the view's padding, so the padding
        // must come after it.
        rv.setBackground(band());
        rv.setPadding(pad, pad, pad, 0);
        // Tiles scroll through the padding and are cut at the band's edge: the row's box draws outside its own
        // bounds, so the RecyclerView clips itself to its outline instead, and lets a focused tile's zoom spill
        // out of its cell into the padding, which leaves room for it.
        rv.setClipToPadding(false);
        rv.setClipChildren(false);
        rv.setOutlineProvider(ViewOutlineProvider.BOUNDS);
        rv.setClipToOutline(true);
        rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rv.setHorizontalScrollBarEnabled(false);
        rv.setItemAnimator(null);
        rv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        rv.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect out, @NonNull View v, @NonNull RecyclerView parent,
                                       @NonNull RecyclerView.State state) {
                int pos = parent.getChildAdapterPosition(v);
                out.right = pos == row.tiles.size() - 1 ? 0 : gap;
            }
        });
        rv.setAdapter(new TileAdapter(row, index));
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                updateNames(row);
            }
        });
        row.rv = rv;
        int n = row.tiles.size();
        int contentW = 2 * pad + n * tileW + Math.max(0, n - 1) * gap;
        box.addView(rv, new LinearLayout.LayoutParams(Math.min(bandMaxW, contentW), 2 * pad + tileH + nameH));

        row.view = box;
        rv.post(() -> updateNames(row));
        return box;
    }

    /**
     * Names only under whole tiles: a tile cut at the band's edge shows no half name. Not the layout manager's
     * "completely visible": that counts the gap after a tile as part of it, and called a whole tile at the band's
     * right edge cut.
     */
    private void updateNames(Row row) {
        for (int i = 0; i < row.rv.getChildCount(); i++) {
            View item = row.rv.getChildAt(i);
            TileHolder h = (TileHolder) row.rv.getChildViewHolder(item);
            h.whole = item.getLeft() >= 0 && item.getRight() <= row.rv.getWidth();
            h.name.setAlpha(nameAlpha(h, h.level));
        }
    }

    private static final class TileHolder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner, icon;
        final TextView label, name;
        final Drawable ring, veil;
        final FocusGlow glow;
        Tile tile;
        /** 0 unfocused, 1 focused, in between while the focus animation runs: veil, glow and name follow it. */
        float level;
        /** Not cut at the band's edge (updateNames). */
        boolean whole = true;

        TileHolder(View item, View card, ImageView banner, ImageView icon, TextView label, TextView name, Drawable ring,
                   Drawable veil, FocusGlow glow) {
            super(item);
            this.glow = glow;
            this.veil = veil;
            this.card = card;
            this.banner = banner;
            this.icon = icon;
            this.label = label;
            this.name = name;
            this.ring = ring;
        }
    }

    private final class TileAdapter extends RecyclerView.Adapter<TileHolder> {
        private final Row row;
        private final int rowIndex;

        TileAdapter(Row row, int rowIndex) {
            this.row = row;
            this.rowIndex = rowIndex;
        }

        @NonNull
        @Override
        public TileHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout item = new LinearLayout(act);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setClipChildren(false);
            item.setClipToPadding(false);
            item.setLayoutParams(new RecyclerView.LayoutParams(tileW, ViewGroup.LayoutParams.MATCH_PARENT));

            FrameLayout card = new FrameLayout(act);
            card.setId(R.id.card);
            card.setFocusable(true);
            card.setClickable(true);
            GradientDrawable fill = new GradientDrawable();
            fill.setCornerRadius(dp(8));
            fill.setColor(0xFF1E2128);
            card.setBackground(fill);
            final float radius = dp(8);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);
            ImageView banner = new ImageView(act);
            banner.setId(R.id.banner);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(banner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView icon = new ImageView(act);
            icon.setId(R.id.icon);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(56), dp(56));
            iconLp.gravity = Gravity.CENTER;
            card.addView(icon, iconLp);
            TextView label = text(12, 0xFFFFFFFF, R.string.font_regular);
            label.setId(R.id.label);
            label.setGravity(Gravity.CENTER);
            label.setPadding(dp(6), 0, dp(6), dp(6));
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.gravity = Gravity.BOTTOM;
            card.addView(label, labelLp);
            item.addView(card, new LinearLayout.LayoutParams(tileW, tileH));

            TextView name = text(16, 0xE6FFFFFF, R.string.font_regular);
            name.setId(R.id.name);
            name.setGravity(Gravity.CENTER);
            name.setShadowLayer(dp(4), 0, dp(1), 0x80000000);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(tileW, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameLp.topMargin = pad + dp(7);
            item.addView(name, nameLp);

            // One holder, one set of listeners and one focus ring for the life of the tile view: binding only
            // changes what it shows.
            // The offline veil and the ring, as the card's foreground: their alphas change with focus and the
            // network, never the drawables.
            GradientDrawable veil = new GradientDrawable();
            veil.setCornerRadius(dp(8));
            veil.setColor(0xFF000000);
            veil.setAlpha(0);
            Drawable ring = focusRing();
            ring.setAlpha(0);
            card.setForeground(new LayerDrawable(new Drawable[]{veil, ring}));
            FocusGlow glow = tileGlow();
            final TileHolder h = new TileHolder(item, card, banner, icon, label, name, ring, veil, glow);
            card.setOnClickListener(x -> {
                if (h.tile != null) launch(h.tile);
            });
            card.setOnLongClickListener(x -> {
                int pos = h.getBindingAdapterPosition();
                if (h.tile != null && pos != RecyclerView.NO_POSITION) {
                    showTileMenu(rowIndex, pos, card);
                }
                return true;
            });
            card.setOnFocusChangeListener((x, has) -> {
                applyFocus(h, has, animate);
                if (has) {
                    int pos = h.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) onTileFocused(rowIndex, pos);
                }
            });
            card.setOnKeyListener((x, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                switch (keyCode) {
                    // Left and Right step within the row and stop at its ends. Not the focus finder: it measures
                    // the screen, and could skip a tile or leave the row for a pill above.
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        wanted.reset();
                        if (pos > 0) {
                            focusTile(rowIndex, pos - 1);
                            x.playSoundEffect(SoundEffectConstants.NAVIGATION_LEFT);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        wanted.reset();
                        if (pos < row.tiles.size() - 1) {
                            focusTile(rowIndex, pos + 1);
                            x.playSoundEffect(SoundEffectConstants.NAVIGATION_RIGHT);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (rowIndex == 0) {
                            if (!focusPlayer()) focusHeaderAbove(x, pos);
                        } else {
                            moveToRow(rowIndex, pos, rowIndex - 1);
                        }
                        x.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (rowIndex < rows.size() - 1) {
                            moveToRow(rowIndex, pos, rowIndex + 1);
                            x.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
                        }
                        return true;
                    default:
                        return false;
                }
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull TileHolder h, int position) {
            Tile t = row.tiles.get(position);
            h.tile = t;
            h.name.setText(t.label);
            h.glow.setColor(t.glow);
            h.whole = true;   // updateNames corrects it once the tile is placed
            if (t.banner != null) {
                h.banner.setImageDrawable(t.banner);
                h.banner.setVisibility(View.VISIBLE);
                h.icon.setImageDrawable(null);
                h.icon.setVisibility(View.GONE);
                h.label.setVisibility(View.GONE);
            } else {
                h.banner.setImageDrawable(null);
                h.banner.setVisibility(View.GONE);
                h.icon.setImageDrawable(t.icon);
                h.icon.setVisibility(View.VISIBLE);
                // The name is under the tile: not a second time inside it.
                h.label.setVisibility(View.GONE);
            }
            applyFocus(h, h.card.isFocused(), false);
        }

        @Override
        public void onBindViewHolder(@NonNull TileHolder h, int position, @NonNull List<Object> payloads) {
            // Only the network changed (refreshTiles): the veil and the name's colour, the tile itself as it is.
            if (!payloads.isEmpty() && h.tile != null && payloads.stream().allMatch(p -> p == NET_STATE)) {
                boolean has = h.card.isFocused();
                // A focus motion under way keeps going (its steps read the network state); at rest, the whole look.
                if (h.level == (has ? 1f : 0f)) {
                    applyFocus(h, has, false);
                } else {
                    setLevel(h, h.level);
                    // The name's colour too, which only applyFocus sets: a tile leaving focus as the network changes
                    // kept the old one (re-review R5a).
                    h.name.setTextColor(nameColor(h, has));
                }
                return;
            }
            onBindViewHolder(h, position);
        }

        @Override
        public int getItemCount() {
            return row.tiles.size();
        }
    }

    /**
     * Focus: a white ring, a slight zoom inside the band and a light glow around it, and the name in full white
     * (shown only now, with category_names_on_focus). The zoom, the glow, the name and (offline) the veil move as
     * one 150 ms motion; the ring is there at once. Offline: net apps faded.
     */
    private void applyFocus(TileHolder h, boolean has, boolean animated) {
        View card = h.card;
        float s = has ? FOCUS_ZOOM : 1f;
        final float from = h.level, to = has ? 1f : 0f;
        if (animated) {
            card.animate().scaleX(s).scaleY(s).setDuration(150)
                    .setUpdateListener(a -> setLevel(h, from + (to - from) * a.getAnimatedFraction())).start();
        } else {
            card.animate().cancel();
            card.setScaleX(s);
            card.setScaleY(s);
            setLevel(h, to);
        }
        // Offline, an app that needs the internet goes dark under a veil, its name fainter; focused it is drawn in
        // full, ring and all. A veil, not transparency: a white banner (Netflix, YouTube) faded with alpha let the
        // photo through and stayed a bright grey slab on the OLED panel.
        h.ring.setAlpha(has ? 255 : 0);
        card.setElevation(has ? dp(6) : 0);
        h.name.setTextColor(nameColor(h, has));
        Typeface face = has ? nameMedium : nameRegular;
        if (h.name.getTypeface() != face) h.name.setTypeface(face);
    }

    private int nameColor(TileHolder h, boolean has) {
        boolean faded = offline && !has && h.tile != null && netApps.contains(h.tile.pkg);
        return has ? NAME_FOCUSED : faded ? NAME_OFFLINE : NAME_COLOR;
    }

    /**
     * A white ring with a dark one inside it. On a white banner (YouTube, Netflix, Play Store) the white ring alone
     * is 1.07:1 against the tile: the dark ring is what shows where focus is.
     */
    private Drawable focusRing() {
        return FocusLook.ring(act.getResources(), 8);
    }

    /**
     * One step of the focus motion: the glow and (category_names_on_focus) the name fade in, and offline a net app's
     * veil fades out.
     */
    private void setLevel(TileHolder h, float level) {
        h.level = level;
        // Dimmed means one thing only: offline, an app that needs the internet. The owner turned down a veil over
        // every unfocused tile: two meanings for the same grey would be confusing.
        boolean net = offline && h.tile != null && netApps.contains(h.tile.pkg);
        h.veil.setAlpha(net ? Math.round((1f - level) * (OFFLINE_VEIL >>> 24)) : 0);
        h.name.setAlpha(nameAlpha(h, level));
        h.glow.setAlpha(Math.round(level * 255));
        boolean was = lit.contains(h);
        if (level > 0f && !was) lit.add(h);
        else if (level <= 0f && was) lit.remove(h);
        // The glows are drawn by the rows' holder: ask for the frame that places them (placeGlows). With the glows in
        // their own nodes the tile's own invalidate is enough: the holder's would repaint every row (GlowNodes).
        if (glowLayer.live() && h.card.isAttachedToWindow()) h.card.invalidate();
        else rowsBox.invalidate();
    }

    /**
     * The name under a tile: none under a tile cut at the band's edge; with category_names_on_focus only under the
     * focused one, unless the tile has no banner (an icon tile: nothing else on it says what it is).
     */
    private float nameAlpha(TileHolder h, float level) {
        if (!h.whole) return 0f;
        boolean always = !namesOnFocus || h.tile == null || h.tile.banner == null;
        return always ? 1f : level;
    }

    private final int[] glowAt = new int[2], glowBase = new int[2];
    private final Rect glowWas = new Rect();
    /** placeGlows' list, reused: it runs before every frame of the window, so it allocates nothing. */
    private final List<FocusGlow> glowNow = new ArrayList<>(2);
    /** The tiles' glow mask, pinned in FocusGlow's cache while this Home shows tiles; 0 when it is not. */
    private long tileGlowKey;

    /** A tile's glow: the banner's colour (set on bind), one mask for the zoomed tile, the Play Store's fall-off. */
    private FocusGlow tileGlow() {
        FocusGlow glow = new FocusGlow(dp(8) * FOCUS_ZOOM, dp(FocusLook.TILE_GLOW_DP), FocusLook.GLOW);
        // One mask for the zoomed tile, stretched while it zooms in (FocusGlow.setMaskSize).
        glow.setMaskSize(Math.round(tileW * FOCUS_ZOOM), Math.round(tileH * FOCUS_ZOOM));
        glow.setFalloff(FocusLook.TILE_GLOW_POWER);
        return glow;
    }

    /**
     * The tiles' glow mask (one size for every tile), pinned and made on the background thread before any tile is
     * focused: the first focus, and every new tile view, find it ready instead of making it on the main thread.
     */
    private void prepareTileGlow() {
        if (tileGlowKey != 0 || destroyed) return;
        FocusGlow glow = tileGlow();
        tileGlowKey = glow.pin();
        io.execute(glow::prepare);
    }

    /** Before each frame: each glow where its tile is drawn now (scrolled, zoomed), in the rows' holder. */
    private void placeGlows() {
        if (lit.isEmpty()) {
            glowLayer.clear();
            return;
        }
        rowsBox.getLocationInWindow(glowBase);
        List<FocusGlow> now = glowNow;
        now.clear();
        boolean moved = false;
        for (int i = 0; i < lit.size(); i++) {
            TileHolder h = lit.get(i);
            View card = h.card;
            if (!card.isAttachedToWindow() || card.getWidth() == 0) continue;
            // In window space, the zoom included.
            card.getLocationInWindow(glowAt);
            int l = glowAt[0] - glowBase[0], t = glowAt[1] - glowBase[1];
            int r = l + Math.round(card.getWidth() * card.getScaleX()), b = t + Math.round(card.getHeight() * card.getScaleY());
            glowWas.set(h.glow.getBounds());
            if (glowWas.left != l || glowWas.top != t || glowWas.right != r || glowWas.bottom != b) {
                h.glow.setBounds(l, t, r, b);
                moved = true;
            }
            now.add(h.glow);
        }
        // Only when something moved or another tile lit: an invalidate here on every frame would keep asking for
        // frames. Drawn through nodes (GlowNodes), set() asks for no frame and records only a changed glow (a fade
        // step moves nothing), so then it runs every time.
        if (moved || !glowLayer.holds(now) || glowLayer.live()) glowLayer.set(now);
    }

    // ---- header focus ---------------------------------------------------------------------------------

    /** Up from a tile of the first row: the header element above it (HeaderFocus), else the top bar as before. */
    private void focusHeaderAbove(View card, int pos) {
        Row row = rows.get(0);
        LinearLayoutManager lm = (LinearLayoutManager) row.rv.getLayoutManager();
        int column = lm == null ? pos : RowColumn.column(pos, lm.findFirstCompletelyVisibleItemPosition());
        int[] at = new int[2];
        card.getLocationInWindow(at);
        float x = at[0] + card.getWidth() * card.getScaleX() / 2f;
        List<View> views = new ArrayList<>();
        List<HeaderFocus.Target> targets = new ArrayList<>();
        if (weather != null) addTarget(weather, true, views, targets);
        ViewGroup bar = act.findViewById(R.id.topButtons);
        for (int i = 0; bar != null && i < bar.getChildCount(); i++) addTarget(bar.getChildAt(i), false, views, targets);
        int pick = HeaderFocus.pick(column, x, targets);
        if (pick >= 0) views.get(pick).requestFocus();
        else focusTopBar();
    }

    private static void addTarget(View v, boolean clockSide, List<View> views, List<HeaderFocus.Target> targets) {
        if (v == null || !v.isShown() || !v.isFocusable() || v.getWidth() == 0) return;
        int[] at = new int[2];
        v.getLocationInWindow(at);
        views.add(v);
        targets.add(new HeaderFocus.Target(at[0], at[0] + v.getWidth(), at[1], at[1] + v.getHeight(), clockSide));
    }

    /**
     * Right from the weather: the nearest top-bar pill on its right (HeaderFocus.beside). Falls back to the first pill
     * (focusTopBar) when nothing is laid out yet.
     */
    private void focusBeside(View from, int dir) {
        List<View> views = new ArrayList<>();
        List<HeaderFocus.Target> targets = new ArrayList<>();
        addTarget(from, true, views, targets);
        if (targets.isEmpty()) {
            focusTopBar(true);
            return;
        }
        HeaderFocus.Target self = targets.get(0);
        ViewGroup bar = act.findViewById(R.id.topButtons);
        for (int i = 0; bar != null && i < bar.getChildCount(); i++) addTarget(bar.getChildAt(i), false, views, targets);
        int pick = HeaderFocus.beside(self, dir, targets);
        if (pick >= 0) views.get(pick).requestFocus();
        else if (dir > 0) focusTopBar(true);
    }

    /**
     * Left or Right from a top-bar pill (MainActivity's pillKeys): the next pill shown that way, by position, so the
     * focus finder never jumps down to a tile. False at the bar's end (the caller keeps its own end rules).
     */
    boolean pillBeside(View pill, int dir) {
        ViewGroup bar = act.findViewById(R.id.topButtons);
        if (bar == null || pill.getParent() != bar) return false;
        List<View> views = new ArrayList<>();
        List<HeaderFocus.Target> targets = new ArrayList<>();
        addTarget(pill, false, views, targets);
        if (targets.isEmpty()) return false;
        HeaderFocus.Target self = targets.get(0);
        for (int i = 0; i < bar.getChildCount(); i++) {
            if (bar.getChildAt(i) != pill) addTarget(bar.getChildAt(i), false, views, targets);
        }
        int pick = HeaderFocus.beside(self, dir, targets);
        if (pick < 0) return false;
        views.get(pick).requestFocus();
        return true;
    }

    // ---- editing the rows from the couch (TileMenu, RowEdits) ------------------------------------------

    /** MainActivity's dropdown window for the tile menu (so a menu open counts as a popup open there). */
    void setPopups(TileMenu.Popups popups) {
        this.popups = popups;
    }

    private void showTileMenu(int rowIndex, int pos, View card) {
        if (rowIndex >= rows.size() || pos >= rows.get(rowIndex).tiles.size()) return;
        Row row = rows.get(rowIndex);
        final String pkg = row.tiles.get(pos).pkg;
        LinkedHashMap<String, String> others = new LinkedHashMap<>();
        for (Row r : rows) if (r != row) others.put(r.title, RowTitles.shown(words, r.title));
        new TileMenu(act).show(popups, card, pos > 0, pos < row.tiles.size() - 1, others, new TileMenu.Actions() {
            @Override
            public void moveBy(int delta) {
                if (gone(pkg)) return;
                edit(RowEdits.move(shown(), edits, pkg, delta), pkg);
            }

            @Override
            public void moveTo(String title) {
                if (gone(pkg)) return;
                edit(RowEdits.moveToRow(shown(), edits, pkg, title), pkg);
            }

            @Override
            public void hide() {
                if (gone(pkg)) return;
                // Focus goes to the neighbour that takes its place (or the one before, at the row's end).
                List<String> inRow = shown().get(row.title);
                int at = inRow == null ? -1 : inRow.indexOf(pkg);
                String next = at < 0 ? null : at + 1 < inRow.size() ? inRow.get(at + 1) : at > 0 ? inRow.get(at - 1) : null;
                edit(RowEdits.hide(edits, pkg), next);
            }

            @Override
            public void info() {
                if (gone(pkg)) return;
                Intent info = SystemSettings.appInfo(act, pkg);
                if (info == null) {
                    Toast.makeText(act, act.getString(R.string.cant_open, act.getString(R.string.tile_info)), Toast.LENGTH_SHORT).show();
                    return;
                }
                // Its own clean task: Back from it returns to Home, not into a stale settings stack.
                try {
                    act.startActivity(info.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                } catch (ActivityNotFoundException ignored) {
                }
            }
        });
    }

    /**
     * The app the open menu is about was uninstalled meanwhile (a reload while the menu was up): nothing to move, and
     * hiding it would keep a reinstall hidden.
     */
    private boolean gone(String pkg) {
        return !tiles.containsKey(pkg);
    }

    /** The rows as shown now, title to packages. */
    private LinkedHashMap<String, List<String>> shown() {
        LinkedHashMap<String, List<String>> m = new LinkedHashMap<>();
        for (Row r : rows) m.put(r.title, packages(r));
        return m;
    }

    /** Saves an edit, rebuilds the rows from the apps already loaded, and puts focus on focusPkg's tile. */
    private void edit(RowEdits next, String focusPkg) {
        if (next == edits) return;
        if (!RowEdits.save(RowEdits.file(act), next)) {
            Toast.makeText(act, R.string.rows_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        edits = next;
        // What the rows show changed: the next reload() must not skip its rebuild on an unchanged app list.
        lastSignature = null;
        backToWeather = false;
        keptPkg = null;
        showRows(focusPkg);
        // After the menu's window has gone and this window has focus back; not if a key moved focus on meanwhile.
        final int keys = keyDowns;
        handler.post(() -> {
            if (keys == keyDowns) restoreFocus();
        });
        // A hidden app's items leave the player too.
        if (watchNext != null) watchNext.refresh();
    }

    // ---- Watch Next for the player (WatchNextRow) --------------------------------------------------------

    /** The apps whose Watch Next items are not shown: hidden by config.xml or by the owner (RowEdits). Main thread. */
    private Set<String> blockedApps() {
        Set<String> s = new HashSet<>(hidden);
        s.addAll(edits.hidden);
        return s;
    }

    /** The items for the next start (WatchNext.encode), written only when their text changed. */
    private void saveCards(List<WatchNextRow.Entry> entries) {
        List<WatchNext.Item> items = new ArrayList<>();
        List<String> apps = new ArrayList<>();
        for (WatchNextRow.Entry e : entries) {
            items.add(e.item);
            apps.add(e.app);
        }
        String text = WatchNext.encode(items, apps);
        if (text.equals(savedCards)) return;
        savedCards = text;
        weatherPrefs().edit().putString(WATCH_CARDS, text).apply();
    }

    /** New Watch Next items from WatchNextRow (only when they changed). */
    private void onWatchNext(List<WatchNextRow.Entry> entries) {
        saveCards(entries);
        watchEntries = entries;
        refreshPlayer();
    }

    // ---- The player (category_player) ----------------------------------------------------------------------

    /**
     * The player above "Media": as tall as the room the scroll area has above the first row, so with focus on Media
     * the player and the row fill the screen under the header. Its first items come from the memory file (small, read
     * once per process: MediaListener has it already) and the saved Watch Next cards, without pictures; the first
     * refresh brings them.
     */
    private void buildPlayer() {
        // The card keeps its approved height (the block minus the old 8dp gap above it); the gap moved under it,
        // 12dp, so the card stands 16dp clear of the "Media" letters. Media moves 4dp lower.
        int cardH = Math.max(dp(150), (screenH - rowsTop) - fadeLen - rowH) - dp(8);
        playerH = cardH + dp(12);
        player = new PlayerCard(act, bandMaxW, cardH, dp(12), pad, nameRegular, nameMedium, new PlayerCard.Host() {
            @Override
            public void up() {
                // Back to the header element the owner was on last, else the Wi-Fi pill.
                if (usable(lastHeader)) {
                    lastHeader.requestFocus();
                    return;
                }
                View wifi = act.findViewById(R.id.btnWifi);
                if (usable(wifi)) {
                    wifi.requestFocus();
                    return;
                }
                View f = player.findFocus();
                if (rows.isEmpty()) focusTopBar();
                else focusHeaderAbove(f != null ? f : player, 0);
            }

            @Override
            public void down() {
                if (rows.isEmpty()) return;
                focusTile(0, rows.get(0).lastPos);
            }

            @Override
            public void resume(PlayerPick.Item i) {
                resumeItem(i);
            }

            @Override
            public void control(PlayerPick.Item i, int button) {
                controlItem(i, button);
            }

            @Override
            public void menu(PlayerPick.Item i, View anchor) {
                new TileMenu(act).showOne(popups, anchor, act.getString(R.string.player_remove), () -> {
                    // A remembered video merged with its Watch Next row: the row's key goes too (PlayerPick).
                    final List<String> keys = PlayerPick.removeKeys(i);
                    final PlayerMemory m = memory;
                    io.execute(() -> m.remove(keys));
                });
            }

            @Override
            public String label(String pkg) {
                return appLabel(pkg);
            }

            @Override
            public Drawable appArt(String pkg) {
                Tile t = tiles.get(pkg);
                Drawable src = t == null ? null : t.banner != null ? t.banner : t.icon;
                Drawable.ConstantState cs = src == null ? null : src.getConstantState();
                // A copy: the tile's own drawable is drawn by its tile, and a drawable has one set of bounds.
                return cs != null ? cs.newDrawable(act.getResources()).mutate() : null;
            }

            @Override
            public Drawable appIcon(String pkg) {
                Tile t = tiles.get(pkg);
                Drawable src = t == null ? null : t.icon;
                Drawable.ConstantState cs = src == null ? null : src.getConstantState();
                return cs != null ? cs.newDrawable(act.getResources()).mutate() : null;
            }
        });
        player.setAnimate(animate);
        FocusLook.unclip(player, scroll);
        trackHeader();
        memory = PlayerMemory.get(act);
        playerGlowKey = player.pinGlow();
        io.execute(player::prepareGlow);
        // The first frame from the memory file (small, read once per process): hidden apps are left out already, or
        // the card could show for one frame and vanish at the first refresh (review 14).
        List<PlayerPick.Item> first = memory.items();
        final Set<String> blocked = blockedApps();
        first.removeIf(i -> blocked.contains(i.pkg));
        player.setItems(PlayerPick.merge(first, watchItems(), memory.removed(), System.currentTimeMillis()), null);
    }

    private static boolean usable(View v) {
        return v != null && v.isShown() && v.isFocusable() && v.getWidth() > 0;
    }

    /** Whether a view is one of the header's focusable elements (the weather, a top-bar pill). */
    private boolean isHeader(View v) {
        if (v == null) return false;
        if (v == weather) return true;
        ViewGroup bar = act.findViewById(R.id.topButtons);
        return bar != null && v.getParent() == bar;
    }

    /**
     * The header element the owner focused last, for Up from the player. (The "Musica ⌄" more-below hint that shared
     * this listener was removed after a look at it on the TV.)
     */
    private void trackHeader() {
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            // Only a move the owner made: at start the window gives the first pill the focus (from nothing) before
            // Home puts it on Media, and that is no choice of his (TV check: Up went to "Debugging on").
            if (was != null && isHeader(now)) lastHeader = now;
        });
    }

    /** Any key on Home (MainActivity.onUserInteraction): the player's bar moves again, and the idle minute restarts. */
    void onUserKey() {
        if (player == null) return;
        player.setIdle(false);
        handler.removeCallbacks(playerIdle);
        if (resumed) handler.postDelayed(playerIdle, PLAYER_IDLE_MS);
    }

    private boolean playerShown() {
        return player != null && player.getVisibility() == View.VISIBLE && !player.isEmpty();
    }

    /** Up from the first row: the player's main button, the screen scrolled to show it. */
    private boolean focusPlayer() {
        if (!playerShown() || !player.takeFocus()) return false;
        if (animate) ((RowsScrollView) scroll).smoothScrollToRow(0);
        else scroll.scrollTo(0, 0);
        return true;
    }

    private List<WatchNext.Item> watchItems() {
        List<WatchNext.Item> out = new ArrayList<>();
        for (WatchNextRow.Entry e : watchEntries) out.add(e.item);
        return out;
    }

    /** The memory and the Watch Next rows merged (PlayerPick) and their pictures prepared, off the main thread. */
    private void refreshPlayer() {
        handler.removeCallbacks(playerRefresh);
        if (player == null || destroyed) return;
        final List<WatchNext.Item> wn = watchItems();
        final Map<String, Bitmap> posters = new java.util.HashMap<>();
        for (WatchNextRow.Entry e : watchEntries) {
            if (e.poster != null) posters.put(e.item.pkg + "|" + PlayerPick.norm(e.item.title), e.poster);
        }
        final String test = TestHooks.player(act);
        final Set<String> blocked = blockedApps();
        final Set<String> installed = new HashSet<>(tiles.keySet());
        final PlayerMemory m = memory;
        try {
            io.execute(() -> {
                long now = System.currentTimeMillis();
                List<PlayerPick.Item> mem = m.items();
                // A picture still remembered as its https URL, or whose file is lost (fetched again from its artUrl):
                // fetched once by the recorder, then the Home is told.
                if (!PlayerPick.artToFetch(mem, null, p -> new java.io.File(p).exists()).isEmpty()) {
                    MediaMemory.get(act).fixArt();
                }
                // Apps hidden by the owner, or gone (only once the rows know what is installed).
                mem.removeIf(i -> blocked.contains(i.pkg) || (!installed.isEmpty() && !installed.contains(i.pkg)));
                if (test != null) mem.addAll(PlayerTestItems.make(test, now));
                List<PlayerPick.Item> merged = PlayerPick.merge(mem, wn, m.removed(), now);
                testResume(merged);
                Map<String, PlayerCard.Art> arts = new java.util.HashMap<>();
                for (PlayerPick.Item i : merged) {
                    PlayerCard.Art a = playerArt(i, posters);
                    if (a != null) arts.put(i.key, a);
                }
                if (!destroyed) handler.post(() -> showPlayer(merged, arts));
            });
        } catch (RuntimeException ignored) {
            // io shut down (Home destroyed).
        }
    }

    /** rehome_test_resume (TestHooks.resume): each new value acts once. io thread. */
    private String lastTestResume;

    private void testResume(List<PlayerPick.Item> merged) {
        String v = TestHooks.resume(act);
        if (v == null || v.equals(lastTestResume)) return;
        lastTestResume = v;
        for (PlayerPick.Item i : merged) {
            android.util.Log.i("Rehome.Memory", "resume would " + resumeWay(i) + ": " + i.key);
        }
        if (!v.startsWith("go")) return;
        for (PlayerPick.Item i : merged) {
            if (i.kind != PlayerPick.SONG) continue;
            // As the card's ⏯ does (PlayerCard.hook): a live song is controlled, a closed one resumed.
            handler.post(() -> {
                if (i.live) controlItem(i, PlayerCard.PLAY_PAUSE);
                else resumeItem(i);
            });
            return;
        }
    }

    /** What Resume (or ⏯ on a song) would do, for the test hook's log. io thread. */
    private String resumeWay(PlayerPick.Item i) {
        if (i.next != null) return "open the Watch Next link (else the next step)";
        if (i.live) return i.kind == PlayerPick.SONG ? "control the live session" : "send the live session's activity";
        if (i.kind == PlayerPick.SONG && MediaResume.service(act, i.pkg) != null) return "play through the media browser";
        if (i.link != null) return "open " + i.link + " in " + i.pkg;
        return "open the app " + i.pkg;
    }

    private void showPlayer(List<PlayerPick.Item> items, Map<String, PlayerCard.Art> arts) {
        if (player == null || destroyed) return;
        boolean was = playerShown(), had = player.hasFocus();
        player.setItems(items, arts);
        player.setOffline(offline, netApps);
        boolean now = playerShown();
        if (was == now) return;
        // The player came or went: the rows under it moved. Focus and the scroll follow the row that had them.
        if (had && !now) {
            restoreFocus();
        } else if (hasFocus() && !rows.isEmpty()) {
            final int r = Math.min(focusedRow, rows.size() - 1);
            scroll.post(() -> {
                if (r < rows.size()) onTileFocused(r, rows.get(r).lastPos);
            });
        }
    }

    /** One item's picture: the Watch Next poster, the remembered file, or the app's banner for its colour. io thread. */
    private PlayerCard.Art playerArt(PlayerPick.Item i, Map<String, Bitmap> posters) {
        Bitmap poster = i.next != null ? posters.get(i.pkg + "|" + PlayerPick.norm(i.title)) : null;
        // Whether the poster is in is part of the key: the first refresh runs before the posters arrive, and a
        // picture-less Art kept under the same key would hide the poster for good (review 2).
        String cacheKey = i.key + "|" + i.art + "|" + i.title + "|" + (poster != null);
        PlayerCard.Art a = playerArt.get(cacheKey);
        if (a != null) return a;
        Bitmap bmp = poster;
        if (bmp == null && i.art != null && !i.art.startsWith("https://")) bmp = decodeArt(i.art);
        a = new PlayerCard.Art();
        if (!playerIcons.containsKey(i.pkg)) {
            Drawable icon = playerIcon(i.pkg);
            // A wordmark for an icon is unreadable at 18dp: its first letter alone instead (Netflix's
            // "N"). On this TV com.netflix.ninja's application, leanback-activity and launcher-activity icons and its
            // logo are all "NETFLIX" on white; there is no square "N" to load.
            // Then kept at the size the app line shows it (TileArt.fit: only a bitmap 1.5x that or more is copied).
            playerIcons.put(i.pkg, icon != null ? TileArt.fit(act.getResources(), initialOfWordmark(icon),
                    dp(PlayerCard.ICON_DP), dp(PlayerCard.ICON_DP)) : null);
        }
        a.icon = playerIcons.get(i.pkg);
        if (bmp != null) {
            a.image = bmp;
            a.square = Math.abs(bmp.getWidth() - bmp.getHeight()) <= Math.max(bmp.getWidth(), bmp.getHeight()) / 10;
            if (a.square) a.blur = PlayerCard.blur(bmp);
            a.color = FocusLook.averageColor(new android.graphics.drawable.BitmapDrawable(act.getResources(), bmp));
        } else {
            try {
                // A fresh drawable of the app's banner, only for its colour (never the tile's own, drawn on main).
                PackageManager pm = act.getPackageManager();
                Drawable banner = pm.getApplicationBanner(i.pkg);
                if (banner == null) banner = pm.getApplicationIcon(i.pkg);
                // Its strongest colour, not the plain average: YouTube's white banner averages to a pale pink and
                // Spotify's black one to grey, which tint the card with nothing.
                a.color = strongestColor(banner);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                a.color = 0x808080;
            }
        }
        playerArt.put(cacheKey, a);
        return a;
    }

    /**
     * An app's icon for the player's app line. A bitmap icon far bigger than the wordmark check reads is decoded
     * smaller (PosterCache.sampleSize): RaiPlay 5.0 ships its icon at 2048x2048, 16 MiB of the launcher's memory for
     * a picture shown at 16dp (three quarters of what the player added to the PSS). Anything else (an
     * adaptive or vector icon, a bitmap of an ordinary size) loads as PackageManager gives it. io thread.
     */
    private Drawable playerIcon(String pkg) {
        PackageManager pm = act.getPackageManager();
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.icon != 0) {
                Resources r = pm.getResourcesForApplication(ai);
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeResource(r, ai.icon, o);
                int sample = PosterCache.sampleSize(o.outWidth, o.outHeight, 2 * WORDMARK_PX, 2 * WORDMARK_PX);
                if (o.outWidth > 0 && o.outHeight > 0 && sample > 1) {
                    o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = sample;
                    Bitmap b = android.graphics.BitmapFactory.decodeResource(r, ai.icon, o);
                    if (b != null) return new android.graphics.drawable.BitmapDrawable(act.getResources(), b);
                }
            }
            return pm.getApplicationIcon(ai);
        } catch (PackageManager.NameNotFoundException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The side, px, an icon is drawn at for the wordmark check (initialOfWordmark). */
    private static final int WORDMARK_PX = 160;

    /** An app's icon, or when it is a wordmark its first letter (PlayerColors.wordmarkInitial). io thread. */
    private Drawable initialOfWordmark(Drawable dr) {
        int n = WORDMARK_PX;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        try {
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            dr.setBounds(0, 0, n, n);
            dr.draw(c);
            int[] px = new int[n * n];
            b.getPixels(px, 0, n, 0, 0, n, n);
            int[] r = PlayerColors.wordmarkInitial(px, n, n);
            if (r == null) return dr;
            // Only the first letter: the square's other columns (the "E" of "NETFLIX") take the icon's ground colour.
            int side = r[2];
            int[] sq = new int[side * side];
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    int sx = r[0] + x;
                    sq[y * side + x] = sx >= r[3] && sx <= r[4] ? px[(r[1] + y) * n + sx] : px[0];
                }
            }
            Bitmap out = Bitmap.createBitmap(sq, side, side, Bitmap.Config.ARGB_8888);
            return new android.graphics.drawable.BitmapDrawable(act.getResources(), out);
        } catch (RuntimeException e) {
            return dr;
        } finally {
            b.recycle();
        }
    }

    /** A drawable's strongest colour (PlayerColors.mostSaturated) from a small copy of it. io thread. */
    private static int strongestColor(Drawable dr) {
        if (dr == null) return 0x808080;
        int w = 64, h = 36;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            dr.setBounds(0, 0, w, h);
            dr.draw(c);
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            return PlayerColors.mostSaturated(px);
        } finally {
            b.recycle();
        }
    }

    /** A remembered picture, decoded near the player's image height (not at its saved size). io thread. */
    private Bitmap decodeArt(String path) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0) return null;
            int want = Math.max(1, playerH - headH);
            int sample = 1;
            while (o.outHeight / (sample * 2) >= want) sample *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(path, o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private String appLabel(String pkg) {
        Tile t = tiles.get(pkg);
        if (t != null && t.label != null) return t.label;
        String had = appLabels.get(pkg);
        if (had != null) return had;
        String label = pkg;
        try {
            PackageManager pm = act.getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        appLabels.put(pkg, label);
        return label;
    }

    /**
     * Resume: the Watch Next link (WatchNextRow.open's checks), else the live session's own screen (YouTube and
     * Netflix go back where they were), else the link the session gave (PlayerPick.link, opened with the same checks
     * into the app that played it), else the app.
     */
    private void resumeItem(PlayerPick.Item i) {
        resumeItem(i, true);
    }

    /**
     * {@code browser}: a song whose app is closed (no live session; ⏮ ⏯ ⏭ come here then, PlayerCard.hook) first
     * plays its app's last track again through the app's media browser (MediaResume, as Android's own resumption);
     * an app without one, or that does not play, goes on with the steps below.
     */
    private void resumeItem(PlayerPick.Item i, boolean browser) {
        if (i.next != null && watchNext != null && watchNext.open(i.next)) return;
        if (browser && i.kind == PlayerPick.SONG && !i.live) {
            MediaResume.play(act, i.pkg, i.title, () -> {
                if (!destroyed) resumeItem(i, false);
            });
            return;
        }
        final PlayerMemory m = memory;
        io.execute(() -> {
            // Only the item's own live session: another video playing now in the same app is not this one (review 15).
            android.media.session.MediaController c = i.live ? m.controller(i.pkg) : null;
            android.app.PendingIntent pi = null;
            try {
                if (c != null) pi = c.getSessionActivity();
                if (pi != null) {
                    pi.send();
                    return;
                }
            } catch (android.app.PendingIntent.CanceledException | RuntimeException ignored) {
            }
            handler.post(() -> {
                if (i.link != null && watchNext != null && watchNext.open(linkOf(i))) return;
                openPackage(i.pkg);
            });
        });
    }

    /** A remembered item's link as a Watch Next row's, for WatchNextRow.open: only into the app that played it. */
    private static WatchNext.Item linkOf(PlayerPick.Item i) {
        WatchNext.Item w = new WatchNext.Item();
        w.pkg = i.pkg;
        w.title = i.title;
        w.intentUri = i.link;
        return w;
    }

    /** ⏮ ⏯ ⏭ on the live session; gone since, the app opens instead. Binder calls: on io. */
    private void controlItem(PlayerPick.Item i, int button) {
        final PlayerMemory m = memory;
        io.execute(() -> {
            android.media.session.MediaController c = m.controller(i.pkg);
            if (c == null) {
                handler.post(() -> resumeItem(i));
                return;
            }
            try {
                android.media.session.MediaController.TransportControls tc = c.getTransportControls();
                if (button == PlayerCard.PREV) tc.skipToPrevious();
                else if (button == PlayerCard.NEXT) tc.skipToNext();
                else {
                    android.media.session.PlaybackState st = c.getPlaybackState();
                    if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) tc.pause();
                    else tc.play();
                }
            } catch (RuntimeException e) {
                handler.post(() -> resumeItem(i));
            }
        });
    }

    // ---- Home keys (HomeKeys) -------------------------------------------------------------------------

    /** Whether Home has a shortcut on this key (a digit or a colour key, HomeKeys), whatever is open now. */
    boolean takesKey(int keyCode) {
        return HomeKeys.handles(keyCode, numberKeys);
    }

    /**
     * Digits and colour keys on Home (MainActivity.dispatchKeyEvent). {@code free}: Home in front, focused, with no
     * popup, dialog or menu over it. The key that went down here has its repeats and its UP eaten too.
     */
    boolean onHomeKey(KeyEvent event, boolean free) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down && event.getRepeatCount() == 0) keyDowns++;
        // The owner moves the focus from here on (Up from the first tile to the player is theirs to make).
        if (down) homeFocus.release();
        boolean eat = homeKeys.onKey(code, down, event.getRepeatCount(), free && HomeKeys.handles(code, numberKeys));
        if (!eat || !down || event.getRepeatCount() > 0) return eat;
        List<String> media = null;
        for (Row r : rows) if (r.title.equals(HomeKeys.MEDIA_ROW)) media = packages(r);
        String pkg = HomeKeys.target(code, numberKeys, media, colorKeys);
        if (pkg != null) openPackage(pkg);
        return true;
    }

    /** An app by package: its tile's entry when it has one, else its TV (or phone) launch entry; gone: nothing. */
    private void openPackage(String pkg) {
        Tile t = tiles.get(pkg);
        if (t != null) {
            launch(t);
            return;
        }
        PackageManager pm = act.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    /**
     * Down or Up: the tile in the same on-screen column of the next row, as Apple TV does; through a shorter row the
     * column is kept for the row after it (RowColumn.Wanted).
     */
    private void moveToRow(int fromRow, int fromPos, int toRow) {
        Row from = rows.get(fromRow), to = rows.get(toRow);
        LinearLayoutManager fl = (LinearLayoutManager) from.rv.getLayoutManager();
        LinearLayoutManager tl = (LinearLayoutManager) to.rv.getLayoutManager();
        int column = wanted.from(fromRow, fromPos,
                fl == null ? 0 : RowColumn.column(fromPos, fl.findFirstCompletelyVisibleItemPosition()));
        int first = tl == null ? 0 : tl.findFirstCompletelyVisibleItemPosition();
        int target = RowColumn.target(first, column, to.tiles.size());
        wanted.landed(column, toRow, target);
        focusTile(toRow, target);
    }

    private void focusTile(int rowIndex, int pos) {
        if (rows.isEmpty()) {
            focusTopBar();
            return;
        }
        rowIndex = Math.max(0, Math.min(rowIndex, rows.size() - 1));
        Row row = rows.get(rowIndex);
        final int p = Math.max(0, Math.min(pos, row.tiles.size() - 1));
        RecyclerView.ViewHolder vh = row.rv.findViewHolderForAdapterPosition(p);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            if (card.isFocused()) onTileFocused(rowIndex, p);
            else card.requestFocus();
            return;
        }
        row.rv.scrollToPosition(p);
        final int ri = rowIndex;
        row.rv.post(() -> {
            RecyclerView.ViewHolder h = row.rv.findViewHolderForAdapterPosition(p);
            if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            else if (row.rv.getChildCount() > 0) row.rv.getChildAt(0).findViewById(R.id.card).requestFocus();
            else focusedRow = ri;
        });
    }

    private void onTileFocused(int rowIndex, int pos) {
        Row row = rows.get(rowIndex);
        row.lastPos = pos;
        int furthest = Math.max(0, rowsBox.getHeight() - scroll.getHeight());
        // The first row with the player shown above it: both on screen (the player fills the space above Media).
        int target = rowIndex == 0 && playerShown() ? 0 : Math.min(furthest, Math.max(0, row.view.getTop() - fadeLen));
        boolean rowChanged = rowIndex != focusedRow || scroll.getScrollY() != target;
        focusedRow = rowIndex;
        if (rowChanged) {
            if (animate) ((RowsScrollView) scroll).smoothScrollToRow(target);
            else scroll.scrollTo(0, target);
        }
        updateNames(row);
    }

    /** Back to the app focus was on (Home, back from an app, Down from the top bar). */
    void restoreFocus() {
        if (backToWeather && weather != null) {
            backToWeather = false;
            weather.requestFocus();
            return;
        }
        if (rows.isEmpty()) {
            focusTopBar();
            return;
        }
        int r = Math.max(0, Math.min(focusedRow, rows.size() - 1));
        if (scroll.getHeight() == 0 || rows.get(r).rv.getChildCount() == 0) {
            // The rows may have been rebuilt, shorter, by the time this runs.
            scroll.post(() -> {
                if (rows.isEmpty()) {
                    focusTopBar();
                    return;
                }
                int rr = Math.min(r, rows.size() - 1);
                focusTile(rr, rows.get(rr).lastPos);
            });
        } else {
            focusTile(r, rows.get(r).lastPos);
        }
    }

    /** Down from a top-bar pill: back to the rows. */
    void onPillDown() {
        backToWeather = false;
        // The player, when shown, is the first thing under the header (review 17).
        if (focusPlayer()) return;
        restoreFocus();
    }

    private void launch(Tile t) {
        Intent i = new Intent(Intent.ACTION_MAIN).setComponent(t.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        i.addCategory(t.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
        try {
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, act.getString(R.string.cant_open, t.label), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- weather -------------------------------------------------------------------------------------

    /** Beside the clock, over the photo: a symbol and the temperature, the place, the sky, and the source. */
    private void buildWeather() {
        weather = new LinearLayout(act);
        weather.setOrientation(LinearLayout.VERTICAL);
        weather.setGravity(Gravity.START);
        weather.setFocusable(true);
        weather.setClickable(true);
        // Focused: the dark glass it always had, now with the tiles' ring and glow (FocusLook).
        weather.setBackground(FocusLook.background(act.getResources(), 16, FocusLook.HEADER_GLOW_DP,
                FocusLook.GLASS_DARK, null));
        weather.setPadding(dp(14), dp(8), dp(14), dp(8));

        // Second to the clock: smaller and in softer whites. Three lines as tall as the clock and the date together
        // (alignHeader spaces them): the temperature, the place and sky, then today's range and the source.
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        wIcon = text(24, 0xD9FFFFFF, R.string.font_light);
        wIcon.setShadowLayer(dp(4), 0, dp(1), 0x59000000);
        wIcon.setText(" ");
        wTemp = text(28, 0xE6FFFFFF, R.string.font_light);
        wTemp.setShadowLayer(dp(4), 0, dp(1), 0x4D000000);
        wTemp.setText("--°C");
        top.addView(wIcon);
        LinearLayout.LayoutParams tempLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tempLp.leftMargin = dp(8);
        // The temperature and the place on one baseline (LinearLayout aligns baselines within the TOP group); the
        // symbol stays centred.
        tempLp.gravity = Gravity.TOP;
        top.addView(wTemp, tempLp);
        wCity = text(16, 0xE6FFFFFF, R.string.font_regular);
        wCity.setShadowLayer(dp(4), 0, dp(1), 0x59000000);
        LinearLayout.LayoutParams cityLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cityLp.leftMargin = dp(12);
        cityLp.gravity = Gravity.TOP;
        top.addView(wCity, cityLp);
        // As wide as its content, not the vertical default MATCH_PARENT: a MATCH_PARENT line adds nothing to the
        // weather's width and is stretched to the other two lines, so under an empty sky and last line ("Set your
        // city") it was 0px wide and the prompt never showed (WeatherLinesTest).
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        weather.addView(top, topLp);

        wPlace = text(16, 0xB3FFFFFF, R.string.font_regular);
        wPlace.setShadowLayer(dp(4), 0, dp(1), 0x59000000);
        LinearLayout.LayoutParams placeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        placeLp.topMargin = dp(6);
        weather.addView(wPlace, placeLp);
        wMore = text(14, 0xCCFFFFFF, R.string.font_regular);
        wMore.setShadowLayer(dp(4), 0, dp(1), 0x59000000);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        moreLp.topMargin = dp(6);
        weather.addView(wMore, moreLp);
        showPlace("");
        showMore("", "");

        // No city yet, or no weather app to open (config.xml's weather_app is empty by default): the city picker.
        weather.setOnClickListener(v -> {
            if (askCity || weatherApp.isEmpty()) openCityPicker();
            else openWeatherApp();
        });
        weather.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    focusBeside(weather, 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    backToWeather = false;
                    // The header is reached only from the top (the player, or Media without it): Down goes back there.
                    if (!focusPlayer()) restoreFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return true;
                default:
                    return false;
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Placed beside the clock by alignHeader, once both are laid out.
        lp.gravity = Gravity.TOP | Gravity.START;
        root.addView(weather, root.indexOfChild(scroll) + 1, lp);   // over the rows (buildRows ran first)
        if (askCity) showSetCity();
        else showCachedWeather();
    }

    /** The weather's three lines while no city is set: the call to set one, where the temperature goes. */
    private void showSetCity() {
        wIcon.setText(" ");
        wIcon.setVisibility(View.GONE);
        // Alone on its line: no gap for the hidden symbol, and no empty place name with its margin after it, so the
        // line is the prompt's width and keepWeatherClear's cap holds for it.
        ((LinearLayout.LayoutParams) wTemp.getLayoutParams()).leftMargin = 0;
        wCity.setVisibility(View.GONE);
        wTemp.setText(act.getString(R.string.weather_set_city));
        showPlace("");
        showMore("", "");
    }

    /** OK on the weather: the city picker; Back from it returns here (a pick rebuilds Home instead). */
    private void openCityPicker() {
        try {
            act.startActivity(new Intent(act, CityPickerActivity.class));
            backToWeather = true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(act, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Up from a top-bar pill: the weather beside the clock, when there is one. */
    boolean onPillUp() {
        if (weather == null) return false;
        weather.requestFocus();
        return true;
    }

    private SharedPreferences weatherPrefs() {
        return act.getSharedPreferences(CityStore.READING_PREFS, Context.MODE_PRIVATE);
    }

    /** The saved reading, unless it is too old to pass for the weather now (days offline, or a clock set back). */
    private void showCachedWeather() {
        if (askCity) return;
        String json = weatherPrefs().getString("json", null);
        if (json == null || weatherStale()) return;
        try {
            showWeather(new JSONObject(json));
        } catch (Exception ignored) {
        }
    }

    private boolean weatherStale() {
        long age = System.currentTimeMillis() - weatherPrefs().getLong("at", 0);
        return age < 0 || age > WEATHER_STALE_MS;
    }

    /** No reading to show. Offline the hint stays instead. */
    private void showNoWeather() {
        if (offline || askCity) return;
        wIcon.setVisibility(View.VISIBLE);
        wIcon.setText(" ");
        wTemp.setText("--°C");
        showPlace("");
        showMore("", "");
    }

    /**
     * Offline, in the weather's place: what to do about it rather than a reading that may be hours old. Same three
     * lines, so alignHeader keeps them on the clock's lines: no temperature, the hint, and an empty last line (no
     * Open-Meteo data is shown, so no credit).
     */
    private void showOfflineHint() {
        wIcon.setText(" ");
        wIcon.setVisibility(View.GONE);
        wTemp.setText("--°C");
        // As bright as the place name it replaces, a shade over the line's own white.
        SpannableStringBuilder hint = new SpannableStringBuilder(act.getString(R.string.offline_hint));
        hint.setSpan(new ForegroundColorSpan(0xE6FFFFFF), 0, hint.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        wPlace.setText(hint);
        showMore("", "");
    }

    /** A reading on screen; offline only checked (it throws on an answer that is not one) and kept for later. */
    private void showWeather(JSONObject o) throws org.json.JSONException {
        JSONObject cur = o.getJSONObject("current");
        JSONObject daily = o.getJSONObject("daily");
        int code = cur.getInt("weather_code");
        boolean day = cur.optInt("is_day", 1) == 1;
        long temp = Math.round(cur.getDouble("temperature_2m"));
        long max = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
        long min = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
        if (offline) return;
        wIcon.setVisibility(View.VISIBLE);
        wIcon.setText(symbol(code, day));
        wTemp.setText(temp + "°C");
        showPlace(describe(code));
        // A reading saved before the wind was asked has none: the line is then the range (and rain) alone.
        String wind = WindNote.note(words, cur.optDouble("wind_speed_10m", Double.NaN),
                cur.optDouble("wind_direction_10m", Double.NaN));
        showMore(max + "° / " + min + "°", rainNote(o), wind);
    }

    /**
     * The next hours' rain or snow (RainNote) from the reading's hourly part, against the clock now: a saved reading
     * shown again later speaks of the hours still ahead. Empty for a reading saved before the hourly part was asked.
     */
    private String rainNote(JSONObject o) {
        long now = System.currentTimeMillis();
        int offset = o.optInt("utc_offset_seconds", java.util.TimeZone.getDefault().getOffset(now) / 1000);
        org.json.JSONObject hourly = o.optJSONObject("hourly");
        org.json.JSONArray time = hourly == null ? null : hourly.optJSONArray("time");
        org.json.JSONArray prob = hourly == null ? null : hourly.optJSONArray("precipitation_probability");
        org.json.JSONArray code = hourly == null ? null : hourly.optJSONArray("weather_code");
        int n = time == null ? 0 : time.length();
        String[] t = new String[n];
        int[] p = new int[n], c = new int[n];
        for (int i = 0; i < n; i++) {
            t[i] = time.optString(i, null);
            p[i] = prob == null ? -1 : prob.optInt(i, -1);
            c[i] = code == null ? -1 : code.optInt(i, -1);
        }
        int[] test = TestHooks.rain(act);
        rainHooked = test != null;
        if (test != null) {
            // TestHooks: the given chances from this hour on, also over a saved reading that has no hours.
            p = test;
            if (n < test.length) {
                t = new String[test.length];
                c = new int[test.length];
                for (int i = 0; i < t.length; i++) {
                    t[i] = RainNote.hourKey(now + i * 3_600_000L, offset) + ":00";
                    c[i] = -1;
                }
            }
        }
        return RainNote.note(words, t, p, c, RainNote.hourKey(now, offset));
    }

    /** The rain note on screen came from TestHooks: shown again when the hook may have gone (onTestHooksChanged). */
    private boolean rainHooked;

    /**
     * ADB went on or off while Home is in front (DebugPill watches it), so the test hooks (TestHooks) may have stopped
     * acting: everything that showed one is read again now, not at the next fetch or resume. MainActivity redoes the
     * network pill (the offline hook) itself.
     */
    void onTestHooksChanged() {
        if (!resumed || destroyed) return;
        if (rainHooked && !offline) showCachedWeather();
        if (meter != null) meter.onResume();
        if (watchNext != null) watchNext.refresh();
        refreshPlayer();
    }

    /**
     * The place after the temperature, the sky alone on the line under it with the whole line to itself (a
     * "Rome | Partly cloudy" line was cut).
     */
    private void showPlace(String sky) {
        if (wCity != null) wCity.setText(weatherCity);
        wPlace.setText(sky);
    }
    /**
     * Between two parts of a weather line: a 1dp bar at 30% white with 10dp each side, not a " · " (the
     * dots read as machine-made).
     */
    private void divider(SpannableStringBuilder line) {
        int at = line.length();
        line.append('|');
        line.setSpan(new GapSpan(dp(10), Math.max(1, dp(1)), 0x4DFFFFFF), at, at + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * Today's range and the rain to come ("Rain from 18:00", when there is any), then the wind. Open-Meteo's credit (its data is
     * CC BY 4.0) is in the launcher's settings, not on Home. The pills are centred on the clock's digits, level with
     * all three lines, so this one is capped before them too (keepWeatherClear, fitMore).
     */
    private void showMore(String range, String rain) {
        showMore(range, rain, "");
    }

    /** The same with the wind ("Wind 14 km/h NE", WindNote) last; the parts parted by the thin bar. */
    private void showMore(String range, String rain, String wind) {
        moreParts = new String[]{range, rain, wind};
        moreShown = null;
        fitMore();
    }

    /** The last line's range, rain and wind (showMore), and the text on screen from them (null: none set yet). */
    private String[] moreParts = {"", "", ""};
    private String moreShown;
    /** The weather's text room keepWeatherClear last found, -1 before the first layout. */
    private int moreRoom = -1;

    /**
     * The last line in the room left of the pills (HeaderFit.thirdLine): the wind goes whole rather than end in
     * "Wind 14 k…", and the rain is shortened only between words ("Rain from…", never "Rain from 1…"). Set only when
     * the text changes, so the layout pass this causes settles at once.
     */
    private void fitMore() {
        if (wMore == null) return;
        android.text.TextPaint paint = wMore.getPaint();
        java.util.List<String> parts = HeaderFit.thirdLine(moreParts[0], moreParts[1], moreParts[2],
                paint::measureText, 2 * dp(10) + Math.max(1, dp(1)), moreRoom);
        String shown = String.join("\n", parts);
        if (shown.equals(moreShown)) return;
        moreShown = shown;
        SpannableStringBuilder line = new SpannableStringBuilder();
        for (String part : parts) {
            if (line.length() > 0) divider(line);
            line.append(part);
        }
        wMore.setText(line);
    }

    private final Runnable weatherTick = this::fetchWeather;

    /** The next reading: now when the saved one is half an hour old (or from a clock since set back), else when it is. */
    private void scheduleWeather() {
        handler.removeCallbacks(weatherTick);
        if (weather == null || askCity || !resumed) return;
        long age = System.currentTimeMillis() - weatherPrefs().getLong("at", 0);
        handler.postDelayed(weatherTick, age < 0 || age >= WEATHER_EVERY_MS ? 0 : WEATHER_EVERY_MS - age);
    }

    /** A network with internet just came up (the hotspot is back): the reading that failed without it, now. */
    void onOnline() {
        weatherRetryMs = WEATHER_RETRY_MIN_MS;
        scheduleWeather();
    }

    private void fetchWeather() {
        // One request at a time: a slow one (16 s of timeouts) is not joined by the next resume's.
        if (!fetching.compareAndSet(false, true)) return;
        // The same model as the weather app this opens, so the two agree.
        final String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f"
                + "&longitude=%.4f&current=temperature_2m,weather_code,is_day,wind_speed_10m,wind_direction_10m"
                + "&wind_speed_unit=kmh&models=icon_seamless"
                + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
                // The rain line: from the current hour on, in the place's time.
                + "&hourly=precipitation_probability,weather_code&forecast_hours=6", weatherLat, weatherLon);
        weatherIo.execute(() -> {
            HttpURLConnection c = null;
            String body = null;
            JSONObject reading = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        // The answer is about 1 KB; a hotspot's sign-in page is not read to its end.
                        while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
                        body = out.toString("UTF-8");
                        reading = new JSONObject(body);
                    }
                }
            } catch (Exception ignored) {
                // No network, or not Open-Meteo's answer: tried again below.
                reading = null;
            } finally {
                if (c != null) c.disconnect();
                fetching.set(false);
            }
            final String json = body;
            final JSONObject o = reading;
            if (!destroyed) handler.post(() -> weatherFetched(json, o));
        });
    }

    private void weatherFetched(String json, JSONObject o) {
        if (destroyed) return;
        // Home rebuilds for a new city only when it resumes: an answer for the old city that arrives in between
        // (Launcher settings open) would be saved as the new city's reading and shown for half an hour. Dropped; the
        // rebuilt Home asks for the new city.
        if (!CityStore.readingFor(weatherLat, weatherLon, CityStore.home(act))) return;
        boolean ok = false;
        if (o != null) {
            try {
                showWeather(o);
                weatherPrefs().edit().putString("json", json).putLong("at", System.currentTimeMillis()).apply();
                ok = true;
            } catch (Exception ignored) {
            }
        }
        // Still on screen after hours without a network: an old reading is not passed off as the weather now.
        if (!ok && weatherStale()) showNoWeather();
        // Still fresh: shown again, so the rain line ("Rain from 18:00") follows the clock through failed retries.
        else if (!ok) showCachedWeather();
        // A reading that arrives while the Home shows itself offline (a network whose validation probe fails, a
        // filtering DNS) does not clear the offline look: that follows Android's validation alone (OfflineGate), and
        // a fetch is not proof enough to override it for the next minutes. The hint stays until validation returns.
        handler.removeCallbacks(weatherTick);
        if (!resumed) return;
        if (ok) {
            weatherRetryMs = WEATHER_RETRY_MIN_MS;
            handler.postDelayed(weatherTick, WEATHER_EVERY_MS);
        } else {
            handler.postDelayed(weatherTick, weatherRetryMs);
            weatherRetryMs = Math.min(weatherRetryMs * 2, WEATHER_RETRY_MAX_MS);
        }
    }

    /** Plain symbols in text presentation (U+FE0E), white like the rest of the header, not colour emoji. */
    private static String symbol(int code, boolean day) {
        if (code <= 1) return day ? "☀︎" : "☾";
        if (code == 2) return day ? "⛅︎" : "☁︎";
        if (code == 3) return "☁︎";
        if (code == 45 || code == 48) return "≡";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "☂︎";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄︎";
        if (code >= 95) return "⚡︎";
        return "☁︎";
    }

    private String describe(int code) {
        int id = sky(code);
        return id == 0 ? "" : act.getString(id);
    }

    /** The sky in words for a WMO weather code, or 0 for a code it has no word for. */
    private static int sky(int code) {
        if (code == 0) return R.string.weather_clear;
        if (code == 1) return R.string.weather_mostly_clear;
        if (code == 2) return R.string.weather_partly_cloudy;
        if (code == 3) return R.string.weather_overcast;
        if (code == 45 || code == 48) return R.string.weather_fog;
        if (code >= 51 && code <= 57) return R.string.weather_drizzle;
        if (code >= 61 && code <= 67) return R.string.weather_rain;
        if (code >= 80 && code <= 82) return R.string.weather_showers;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.string.weather_snow;
        if (code >= 95) return R.string.weather_thunderstorm;
        return 0;
    }

    private void openWeatherApp() {
        if (weatherApp.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(weatherApp);
        List<ResolveInfo> found = act.getPackageManager().queryIntentActivities(i, 0);
        if (found.isEmpty() || found.get(0).activityInfo == null) {
            Toast.makeText(act, act.getString(R.string.cant_open, act.getString(R.string.weather_name)), Toast.LENGTH_SHORT).show();
            return;
        }
        i.setComponent(new ComponentName(weatherApp, found.get(0).activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            act.startActivity(i);
            backToWeather = true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(act, act.getString(R.string.cant_open, act.getString(R.string.weather_name)), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- data meter ---------------------------------------------------------------------------------

    /** The data meter's line for the Wi-Fi dropdown (MainActivity.showWifiPopup), or null. */
    CharSequence dataLine() {
        return meter != null ? meter.line() : null;
    }

    boolean dataDetail() {
        return meter != null && meter.hasDetail();
    }

    /**
     * OK on the meter's line in the Wi-Fi dropdown: its detail under the same pill. {@code back} reopens the dropdown
     * on that line when the detail is closed while Home stays in front (Back goes up one level at a time).
     */
    void showDataDetail(View anchor, int gravity, Runnable back) {
        if (meter != null && resumed) meter.showDetail(anchor, gravity, back);
    }

    /** MainActivity's network callback: the data meter's ledger notes which hotspot the bytes so far went through. */
    void onNetworkChanged() {
        if (meter != null) meter.onNetworkChanged();
    }

    // ---- lifecycle -----------------------------------------------------------------------------------

    /**
     * Off the network on Home, ask for a Wi-Fi scan every 45 s: Android joins any saved network the results show,
     * without a password, and on its own it backs off to a scan every few minutes. Not when another network
     * (Ethernet) already carries the TV.
     */
    private final Runnable wifiNudge = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, WIFI_NUDGE_EVERY_MS);
            Context app = act.getApplicationContext();
            android.net.ConnectivityManager cm = app.getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return;
            android.net.wifi.WifiManager wm = app.getSystemService(android.net.wifi.WifiManager.class);
            if (wm == null || !wm.isWifiEnabled()) return;
            try {
                wm.startScan();
            } catch (SecurityException ignored) {
            }
        }
    };

    void onResume() {
        resumed = true;
        handler.removeCallbacks(wifiNudge);
        handler.postDelayed(wifiNudge, 5_000L);
        if (watchNext != null) watchNext.onResume();
        if (meter != null) meter.onResume();
        if (player != null) {
            memory.addListener(playerChanged, handler);
            refreshPlayer();
            player.onShown(true);
            onUserKey();
        }
        if (weather == null || askCity) return;
        // Left on screen for hours and back: what is shown may have aged past showing.
        if (weatherStale()) showNoWeather();
        // A test rain note (TestHooks) whose hook went with ADB while Home was away: the reading's own note again.
        else if (rainHooked && !offline) showCachedWeather();
        scheduleWeather();
    }

    void onPause() {
        resumed = false;
        homeFocus.release();
        handler.removeCallbacks(weatherTick);
        handler.removeCallbacks(wifiNudge);
        if (watchNext != null) watchNext.onPause();
        if (meter != null) meter.onPause();
        if (player != null) {
            memory.removeListener(playerChanged);
            player.onShown(false);
            handler.removeCallbacks(playerIdle);
        }
    }

    void onDestroy() {
        destroyed = true;
        resumed = false;
        homeFocus.release();
        root.getViewTreeObserver().removeOnGlobalLayoutListener(homeLayout);
        root.getViewTreeObserver().removeOnGlobalFocusChangeListener(homeFocusMoved);
        rowsBox.getViewTreeObserver().removeOnPreDrawListener(placeGlows);
        FocusGlow.unpin(tileGlowKey);
        tileGlowKey = 0;
        if (meter != null) meter.onDestroy();
        if (player != null) {
            memory.removeListener(playerChanged);
            player.onShown(false);
            FocusGlow.unpin(playerGlowKey);
        }
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        netIo.shutdownNow();
        weatherIo.shutdownNow();
        if (watchNext != null) watchNext.destroy();
    }
}
