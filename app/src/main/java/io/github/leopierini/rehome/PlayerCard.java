package io.github.leopierini.rehome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * category_player: the player above "Media": one wide card, the picture on the left (always
 * a 16:9 slot; a square cover sits on a blurred copy of itself), the player in the middle (app and when, title,
 * subtitle, progress with its times on the bar's line, then ⏮ ⏯ ⏭ or "Resume", both from the title's left edge and
 * pinned to the column's bottom, so the main button stays put across items), and what is known about it on the
 * right. Several items: Left and Right page through them (Nav), with dots in the card's top-right corner, faint ‹ ›
 * outside its edges while it is focused, and a short slide of the content the way the owner pressed. No label line
 * above it. Its buttons are the Home's pills
 * (TopButton, TopButtonIcon).
 *
 * <p>OLED: glass buttons, never filled white; the card's tint is faint and only its focused look glows. Text ≥ 14sp.
 * Everything heavy (decoding, blurring, averaging a colour) is done by the host off the main thread (Art).
 *
 * <p><b>Spacing</b> (lines were glued; the card's height unchanged), on a 4dp grid, the same for every
 * kind (song with bar, video with or without bar, two-line title, series with three lines, Netflix). A gap is measured
 * on the ink, as the eye sees it: from the lowest point the upper line's letters can reach (the descender of g p y; a
 * digits-only line ends on its baseline) to the capitals of the line below. Outfit's line box has ~0.32em of empty
 * room over the capitals and ~0.06em under the descenders, so each line's top margin takes them off (Ink, measured on
 * the paint: no font number is written here).
 * <pre>
 *   card inner padding           16 top = 16 bottom (PAD_DP)
 *   picture                      16:9, its top on the top line, its bottom on the bottom line
 *   app line (icon 16dp)         its first ink (the icon's top) on the top line; name and state centred on the icon
 *   app line   → title           12 (GAP_HEAD_DP)
 *   title      → subtitle         4 (GAP_PAIR_DP: artist, channel, episode name)
 *   subtitle   → third line       4 (GAP_PAIR_DP: a series' "Season 4, episode 10")
 *   last text  → bar/times line  12 (GAP_BAR_DP; the bar centred on the digits' capitals)
 *   last line  → buttons        ≥16 (GAP_BUTTONS_DP; the spacer takes the rest, so the buttons sit at the same y on
 *                                    every kind)
 *   buttons    → card edge       16 (the bottom padding; the focus ring is inside the 40dp pill)
 *   right column                 top-aligned: its first line's capitals on the title's; facts 12 apart (GAP_FACT_DP);
 *                                value → secondary 4 (GAP_PAIR_DP: 2 is off the grid and let a g/p touch the capitals)
 *   pager dots                   centred on the app line; the long-OK hint's ink ends on the bottom line
 * </pre>
 * A song's five lines fill the 142dp column: 16 + 12 + title 20sp + 4 + subtitle 16sp + 12 + times + 16 + 40 = 141.
 * To get there: title 22 → 20sp, buttons 42 → 40dp, app icon 18 → 16dp; the subtitle stays 16sp. A two-line title's
 * lines are GAP_PAIR_DP apart too (titleLead), and it is used only where it still leaves GAP_BUTTONS_DP (twoLinesFit):
 * without a bar, with a subtitle and no third line, it fits with 1dp to spare.
 */
@SuppressLint("ViewConstructor")
final class PlayerCard extends LinearLayout {

    static final int PREV = 0, PLAY_PAUSE = 1, NEXT = 2;

    interface Host {
        void up();

        void down();

        /** OK on Resume (or on ⏯ of a song whose session is gone). */
        void resume(PlayerPick.Item i);

        /** ⏮ ⏯ ⏭ of a song whose session lives. */
        void control(PlayerPick.Item i, int button);

        /** Long OK: the item's menu ("Remove from player"). */
        void menu(PlayerPick.Item i, View anchor);

        String label(String pkg);

        /** The app's own banner or icon, for an item without a picture; may be null. */
        Drawable appArt(String pkg);

        /** The app's icon (18dp beside the app line); may be null. */
        Drawable appIcon(String pkg);
    }

    /** One item's picture, prepared off the main thread by the host (decode, blur(), average colour). */
    static final class Art {
        Bitmap image, blur;
        /** RGB average of the picture (FocusLook.averageColor), or of the app's banner. */
        int color = 0x808080;
        boolean square;
        /** The app's icon for the app line, loaded with the picture (a PackageManager call: not on main). */
        Drawable icon;
    }

    /** Left and Right: pure, tested (PlayerCardNavTest). Buttons: SONG 0 ⏮ 1 ⏯ 2 ⏭; others 0; -1 the main one. */
    static final class Nav {
        private Nav() {}

        static int[] left(int item, int button, int kind, int count) {
            if (kind == PlayerPick.SONG) {
                int b = button < 0 ? PLAY_PAUSE : button;
                if (b > 0) return new int[]{item, b - 1};
                return item > 0 ? new int[]{item - 1, -1} : new int[]{item, 0};
            }
            return item > 0 ? new int[]{item - 1, -1} : new int[]{item, button};
        }

        /**
         * Which item shows after new items arrive: the newest (0) while the player is not focused, so Home always
         * opens on what played last; while focused, the one the owner is on (by key), 0 if it is gone.
         */
        static int shown(List<String> keys, String key, boolean focused) {
            if (!focused || key == null) return 0;
            int at = keys.indexOf(key);
            return Math.max(0, at);
        }

        static int[] right(int item, int button, int kind, int count) {
            if (kind == PlayerPick.SONG) {
                int b = button < 0 ? PLAY_PAUSE : button;
                if (b < NEXT) return new int[]{item, b + 1};
                return item < count - 1 ? new int[]{item + 1, -1} : new int[]{item, NEXT};
            }
            return item < count - 1 ? new int[]{item + 1, -1} : new int[]{item, button};
        }
    }

    /**
     * "Blurred": the cover brought down in halving steps (each one filtered, so no detail aliases into blocks) to about
     * 48 px, box-blurred three times (PlayerColors.boxBlur, close to a Gaussian), and drawn back up filtered. The old
     * scale-only blur (16×9 and back) showed a mosaic on the TV. Host thread.
     */
    static Bitmap blur(Bitmap src) {
        Bitmap cur = src;
        try {
            while (cur.getWidth() > 96 && cur.getHeight() > 96) {
                Bitmap half = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
                if (cur != src) cur.recycle();
                cur = half;
            }
            int w = Math.max(1, Math.round(48f * cur.getWidth() / Math.max(cur.getWidth(), cur.getHeight())));
            int h = Math.max(1, Math.round(48f * cur.getHeight() / Math.max(cur.getWidth(), cur.getHeight())));
            Bitmap small = Bitmap.createScaledBitmap(cur, w, h, true);
            if (cur != src && cur != small) cur.recycle();
            int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            if (small != src) small.recycle();
            for (int k = 0; k < 3; k++) px = PlayerColors.boxBlur(px, w, h, 3);
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Brighter than the first build: on a light wallpaper the dim lines read poorly. */
    static final int TEXT = 0xFFFFFFFF, DIM = 0xD9FFFFFF, FAINT = 0xB3FFFFFF;
    /** A fact's second line ("Track 3 of 12", "for 31 min"): under its value, smaller and greyer. */
    private static final int SECONDARY = 0x8CFFFFFF;
    /** The header's text shadow, on the card's words too: they sit on a wallpaper seen through glass. */
    static final int SHADOW = 0x59000000;
    private static final long TICK_MS = 1000;
    /** A page change: the content slides this far in from the side pressed, fading in. */
    private static final int SLIDE_DP = 24;
    private static final long SLIDE_MS = 120;
    /** The card's own glow (not a tile's): tighter and lighter, it is a large area. */
    private static final float CARD_GLOW_DP = 20;
    private static final int CARD_GLOW_ALPHA = 153;
    private static final int STROKE = 0x3DFFFFFF, STROKE_FOCUSED = 0x99FFFFFF;
    /** Resume and the round ⏮ ⏯ ⏭: 40dp, 2dp under the header's pills (TopButton 42dp), so a song fits (Spacing). */
    private static final int PILL_DP = 40;
    /** The spacing system: see the class comment. All in dp, all on the 4dp grid. */
    private static final int PAD_DP = 16, APP_ROW_DP = 16;
    /** The app line's icon, dp: the host keeps an app's icon at this size (CategoryHome.playerIcon). */
    static final int ICON_DP = APP_ROW_DP;
    private static final int GAP_HEAD_DP = 12, GAP_PAIR_DP = 4, GAP_BAR_DP = 12, GAP_BUTTONS_DP = 16, GAP_FACT_DP = 12;
    private static final int BAR_DP = 4;

    private final Activity act;
    private final Host host;
    /** The card's words (PlayerText), in the UI's language. */
    private final Words words;
    private final float d;
    private final Typeface regular, medium;
    private final int cardH, imgW, imgH, factsW;

    private final TextView pager, removeHint;
    /** False while the facts reach the long-OK hint: it stays hidden for that item (updateHint). */
    private boolean hintRoom = true;
    private final ImageView prevArrow, nextArrow;
    private final FrameLayout card;
    private final LinearLayout inner;
    private final FocusGlow glow;
    private final GradientDrawable glass, tint, veil, slotFill;
    private final ImageView blurView, image, appIcon;
    private final TextView appLine, title, sub, season, timeLeft, timeRight;
    /** The state after the app's name, plain ("Playing" with a dot in the app's colour, "Paused", "yesterday"). */
    private final TextView chip;
    private final GradientDrawable chipDot;
    private final Bar bar;
    private final LinearLayout barRow, buttons, facts;
    /** Each line's empty room over its capitals and under its lowest ink, px (Ink). */
    private final Ink appInk, chipInk, titleInk, subInk, seasonInk, timeInk, hintInk;
    /** The title's line spacing (negative): a two-line title is that much less than two font boxes. */
    private final int titleLead;
    private final View divider;
    private final Map<String, Drawable> icons = new java.util.HashMap<>();
    private final TextView[] songButtons = new TextView[3];
    private final Icon playPauseIcon;
    private final TextView resumeButton;

    /** The right column's facts as built last (bindWords), "" for none. */
    private String factsShown = "";
    private List<PlayerPick.Item> items = Collections.emptyList();
    private Map<String, Art> arts = Collections.emptyMap();
    private int index;
    private boolean offline, shown, ticking, idle, animate = true;
    private Set<String> netApps = Collections.emptySet();
    private long glowKey;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Each item's position as the last refresh gave it, and when (elapsedRealtime): a running song counts from it. */
    private final Map<String, Long> basePos = new java.util.HashMap<>();
    private long baseAt;
    private final Runnable tick = this::tick;
    private final android.graphics.Rect visible = new android.graphics.Rect();
    /** Scrolled back into view while a song plays: its bar moves again (it stops while the card is off screen). */
    private final android.view.ViewTreeObserver.OnScrollChangedListener scrolled = () -> {
        if (shown && !ticking && !idle) startTick();
    };
    /** "adesso", "5 min fa": the app line (and what the facts leave out) moves on with the clock while Home shows. */
    private final Runnable minute = new Runnable() {
        @Override
        public void run() {
            if (!shown) return;
            PlayerPick.Item i = current();
            if (i != null) bindWords(i, System.currentTimeMillis());
            main.postDelayed(this, untilNextMinute());
        }
    };
    private float slideFrom;
    private long slideStart;
    private final Runnable slideStep = new Runnable() {
        @Override
        public void run() {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - slideStart) / (float) SLIDE_MS);
            float e = 1f - (1f - t) * (1f - t);
            inner.setTranslationX(slideFrom * (1f - e));
            inner.setAlpha(0.35f + 0.65f * e);
            if (t < 1f) inner.postOnAnimation(this);
        }
    };
    /** Focus left the player: it shows the newest item again (Nav.shown), after the move that took the focus. */
    private final Runnable left = () -> {
        if (hasFocus() || index == 0 || items.isEmpty()) return;
        index = 0;
        bind();
    };

    /**
     * @param width     the card's width
     * @param cardH     the card's height (kept as the owner approved it)
     * @param below     the space under the card, before the next row's title (16dp to its letters with the title's
     *                  own top room)
     * @param leftInset the card's inner left padding: the picture's left edge on the tiles', the titles' and the
     *                  clock's line
     */
    PlayerCard(Activity act, int width, int cardH, int below, int leftInset, Typeface regular, Typeface medium,
               Host host) {
        super(act);
        this.act = act;
        this.host = host;
        this.words = Words.of(act.getResources());
        this.regular = regular;
        this.medium = medium;
        d = act.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(0, 0, 0, below);

        this.cardH = cardH;
        int pad = dp(PAD_DP);
        imgH = cardH - 2 * pad;
        imgW = Math.round(imgH * 16f / 9f);
        factsW = Math.round(width * 0.30f);

        card = new FrameLayout(act);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        glow = new FocusGlow(dp(14), dp(CARD_GLOW_DP), FocusLook.GLOW);
        glow.setFalloff(FocusLook.TILE_GLOW_POWER);
        glow.setMaskSize(width, cardH);
        glow.setAlpha(0);
        // A dark layer under the glass: the words stay readable on a light wallpaper.
        GradientDrawable dark = new GradientDrawable();
        dark.setCornerRadius(dp(14));
        dark.setColor(0x59000000);
        glass = new GradientDrawable();
        glass.setCornerRadius(dp(14));
        glass.setColor(0x1FFFFFFF);
        glass.setStroke(Math.max(1, dp(0.5f)), STROKE);
        tint = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0, 0, 0});
        tint.setCornerRadius(dp(14));
        card.setBackground(new LayerDrawable(new Drawable[]{glow, dark, glass, tint}));
        veil = new GradientDrawable();
        veil.setCornerRadius(dp(14));
        veil.setColor(0xFF000000);
        veil.setAlpha(0);
        card.setForeground(veil);
        addView(card, new LayoutParams(width, cardH));

        // The same padding top and bottom (Spacing): the picture, the app line and the buttons' bottom sit on its lines.
        inner = new LinearLayout(act);
        inner.setOrientation(HORIZONTAL);
        inner.setPadding(leftInset, pad, pad, pad);
        inner.setGravity(Gravity.TOP);
        inner.setClipChildren(false);
        inner.setClipToPadding(false);
        card.addView(inner, new FrameLayout.LayoutParams(width, cardH));

        // Several items: dots only (no "1 / N", owner), small, in the card's top-right corner, centred on the app line:
        // the dots' middle is ~10dp under their box's top (a fallback font's glyph; measured on the TV), the icon's 8dp
        // under the top line.
        pager = text(14, DIM, regular);
        FrameLayout.LayoutParams pagerLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        pagerLp.topMargin = pad - dp(2);
        pagerLp.rightMargin = pad;
        card.addView(pager, pagerLp);

        // ‹ › just outside the card's edges, only while it is focused and only towards an item that exists.
        prevArrow = arrow(Chevron.LEFT);
        nextArrow = arrow(Chevron.RIGHT);
        FrameLayout.LayoutParams prevLp = new FrameLayout.LayoutParams(dp(20), dp(32),
                Gravity.CENTER_VERTICAL | Gravity.START);
        prevLp.leftMargin = -dp(26);
        card.addView(prevArrow, prevLp);
        FrameLayout.LayoutParams nextLp = new FrameLayout.LayoutParams(dp(20), dp(32),
                Gravity.CENTER_VERTICAL | Gravity.END);
        nextLp.rightMargin = -dp(26);
        card.addView(nextArrow, nextLp);

        // Long OK is not discoverable: said, small and dim, in the corner while the card is focused.
        removeHint = text(14, FAINT, regular);
        removeHint.setText(R.string.player_remove_hint);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        hintInk = new Ink(removeHint, false);
        hintLp.bottomMargin = pad - hintInk.bottom;
        hintLp.rightMargin = pad;
        removeHint.setVisibility(INVISIBLE);   // laid out even when unseen, so hintRoom can measure it
        card.addView(removeHint, hintLp);
        // The facts column can grow down to the hint (a song's Album, Year, Genre, Heard): then the hint steps aside.
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateHint());

        // The picture: a 16:9 slot with rounded corners; a square cover over its own blur.
        FrameLayout slot = new FrameLayout(act);
        slot.setOutlineProvider(rounded(dp(8)));
        slot.setClipToOutline(true);
        slotFill = new GradientDrawable();
        slotFill.setColor(0xFF14161A);
        slot.setBackground(slotFill);
        blurView = new ImageView(act);
        blurView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // Faint on the dark slot: a pale cover's blur would otherwise fill the slot with a bright area (OLED).
        blurView.setAlpha(0.3f);
        slot.addView(blurView, new FrameLayout.LayoutParams(imgW, imgH));
        image = new ImageView(act);
        slot.addView(image, new FrameLayout.LayoutParams(imgW, imgH, Gravity.CENTER));
        inner.addView(slot, new LinearLayout.LayoutParams(imgW, imgH));

        // The player: its lines from the picture's top, the buttons pinned to the bottom under a spacer, so a long
        // title never pushes them and the main button sits in the same place for every item.
        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(VERTICAL);
        mid.setGravity(Gravity.TOP);
        mid.setPadding(dp(20), 0, dp(20), 0);
        mid.setClipChildren(false);
        mid.setClipToPadding(false);
        // The app line: a row exactly as tall as its 16dp icon (its ink), the name's and the state's ink centred on it
        // and on one baseline; their empty font room hangs outside the row, unclipped (Spacing).
        LinearLayout appRow = new LinearLayout(act);
        appRow.setGravity(Gravity.TOP);
        appRow.setBaselineAligned(false);
        appRow.setClipChildren(false);
        appRow.setClipToPadding(false);
        appIcon = new ImageView(act);
        appIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        appIcon.setOutlineProvider(rounded(dp(4)));
        appIcon.setClipToOutline(true);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(APP_ROW_DP), dp(APP_ROW_DP));
        iconLp.rightMargin = dp(8);
        appRow.addView(appIcon, iconLp);
        appLine = text(16, DIM, regular);
        appInk = new Ink(appLine, false);
        LinearLayout.LayoutParams appLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        appLp.topMargin = (dp(APP_ROW_DP) - appInk.height) / 2 - appInk.top;
        appRow.addView(appLine, appLp);
        // The state as plain muted words after the name, nothing like a button (a pill read as one).
        chip = text(14, 0x99FFFFFF, regular);
        chipInk = new Ink(chip, false);
        chip.setCompoundDrawablePadding(dp(4));
        chipDot = new GradientDrawable();
        chipDot.setShape(GradientDrawable.OVAL);
        chipDot.setSize(dp(6), dp(6));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.leftMargin = dp(6);
        // On the name's baseline.
        chipLp.topMargin = appLp.topMargin + appInk.ascent - chipInk.ascent;
        appRow.addView(chip, chipLp);
        mid.addView(appRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(APP_ROW_DP)));
        title = text(20, TEXT, medium);
        titleInk = new Ink(title, false);
        // A two-line title's lines GAP_PAIR_DP apart on the ink, as title and subtitle are (only between lines: the
        // last line gets no extra, so a one-line title is unchanged).
        titleLead = dp(GAP_PAIR_DP) - titleInk.top - titleInk.bottom;
        title.setLineSpacing(titleLead, 1f);
        mid.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        sub = text(16, DIM, regular);
        subInk = new Ink(sub, false);
        mid.addView(sub, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        season = text(14, FAINT, regular);
        seasonInk = new Ink(season, false);
        season.setVisibility(GONE);
        mid.addView(season, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // "1:12 ━━━ 2:44": the times on the bar's own line, one line less than under it; the bar
        // centred on the digits' capitals, not on their font box.
        barRow = new LinearLayout(act);
        barRow.setGravity(Gravity.TOP);
        barRow.setBaselineAligned(false);
        timeLeft = text(14, FAINT, regular);
        timeInk = new Ink(timeLeft, true);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        leftLp.rightMargin = dp(12);
        barRow.addView(timeLeft, leftLp);
        bar = new Bar(act, dp(2));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(BAR_DP), 1);
        barLp.topMargin = timeInk.top + (timeInk.cap - dp(BAR_DP)) / 2;
        barRow.addView(bar, barLp);
        timeRight = text(14, FAINT, regular);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rightLp.leftMargin = dp(12);
        barRow.addView(timeRight, rightLp);
        mid.addView(barRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mid.addView(new View(act), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // The buttons' row is as tall as a pill: never squeezed by the lines above (the focus ring was
        // cut to "( )" when it got less).
        buttons = new LinearLayout(act);
        buttons.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        // At least GAP_BUTTONS_DP over the buttons, whatever is above them (space()); more room goes to the spacer, so
        // the buttons stay pinned to the bottom (they were too close to "Mara Vale" without a bar).
        mid.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PILL_DP)));
        inner.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // All four buttons are made and added once; an item shows its own (showButtons). Removing a focused view
        // sends the focus away first (it went to the rows and came back: the TV check's jumping focus).
        playPauseIcon = new Icon(Icon.PAUSE, d, 22);
        Icon[] icons = {new Icon(Icon.PREV, d, 22), playPauseIcon, new Icon(Icon.NEXT, d, 22)};
        for (int b = 0; b < 3; b++) {
            TextView t = pill(R.style.TopButtonIcon);
            // The 22dp icon centred in the 40dp circle (the style's 10dp sides are for its 42dp one).
            t.setPaddingRelative(dp((PILL_DP - 22) / 2f), 0, dp((PILL_DP - 22) / 2f), 0);
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(icons[b], null, null, null);
            hook(t, b);
            songButtons[b] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(PILL_DP), dp(PILL_DP));
            if (b > 0) lp.leftMargin = dp(14);
            buttons.addView(t, lp);
        }
        resumeButton = pill(R.style.TopButton);
        resumeButton.setText(R.string.player_resume);
        resumeButton.setCompoundDrawablesRelativeWithIntrinsicBounds(new Icon(Icon.PLAY, d, 18), null, null, null);
        hook(resumeButton, 0);
        buttons.addView(resumeButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(PILL_DP)));

        divider = new View(act);
        divider.setBackgroundColor(0x1AFFFFFF);
        // The divider from the top line to the bottom line, as the picture; the facts 20dp from it, as the player's
        // words are on its other side; their first capitals on the title's (app line + GAP_HEAD_DP under the top line).
        inner.addView(divider, new LinearLayout.LayoutParams(Math.max(1, dp(1)), imgH));
        facts = new LinearLayout(act);
        facts.setOrientation(VERTICAL);
        facts.setGravity(Gravity.TOP);
        facts.setPadding(dp(20), dp(APP_ROW_DP + GAP_HEAD_DP), 0, 0);
        inner.addView(facts, new LinearLayout.LayoutParams(factsW, imgH));

        setVisibility(GONE);
    }

    /** Whether page changes slide (the Home's animations setting). */
    void setAnimate(boolean on) {
        animate = on;
    }

    /** The glow's mask made on the host's thread before the first focus (FocusGlow.prepare); returns its pin key. */
    long pinGlow() {
        glowKey = glow.pin();
        return glowKey;
    }

    void prepareGlow() {
        glow.prepare();
    }

    // ---- data ----

    /**
     * New items (PlayerPick.merge) and their pictures. Not focused, the newest shows; focused, the one the owner is on
     * stays (Nav.shown). The views are updated in place: no button is removed, so the focus stays where it is.
     */
    void setItems(List<PlayerPick.Item> next, Map<String, Art> art) {
        String was = current() != null ? current().key : null;
        items = next == null ? Collections.emptyList() : new ArrayList<>(next);
        arts = art == null ? Collections.emptyMap() : art;
        baseAt = SystemClock.elapsedRealtime();
        basePos.clear();
        for (PlayerPick.Item i : items) basePos.put(i.key, i.position);
        List<String> keys = new ArrayList<>();
        for (PlayerPick.Item i : items) keys.add(i.key);
        index = Nav.shown(keys, was, hasFocus());
        if (items.isEmpty()) {
            setVisibility(GONE);
            stopTick();
            return;
        }
        setVisibility(VISIBLE);
        bind();
    }

    boolean isEmpty() {
        return items.isEmpty();
    }

    /** Memory pressure, Home hidden: the pictures go (the next refresh brings them back), the words stay. */
    void dropArt() {
        arts = Collections.emptyMap();
        image.setImageDrawable(null);
        blurView.setImageDrawable(null);
    }

    void setOffline(boolean off, Set<String> needNet) {
        offline = off;
        netApps = needNet == null ? Collections.emptySet() : needNet;
        applyVeil();
    }

    /** Focus on the shown item's main button; false when there is nothing to focus. */
    boolean takeFocus() {
        if (items.isEmpty() || getVisibility() != VISIBLE) return false;
        return focusButton(-1);
    }

    /** Whether the player is on screen (Home in front): the progress of a playing song moves only then. */
    void onShown(boolean visible) {
        if (visible == shown) return;
        shown = visible;
        main.removeCallbacks(minute);
        if (visible) {
            getViewTreeObserver().addOnScrollChangedListener(scrolled);
            startTick();
            PlayerPick.Item i = current();
            if (i != null) bindWords(i, System.currentTimeMillis());
            main.postDelayed(minute, untilNextMinute());
        } else {
            getViewTreeObserver().removeOnScrollChangedListener(scrolled);
            stopTick();
        }
    }

    /**
     * No key for a while (the host's idle timer): the bar stops moving, so the picture is still and the Philips
     * screensaver can protect the OLED panel; any key brings it back.
     */
    void setIdle(boolean on) {
        if (idle == on) return;
        idle = on;
        if (on) {
            stopTick();
            PlayerPick.Item i = current();
            if (i != null) bindTimes(i);   // its running time hidden (PlayerText.timesHidden)
        } else if (shown) {
            PlayerPick.Item i = current();
            if (i != null) {
                catchUp(i);
                bindTimes(i);
            }
            startTick();
        }
    }

    private static long untilNextMinute() {
        return 60_000L - System.currentTimeMillis() % 60_000L + 50;
    }

    private PlayerPick.Item current() {
        return items.isEmpty() ? null : items.get(Math.max(0, Math.min(index, items.size() - 1)));
    }

    private void bind() {
        PlayerPick.Item i = current();
        if (i == null) return;
        long now = System.currentTimeMillis();
        Art a = arts.get(i.key);
        int color = a != null ? a.color : 0x808080;
        // Picture: a video frame fills the slot; a square cover sits in it on its own blur; none, the app's banner
        // fills the slot as on its tile (rather than a small banner on a dark slot); an app with only a
        // square icon keeps it small, with rounded corners, on the dark slot.
        if (a != null && a.image != null) {
            image.setImageBitmap(a.image);
            image.setScaleType(a.square ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) image.getLayoutParams();
            lp.width = a.square ? Math.round(imgH * 0.84f) : imgW;
            lp.height = a.square ? Math.round(imgH * 0.84f) : imgH;
            image.setLayoutParams(lp);
            image.setElevation(a.square ? dp(6) : 0);
            image.setClipToOutline(false);
            image.setAlpha(1f);
            if (a.square && a.blur != null) {
                BitmapDrawable bd = new BitmapDrawable(getResources(), a.blur);
                bd.setFilterBitmap(true);
                blurView.setImageDrawable(bd);
                blurView.setVisibility(VISIBLE);
            } else {
                blurView.setImageDrawable(null);
                blurView.setVisibility(GONE);
            }
        } else {
            Drawable art = host.appArt(i.pkg);
            image.setImageDrawable(art);
            if (art != null && PlayerColors.fillsSlot(art.getIntrinsicWidth(), art.getIntrinsicHeight())) {
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) image.getLayoutParams();
                lp.width = imgW;
                lp.height = imgH;
                image.setLayoutParams(lp);
                image.setElevation(0);
                image.setClipToOutline(false);
                image.setAlpha(1f);
                blurView.setImageDrawable(null);
                blurView.setVisibility(GONE);
            } else {
                image.setScaleType(ImageView.ScaleType.FIT_XY);
                // The icon's own shape inside 42% of the slot, so the rounded clip falls on its corners.
                int boxW = Math.round(imgW * 0.42f), boxH = Math.round(imgH * 0.42f);
                int w = boxW, h = boxH;
                if (art != null && art.getIntrinsicWidth() > 0 && art.getIntrinsicHeight() > 0) {
                    float k = Math.min(boxW / (float) art.getIntrinsicWidth(), boxH / (float) art.getIntrinsicHeight());
                    w = Math.round(art.getIntrinsicWidth() * k);
                    h = Math.round(art.getIntrinsicHeight() * k);
                }
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) image.getLayoutParams();
                lp.width = w;
                lp.height = h;
                image.setLayoutParams(lp);
                image.setElevation(0);
                image.setOutlineProvider(rounded(dp(6)));
                image.setClipToOutline(true);
                image.setAlpha(0.85f);
                blurView.setImageDrawable(null);
                blurView.setVisibility(GONE);
            }
        }
        // The card takes the picture's colour, faint (OLED), and its glow the same hue as a tile's would.
        tint.setColors(new int[]{(0x4D << 24) | color, (0x1F << 24) | color, 0x0DFFFFFF});
        glow.setColor(FocusLook.tileGlow(color));
        Drawable icon = a != null && a.icon != null ? a.icon : icons.get(i.pkg);
        if (icon == null && !icons.containsKey(i.pkg)) {
            icon = host.appIcon(i.pkg);
            icons.put(i.pkg, icon);
        }
        appIcon.setImageDrawable(icon);
        appIcon.setVisibility(icon == null ? GONE : VISIBLE);

        String app = host.label(i.pkg);
        String t = PlayerText.title(words, i, app);
        title.setText(t);
        String s = PlayerText.sub(words, i);
        String[] series = PlayerText.seriesLines(words, i);
        String third = series == null ? "" : series[1];
        if (!third.isEmpty() && PlayerText.progress(i) >= 0) {
            // A series with a bar has no room for a third line: the season after the episode's name, dimmer, past a
            // 10dp gap, on the same line.
            android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder(s);
            int at = line.length();
            line.append(' ');
            line.setSpan(new GapSpan(dp(5), 0, 0), at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.append(third);
            line.setSpan(new android.text.style.ForegroundColorSpan(FAINT), at + 1, line.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sub.setText(line);
            third = "";
        } else {
            sub.setText(s);
        }
        sub.setVisibility(s.isEmpty() ? GONE : VISIBLE);
        // A series: "Season 4, episode 10" on a line of its own under the episode's name.
        season.setText(third);
        season.setVisibility(third.isEmpty() ? GONE : VISIBLE);
        bindTimes(i);
        bar.setColor(0xFF000000 | brighten(color));
        chipDot.setColor(0xFF000000 | brighten(color));
        playPauseIcon.setShape(i.live && i.playing ? Icon.PAUSE : Icon.PLAY);
        resumeButton.setText(PlayerText.button(words, i, app));
        showButtons(i.kind == PlayerPick.SONG);
        bindWords(i, now);

        if (items.size() > 1) {
            StringBuilder dots = new StringBuilder();
            for (int k = 0; k < items.size(); k++) dots.append(k == 0 ? "" : " ").append(k == index ? "●" : "○");
            pager.setText(dots);
            pager.setVisibility(VISIBLE);
        } else {
            pager.setVisibility(GONE);
        }
        focusLook();
        if (shown) startTick();
    }

    /** What moves with the clock: the app line ("5 min ago") and the facts (which leave out what it says). */
    private void bindWords(PlayerPick.Item i, long now) {
        appLine.setText(host.label(i.pkg));
        chip.setText(PlayerText.when(words, i, now));
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(i.live && i.playing ? chipDot : null, null, null, null);
        List<String[]> rows = PlayerText.facts(words, i, now, TimeZone.getDefault());
        boolean show = PlayerText.showFacts(i, rows);
        facts.setVisibility(show ? VISIBLE : GONE);
        divider.setVisibility(show ? VISIBLE : GONE);
        // The same facts as on screen (most refreshes, and most minutes): their views stay as they are.
        StringBuilder key = new StringBuilder();
        if (show) for (String[] f : rows) key.append(String.join("\u0001", f)).append('\u0002');
        if (key.toString().equals(factsShown)) return;
        factsShown = key.toString();
        facts.removeAllViews();
        if (show) {
            // Each fact's first capitals GAP_FACT_DP under the one above's lowest ink; the first on the facts' top
            // padding, the title's capitals line.
            int above = Integer.MIN_VALUE;
            for (String[] f : rows) {
                TextView[] ends = new TextView[2];
                View row = factRow(f, ends);
                Ink first = new Ink(ends[0], false);
                ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin =
                        (above == Integer.MIN_VALUE ? 0 : dp(GAP_FACT_DP) - above) - first.top;
                above = ends[1] == ends[0] ? first.bottom : new Ink(ends[1], false).bottom;
                facts.addView(row);
            }
        }
    }

    /**
     * ⏮ ⏯ ⏭ for a song, Resume otherwise, both from the title's left edge. When the focused button is about to hide, the
     * new main button takes the focus first, so it never leaves the card on the way.
     */
    private void showButtons(boolean song) {
        View focused = findFocus();
        View main = song ? songButtons[PLAY_PAUSE] : resumeButton;
        for (int b = 0; b < 3; b++) if (song) songButtons[b].setVisibility(VISIBLE);
        if (!song) resumeButton.setVisibility(VISIBLE);
        if (focused != null && focused.getParent() == buttons && (song ? focused == resumeButton : focused != resumeButton)) {
            main.requestFocus();
        }
        for (int b = 0; b < 3; b++) if (!song) songButtons[b].setVisibility(GONE);
        if (song) resumeButton.setVisibility(GONE);
    }

    private void bindTimes(PlayerPick.Item i) {
        String[] t = PlayerText.times(words, i);
        float p = PlayerText.progress(i);
        // Idle: a running song's times would stop while it goes on; hidden, the row keeping its place.
        barRow.setVisibility(p < 0 ? GONE : PlayerText.timesHidden(i, idle) ? INVISIBLE : VISIBLE);
        bar.setProgress(Math.max(0f, p));
        timeLeft.setText(t == null ? "" : t[0]);
        timeRight.setText(t == null ? "" : t[1]);
        timeLeft.setVisibility(t == null || t[0].isEmpty() ? GONE : VISIBLE);
        timeRight.setVisibility(t == null || t[1].isEmpty() ? GONE : VISIBLE);
        space();
        // No bar: the title may take two lines, when they leave the buttons their gap; with a bar,
        // one line keeps the buttons' room.
        boolean two = p < 0 && twoLinesFit();
        if (title.getMaxLines() != (two ? 2 : 1)) {
            title.setSingleLine(!two);
            title.setMaxLines(two ? 2 : 1);
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    /**
     * The middle column's top margins from the lines shown now (Spacing): each line's capitals its gap under the lowest
     * ink of the line above, the buttons GAP_BUTTONS_DP under the last one. Only changed margins ask for a layout (the
     * song's tick calls this every second).
     */
    private void space() {
        int above = 0;   // the app row ends on its ink (the icon's bottom)
        setTop(title, dp(GAP_HEAD_DP) - above - titleInk.top);
        above = titleInk.bottom;
        if (sub.getVisibility() == VISIBLE) {
            setTop(sub, dp(GAP_PAIR_DP) - above - subInk.top);
            above = subInk.bottom;
        }
        if (season.getVisibility() == VISIBLE) {
            setTop(season, dp(GAP_PAIR_DP) - above - seasonInk.top);
            above = seasonInk.bottom;
        }
        if (barRow.getVisibility() != GONE) {   // INVISIBLE (idle) keeps its place
            boolean times = timeLeft.getVisibility() == VISIBLE || timeRight.getVisibility() == VISIBLE;
            int top = times ? timeInk.top : ((LinearLayout.LayoutParams) bar.getLayoutParams()).topMargin;
            setTop(barRow, dp(GAP_BAR_DP) - above - top);
            above = times ? timeInk.bottom : 0;
        }
        setTop(buttons, dp(GAP_BUTTONS_DP) - above);
    }

    private static void setTop(View v, int margin) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if (lp.topMargin == margin) return;
        lp.topMargin = margin;
        v.setLayoutParams(lp);
    }

    /**
     * Whether a two-line title still leaves GAP_BUTTONS_DP over the buttons: the middle column's height (the card's
     * less the inner paddings) against the app line, the title's two lines, the lines under it, their gaps (space(),
     * already set for the lines shown) and the buttons.
     */
    private boolean twoLinesFit() {
        int avail = cardH - 2 * dp(PAD_DP);
        int box = title.getPaint().getFontMetricsInt(null);
        int used = dp(APP_ROW_DP) + topOf(title) + 2 * box + titleLead
                + (sub.getVisibility() == VISIBLE ? topOf(sub) + sub.getLineHeight() : 0)
                + (season.getVisibility() == VISIBLE ? topOf(season) + season.getLineHeight() : 0)
                + topOf(buttons) + dp(PILL_DP);
        // dp(1) of slack: the integer metrics round each line's ink up (Ink), so the gaps they set are already that
        // much wider than the type's own; without it a two-line title with a subtitle missed by 1px.
        return used <= avail + dp(1);
    }

    private static int topOf(View v) {
        return ((ViewGroup.MarginLayoutParams) v.getLayoutParams()).topMargin;
    }

    /**
     * One fact: the label in a fixed 84dp column on the value's baseline, the value (16sp, white) in the second, its
     * secondary line (14sp, greyer) GAP_PAIR_DP under it (ink to capitals). A plot (no label) runs across both columns
     * on up to three lines. ends gets the fact's first and last line, for the gaps between facts (bindWords).
     */
    private View factRow(String[] f, TextView[] ends) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (f[0].isEmpty()) {
            TextView plot = text(15, DIM, regular);
            plot.setSingleLine(false);
            plot.setMaxLines(3);
            plot.setLineSpacing(dp(2), 1f);
            plot.setText(f[1]);
            plot.setLayoutParams(lp);
            ends[0] = plot;
            ends[1] = plot;
            return plot;
        }
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.TOP);
        // The label sits on the value's first line (its baseline), not on the secondary line's.
        row.setBaselineAligned(true);
        TextView k = text(14, FAINT, regular);
        k.setText(f[0]);
        row.addView(k, new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(VERTICAL);
        TextView v = text(16, TEXT, regular);
        v.setSingleLine(false);
        v.setMaxLines(2);
        v.setText(f[1]);
        col.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // After the value is in: the index must name an existing child.
        col.setBaselineAlignedChildIndex(0);
        ends[0] = v;
        ends[1] = v;
        if (f.length > 2 && !f[2].isEmpty()) {
            TextView s2 = text(14, SECONDARY, regular);
            s2.setText(f[2]);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.topMargin = dp(GAP_PAIR_DP) - new Ink(v, false).bottom - new Ink(s2, false).top;
            col.addView(s2, sl);
            ends[1] = s2;
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setLayoutParams(lp);
        return row;
    }

    /** Offline, an app that needs the internet is dimmed as its tile is; focused, it shows in full. */
    private void applyVeil() {
        PlayerPick.Item i = current();
        boolean dim = offline && i != null && netApps.contains(i.pkg) && findFocus() == null;
        veil.setAlpha(dim ? 0x80 : 0);
    }

    /** Focused or not: the glow, a brighter edge, the ‹ › towards items that exist, the long-OK hint, the veil. */
    private void focusLook() {
        boolean f = findFocus() != null;
        glow.setAlpha(f ? CARD_GLOW_ALPHA : 0);
        glass.setStroke(Math.max(1, dp(0.5f)), f ? STROKE_FOCUSED : STROKE);
        prevArrow.setVisibility(f && index > 0 ? VISIBLE : INVISIBLE);
        nextArrow.setVisibility(f && index < items.size() - 1 ? VISIBLE : INVISIBLE);
        removeHint.setVisibility(f && hintRoom ? VISIBLE : INVISIBLE);
        applyVeil();
    }

    /** Whether the hint's top is clear of the facts' last line, in the card's own coordinates. */
    static boolean hintFits(int factsBottom, int hintTop, int gap) {
        return factsBottom + gap <= hintTop;
    }

    /** After every layout (browsing items, a new fact): hide the hint when the facts column would reach it. */
    private void updateHint() {
        boolean room = true;
        int n = facts.getChildCount();
        if (facts.getVisibility() == VISIBLE && n > 0) {
            int bottom = facts.getChildAt(n - 1).getBottom() + facts.getTop();
            for (View p = (View) facts.getParent(); p != null && p != card; p = (View) p.getParent()) bottom += p.getTop();
            room = hintFits(bottom, removeHint.getTop() + hintInk.top, dp(8));
        }
        if (room == hintRoom) return;
        hintRoom = room;
        focusLook();
    }

    // ---- the playing song's progress, while Home shows it and the card is in view ----

    private void startTick() {
        stopTick();
        PlayerPick.Item i = current();
        if (!shown || idle || !PlayerText.runs(i)) return;
        if (!onScreen()) return;   // the scroll listener starts it when the card comes back
        // Back on screen or out of idle: the time it was stopped is counted at once, not at the next refresh.
        catchUp(i);
        bindTimes(i);
        ticking = true;
        main.postDelayed(tick, TICK_MS);
    }

    /** A running song's position now, from the last refresh (PlayerPick.positionAfter). */
    private void catchUp(PlayerPick.Item i) {
        Long base = i == null ? null : basePos.get(i.key);
        if (base == null || !PlayerText.runs(i)) return;
        i.position = PlayerPick.positionAfter(base, baseAt, SystemClock.elapsedRealtime(), i.duration);
    }

    private void stopTick() {
        ticking = false;
        main.removeCallbacks(tick);
    }

    private boolean onScreen() {
        return isShown() && getGlobalVisibleRect(visible) && visible.height() > 0;
    }

    private void tick() {
        PlayerPick.Item i = current();
        if (i == null || !shown || idle) {
            ticking = false;
            return;
        }
        catchUp(i);
        if (!onScreen()) {
            // Scrolled away (focus in a lower row): no timer while nothing shows it; the scroll listener's startTick
            // catches the position up when the card comes back.
            ticking = false;
            return;
        }
        bindTimes(i);
        if (i.position < i.duration) main.postDelayed(tick, TICK_MS);
        else ticking = false;
    }

    // ---- focus and keys ----

    /** A button as the Home's pills are drawn (TopButton / TopButtonIcon: pill_bg, its glass, ring and glow). */
    private TextView pill(int style) {
        TextView t = new TextView(act, null, 0, style);
        t.setTypeface(medium);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setLongClickable(true);
        return t;
    }

    private void hook(TextView t, int which) {
        t.setOnClickListener(v -> {
            PlayerPick.Item i = current();
            if (i == null) return;
            if (i.kind == PlayerPick.SONG && i.live) host.control(i, which);
            else host.resume(i);
        });
        t.setOnLongClickListener(v -> {
            PlayerPick.Item i = current();
            if (i != null) host.menu(i, v);
            return true;
        });
        t.setOnFocusChangeListener((v, has) -> {
            focusLook();
            main.removeCallbacks(left);
            if (!has) main.post(left);
        });
        t.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            PlayerPick.Item i = current();
            if (i == null) return false;
            int button = focusedButton(v);
            int[] to;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    to = Nav.left(index, button, i.kind, items.size());
                    move(to, v, SoundEffectConstants.NAVIGATION_LEFT);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    to = Nav.right(index, button, i.kind, items.size());
                    move(to, v, SoundEffectConstants.NAVIGATION_RIGHT);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    v.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    host.up();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    v.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
                    host.down();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void move(int[] to, View from, int sound) {
        boolean itemChanged = to[0] != index;
        if (!itemChanged && to[1] == focusedButton(from)) return;
        from.playSoundEffect(sound);
        if (itemChanged) {
            int dir = to[0] > index ? 1 : -1;
            index = to[0];
            bind();
            slide(dir);
        }
        focusButton(to[1]);
    }

    /** The new item's content comes in from the side pressed: short, so paging never feels slow. */
    private void slide(int dir) {
        inner.removeCallbacks(slideStep);
        if (!animate) {
            inner.setTranslationX(0);
            inner.setAlpha(1f);
            return;
        }
        // Own frame loop, not ViewPropertyAnimator: this TV runs its animator scale at 0.25, which would cut the
        // owner's ~120 ms to 30 ms.
        slideFrom = dir * dp(SLIDE_DP);
        slideStart = SystemClock.uptimeMillis();
        inner.setTranslationX(slideFrom);
        inner.setAlpha(0.35f);
        inner.postOnAnimation(slideStep);
    }

    /** The button index of a view (SONG 0..2, else 0), -1 when it is none of them. */
    private int focusedButton(View v) {
        if (v == null) return -1;
        for (int b = 0; b < 3; b++) if (v == songButtons[b]) return b;
        return v == resumeButton ? 0 : -1;
    }

    private boolean focusButton(int button) {
        PlayerPick.Item i = current();
        if (i == null) return false;
        View target;
        if (i.kind == PlayerPick.SONG) target = songButtons[button < 0 ? PLAY_PAUSE : Math.min(button, NEXT)];
        else target = resumeButton;
        return target.requestFocus();
    }

    // ---- small pieces ----

    private TextView text(float sp, int color, Typeface face) {
        TextView t = new TextView(act);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setShadowLayer(dp(4), 0, dp(1), SHADOW);
        return t;
    }

    private ImageView arrow(int dir) {
        ImageView v = new ImageView(act);
        v.setImageDrawable(new Chevron(dir, d, FAINT));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setVisibility(INVISIBLE);
        return v;
    }

    private static ViewOutlineProvider rounded(final float r) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        };
    }

    private int dp(float v) {
        return Math.round(v * d);
    }

    /** A dark picture's colour brought up, so the dot and the bar read on the glass. */
    private static int brighten(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int m = Math.max(r, Math.max(g, b));
        if (m >= 170) return rgb & 0xFFFFFF;
        if (m == 0) return 0xAAAAAA;
        float k = 170f / m;
        return (Math.min(255, Math.round(r * k)) << 16) | (Math.min(255, Math.round(g * k)) << 8)
                | Math.min(255, Math.round(b * k));
    }

    /**
     * A line's ink inside its font box (includeFontPadding off), px, from the paint: top = the empty room over the
     * capitals (H), bottom = the room under the lowest ink (the descender of g j p q y; the whole descent for a
     * digits-only line), ascent = box top to baseline, cap and height of the ink.
     */
    static final class Ink {
        final int top, bottom, ascent, cap, height;

        Ink(TextView t, boolean digits) {
            Paint p = t.getPaint();
            Paint.FontMetricsInt fm = p.getFontMetricsInt();
            android.graphics.Rect r = new android.graphics.Rect();
            p.getTextBounds("H", 0, 1, r);
            cap = -r.top;
            ascent = -fm.ascent;
            top = ascent - cap;
            int low = 0;
            if (!digits) {
                p.getTextBounds("gjpqy", 0, 5, r);
                low = r.bottom;
            }
            bottom = Math.max(0, fm.descent - low);
            height = cap + low;
        }
    }

    /** The progress bar: a faint track and the item's colour. */
    private static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private float progress;

        Bar(Activity act, float radius) {
            super(act);
            this.radius = radius;
            track.setColor(0x26FFFFFF);
            fill.setColor(0xFF4AA3FF);
        }

        void setProgress(float p) {
            progress = p;
            invalidate();
        }

        void setColor(int argb) {
            fill.setColor(argb);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawRoundRect(0, 0, w, h, radius, radius, track);
            if (progress > 0) c.drawRoundRect(0, 0, Math.max(h, w * progress), h, radius, radius, fill);
        }
    }

    /** ‹ › drawn as strokes (a fallback font's glyph would not match): the player's page arrows. */
    static final class Chevron extends Drawable {
        static final int LEFT = 0, RIGHT = 1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int dir, w, h;

        Chevron(int dir, float density, int color) {
            this.dir = dir;
            w = Math.round(10 * density);
            h = Math.round(20 * density);
            for (Paint p : new Paint[]{paint, shade}) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * density);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
            }
            paint.setColor(color);
            shade.setColor(SHADOW);
            shade.setStrokeWidth(4f * density);
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }

        @Override
        public void draw(Canvas c) {
            float cx = getBounds().exactCenterX(), cy = getBounds().exactCenterY();
            float m = paint.getStrokeWidth();
            float hw = w / 2f - m, hh = h / 2f - m;
            Path p = new Path();
            float s = dir == LEFT ? 1 : -1;
            p.moveTo(cx + s * hw, cy - hh);
            p.lineTo(cx - s * hw, cy);
            p.lineTo(cx + s * hw, cy + hh);
            c.drawPath(p, shade);
            c.drawPath(p, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** ⏮ ▶ ❚❚ ⏭ drawn as paths: no font or emoji decides their look. */
    static final class Icon extends Drawable {
        static final int PLAY = 0, PAUSE = 1, PREV = 2, NEXT = 3;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        private int shape;

        Icon(int shape, float density, int sizeDp) {
            this.shape = shape;
            size = Math.round(sizeDp * density);
            paint.setColor(0xFFFFFFFF);
        }

        void setShape(int s) {
            if (shape == s) return;
            shape = s;
            invalidateSelf();
        }

        @Override
        public int getIntrinsicWidth() {
            return size;
        }

        @Override
        public int getIntrinsicHeight() {
            return size;
        }

        @Override
        public void draw(Canvas c) {
            float l = getBounds().left, t = getBounds().top, s = getBounds().width();
            Path p = new Path();
            switch (shape) {
                case PLAY:
                    p.moveTo(l + s * 0.22f, t + s * 0.12f);
                    p.lineTo(l + s * 0.88f, t + s * 0.5f);
                    p.lineTo(l + s * 0.22f, t + s * 0.88f);
                    p.close();
                    c.drawPath(p, paint);
                    break;
                case PAUSE:
                    c.drawRoundRect(l + s * 0.2f, t + s * 0.12f, l + s * 0.42f, t + s * 0.88f, s * 0.05f, s * 0.05f, paint);
                    c.drawRoundRect(l + s * 0.58f, t + s * 0.12f, l + s * 0.8f, t + s * 0.88f, s * 0.05f, s * 0.05f, paint);
                    break;
                case PREV:
                    c.drawRect(l + s * 0.14f, t + s * 0.16f, l + s * 0.28f, t + s * 0.84f, paint);
                    p.moveTo(l + s * 0.86f, t + s * 0.16f);
                    p.lineTo(l + s * 0.32f, t + s * 0.5f);
                    p.lineTo(l + s * 0.86f, t + s * 0.84f);
                    p.close();
                    c.drawPath(p, paint);
                    break;
                default:
                    c.drawRect(l + s * 0.72f, t + s * 0.16f, l + s * 0.86f, t + s * 0.84f, paint);
                    p.moveTo(l + s * 0.14f, t + s * 0.16f);
                    p.lineTo(l + s * 0.68f, t + s * 0.5f);
                    p.lineTo(l + s * 0.14f, t + s * 0.84f);
                    p.close();
                    c.drawPath(p, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
