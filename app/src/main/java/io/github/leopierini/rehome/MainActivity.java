package io.github.leopierini.rehome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ChangedPackages;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Home: the header (clock, weather, pills) over the category rows and the player (CategoryHome).
 */
public class MainActivity extends Activity {
    /**
     * The Config attachBaseContext read, for onCreate (taken once; null when it was read through other resources).
     * Kept in this activity, not in a static other activities also write (speed review M5).
     */
    private Config createConfig;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        Config config = Config.load(base);
        android.content.Context wrapped = LauncherLocale.wrap(base, config);   // locale
        createConfig = LauncherLocale.keptForCreate(wrapped, base, config);
        super.attachBaseContext(wrapped);
    }


    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "io.github.leopierini.rehome.action.LAUNCHER_SETTINGS";
    /** Sent by the "library" action word of app_keys: there is no App library any more, so it only brings Home forward. */
    static final String ACTION_APP_LIBRARY = "io.github.leopierini.rehome.action.APP_LIBRARY";
    /** The system input switcher, forwarded here by InputsRequestActivity while handle_view_inputs is on. */
    static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";
    private static final long MINUTE_MS = 60_000L;
    private static final long STATS_MS = 10_000L;
    /**
     * 2025-01-01. TVs without a clock battery boot at the epoch (08:00 in China) and keep it until network
     * time arrives; the clock, date and greeting stay hidden before then.
     */
    private static final long TIME_VALID_AFTER_MS = 1_735_689_600_000L;
    private String liveTvPkg;
    private boolean homeIgnoreChannelKeys;
    /** Launcher settings' change count this Home was built with (see onResume). */
    private long settingsRevision;
    /** Idle time on Home before ClockActivity; 0 is off (config.xml, home_idle_clock_minutes). */
    private long idleClockMs;
    /** Back on Home opens ClockActivity (config.xml, back_opens_clock). */
    private boolean backOpensClock;
    private final Runnable showIdleClock = () -> startActivity(new Intent(this, ClockActivity.class));
    private String liveTvAction;
    private String liveTvInputId;
    private String inputAction;
    private String inputUriScheme;

    private FrameLayout root;
    /** Home itself: the titled category rows, the player above them, the weather and the offline state. */
    private CategoryHome categoryHome;
    private WallpaperPlayer wallpaperPlayer;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View clockBlock;
    private View btnInputs;
    /** config show_inputs_pill: false keeps the Inputs pill hidden even when the TV has inputs. */
    private boolean showInputsPill = true;
    private TextView btnWifi;
    /** The network pill's icon as last set, so a refresh that changes nothing does not lay the top bar out again. */
    private int wifiIcon;
    /** The pills have been filled in once; later resumes refresh them after the first frame. */
    private boolean pillsShown;
    private TextView stats;
    /** config.xml show_stats, overridable by a theme. Off: the line is gone and nothing is read. */
    private boolean showStats;
    /** config.xml inputs_connected_only: the Inputs menu lists only connected inputs, the rest behind "More inputs". */
    private boolean inputsConnectedOnly;
    /** config.xml inputs_cec_state_only: only CEC device rows show a connection state, bare HDMI ports none. */
    private boolean inputsCecStateOnly;
    /** greeting_name: said after the greeting, which is shown in capitals. */
    private String greetingName = "";
    private String vpnApp;
    /** vpn_pill: the VPN shown as its own pill (btnVpn) instead of a badge in the network pill. Off with top_menu. */
    private boolean vpnPill;
    private TextView btnVpn;
    /** The VpnBadge pill state btnVpn shows, so a refresh that changes nothing does not restyle it. */
    private int vpnPillState = VpnBadge.PILL_HIDDEN;
    /** icon_only_vpn: the VPN pill is a round lock button; off, it keeps the plain glass look saved here. */
    private boolean iconOnlyVpn;
    private Drawable.ConstantState vpnPlainBackground;
    private ColorStateList vpnPlainInk;
    private float density;
    /** config settings_key_keycodes / settings_key_panel, so the Settings button works on Home without HomeKeyService. */
    private int[] settingsKeycodes = new int[0];
    private boolean settingsKeyPanel;
    private boolean settingsKeyClearTask;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): short fades, no entrance. */
    private boolean reducedMotion;
    private int greetingColor;
    /** boot_to_live_tv: open the Live TV app on the first Home of each boot. */
    private boolean bootToLiveTv;
    /** live_tv_package's launch_actions entry ("input:<id>"), opened at power-on instead of the Live TV app. */
    private String bootInputId;
    /** open_on_start_choice: the app picked in Launcher settings may open on the first Home of each boot. */
    private boolean openOnStartChoice;
    /** open_on_start_network_wait, in ms; the boot's app while it waits for the network, and until when. */
    private long openOnStartWaitMs;
    /** open_on_start_on_wake: wakeReceiver is registered for this activity's life. */
    private boolean openOnStartOnWake;
    private volatile Intent startPending;
    private long startDeadline;
    /** Opens the boot's app once the network is validated (networkCallback posts this) or the wait is over. */
    private final Runnable openWhenOnline = new Runnable() {
        @Override
        public void run() {
            if (startPending == null) return;
            if (!OpenOnStart.opensNow(OpenOnStart.online(MainActivity.this), SystemClock.elapsedRealtime(),
                    startDeadline)) return;
            handler.removeCallbacks(this);   // the deadline's run, when the network came first
            Intent i = startPending;
            startPending = null;
            open(i);
        }
    };
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            openStartAppOnWake();
        }
    };
    /** hidden_apps (config.xml or a theme's config.json): apps the rows never show. */
    private Set<String> hiddenApps = Collections.emptySet();
    /** Between onStop and onStart: Home was fully covered by an app, not just by the translucent quick panel. */
    private boolean stopped;
    /** Between onStart and onStop: Home is (at least partly) on screen. */
    private boolean started;
    /**
     * Between onResume and onPause: Home is in front. The idle clock only runs then; a key's ACTION_UP can still
     * reach Home after onPause, when that key opened another app, and must not start the clock over the app.
     */
    private boolean resumed;
    /** Rebuild the tiles on the way back to Home only when apps changed meanwhile (TileRefresh). */
    private final TileRefresh tileRefresh = new TileRefresh();
    /** PackageManager's change counter at the last rebuild, to catch a package broadcast that never arrived. */
    private int packageSequence;
    /** The dropdown (Inputs, Wi-Fi, a menu) while one is open, so onPause can close it. */
    private PopupWindow openPopup;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            // The next network to come up validated is a return online (weather, offline Home), even when the
            // lost one had been validated too: without this the hotspot coming back was not noticed.
            lastValidated = false;
            handler.post(MainActivity.this::updateWifiLabel);
        }

        /** wifi_status_warnings follows validation; signal-strength updates also land here, so skip those. */
        private boolean lastValidated;

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
            boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            // open_on_start_network_wait: the boot's app opens on the first validated network.
            if (validated && startPending != null) handler.post(openWhenOnline);
            if (validated == lastValidated) return;
            lastValidated = validated;
            // The weather that failed while the hotspot was away is fetched now, not in 30 minutes.
            if (validated) handler.post(() -> {
                if (resumed) categoryHome.onOnline();
            });
            // The pill's colour (wifi_status_warnings) and the offline Home both follow validation.
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** When the Home shows itself offline; a hotspot that drops for a second does not count. */
    private final OfflineGate offlineGate = new OfflineGate();
    private final Runnable offlineTick = () -> {
        if (offlineGate.onTick(SystemClock.elapsedRealtime())) applyOffline();
    };
    /** The red "Debugging on" pill while ADB is on. */
    private DebugPill debugPill;
    /** One-press Wi-Fi through the companion app, and the "connecting" state the pill spins for. */
    private WifiLink wifiLink;
    /** The pill's spinner while wifiLink is busy: its drawable and the animator turning it. */
    private Drawable wifiSpinner;
    private android.animation.ValueAnimator wifiSpinnerTurn;
    /** wifiIcon while the spinner is shown. */
    private static final int WIFI_ICON_SPINNER = -1;
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            // In the background this only marks the tiles stale; onResume rebuilds them once.
            if (!tileRefresh.onPackagesChanged()) return;
            // A Play Store session sends these in bursts: one rebuild once it settles, not one per broadcast.
            handler.removeCallbacks(packageReload);
            handler.postDelayed(packageReload, PACKAGE_RELOAD_DELAY_MS);
        }
    };
    private static final long PACKAGE_RELOAD_DELAY_MS = 500;
    private static final long WALLPAPER_START_DELAY_MS = 1200;
    private final Runnable wallpaperStart = () -> {
        if (resumed) wallpaperPlayer.start();
    };
    private final Runnable packageReload = () -> {
        if (isFinishing() || isDestroyed()) return;
        if (!started) {
            // Home left the screen during the wait: rebuild on the way back instead.
            tileRefresh.invalidate();
            return;
        }
        reloadApps(false, true);
    };
    // Right after boot the clock is often wrong until network time arrives; don't wait a minute to catch up.
    private final IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClockVisibility();
            clockRefresh.start();   // the wall clock was set: line the redraw up with the new minute
        }
    };
    /** Keeps the Home clocks moving on Android before 12 when the firmware stops ACTION_TIME_TICK (see ClockRefresh). */
    private ClockRefresh clockRefresh;
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            wallpaperPlayer.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    private java.util.Map<Integer, String> homeAppKeys = java.util.Collections.emptyMap();
    private java.util.Map<Integer, String> homeHoldAppKeys = java.util.Collections.emptyMap();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json from a pushed theme (see Config).
        Config cfg = createConfig != null ? createConfig : Config.load(this);
        createConfig = null;
        homeAppKeys = RemoteKeys.parseAppKeys(cfg.appKeys);
        ClockFormat.apply(getWindow().getDecorView(), cfg.clockFormat);
        homeHoldAppKeys = RemoteKeys.parseHoldAppKeys(cfg.appKeys);
        // restore_key_services: after a force stop, put back the remote's accessibility helpers that Android took
        // off the list (needs WRITE_SECURE_SETTINGS, granted over ADB with pm grant).
        if (cfg.restoreKeyServices) ServiceRepair.restore(this);
        settingsRevision = settingsRevision();
        applyViewInputsHandler(cfg.handleViewInputs);
        settingsKeycodes = cfg.settingsKeycodes;
        settingsKeyPanel = cfg.settingsKeyPanel;
        settingsKeyClearTask = cfg.settingsKeyClearTask;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;
        reducedMotion = cfg.reducedMotion;
        liveTvPkg = cfg.liveTvPackage;
        homeIgnoreChannelKeys = cfg.homeIgnoreChannelKeys;
        idleClockMs = cfg.homeIdleClockMinutes * MINUTE_MS;
        backOpensClock = cfg.backOpensClock;
        bootToLiveTv = cfg.bootToLiveTv;
        bootInputId = LaunchActions.inputId(cfg.launchActions.get(cfg.liveTvPackage));
        openOnStartChoice = cfg.openOnStartChoice;
        openOnStartWaitMs = cfg.openOnStartNetworkWait * 1000L;
        openOnStartOnWake = OpenOnStart.onWake(cfg.openOnStartChoice, cfg.openOnStartOnWake);
        liveTvAction = cfg.liveTvAction;
        liveTvInputId = cfg.liveTvInputId;
        inputAction = cfg.inputAction;
        inputUriScheme = cfg.inputUriScheme;

        root = findViewById(R.id.root);
        root.setBackgroundColor(cfg.backgroundColor);
        // root fills the window with an opaque colour (Config keeps background_color opaque), so the window's own
        // black under it is a second full-screen fill on every frame for nothing. The starting window still uses
        // the theme's background, so launch looks the same.
        getWindow().setBackgroundDrawable(null);
        wallpaperPlayer = new WallpaperPlayer(this, root, cfg);
        greeting = findViewById(R.id.greeting);
        clockBlock = findViewById(R.id.leftBlock);
        clockRefresh = new ClockRefresh(clockBlock);
        greetingColor = greeting.getCurrentTextColor();
        if (!cfg.showGreeting) greeting.setVisibility(View.GONE);
        // Keep the other header layouts as designed: their extra lines change the block's centre.
        if (!cfg.showGreeting && !cfg.dateBelowClock
                && !cfg.topMenu && !getResources().getBoolean(R.bool.rtl_menu_bar)) {
            centerCompactHeader();
        }
        if (cfg.headerWhite) {
            // The clock's own white for the date and the line before it, instead of their dimmed whites.
            ((TextView) findViewById(R.id.date)).setTextColor(Color.WHITE);
            findViewById(R.id.clockDivider).setBackgroundColor(Color.WHITE);
        }
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            // Center Hebrew greetings over the numeric time, independent of a visible AM/PM label. The clock's row
            // is watched too: date_below_clock can move the whole row right under a wider date.
            View clock = findViewById(R.id.clock);
            View clockRow = (View) clock.getParent();
            View.OnLayoutChangeListener centerGreeting = (v, l, t, right, bottom, ol, ot, or, ob) -> {
                ViewGroup.LayoutParams params = greeting.getLayoutParams();
                if (params.width != clock.getWidth()) {
                    params.width = clock.getWidth();
                    greeting.setLayoutParams(params);
                }
                greeting.setTranslationX(clockRow.getLeft() + clock.getLeft());
            };
            clock.addOnLayoutChangeListener(centerGreeting);
            clockRow.addOnLayoutChangeListener(centerGreeting);
        }
        btnWifi = findViewById(R.id.btnWifi);
        stats = findViewById(R.id.stats);
        showStats = cfg.showStats;
        inputsConnectedOnly = cfg.inputsConnectedOnly;
        inputsCecStateOnly = cfg.inputsCecStateOnly;
        greetingName = cfg.greetingName;
        if (cfg.dateBelowClock) moveDateBelowClock();
        if (cfg.dateBelowClock && cfg.iconOnlyButtons && !cfg.topMenu
                && !getResources().getBoolean(R.bool.rtl_menu_bar)) {
            alignDateBelowIconsWithClock();
        }
        vpnApp = cfg.vpnApp;
        vpnPill = cfg.vpnPill && !cfg.topMenu;
        btnVpn = findViewById(R.id.btnVpn);
        hiddenApps = new HashSet<>(cfg.hiddenApps);
        categoryHome = new CategoryHome(this, root, hiddenApps, animations && !reducedMotion);
        categoryHome.setPopups(this::glassPopup);
        // Its header restyles the greeting; updateGreeting() puts this colour back every minute.
        greetingColor = greeting.getCurrentTextColor();

        btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        btnInputs.setOnClickListener(v -> showInputs());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnVpn.setOnClickListener(v -> openVpnPill());
        btnWifi.setOnLongClickListener(v -> {
            open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            return true;
        });
        launcherSettingsButton = cfg.launcherSettingsButton;
        if (launcherSettingsButton) {
            ((TextView) btnSettings).setText(R.string.launcher_settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_home_settings_pill, 0, 0, 0);
        }
        btnSettings.setOnClickListener(v -> {
            if (launcherSettingsButton) startActivityForResult(
                    new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
            else open(systemIntent(Settings.ACTION_SETTINGS));
        });
        if (!cfg.showNetworkPill) btnWifi.setVisibility(View.GONE);
        showInputsPill = cfg.showInputsPill;
        showInputsMenuRow = cfg.showInputsPill;
        addTopBarApps(cfg.topBarApps, cfg.launchActions);
        iconOnlyButtons = cfg.iconOnlyButtons;
        topMenu = cfg.topMenu;
        wifiIconOnly = iconOnlyButtons && cfg.wifiLabelWidth <= 0;
        if (cfg.wifiStatusColor) {
            wifiPlainTint = btnWifi.getCompoundDrawableTintList();
            wifiOnlineTint = getColorStateList(R.color.pill_icon_online);
            if (cfg.wifiStatusWarnings) {
                wifiNoInternetTint = getColorStateList(R.color.pill_icon_no_internet);
                wifiOfflineTint = getColorStateList(R.color.pill_icon_offline);
            }
        }
        if (cfg.topMenu) {
            // One gear: Inputs and Wi-Fi move into its dropdown. The Wi-Fi pill stays hidden but keeps its
            // label up to date, which the menu's Wi-Fi row shows.
            showInputsPill = false;
            btnInputs.setVisibility(View.GONE);
            btnWifi.setVisibility(View.GONE);
            ((TextView) btnSettings).setText(R.string.settings);
            ((TextView) btnSettings).setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings, 0, 0, 0);
            makeIconOnly((TextView) btnSettings);
            btnSettings.setOnClickListener(v -> showTopMenu());
            // The lone gear sits at the header's middle, not on its bottom line like the row of pills.
            LinearLayout topButtons = findViewById(R.id.topButtons);
            RelativeLayout.LayoutParams tlp = (RelativeLayout.LayoutParams) topButtons.getLayoutParams();
            tlp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
            tlp.bottomMargin = 0;
            topButtons.setLayoutParams(tlp);
            topButtons.setGravity(Gravity.CENTER_VERTICAL);
        }
        if (iconOnlyButtons) {
            if (cfg.wifiLabelWidth > 0) btnWifi.setMaxWidth(Math.round(cfg.wifiLabelWidth * density));
            else makeIconOnly(btnWifi);
        }
        // The gear is always a round icon (its name goes to talkback): a labelled Settings pill pushed the weather
        // under the pills on a 55" TV, and the gear says what it is (top_menu made it one already).
        if (!cfg.topMenu) makeIconOnly((TextView) btnSettings);
        iconOnlyInputs = cfg.iconOnlyInputs;
        if (iconOnlyInputs) makeIconOnly((TextView) btnInputs);
        iconOnlyVpn = vpnPill && cfg.iconOnlyVpn;
        if (iconOnlyVpn) {
            vpnPlainBackground = btnVpn.getBackground().getConstantState();
            vpnPlainInk = btnVpn.getCompoundDrawableTintList();
            makeIconOnly(btnVpn);
        }
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });

        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        // Not with top_menu: the bar there is one gear by design. Shown and hidden by onResume.
        if (!cfg.topMenu) {
            debugPill = new DebugPill(this, (ViewGroup) btnSettings.getParent(), rtlMenuBar, handler, this::onDebugPillShown);
        }

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                categoryHome.onPillUp();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                categoryHome.onPillDown();
                return true;
            }
            // Left and Right go to the next pill by position (CategoryHome.pillBeside), never through the focus
            // finder, which may pick a tile of a row scrolled out of view.
            if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    && categoryHome.pillBeside(v, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (v != barEnd(true)) return false;
                // The weather sits left of the pills, so Left from the first pill reaches it.
                if (!rtlMenuBar) categoryHome.onPillUp();
                return true;
            }
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v == barEnd(false);
        };
        if (debugPill != null) debugPill.view().setOnKeyListener(pillKeys);
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnVpn.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        for (View button : topAppButtons) button.setOnKeyListener(pillKeys);
        if (advancedEffects) {
            List<View> pills = new ArrayList<>(Arrays.asList(btnInputs, btnWifi, btnVpn, btnSettings));
            pills.addAll(topAppButtons);
            if (debugPill != null) pills.add(debugPill.view());
            for (View button : pills) {
                button.setOnFocusChangeListener((view, focused) -> view.animate()
                        .scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                        .setDuration(reducedMotion ? Motion.REDUCED_MS : 160).start());
            }
        }

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addDataScheme("package");
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        // Registered for the activity's whole life, not only while it is started: an app installed or updated while
        // another app is in front must still mark the tiles stale, or Home would have to rebuild on every return.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, packageFilter);
        }
        if (openOnStartOnWake) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(wakeReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
            }
        }

        // From the application, not this activity: on Android 12 a ConnectivityManager keeps the context it was
        // made with (its static sInstance, and the callback Messenger the system holds), so every recreate() left
        // the previous Home and its bitmaps behind.
        ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        wifiLink = new WifiLink(this, handler, this::updateWifiLabel);
        wifiLink.register();
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        // Not after recreate(): the launch intent survives it and would open the settings again.
        if (savedInstanceState == null && !openLauncherSettingsIfAsked(getIntent()) && !bringForwardIfAsked(getIntent())) {
            openInputsIfAsked(getIntent());
        }
    }

    /**
     * handle_view_inputs: switch InputsRequestActivity, the VIEW_INPUTS handler, on or off. It is disabled in the
     * manifest, so a TV with its own input picker never sees it; only the sets that need it turn it on. Written
     * only when the state differs, so an ordinary start does not touch the package manager.
     */
    private void applyViewInputsHandler(boolean on) {
        ComponentName handler = new ComponentName(this, InputsRequestActivity.class);
        PackageManager pm = getPackageManager();
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if (pm.getComponentEnabledSetting(handler) != want) {
            pm.setComponentEnabledSetting(handler, want, PackageManager.DONT_KILL_APP);
        }
    }

    /**
     * VIEW_INPUTS, forwarded by InputsRequestActivity while handle_view_inputs is on: open the Inputs menu once
     * the pill is on screen. The intent is replaced so a later recreate() does not
     * open it again. Nothing to do when the TV has no inputs to list (the pill is hidden then).
     * top_menu hides the pill, so the list hangs under the gear there, as in showInputs().
     */
    private boolean openInputsIfAsked(Intent intent) {
        if (intent == null || !ACTION_VIEW_INPUTS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        View anchor = topMenu ? findViewById(R.id.btnSettings) : btnInputs;
        if (anchor.getVisibility() != View.VISIBLE) return true;
        if (anchor.isLaidOut()) {
            // Android 9 delivers a new intent to a paused activity with a brief resume and pause around it, and
            // onPause() closes popups, so open the menu once that has run (HiSmartTV A4, Android TV 9).
            anchor.post(() -> {
                if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                anchor.requestFocus();
                showInputs();
            });
            return true;
        }
        // Cold start: the popup anchors to the pill (or the gear), so wait for the first layout.
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                anchor.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                anchor.post(() -> {
                    if (isFinishing() || anchor.getVisibility() != View.VISIBLE) return;
                    anchor.requestFocus();
                    showInputs();
                });
            }
        });
        return true;
    }

    /**
     * ACTION_APP_LIBRARY, sent by the "library" action word: there is no App library, so the press only brings Home
     * forward. The intent is replaced so a later recreate() does not see it again.
     */
    private boolean bringForwardIfAsked(Intent intent) {
        if (intent == null || !ACTION_APP_LIBRARY.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        return true;
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first app of the first row focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        if (bringForwardIfAsked(intent)) return;
        if (openInputsIfAsked(intent)) return;
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        categoryHome.resetToFirst();
        if (stopped) {
            tileRefresh.onHomeAgain();
        } else {
            // Still on screen (the quick panel was over it, or Home was already in front): the tiles are current,
            // so only go back to the first one; rebuilding would replay the entrance animation for nothing.
            root.post(categoryHome::restoreFocus);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        trimCollected = false;
        stopped = false;
        started = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeReceiver, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timeReceiver, timeFilter);
        }
        updateClockVisibility();
        clockRefresh.start();   // with timeReceiver: while Home is visible, paused or not
        tileRefresh.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Something changed in Launcher settings: rebuild with it, however the settings were left. Only Back
        // delivered their RESULT_OK; Home (the remote's button) clears them off this task and the result comes
        // back cancelled, so a new wallpaper or layout waited for the next process start.
        if (Config.settingsChanged(settingsRevision, settingsRevision())) {
            recreate();
            return;
        }
        restartIdleClock();
        categoryHome.onResume();
        if (debugPill != null) debugPill.onResume();
        switch (tileRefresh.onResume(packagesChangedSinceRebuild())) {
            case REBUILD:
                reloadApps(true, true);
                break;
            case REFOCUS:
                // The same tiles: only the focus goes back, as after a rebuild.
                reloadApps(true, false);
                break;
            default:
                break;
        }
        if (openLiveTvAfterBoot()) return;
        updateGreeting();
        // The network and Inputs pills keep their state while Home is in the background, and callbacks update them
        // when the network or the inputs change, so this refresh only catches anything missed. Its binder calls
        // (about 7 ms on a TCL C755, most of it WifiManager.getConnectionInfo) would hold up the first frame back
        // on Home, so after the first resume it runs once that frame is drawn.
        if (pillsShown) {
            refreshPillsAfterNextDraw();
        } else {
            updateWifiLabel();
            updateInputsButton();
            pillsShown = true;
        }
        if (showStats) startStats();
        // A rotation that came due while an app was in front would decode a full-screen picture during the very
        // frames that bring Home back: let those draw first.
        handler.removeCallbacks(wallpaperStart);
        handler.postDelayed(wallpaperStart, WALLPAPER_START_DELAY_MS);
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    /** Launcher settings' change count (Config.SETTINGS_REVISION_KEY); onResume rebuilds Home when it moved. */
    private long settingsRevision() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getLong(Config.SETTINGS_REVISION_KEY, 0);
    }

    /**
     * Rebuilds the rows ({@code rebuild}), and puts focus back on the app it was on when Home is coming back
     * ({@code refocus}) or already had it. A package change must not steal focus from a top button or a dropdown.
     */
    private void reloadApps(boolean refocus, boolean rebuild) {
        final boolean restoreFocus = refocus || categoryHome.hasFocus();
        if (rebuild) reloadTiles();
        // After reload() the view that holds focus may no longer show the app it had.
        if (restoreFocus) root.post(categoryHome::restoreFocus);
    }

    /** True when PackageManager reports apps changed since the last rebuild (one binder call). */
    private boolean packagesChangedSinceRebuild() {
        ChangedPackages changed = getPackageManager().getChangedPackages(packageSequence);
        return changed != null && !changed.getPackageNames().isEmpty();
    }

    private int currentPackageSequence(int since) {
        ChangedPackages changed = getPackageManager().getChangedPackages(since);
        return changed != null ? changed.getSequenceNumber() : since;
    }

    /** Rebuilds the rows from one LEANBACK query. */
    private void reloadTiles() {
        final List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        categoryHome.reload(leanback);
        packageSequence = currentPackageSequence(packageSequence);
    }

    /** Any key on Home restarts the countdown to the idle clock. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        restartIdleClock();
        // The player's bar stops a minute after the last key, for the OLED screensaver (CategoryHome.onUserKey).
        categoryHome.onUserKey();
    }

    private void restartIdleClock() {
        root.removeCallbacks(showIdleClock);
        if (resumed && idleClockMs > 0) root.postDelayed(showIdleClock, idleClockMs);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // No UP reaches a paused Home, so a vendor-key hold it saw go down would still open its app later.
        // Only Home's own share is dropped: a key service that saw the same DOWN keeps its hold.
        RemoteKeys.release(this);
        categoryHome.onPause();
        if (debugPill != null) debugPill.onPause();
        root.removeCallbacks(showIdleClock);
        handler.removeCallbacks(wallpaperStart);
        wallpaperPlayer.stop();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(openWhenOnline);
        startPending = null;   // leaving Home before the network came back: the boot's app stays closed
        stopStats();
        // Nothing of ours stays on screen over another app.
        if (openPopup != null) openPopup.dismiss();
        stopWifiSpinner();   // turned again by the resume's updateWifiLabel while still connecting
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        unregisterReceiver(timeReceiver);
        clockRefresh.stop();
        handler.removeCallbacks(clockCheck);
        stopped = true;
        tileRefresh.onStop();
    }

    /**
     * Another app needs the memory while Home is in the background: let go of every tile's artwork, and
     * rebuild it when Home comes back. Not at UI_HIDDEN, the ordinary trip to an app, where keeping the
     * tiles is what makes the return instant.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // RUNNING_LOW and CRITICAL also arrive while Home is visible when an accessibility service keeps the
        // process in the foreground; never blank the screen the owner is looking at.
        if (!MemoryTrimPolicy.shouldRelease(level, started)) return;
        wallpaperPlayer.releaseForMemoryPressure();
        categoryHome.release();
        tileRefresh.invalidate();
        // What was let go is native memory owned by Java objects (bitmaps, views): it goes back only when they are
        // collected, and a process behind another app allocates nothing that starts a collection. On the TV the PSS
        // stayed at 48 MB after a release (final fix I2). Once per stay in the background.
        if (!trimCollected) {
            trimCollected = true;
            handler.post(() -> Runtime.getRuntime().gc());
        }
    }

    /** onTrimMemory asked for a collection since Home was last started. */
    private boolean trimCollected;

    @Override
    protected void onDestroy() {
        super.onDestroy();
        RemoteKeys.release(this);
        categoryHome.onDestroy();
        if (debugPill != null) debugPill.onPause();
        handler.removeCallbacks(offlineTick);
        handler.removeCallbacks(packageReload);
        handler.removeCallbacks(wallpaperStart);
        unregisterReceiver(packageReceiver);
        if (openOnStartOnWake) unregisterReceiver(wakeReceiver);
        wallpaperPlayer.close();
        ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);   // as registered
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (wifiLink != null) wifiLink.unregister();
        stopWifiSpinner();
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    /**
     * A Settings button sent as a keycode (settings_key_keycodes, e.g. 83 on TCL Google TV remotes) opens the same
     * thing HomeKeyService would. While that service runs it consumes the key first; without it (not enabled,
     * or switched off by a force-stop) the stock launcher that used to handle the key is gone, and the button did
     * nothing even on Home.
     */
    private boolean handleSettingsKey(KeyEvent event) {
        for (int code : settingsKeycodes) {
            if (event.getKeyCode() != code) continue;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent settings = settingsKeyPanel ? new Intent(this, QuickSettingsActivity.class)
                        : systemIntent(Settings.ACTION_SETTINGS);
                // settings_key_clear_task, as in HomeKeyService: Back from Settings goes Home, not to a vendor page.
                if (settingsKeyClearTask && !settingsKeyPanel) settings.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                open(settings);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (homeIgnoreChannelKeys && RemoteKeys.isChannelKey(event.getKeyCode())) return true;
        // Vendor keys (TCL Netflix button, 4062) never reach the key services, only the focused window, so their
        // "hold:" entries run here too or nowhere. Only while resumed: onPause releases Home's holds, and a DOWN
        // taken after that would arm one nothing releases.
        if (resumed && event.getKeyCode() >= 1000 && RemoteKeys.handle(this, event, homeAppKeys.get(event.getKeyCode()),
                homeHoldAppKeys.get(event.getKeyCode()))) return true;
        if (handleSettingsKey(event)) return true;
        // Digits and colour keys (HomeKeys), only with Home in front and nothing open over it.
        if (categoryHome.onHomeKey(event, resumed && hasWindowFocus() && openPopup == null)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Home screen: Back goes nowhere, unless back_opens_clock is on.
        if (backOpensClock) startActivity(new Intent(this, ClockActivity.class));
    }

    // ---- optional system stats (config.xml show_stats) --------------------------------------------

    /** Background reader. Started only while Home is resumed and show_stats is on. */
    private HandlerThread statsThread;
    /** The current run's handler; a run whose handler is no longer this one stops (volatile: read off-thread). */
    private volatile Handler statsBg;

    private void startStats() {
        if (statsThread != null) return;
        stats.setVisibility(View.VISIBLE);
        cpuTotalPrev = -1;
        idlePrev = -1;
        procStatDead = false;
        statsThread = new HandlerThread("stats");
        statsThread.start();
        // Each start gets its own handler, held by its task: stopStats on the main thread can clear the field
        // at any moment without the reader tripping over it.
        Handler bg = new Handler(statsThread.getLooper());
        statsBg = bg;
        bg.post(new Runnable() {
            @Override
            public void run() {
                if (statsBg != bg) return;
                String line = readStatsLine();
                handler.post(() -> {
                    if (showStats && stats != null && statsBg == bg) stats.setText(line);
                });
                if (statsBg == bg) bg.postDelayed(this, STATS_MS);
            }
        });
    }

    private void stopStats() {
        if (statsThread == null) return;
        statsBg.removeCallbacksAndMessages(null);
        statsThread.quit();
        statsThread = null;
        statsBg = null;
        // Paused (the quick panel or an app on top): keep the last line on screen.
        if (!showStats) stats.setVisibility(View.GONE);
    }

    /** One quiet line, e.g. "CPU 8%  ·  Memory 430 MB free  ·  Storage 3.1 GB free". Runs off the main thread. */
    private static void appendStat(StringBuilder line, String part) {
        if (line.length() > 0) line.append("  ·  ");
        line.append(part);
    }

    private String readStatsLine() {
        int cpu = cpuPercent();
        if (cpu < 0) {
            try {
                Thread.sleep(200);             // second sample, so the first line already has a CPU figure
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
            cpu = cpuPercent();
        }
        // Android 8 and later hide /proc/stat from apps on many TVs: then the line leaves CPU out rather than
        // showing a dash that never fills in.
        StringBuilder line = new StringBuilder();
        if (cpu >= 0) line.append(getString(R.string.stats_cpu, cpu));
        long[] mem = memUsedOfTotalMb();
        if (mem != null) appendStat(line, getString(R.string.stats_memory, mem[0], mem[1]));
        String free = formatGb(getResources(), Environment.getDataDirectory().getFreeSpace());
        if (!free.isEmpty()) appendStat(line, getString(R.string.stats_storage, free));
        return line.toString();
    }

    // -1: need another sample. -2: this source cannot be read (Android 9 hides /proc/stat from apps).
    private static final int CPU_UNREADABLE = -2;
    private long cpuTotalPrev = -1, cpuIdlePrev = -1;
    private boolean procStatDead;
    private long idlePrev = -1, wallPrev = -1;

    private int cpuPercent() {
        if (!procStatDead) {
            int fromStat = cpuFromProcStat();
            if (fromStat != CPU_UNREADABLE) return fromStat;
            procStatDead = true;
            idlePrev = -1;
        }
        return cpuFromIdle();
    }

    private int cpuFromProcStat() {
        String s = readFirstLine("/proc/stat");
        if (s == null || !s.startsWith("cpu")) return CPU_UNREADABLE;
        String[] t = s.trim().split("\\s+");
        if (t.length < 5) return CPU_UNREADABLE;
        try {
            long user = Long.parseLong(t[1]), nice = Long.parseLong(t[2]), sys = Long.parseLong(t[3]);
            long idle = Long.parseLong(t[4]);
            long iowait = t.length > 5 ? Long.parseLong(t[5]) : 0;
            long irq = t.length > 6 ? Long.parseLong(t[6]) : 0;
            long soft = t.length > 7 ? Long.parseLong(t[7]) : 0;
            long total = user + nice + sys + idle + iowait + irq + soft;
            long idleAll = idle + iowait;
            if (cpuTotalPrev < 0) {
                cpuTotalPrev = total;
                cpuIdlePrev = idleAll;
                return -1;
            }
            long dt = total - cpuTotalPrev, di = idleAll - cpuIdlePrev;
            cpuTotalPrev = total;
            cpuIdlePrev = idleAll;
            if (dt <= 0) return -1;
            int busy = (int) Math.round((dt - di) * 100.0 / dt);
            return Math.max(0, Math.min(100, busy));
        } catch (NumberFormatException e) {
            return CPU_UNREADABLE;
        }
    }

    /** Busy percent from cpuidle time (microseconds) across the cores. -1 until the second sample. */
    private int cpuFromIdle() {
        long idle = 0;
        int cores = 0;
        for (int i = 0; i < 8; i++) {
            boolean any = false;
            for (int state = 0; state < 8; state++) {
                String t = readFirstLine("/sys/devices/system/cpu/cpu" + i
                        + "/cpuidle/state" + state + "/time");
                if (t == null) break;
                try {
                    idle += Long.parseLong(t.trim());
                    any = true;
                } catch (NumberFormatException e) {
                    break;
                }
            }
            if (!any) break;
            cores++;
        }
        if (cores == 0) return -1;
        long wall = SystemClock.elapsedRealtime() * 1000L;
        if (idlePrev < 0) {
            idlePrev = idle;
            wallPrev = wall;
            return -1;
        }
        long di = idle - idlePrev;
        long dw = (wall - wallPrev) * cores;
        idlePrev = idle;
        wallPrev = wall;
        if (dw <= 0) return -1;
        int busy = (int) Math.round((dw - di) * 100.0 / dw);
        return Math.max(0, Math.min(100, busy));
    }

    /** RAM usage as {used, total} in MB (MemInfo.usedOfTotalMb); null when /proc/meminfo cannot be read. */
    private long[] memUsedOfTotalMb() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            for (String line; (line = r.readLine()) != null;) lines.add(line);
        } catch (IOException ignored) {
            return null;
        }
        return MemInfo.usedOfTotalMb(lines);
    }

    private static String readFirstLine(String path) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path))) {
            return r.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatGb(Resources res, long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 10 ? res.getString(R.string.storage_gb_whole, gb)
                : res.getString(R.string.storage_gb_decimal, gb);
    }

    // ---- navigation ------------------------------------------------------------------------------

    /**
     * date_below_clock: the date column moves to its own line under the clock, in the vertical LinearLayout that holds
     * the clock's row, and the line between them goes. In Hebrew (rtl_menu_bar) the date and the clock's row sit right.
     */
    private void moveDateBelowClock() {
        View column = (View) findViewById(R.id.date).getParent();
        View clockRow = (View) findViewById(R.id.clock).getParent();
        LinearLayout clockColumn = (LinearLayout) clockRow.getParent();
        ((ViewGroup) column.getParent()).removeView(column);
        findViewById(R.id.clockDivider).setVisibility(View.GONE);
        // Its own width, not the column's: in Hebrew the column is only as wide as the clock.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(4 * density);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            // Date and clock flush right; the greeting stays left and follows the clock by translation.
            lp.gravity = Gravity.RIGHT;
            ((LinearLayout.LayoutParams) clockRow.getLayoutParams()).gravity = Gravity.RIGHT;
        }
        clockColumn.addView(column, clockColumn.indexOfChild(clockRow) + 1, lp);
        // The column now wraps its texts, so they must wrap too: a match_parent TextView does not ask for a new
        // layout when its text gets longer, and the column kept the width of the day before ("TUESDAY 27 OCTOB…").
        ViewGroup texts = (ViewGroup) column;
        for (int i = 0; i < texts.getChildCount(); i++) {
            View text = texts.getChildAt(i);
            LinearLayout.LayoutParams textLp = (LinearLayout.LayoutParams) text.getLayoutParams();
            textLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            // Keep each line where its own gravity put it (right in Hebrew) now that it no longer fills the column.
            textLp.gravity = ((TextView) text).getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK;
            text.setLayoutParams(textLp);
        }
    }

    /** Centre the one-row header when the greeting and extra date lines are absent. */
    private void centerCompactHeader() {
        LinearLayout clockRow = (LinearLayout) findViewById(R.id.clock).getParent();
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        clockRow.setBaselineAligned(false);
        View[] columns = {findViewById(R.id.clockDivider), (View) findViewById(R.id.date).getParent()};
        for (View column : columns) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) column.getLayoutParams();
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.bottomMargin = 0;
            column.setLayoutParams(lp);
        }
        LinearLayout buttons = findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
        lp.bottomMargin = 0;
        buttons.setLayoutParams(lp);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
    }

    /** With the date below, keep the icons beside the clock rather than beside the last date line. */
    private void alignDateBelowIconsWithClock() {
        View clock = findViewById(R.id.clock);
        View clockRow = (View) clock.getParent();
        LinearLayout buttons = findViewById(R.id.topButtons);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) buttons.getLayoutParams();
        lp.addRule(RelativeLayout.ALIGN_TOP, R.id.leftBlock);
        lp.removeRule(RelativeLayout.ALIGN_BOTTOM);
        lp.topMargin = 0;
        lp.bottomMargin = 0;
        buttons.setLayoutParams(lp);
        // Either side can be laid out first; update after both without requesting another layout mid-pass.
        Runnable align = () -> buttons.setTranslationY(clockRow.getTop() + clock.getTop()
                + (clock.getHeight() - buttons.getHeight()) / 2f);
        clockRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());
        buttons.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> align.run());
    }

    /** The first top-bar button after Inputs that is shown, in bar order (top_bar_apps, the network pill, ...); Settings is always there. */
    private View firstTopButton() {
        ViewGroup bar = findViewById(R.id.topButtons);
        // The Hebrew layout (layout-iw) lists the bar mirrored, Settings first and Inputs last, so bar order
        // runs from the end there. Walking it forwards returned Settings, and the pill key guard then treated
        // Settings as both ends of the bar: Right from Settings was swallowed and never reached Wi-Fi.
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        int n = bar.getChildCount();
        for (int k = 0; k < n; k++) {
            View button = bar.getChildAt(rtl ? n - 1 - k : k);
            // Not the debug pill either: a warning, not where focus should land on its own.
            if (button != btnInputs && (debugPill == null || button != debugPill.view())
                    && button.getVisibility() == View.VISIBLE) return button;
        }
        return findViewById(R.id.btnSettings);
    }

    /**
     * The top bar's leftmost (or rightmost) pill on screen. Children are laid out left to right in their order, the
     * mirrored Hebrew bar included (the launcher does not support RTL layout), so the ends are the first and last
     * shown child: a pill added or hidden later (the debug pill) needs no change here.
     */
    private View barEnd(boolean left) {
        ViewGroup bar = findViewById(R.id.topButtons);
        int n = bar.getChildCount();
        for (int k = 0; k < n; k++) {
            View pill = bar.getChildAt(left ? k : n - 1 - k);
            if (pill.getVisibility() == View.VISIBLE && pill.isFocusable()) return pill;
        }
        return findViewById(R.id.btnSettings);
    }

    /**
     * The debug pill came or went. The header has no room for one more labelled pill beside the weather, so while it
     * shows, Inputs shows only its icon (the HDMI plug; its name goes to talkback), and gets it back when it goes. The
     * gear is an icon in every state. Focus on the pill as it went moves to the Inputs pill (or the first one shown).
     */
    private void onDebugPillShown(boolean shown, boolean hadFocus) {
        // ADB went on or off: the test hooks (TestHooks) act only while it is on, so what they showed is read again.
        if (!isDestroyed()) {
            categoryHome.onTestHooksChanged();
            if (resumed) updateWifiLabel();
        }
        if (!iconOnlyInputs) setPillCompact((TextView) btnInputs, shown);
        if (!shown && hadFocus) {
            (btnInputs.getVisibility() == View.VISIBLE ? btnInputs : firstTopButton()).requestFocus();
        }
    }

    /**
     * makeIconOnly, and back to exactly what the pill was: its label, talkback text, padding, icon gap and width are
     * kept when it is compacted (not re-read from the talkback text or copied from the style).
     */
    private void setPillCompact(TextView pill, boolean compact) {
        Object[] was = compactPills.get(pill);
        if (compact == (was != null)) return;
        if (compact) {
            compactPills.put(pill, new Object[]{pill.getText(), pill.getContentDescription(), pill.getPaddingStart(),
                    pill.getPaddingEnd(), pill.getCompoundDrawablePadding(), pill.getLayoutParams().width,
                    pill.getMaxWidth()});
            makeIconOnly(pill);
            return;
        }
        compactPills.remove(pill);
        pill.setText((CharSequence) was[0]);
        pill.setContentDescription((CharSequence) was[1]);
        pill.setPaddingRelative((Integer) was[2], pill.getPaddingTop(), (Integer) was[3], pill.getPaddingBottom());
        pill.setCompoundDrawablePadding((Integer) was[4]);
        pill.getLayoutParams().width = (Integer) was[5];
        pill.setMaxWidth((Integer) was[6]);
        pill.requestLayout();
    }

    /** setPillCompact: the pill shown icon-only for the debug pill (Inputs), with what it was before. */
    private final java.util.Map<View, Object[]> compactPills = new java.util.HashMap<>();

    /**
     * The network as updateWifiLabel just read it. Going offline is shown only once it lasts (OfflineGate), coming
     * back at once.
     */
    private void onNetworkRead(boolean validated) {
        long now = SystemClock.elapsedRealtime();
        if (offlineGate.onSignal(validated, now)) applyOffline();
        handler.removeCallbacks(offlineTick);
        long wait = offlineGate.msUntilOffline(now);
        if (wait >= 0) handler.postDelayed(offlineTick, wait);
    }

    private void applyOffline() {
        if (!isDestroyed()) categoryHome.setOffline(offlineGate.offline());
    }

    /** top_bar_apps buttons, in bar order. */
    private final List<View> topAppButtons = new ArrayList<>();

    /**
     * Puts each top_bar_apps app in the top bar as a round pill with the app's own logo, left of the network pill.
     * The logo keeps its colours: the pill style's tint, which turns the other icons white, is cleared.
     */
    private void addTopBarApps(List<String> packages, Map<String, String> launchActions) {
        ViewGroup bar = findViewById(R.id.topButtons);
        PackageManager pm = getPackageManager();
        int size = Math.round(22 * density);
        for (String pkg : packages) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;   // not installed, or disabled
            // Use the configured launch_actions; retain the normal entry if unavailable.
            launch = LaunchActions.select(pkg, launchActions, launch, (targetPackage, action) -> {
                Intent custom = LaunchActions.intent(targetPackage, action);
                return pm.resolveActivity(custom, 0) != null ? custom : null;
            });
            Drawable logo;
            CharSequence label;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                logo = info.loadIcon(pm);
                label = info.loadLabel(pm);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            TextView button = (TextView) getLayoutInflater().inflate(R.layout.top_app_button, bar, false);
            // pillKeys finds the bar's ends by id: without one of its own, every app button would be an end.
            button.setId(View.generateViewId());
            logo.setBounds(0, 0, size, size);
            button.setCompoundDrawablesRelative(logo, null, null, null);
            button.setCompoundDrawableTintList(null);
            button.setContentDescription(label);
            Intent target = launch;
            // An input: action switches the TV input.
            String inputId = LaunchActions.inputId(launchActions.get(pkg));
            if (inputId != null) button.setOnClickListener(v -> openInput(inputId));
            else button.setOnClickListener(v -> open(new Intent(target)));
            // After the network pill and the VPN pill; the Hebrew bar runs the other way, so before both there.
            bar.addView(button, bar.indexOfChild(getResources().getBoolean(R.bool.rtl_menu_bar) ? btnVpn : btnWifi));
            topAppButtons.add(button);
        }
    }

    // ---- top bar ---------------------------------------------------------------------------------

    /** Some TVs (Coocaa OS) set the network time without ACTION_TIME_CHANGED, so keep checking until it is valid. */
    private final Runnable clockCheck = this::updateClockVisibility;

    private void updateClockVisibility() {
        boolean valid = System.currentTimeMillis() >= TIME_VALID_AFTER_MS;
        clockBlock.setVisibility(valid ? View.VISIBLE : View.INVISIBLE);
        handler.removeCallbacks(clockCheck);
        if (valid) {
            updateGreeting();
        } else {
            handler.postDelayed(clockCheck, 1_000L);
        }
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) res = R.string.good_night;
        else if (hour < 12) res = R.string.good_morning;
        else if (hour < 17) res = R.string.good_afternoon;
        else if (hour < 22) res = R.string.good_evening;
        else res = R.string.good_night;
        greeting.setText(greetingName.isEmpty() ? getString(res)
                : getString(R.string.greeting_with_name, getString(res), greetingName));
        greeting.setTextColor(greetingColor);
    }

    private void refreshPillsAfterNextDraw() {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                // Posted from inside the frame's traversal, so it runs after that frame is drawn. A post straight
                // from onResume would run first: the frame is only scheduled after onResume returns.
                handler.post(() -> {
                    if (isDestroyed()) return;
                    updateWifiLabel();
                    updateInputsButton();
                });
                return true;
            }
        });
        root.invalidate();   // a resume that changes nothing on screen still draws once, so the refresh runs
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    @SuppressWarnings("deprecation")
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false, validated = false;
        int icon = R.drawable.ic_wifi;
        try {
            ConnectivityManager cm = getApplicationContext().getSystemService(ConnectivityManager.class);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                // Android reached the internet through it; a captive portal stays unvalidated until login.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) validated = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            // TestHooks: the offline Home on a TV that is online (cutting the network would cut ADB as well).
            if (TestHooks.offline(this)) online = validated = false;
            if (!online) {
                // "No network" with the crossed-out fan instead of "Offline" (WifiPopup lists networks): on a
                // hotspot-only TV "no network" says what to do, "Offline" only what is wrong.
                text = getString(R.string.network_none);
                icon = R.drawable.ic_wifi_off;
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) text = ssid;
                }
            }
        } catch (Exception ignored) {
        }
        // WifiLink: while the TV joins a network the pill names it (the one asked for, else the one the
        // framework is joining) and spins instead of showing the fan; online on the old one meanwhile or not.
        boolean connecting = wifiLink != null && wifiLink.busy();
        if (connecting) {
            String target = wifiLink.target();
            if (target != null && !target.isEmpty()) text = target;
            else if (!online) {
                String joining = wifiLink.joining();
                text = joining != null ? joining : getString(R.string.wifi_connecting);
            }
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty() && !connecting;
        if (vpnPill) updateVpnPill(vpn);
        if ((vpn || vpnDown) && !vpnPill && !connecting) {
            // Network name, then the green lock-and-VPN (or red "VPN off") capsule drawn in place of the word.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            // The pill has a maximum width and ellipsizes at the end, which would cut the badge off after
            // a long network name, so shorten the name to whatever room the badge leaves. The Wi-Fi icon and its
            // gap are counted by hand: until the pill has a resolved layout direction (the first call, from
            // onResume, runs before that) getTotalPaddingStart/End leave the compound drawables out, which
            // left the name about 31dp too much room and the badge cut off.
            TextPaint paint = btnWifi.getPaint();
            Drawable[] compound = btnWifi.getCompoundDrawablesRelative();
            int iconRoom = compound[0] != null ? compound[0].getIntrinsicWidth() + btnWifi.getCompoundDrawablePadding() : 0;
            float room = btnWifi.getMaxWidth() - btnWifi.getPaddingStart() - btnWifi.getPaddingEnd() - iconRoom
                    - span.getSize(paint, badge, 0, badge.length(), null) - paint.measureText(" ") - 2 * density;
            String name = TextUtils.ellipsize(text, paint, Math.max(0, room), TextUtils.TruncateAt.END).toString();
            // A long badge ("VPN отключён") can leave room for a stub like "MT…", which says nothing and, after
            // rounding, can push the badge itself out of the pill. Show the badge alone then. On Ethernet the
            // icon already names the link, and "Ethernet" cut to "Ethe…" or not depended on the font size,
            // so Ethernet always shows the icon and the badge alone.
            // The space stays even then: a line holding only the span gets no font metrics and collapses.
            boolean stub = name.length() < text.length() && name.length() < 5;
            text = stub || icon == R.drawable.ic_ethernet ? "" : name;
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!TextUtils.equals(btnWifi.getText(), label)) btnWifi.setText(label);
        } else if (!TextUtils.equals(btnWifi.getText(), text)) {
            btnWifi.setText(text);
        }
        if (wifiIconOnly) {
            btnWifi.setContentDescription(btnWifi.getText());
            btnWifi.setText(null);
        }
        if (connecting) {
            showWifiSpinner();
        } else {
            stopWifiSpinner();
            if (icon != wifiIcon) {
                wifiIcon = icon;
                btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            }
            ColorStateList tint = online ? wifiOnlineTint : wifiPlainTint;
            // wifi_status_warnings: orange while connected but not validated (a captive portal), red while offline.
            if (wifiNoInternetTint != null) tint = !online ? wifiOfflineTint : validated ? wifiOnlineTint : wifiNoInternetTint;
            if (wifiOnlineTint != null && btnWifi.getCompoundDrawableTintList() != tint) {
                btnWifi.setCompoundDrawableTintList(tint);
            }
        }
        onNetworkRead(validated);
        // The data meter's ledger puts the bytes so far on the network that was connected.
        categoryHome.onNetworkChanged();
    }

    /** WifiLink busy: the spinner in place of the fan, in the pill's plain colour, turning while resumed. */
    private void showWifiSpinner() {
        if (wifiSpinner == null) wifiSpinner = getDrawable(R.drawable.ic_wifi_connecting);
        if (wifiSpinner == null) return;
        if (wifiIcon != WIFI_ICON_SPINNER) {
            wifiIcon = WIFI_ICON_SPINNER;
            btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(wifiSpinner, null, null, null);
        }
        if (wifiOnlineTint != null && btnWifi.getCompoundDrawableTintList() != wifiPlainTint) {
            btnWifi.setCompoundDrawableTintList(wifiPlainTint);
        }
        if (!resumed || (wifiSpinnerTurn != null && wifiSpinnerTurn.isStarted())) return;
        wifiSpinnerTurn = android.animation.ValueAnimator.ofInt(0, 10000);
        wifiSpinnerTurn.setDuration(900);
        wifiSpinnerTurn.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        wifiSpinnerTurn.setInterpolator(new android.view.animation.LinearInterpolator());
        Drawable turning = wifiSpinner;
        wifiSpinnerTurn.addUpdateListener(a -> turning.setLevel((Integer) a.getAnimatedValue()));
        wifiSpinnerTurn.start();
    }

    private void stopWifiSpinner() {
        if (wifiSpinnerTurn != null) {
            wifiSpinnerTurn.cancel();
            wifiSpinnerTurn = null;
        }
    }

    /** config.xml icon_only_buttons. */
    private boolean iconOnlyButtons;
    /** config.xml icon_only_inputs: the Inputs pill is a round icon for good, not only beside the debug pill. */
    private boolean iconOnlyInputs;
    /** config.xml top_menu: Inputs, Wi-Fi and Settings folded into one gear. */
    private boolean topMenu;
    /** show_inputs_pill as configured: top_menu clears showInputsPill, so its Inputs row reads this. */
    private boolean showInputsMenuRow = true;
    private boolean launcherSettingsButton;
    /** icon_only_buttons without wifi_label_width: the Wi-Fi pill shows no network name. */
    private boolean wifiIconOnly;
    /** config.xml wifi_status_color: the Wi-Fi icon's green while online and its usual pill colours; null while off. */
    private ColorStateList wifiOnlineTint, wifiPlainTint;
    /** wifi_status_warnings (with wifi_status_color): orange without internet, red offline; null while off. */
    private ColorStateList wifiNoInternetTint, wifiOfflineTint;

    /** Turns a labelled top pill into the round TopButtonIcon shape: no text, the icon centred by even padding. */
    private void makeIconOnly(TextView pill) {
        pill.setContentDescription(pill.getText());
        pill.setText(null);
        pill.setMaxWidth(Integer.MAX_VALUE);
        int pad = Math.round(10 * density);
        pill.setPaddingRelative(pad, 0, pad, 0);
        pill.setCompoundDrawablePadding(0);
        pill.getLayoutParams().width = Math.round(42 * density);
    }

    /**
     * The network pill opens the VPN app when one is configured and installed, otherwise the Wi-Fi dropdown
     * (WifiPopup). With vpn_pill the VPN app has its own pill, so this one always opens the dropdown.
     */
    private void openNetworkPill() {
        Intent i = vpnPill ? null : vpnAppIntent();
        // Posted: the top_menu row calling this has just dismissed its own popup.
        if (i != null) open(i);
        else root.post(this::showWifiPopup);
    }

    /** Nearby networks under the Wi-Fi pill (under the gear with top_menu), styled like the Inputs list. */
    private void showWifiPopup() {
        showWifiPopup(false);
    }

    /** @param onData focus on the data line at the bottom (back from its detail), not the first network. */
    private void showWifiPopup(boolean onData) {
        if (isFinishing() || isDestroyed() || !resumed) return;
        if (openPopup != null) openPopup.dismiss();
        boolean underGear = btnWifi.getVisibility() != View.VISIBLE;
        View anchor = underGear ? findViewById(R.id.btnSettings) : btnWifi;
        int gravity = getResources().getBoolean(R.bool.rtl_menu_bar) != underGear ? Gravity.END : Gravity.START;
        new WifiPopup(this, root, new WifiPopup.Host() {
            @Override
            public PopupWindow glassPopup(View panel, int width) {
                return MainActivity.this.glassPopup(panel, width);
            }

            @Override
            public void dropIn(PopupWindow popup, View panel, View a) {
                MainActivity.this.dropIn(popup, panel, a, gravity);
            }

            @Override
            public void open(Intent intent) {
                MainActivity.this.open(intent);
            }

            @Override
            public void openWifiSettings() {
                MainActivity.this.open(systemIntent(Settings.ACTION_WIFI_SETTINGS));
            }

            // One-press Wi-Fi (WifiLink): the fallbacks are the old TvSettings screens.
            @Override
            public boolean quickEnable(Runnable fallback) {
                return wifiLink != null && wifiLink.enable(() -> {
                    if (resumed) fallback.run();
                });
            }

            @Override
            public boolean quickConnect(String ssid, int security, Runnable fallback) {
                return wifiLink != null && wifiLink.connect(ssid, security, () -> {
                    if (resumed) fallback.run();
                });
            }

            @Override
            public String connectingTo() {
                return wifiLink != null ? wifiLink.target() : null;
            }

            @Override
            public boolean isSaved(String ssid) {
                return wifiLink != null && wifiLink.isSaved(ssid);
            }

            @Override
            public void watchSaved(Runnable changed, long maxAgeMs) {
                if (wifiLink == null) return;
                wifiLink.setSavedListener(changed);
                if (changed != null) wifiLink.loadSaved(maxAgeMs);
            }

            @Override
            public int rank(String ssid) {
                return wifiLink != null ? wifiLink.rank(ssid) : Integer.MAX_VALUE;
            }

            // The data meter's line at the bottom, and its detail under the same pill.
            @Override
            public CharSequence dataLine() {
                return categoryHome.dataLine();
            }

            @Override
            public boolean dataDetail() {
                return categoryHome.dataDetail();
            }

            @Override
            public void openData() {
                if (categoryHome != null) {
                    root.post(() -> categoryHome.showDataDetail(anchor, gravity, () -> showWifiPopup(true)));
                }
            }
        }).show(anchor, onData);
    }

    /** vpn_app's launch intent, or null when none is set or it is not installed. */
    private Intent vpnAppIntent() {
        if (vpnApp.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        return i != null ? i : getPackageManager().getLaunchIntentForPackage(vpnApp);
    }

    /** vpn_pill: the VPN app, where it is switched on and off; Android's VPN settings without one installed. */
    private void openVpnPill() {
        Intent i = vpnAppIntent();
        open(i != null ? i : systemIntent(Settings.ACTION_VPN_SETTINGS));
    }

    /** vpn_pill: the pill outlined in green with a lock while a VPN is up, outlined in red "VPN off" while it is not;
     *  solid with a white ring only while focused. */
    private void updateVpnPill(boolean vpn) {
        int state = VpnBadge.pillState(vpn, vpnAppIntent() != null);
        if (state == VpnBadge.PILL_HIDDEN) {
            if (btnVpn.getVisibility() == View.GONE) return;
            boolean focused = btnVpn.hasFocus();
            btnVpn.setVisibility(View.GONE);
            if (focused) (btnWifi.getVisibility() == View.VISIBLE ? btnWifi : firstTopButton()).requestFocus();
            return;
        }
        if (state != vpnPillState) {
            vpnPillState = state;
            boolean up = state == VpnBadge.PILL_UP;
            // The state colour at rest (the pill is an outline then), the contrasting ink on the solid focused fill.
            ColorStateList ink = new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                    new int[]{VpnBadge.pillInkColor(up, true), VpnBadge.pillInkColor(up, false)});
            if (iconOnlyVpn) {
                // An open lock on the plain glass button, or the closed lock outlined in green like the pill (solid
                // while focused); the words go to talkback.
                btnVpn.setContentDescription(getString(up ? R.string.vpn : R.string.vpn_off));
                btnVpn.setBackground(up ? VpnBadge.pillBackground(true, density) : vpnPlainBackground.newDrawable());
                btnVpn.setCompoundDrawablesRelativeWithIntrinsicBounds(VpnBadge.iconOnlyLock(up), 0, 0, 0);
                btnVpn.setCompoundDrawableTintList(up ? ink : vpnPlainInk);
                btnVpn.setVisibility(View.VISIBLE);
                return;
            }
            btnVpn.setText(up ? R.string.vpn : R.string.vpn_off);
            btnVpn.setTextColor(ink);
            btnVpn.setBackground(VpnBadge.pillBackground(up, density));
            btnVpn.setCompoundDrawablesRelativeWithIntrinsicBounds(up ? R.drawable.ic_lock : 0, 0, 0, 0);
            btnVpn.setCompoundDrawableTintList(ink);
        }
        btnVpn.setVisibility(View.VISIBLE);
    }


    /** Every settings entry shares the standard intent and the vendor fallback (SystemSettings). */
    private Intent systemIntent(String standardAction) {
        return SystemSettings.intent(this, standardAction);
    }

    /** Hides the Inputs pill when it would have nothing to list: streaming boxes have no HDMI-in, AV or tuner. */
    private void updateInputsButton() {
        if (!showInputsPill) {
            btnInputs.setVisibility(View.GONE);
            return;
        }
        boolean any = liveTvIntent() != null;
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (!any && tim != null) {
            for (TvInputInfo info : tim.getTvInputList()) {
                // Inputs hidden in Settings > Inputs are left out of the menu, so they don't count here either.
                if (info.isPassthroughInput() && !info.isHidden(this)) {
                    any = true;
                    break;
                }
            }
        }
        btnInputs.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /** boot_to_live_tv, or the Open on start app: the first time Home comes up after the TV starts, open that app
     *  instead. Once per boot, keyed on Settings.Global.BOOT_COUNT and committed to disk before the app starts: on a
     *  low-memory TV the app can push the launcher out of memory, and an apply() still in flight was lost then, so
     *  every Home press opened the app again. */
    private boolean openLiveTvAfterBoot() {
        String app = openOnStartChoice ? getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString(OpenOnStart.KEY, "") : "";
        if (!bootToLiveTv && app.isEmpty()) return false;
        int boot = OpenOnStart.boot(this);
        SharedPreferences prefs = getSharedPreferences(OpenOnStart.BOOT_PREFS, MODE_PRIVATE);
        String target = bootToLiveTv ? liveTvPkg : app;
        if (!OpenOnStart.due(boot, prefs.getInt(OpenOnStart.BOOT_KEY, -1), target, getPackageName())) return false;
        if (!prefs.edit().putInt(OpenOnStart.BOOT_KEY, boot).commit()) return false;
        if (bootToLiveTv && bootInputId != null) {
            openInput(bootInputId);
            return true;
        }
        Intent i = bootToLiveTv ? liveTvIntent() : OpenOnStart.intent(getPackageManager(), app);
        if (i == null) return false;
        return openWhenReady(i);
    }

    /** open_on_start_on_wake: the remote's power button is a light sleep on some sets, with no boot for
     *  openLiveTvAfterBoot to catch. */
    private void openStartAppOnWake() {
        String app = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(OpenOnStart.KEY, "");
        if (app.isEmpty() || app.equals(getPackageName())) return;
        Intent i = OpenOnStart.intent(getPackageManager(), app);
        if (i != null) openWhenReady(i);
    }

    private boolean openWhenReady(Intent i) {
        // open_on_start_network_wait: Home stays on screen until the network is back or the wait is over.
        if (openOnStartWaitMs > 0 && !OpenOnStart.online(this)) {
            startPending = i;
            startDeadline = SystemClock.elapsedRealtime() + openOnStartWaitMs;
            handler.postDelayed(openWhenOnline, openOnStartWaitMs);
            // Validated between the check above and startPending: networkCallback may have skipped it.
            if (OpenOnStart.online(this)) handler.post(openWhenOnline);
            return false;
        }
        open(i);
        return true;
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI, which the
     *  Live TV app handles; where nothing takes that, the configured `input_action` is sent with the same URI instead
     *  (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). An `input_action` written as "package/.Activity" starts that activity
     *  itself, ahead of the standard way, for firmware that answers the channel URI with "unsupported"
     *  (Redmi X65, MiTV-NSOP0: com.xiaomi.mitv.tvplayer/.ExternalSourceActivity). With `input_uri_scheme` set (YaOS: live-tv) the same URI goes out
     *  with that scheme and no MIME type. A player guarded by a permission this build does not declare
     *  throws SecurityException, which used to take Home down with it (YaOS before the manifest asked
     *  for ACCESS_TV_SOURCE). */
    private void openInput(String inputId) {
        if (InputAction.isComponent(inputAction)) {
            // An activity named in input_action is the vendor's own source picker (Xiaomi: tvplayer's
            // ExternalSourceActivity). Its firmware answers the standard channel URI with "unsupported", so the
            // row goes straight there; the picker is the overlay the remote's Source button opens.
            try {
                startActivity(inputActionIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // fall through to the standard way
            }
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = (inputUriScheme.isEmpty()
                ? new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                : new Intent(Intent.ACTION_VIEW, uri.buildUpon().scheme(inputUriScheme).build()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Aim it at the TV app first: apps installed later (a streaming app, a file manager) can claim the same
        // channel URI too, and with two or more handlers Android shows a chooser instead of the input (WhaleOS).
        if (!liveTvPkg.isEmpty()) {
            try {
                startActivity(new Intent(view).setPackage(liveTvPkg));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (inputAction.isEmpty()) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(inputActionIntent().setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** `input_action` as an intent: an explicit component when it reads "package/.Activity", else an action. */
    private Intent inputActionIntent() {
        ComponentName component = InputAction.isComponent(inputAction)
                ? ComponentName.unflattenFromString(inputAction) : null;
        return component != null ? new Intent().setComponent(component) : new Intent(inputAction);
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        // top_menu: the pill is gone, so the list hangs under the gear, growing inward from the right.
        // Read the switch, not the pill: show_inputs_pill=false hides the pill too, and there the list
        // keeps its usual place.
        boolean underGear = topMenu;
        View anchor = findViewById(underGear ? R.id.btnSettings : R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        // inputs_connected_only: ports without a connected device and the AV jacks fold behind a "More inputs" row.
        boolean connectedOnly = inputsConnectedOnly;
        List<View> folded = new ArrayList<>();
        boolean sawNonHdmi = false;
        // An explicit root URI asks the Live TV app to leave any HDMI source selected by its own UI.
        boolean rootChannel = TvContract.Channels.CONTENT_URI.toString().equals(liveTvInputId);
        // live_tv_input_id "app": the Live TV row only opens the Live TV app, with no tuner URI. On Philips PH1M
        // (MediaTek, Android 11) the passthrough URI of the tuner crashes com.mediatek.tvinput each time
        // (NumberFormatException in TunerInputService.getChannel: it reads the input ID as a channel number).
        boolean liveTvAppOnly = "app".equals(liveTvInputId);
        String tunerId = null;
        boolean tunerIsHw = false;
        boolean tunerIsAnalog = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // An HDMI-CEC device (an Apple TV, a soundbar) gets an input of its own whose parent is the
            // port it sits on, so the port and the device would each get a row (Vestel MT7332: "HDMI 1"
            // and "HDMI Apple TV"). The device takes its port's row instead, and a bare port is listed
            // only when nothing on it speaks CEC.
            Set<String> inputIds = new HashSet<>();
            for (TvInputInfo info : inputs) inputIds.add(info.getId());
            Map<String, List<TvInputInfo>> cecDevices = new HashMap<>();
            for (TvInputInfo info : inputs) {
                String parent = info.getParentId();
                if (parent == null || !inputIds.contains(parent)) continue;
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                List<TvInputInfo> onPort = cecDevices.get(parent);
                if (onPort == null) cecDevices.put(parent, onPort = new ArrayList<>());
                onPort.add(info);
            }
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            // inputs_cec_state_only: the TV puts port states on the wrong port (TCL UnionTV, Android 9), so
            // ports show none, and a CEC device row shows its own state, which the CEC bus keeps right.
            boolean hdmiStateBogus = inputsCecStateOnly || (hdmiPorts > 1 && hdmiConnected == hdmiPorts);
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && !liveTvAppOnly
                        && (liveTvInputId.isEmpty() || rootChannel || liveTvInputId.equals(info.getId()))) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    // Among tuners of the same kind, skip an analog input when another is present.
                    // MediaTek names them AnalogInputService and TunerInputService; alphabetical order
                    // would pick the analog one, and Live TV then opens a dead ATV source.
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    boolean analog = info.getId().toLowerCase(Locale.ROOT).contains("analog");
                    boolean better = tunerId == null
                            || (hw && !tunerIsHw)
                            || (hw == tunerIsHw && tunerIsAnalog && !analog)
                            || (hw == tunerIsHw && analog == tunerIsAnalog
                                    && info.getId().compareTo(tunerId) < 0);
                    if (better) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                        tunerIsAnalog = analog;
                    }
                }
                // Inputs hidden in the TV's Settings > Inputs stay out of the menu too.
                if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                // A CEC device's row is added with its port, below.
                if (info.getParentId() != null && inputIds.contains(info.getParentId())) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                CharSequence custom = InputNames.tidy(info.loadCustomLabel(this));
                boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(label);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) {
                    View line = addSeparator(rows);
                    if (connectedOnly) fold(line, folded);
                }
                if (!hdmi) sawNonHdmi = true;
                List<TvInputInfo> devices = cecDevices.get(info.getId());
                if (devices != null) {
                    // "Apple TV · HDMI 1": the device's own name (or the one given to it or to its port in
                    // Settings > Inputs), then the port. Opening the device's input also makes it the CEC
                    // active source, which wakes it.
                    for (TvInputInfo device : devices) {
                        CharSequence name = InputNames.tidy(device.loadCustomLabel(this));
                        if (name == null || name.length() == 0) name = named ? custom : device.loadLabel(this);
                        String deviceLabel = name == null || name.length() == 0 ? label : name + " \u00B7 " + label;
                        final String deviceId = device.getId();
                        int state = hdmiStateBogus && !inputsCecStateOnly ? -1 : tim.getInputState(deviceId);
                        View row = addInputRow(rows, popup, R.drawable.ic_input, deviceLabel, state, () -> openInput(deviceId));
                        if (connectedOnly && InputFold.folds(true, state)) fold(row, folded);
                    }
                    continue;
                }
                // The name given in Settings > Inputs (e.g. "MacBook Pro") goes first, the port after it.
                if (named) label = custom + " \u00B7 " + label;
                final String id = info.getId();
                // Analog AV jacks have no hotplug detection, and some TVs report them as always connected
                // (TCL Smart TV Pro G03 says "Connected" with nothing plugged in), so AV rows show no state.
                int state = !hdmi || hdmiStateBogus ? -1 : tim.getInputState(id);
                View row = addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
                if (connectedOnly && InputFold.folds(hdmi, state)) fold(row, folded);
            }
        }
        if (!folded.isEmpty()) {
            View more = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            ((ImageView) more.findViewById(R.id.icon)).setImageResource(R.drawable.ic_input);
            ((TextView) more.findViewById(R.id.title)).setText(R.string.more_inputs);
            more.findViewById(R.id.status).setVisibility(View.GONE);
            more.findViewById(R.id.dot).setVisibility(View.GONE);
            more.setOnClickListener(v -> {
                for (View f : folded) f.setVisibility(View.VISIBLE);
                rows.removeView(more);
                for (View f : folded) {
                    if (f.isFocusable()) { f.requestFocus(); break; }
                }
            });
            rows.addView(more);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            // Tune that input only when the Live TV package itself handles the URI.
            // The unrestricted input player also resolves it (that is how HDMI opens), but on the
            // AIWA AI PONT SA that player stays black, and the Live TV package does not handle the
            // URI at all, which used to toast "Not available on this TV". Keep the leanback activity
            // then: it is the same path as the Live TV tile. Do not drop the package restriction.
            // An explicit tuner uses its channel collection (e.g. DVB-S rather than DVB-T/C).
            // Keep the legacy passthrough route for automatic selection: some OEM players need it.
            // If the configured input is absent, tunerId stays null and the app opens normally.
            boolean configured = !liveTvInputId.isEmpty() && !rootChannel;
            Intent tuned = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(configured ? TvContract.buildChannelsUriForInput(tunerId)
                                    : TvContract.buildChannelUriForPassthroughInput(tunerId),
                            configured ? TvContract.Channels.CONTENT_TYPE : TvContract.Channels.CONTENT_ITEM_TYPE)
                    .setPackage(liveTvPkg);
            ComponentName handler = tuned.resolveActivity(getPackageManager());
            if (handler != null) {
                // On some MediaTek sets the tuner passthrough URI leaves the app's HDMI source selected.
                // The stock launcher addresses the same activity with the root channel URI instead.
                liveTv = rootChannel
                        ? new Intent(Intent.ACTION_VIEW).setData(TvContract.Channels.CONTENT_URI).setComponent(handler)
                        : tuned;
            }
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, Math.min(panel.getMeasuredWidth(), (int) (360 * density))));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) != underGear ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child.isFocusable()) { child.requestFocus(); break; }
        }
    }

    private static void fold(View view, List<View> folded) {
        view.setVisibility(View.GONE);
        folded.add(view);
    }

    PopupWindow glassPopup(View panel, int width) {
        final PopupWindow[] self = new PopupWindow[1];
        final PopupWindow popup = new PopupWindow(keepHomeKeys(panel, self), width, ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        self[0] = popup;
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
            // What else goes with this popup (markOpen): one listener, so neither replaces the other.
            Runnable also = onPopupClosed.remove(popup);
            if (also != null) also.run();
        });
        onPopupClosed.put(popup, null);
        openPopup = popup;
        return popup;
    }

    /**
     * A digit or colour key that Home would take does nothing while a dropdown is open, rather than
     * reach the TV (unhandled in the popup's window, the blue key opened the Philips manual over Home). The panel is
     * wrapped so the popup's rows still see every key first. Also: Up on a pill dropdown's first row closes it as Back
     * does (DropdownKeys; the pill gets the focus back and markOpen's close resets its open look).
     */
    private View keepHomeKeys(View panel, PopupWindow[] self) {
        FrameLayout wrap = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (super.dispatchKeyEvent(event)) return true;
                PopupWindow p = self[0];
                if (p != null && pillPopups.contains(p) && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
                    View f = findFocus();
                    boolean above = f != null && android.view.FocusFinder.getInstance()
                            .findNextFocus(this, f, View.FOCUS_UP) != null;
                    if (DropdownKeys.closesOnUp(event.getKeyCode(), event.getAction(), event.getRepeatCount(), above)) {
                        p.dismiss();
                        return true;
                    }
                }
                return categoryHome.takesKey(event.getKeyCode());
            }
        };
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);
        wrap.addView(panel);
        return wrap;
    }

    /** The dropdowns hanging under a top-bar pill (markOpen): Up on their first row closes them (keepHomeKeys). */
    private final java.util.Set<PopupWindow> pillPopups =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** glassPopup's popups and what runs when each goes, besides clearing openPopup. */
    private final java.util.Map<PopupWindow, Runnable> onPopupClosed = new java.util.WeakHashMap<>();

    /**
     * A top-bar pill whose dropdown is open is drawn open (activated: pill_bg's brighter glass with a thin rim) until
     * the dropdown goes, rather than keeping the inverted focus fill while focus is in the dropdown. Not for a tile
     * that anchors a dropdown (the tile menu).
     */
    private void markOpen(PopupWindow popup, View anchor) {
        if (anchor == null || anchor.getParent() != findViewById(R.id.topButtons)) return;
        anchor.setActivated(true);
        pillPopups.add(popup);
        if (onPopupClosed.containsKey(popup)) {
            alsoOnClose(popup, () -> anchor.setActivated(false));
        } else {
            // Not one of glassPopup's: its own listener is the only one.
            popup.setOnDismissListener(() -> anchor.setActivated(false));
        }
    }

    /** One more thing to run when a glassPopup goes, after what is already there (markOpen's, a caller's own). */
    void alsoOnClose(PopupWindow popup, Runnable r) {
        if (!onPopupClosed.containsKey(popup)) return;
        Runnable had = onPopupClosed.get(popup);
        onPopupClosed.put(popup, had == null ? r : () -> {
            had.run();
            r.run();
        });
    }

    /** The gap between a pill and its dropdown (dropIn); WifiPopup needs it to know where its panel starts. */
    static final int DROP_GAP_DP = 10;

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        markOpen(popup, anchor);
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (DROP_GAP_DP * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(reducedMotion ? Motion.REDUCED_MS : 220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private View addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
        return line;
    }

    private View addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
        return row;
    }

    // ---- top menu and settings menu ----------------------------------------------------------------

    /** top_menu: the gear's dropdown, standing in for the Inputs, Wi-Fi and Settings pills. */
    private void showTopMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (220 * density));
        // Inputs opens its own list once this one has closed.
        if (showInputsMenuRow) addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, () -> root.post(this::showInputs));
        addInputRow(rows, popup, R.drawable.ic_wifi, getString(R.string.quick_network), -1, this::openNetworkPill);
        CharSequence network = wifiIconOnly ? btnWifi.getContentDescription() : btnWifi.getText();
        if (!TextUtils.isEmpty(network)) {
            TextView status = rows.getChildAt(rows.getChildCount() - 1).findViewById(R.id.status);
            status.setText(network);
            status.setVisibility(View.VISIBLE);
        }
        if (launcherSettingsButton) {
            addInputRow(rows, popup, R.drawable.ic_home_settings_pill, getString(R.string.launcher_settings), -1,
                    () -> startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        } else {
            addInputRow(rows, popup, R.drawable.ic_settings, getString(R.string.settings), -1,
                    () -> open(systemIntent(Settings.ACTION_SETTINGS)));
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (220 * density), Math.min(panel.getMeasuredWidth(), (int) (360 * density))));
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    /** The header opens launcher settings directly; hold OK keeps the extra home actions available. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        if (!launcherSettingsButton) addSettingsMenuRow(rows, popup, R.string.launcher_settings, () -> startActivityForResult(
                new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST));
        // Firmware that ships its own settings app next to the AOSP one (Xiaomi China is one): the
        // vendor app holds pages the AOSP settings has no row for, such as Account & Security with
        // the ADB switch. Offered here only while one of them is installed.
        if (SystemSettings.vendorIntent(this) != null) {
            addSettingsMenuRow(rows, popup, R.string.native_settings, () -> {
                Intent vendor = SystemSettings.vendorIntent(this);
                if (vendor != null) open(vendor);
            });
        }
        // A launcher may not restart the TV itself; the Home key helper can open the system power menu instead.
        if (HomeKeyService.canOpenPowerMenu()) addSettingsMenuRow(rows, popup, R.string.power_menu, HomeKeyService::openPowerMenu);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // Hebrew puts Settings at the left of the header: grow the menu inward from it.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

}
