package io.github.leopierini.rehome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * {@code config.json} in the app's external files directory
 * ({@code /sdcard/Android/data/io.github.leopierini.rehome/files/}). Every key in the JSON is optional and an
 * unknown or badly typed key is ignored, so a theme pushed there changes the look without a rebuild; Home reads it
 * when it starts.
 *
 * Read once in {@link MainActivity#onCreate}; nothing here touches the network.
 */
final class Config {
    private static final String TAG = "Rehome.Config";
    static final String FILE_NAME = "config.json";
    static final String WALLPAPER_NAME = "wallpaper.jpg";
    static final String SETTINGS_NAME = "launcher_settings";
    static final String WALLPAPER_CHOICE_KEY = "wallpaper_choice";
    static final String CLOCK_FORMAT_KEY = "clock_format";
    /**
     * Counts the changes made in Launcher settings. Home remembers the count it was built with and rebuilds on
     * resume when it differs, however the settings were left (see MainActivity#onResume).
     */
    static final String SETTINGS_REVISION_KEY = "settings_revision";

    /** True when Launcher settings changed something since Home was built at revision {@code seen}. */
    static boolean settingsChanged(long seen, long current) {
        return seen != current;
    }
    private static final String THEME_REVISION_NAME = "theme_revision";
    private static final String THEME_SIGNATURE_KEY = "theme_signature";

    final List<String> hiddenApps;
    final String liveTvPackage;
    final String liveTvAction;
    final String liveTvInputId;
    /** Package to the action its tile sends instead of opening the start screen (config.xml, launch_actions). */
    final Map<String, String> launchActions;
    final String inputAction;
    /** Scheme an Inputs row's passthrough URI is sent with instead of content:// (config.xml or theme input_uri_scheme). */
    final String inputUriScheme;
    final boolean animations;
    final boolean advancedEffects;
    /** The date on its own line under the clock instead of beside it (config.xml, date_below_clock). */
    final boolean dateBelowClock;
    /** The VPN state as its own pill after the network pill, instead of a badge inside it (config.xml, vpn_pill). */
    final boolean vpnPill;
    /** Language tag the launcher shows itself in instead of the TV's, or "" for the TV's (config.xml, locale). */
    final String locale;
    /** Name after the greeting ("Good morning, Mr. Trịnh"); empty for the greeting alone. */
    final String greetingName;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    final boolean reducedMotion;
    /** Open the Live TV app on the first Home after the TV starts (config.xml, boot_to_live_tv). */
    final boolean bootToLiveTv;
    /** Show the Open on start row in Launcher settings (config.xml, open_on_start_choice). */
    final boolean openOnStartChoice;
    /** Seconds the boot's app waits for a validated network; 0 opens it at once (open_on_start_network_wait). */
    final int openOnStartNetworkWait;
    /** The Open on start app also opens when the screen comes back on (config.xml, open_on_start_on_wake). */
    final boolean openOnStartOnWake;
    /** Inputs, Wi-Fi and Settings folded into one gear button with a dropdown, like Google TV (config.xml top_menu). */
    final boolean topMenu;
    /** Direct launcher-settings header action; off by default (launcher_settings_button). */
    final boolean launcherSettingsButton;
    /** Show the Wi-Fi/Ethernet pill in the top bar (config.xml, show_network_pill). */
    final boolean showNetworkPill;
    /** Show the Inputs pill when the TV has inputs to list (config.xml, show_inputs_pill). */
    final boolean showInputsPill;
    /** The Wi-Fi pill as a round icon button like the gear, Inputs keeps its label (config.xml icon_only_buttons). */
    final boolean iconOnlyButtons;
    /** The Inputs pill as a round icon button too (config.xml icon_only_inputs). */
    final boolean iconOnlyInputs;
    /** With vpn_pill, the VPN pill as a round lock button (config.xml / theme icon_only_vpn). */
    final boolean iconOnlyVpn;
    /** Back on the Home screen opens the clock screensaver instead of doing nothing (config.xml / theme back_opens_clock). */
    final boolean backOpensClock;
    /** The remote's Settings button clears the TV Settings task before opening it (config.xml / theme settings_key_clear_task). */
    final boolean settingsKeyClearTask;
    /** With icon_only_buttons, the Wi-Fi pill keeps the network name up to this width in dp (0: icon only). */
    final int wifiLabelWidth;
    /** Decode wallpapers in 32-bit colour instead of RGB_565 (config.xml wallpaper_true_color). */
    final boolean wallpaperTrueColor;
    final String vpnApp;
    /** One line under the clock: CPU, free memory, free storage. Off unless config or a theme turns it on. */
    final boolean showStats;
    /** Background colour behind everything, #RRGGBB; black unless a theme says otherwise. */
    final int backgroundColor;
    /** Remote keycodes that open Settings through HomeKeyService (config.xml, settings_key_keycodes). */
    final int[] settingsKeycodes;
    /** Extra keycodes that open the input switcher in InputKeyService (config.xml input_key_keycodes). */
    final int[] inputKeycodes;
    /** Remote buttons that open an app, "keycode:package" or "KEYCODE_NAME:package" (config.xml app_keys). */
    final List<String> appKeys;
    /** Whether the key services request key filtering (config.xml or theme key_filter); false keeps key
     *  auto-repeat working on firmware whose AccessibilityInputFilter drops repeats (TCL Android 12). */
    final boolean keyFilter;
    /** With keyFilter on, the key services stop filtering while a game controller is connected (gamepad_passthrough). */
    final boolean gamepadPassthrough;
    /** With keyFilter on, the key services swallow Volume Up/Down and repeat a held one themselves (consume_volume_keys). */
    final boolean consumeVolumeKeys;
    /** With keyFilter on, a volume change stops the key services filtering for a few seconds (volume_pauses_filter). */
    final boolean volumePausesFilter;
    /** Channel Up/Down do nothing while Home is in front, instead of opening the TV's Live TV app. */
    final boolean homeIgnoreChannelKeys;
    /** Stock launcher whose home screen is sent back here if the firmware revives it (config.xml stock_home_redirect). */
    final String stockHomeRedirect;
    /** A Live TV window the firmware opens by itself at power-on is sent back to Home (power_on_home). */
    final boolean powerOnHome;
    /** Every Home press opens this launcher, a held one too, instead of Android's long-press action (home_long_press_home). */
    final boolean homeLongPressHome;
    /** Home at every power-on, whatever the firmware put in front: an input, Live TV or an app (power_on_home_always). */
    final boolean powerOnHomeAlways;
    /** The Inputs menu lists only inputs reporting connected; the rest fold behind a More inputs row (config.xml / theme inputs_connected_only). */
    final boolean inputsConnectedOnly;
    /** Only CEC device rows in the Inputs menu show a connection state, never a bare HDMI port (config.xml / theme inputs_cec_state_only). */
    final boolean inputsCecStateOnly;
    /** "12", "24" or "system" (theme clock_format, or Launcher settings' Clock row); see ClockFormat. */
    final String clockFormat;
    /** The Settings button opens the quick settings panel instead of the TV's Settings (settings_key_panel). */
    final boolean settingsKeyPanel;
    /** Home puts the remote's accessibility helpers back after a force stop (restore_key_services; ServiceRepair). */
    final boolean restoreKeyServices;
    /** Whether Home shows a wallpaper (config.xml or a theme's config.json, wallpaper). */
    final boolean wallpaperEnabled;
    /** The picture picked in Launcher settings, else a theme's single wallpaper.jpg, else null. */
    final File wallpaper;
    /** What Home rotates through: a theme's collection, its single wallpaper, or the built-in pictures. */
    final List<File> wallpapers;
    final int wallpaperRotationMinutes;
    final int wallpaperDim;
    /**
     * Playback order for a wallpaper collection: {@code sequential} or {@code shuffle}. Missing or
     * invalid values keep the historical shuffle so existing themes rotate exactly as before.
     */
    final String wallpaperOrder;
    /** Whether the overlay file was present and parsed. */
    final boolean overlayApplied;
    /** Minutes Home may sit untouched before the clock screensaver (config.xml, home_idle_clock_minutes); 0 is off. */
    final int homeIdleClockMinutes;
    /** The Wi-Fi pill's icon green while the TV is online (config.xml, wifi_status_color); false keeps it white. */
    final boolean wifiStatusColor;
    /** A rotating collection's groups by time of day, as file names (config.xml, wallpaper_moods); empty rotates through all of it. */
    final Map<String, Set<String>> wallpaperMoods;

    /** Show each rotating wallpaper's name from wallpapers/captions.txt (config.xml / theme wallpaper_captions). */
    final boolean wallpaperCaptions;
    /** Answer VIEW_INPUTS with the Inputs menu (handle_view_inputs); see InputsRequestActivity. */
    final boolean handleViewInputs;
    /** With wifi_status_color, the icon orange while connected without internet (a captive portal) and red while offline (config.xml, wifi_status_warnings). */
    final boolean wifiStatusWarnings;
    /** Apps shown as round logo buttons in the top bar, left of the network pill, and no longer as tiles (config.xml, top_bar_apps). */
    final List<String> topBarApps;
    /** The greeting above the clock (Good evening); false hides it (config.xml, show_greeting). */
    final boolean showGreeting;
    /** The date and the line between it and the clock in the clock's full white instead of dimmed (config.xml, header_white). */
    final boolean headerWhite;
    /** The clock screensaver over Home's wallpaper instead of the black glow (config.xml, clock_wallpaper). */
    final boolean clockWallpaper;
    /** How dark the clock screensaver's wallpaper is, 0 to 90 % (config.xml, clock_wallpaper_dim). */
    final int clockWallpaperDim;
    /** The clock screensaver's coloured glow; false leaves the clock on plain black, e.g. for an OLED (config.xml, clock_glow). */
    final boolean clockGlow;

    private Config(List<String> hiddenApps, String liveTvPackage,
                   String liveTvAction, String liveTvInputId, Map<String, String> launchActions, String inputAction,
                   String inputUriScheme, boolean animations, boolean advancedEffects,
                   String greetingName, boolean reducedMotion, boolean bootToLiveTv, boolean openOnStartChoice, boolean topMenu, boolean launcherSettingsButton, boolean showNetworkPill, boolean showInputsPill, boolean iconOnlyButtons, boolean iconOnlyInputs, int wifiLabelWidth, boolean wallpaperTrueColor, String vpnApp,
                   boolean showStats, int backgroundColor,
                   int[] settingsKeycodes, boolean settingsKeyPanel, boolean restoreKeyServices, boolean wallpaperEnabled, File wallpaper, List<File> wallpapers,
                   int wallpaperRotationMinutes, int wallpaperDim, String wallpaperOrder, boolean overlayApplied,
                   int[] inputKeycodes, List<String> appKeys, String stockHomeRedirect, int homeIdleClockMinutes,
                   boolean keyFilter, boolean wallpaperCaptions,
                   boolean handleViewInputs, boolean wifiStatusColor,
                   Map<String, Set<String>> wallpaperMoods, boolean gamepadPassthrough, boolean consumeVolumeKeys, boolean volumePausesFilter, boolean homeIgnoreChannelKeys, boolean powerOnHome, boolean homeLongPressHome, boolean wifiStatusWarnings, List<String> topBarApps, boolean showGreeting, boolean headerWhite, boolean clockWallpaper, int clockWallpaperDim, boolean clockGlow, boolean dateBelowClock, boolean vpnPill, String locale, boolean powerOnHomeAlways, boolean inputsConnectedOnly, boolean inputsCecStateOnly,
                   int openOnStartNetworkWait, boolean openOnStartOnWake, boolean iconOnlyVpn, String clockFormat, boolean backOpensClock,
                   boolean settingsKeyClearTask) {
        this.handleViewInputs = handleViewInputs;
        this.inputKeycodes = inputKeycodes;
        this.appKeys = Collections.unmodifiableList(appKeys);
        this.keyFilter = keyFilter;
        this.stockHomeRedirect = stockHomeRedirect;
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.liveTvInputId = liveTvInputId;
        this.launchActions = Collections.unmodifiableMap(launchActions);
        this.inputAction = inputAction;
        this.inputUriScheme = inputUriScheme;
        this.animations = animations;
        this.advancedEffects = advancedEffects;
        this.dateBelowClock = dateBelowClock;
        this.vpnPill = vpnPill;
        this.locale = locale;
        this.greetingName = greetingName;
        this.reducedMotion = reducedMotion;
        this.bootToLiveTv = bootToLiveTv;
        this.openOnStartChoice = openOnStartChoice;
        this.topMenu = topMenu;
        this.launcherSettingsButton = launcherSettingsButton;
        this.showNetworkPill = showNetworkPill;
        this.showInputsPill = showInputsPill;
        this.iconOnlyButtons = iconOnlyButtons;
        this.iconOnlyInputs = iconOnlyInputs;
        this.iconOnlyVpn = iconOnlyVpn;
        this.backOpensClock = backOpensClock;
        this.settingsKeyClearTask = settingsKeyClearTask;
        this.wifiLabelWidth = wifiLabelWidth;
        this.wallpaperTrueColor = wallpaperTrueColor;
        this.vpnApp = vpnApp;
        this.showStats = showStats;
        this.backgroundColor = backgroundColor;
        this.settingsKeycodes = settingsKeycodes;
        this.settingsKeyPanel = settingsKeyPanel;
        this.restoreKeyServices = restoreKeyServices;
        this.wallpaperEnabled = wallpaperEnabled;
        this.wallpaper = wallpaper;
        this.wallpapers = Collections.unmodifiableList(wallpapers);
        this.wallpaperRotationMinutes = wallpaperRotationMinutes;
        this.wallpaperDim = wallpaperDim;
        this.wallpaperOrder = wallpaperOrder;
        this.overlayApplied = overlayApplied;
        this.homeIdleClockMinutes = homeIdleClockMinutes;
        this.wifiStatusColor = wifiStatusColor;
        this.wallpaperMoods = wallpaperMoods;
        this.gamepadPassthrough = gamepadPassthrough;
        this.consumeVolumeKeys = consumeVolumeKeys;
        this.volumePausesFilter = volumePausesFilter;
        this.homeIgnoreChannelKeys = homeIgnoreChannelKeys;
        this.powerOnHome = powerOnHome;
        this.homeLongPressHome = homeLongPressHome;
        this.powerOnHomeAlways = powerOnHomeAlways;
        this.inputsConnectedOnly = inputsConnectedOnly;
        this.inputsCecStateOnly = inputsCecStateOnly;
        this.openOnStartNetworkWait = openOnStartNetworkWait;
        this.openOnStartOnWake = openOnStartOnWake;
        this.clockFormat = clockFormat;
        this.wifiStatusWarnings = wifiStatusWarnings;
        this.topBarApps = Collections.unmodifiableList(topBarApps);
        this.showGreeting = showGreeting;
        this.headerWhite = headerWhite;
        this.clockWallpaper = clockWallpaper;
        this.clockWallpaperDim = clockWallpaperDim;
        this.clockGlow = clockGlow;
        this.wallpaperCaptions = wallpaperCaptions;
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        String liveTv = res.getString(R.string.live_tv_package).trim();
        String liveTvAction = res.getString(R.string.live_tv_action).trim();
        String liveTvInputId = res.getString(R.string.live_tv_input_id).trim();
        List<String> launchActions = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.launch_actions)));
        String inputAction = res.getString(R.string.input_action).trim();
        String inputUriScheme = res.getString(R.string.input_uri_scheme).trim();
        boolean animations = res.getBoolean(R.bool.animations);
        boolean advancedEffects = res.getBoolean(R.bool.advanced_effects);
        String greetingName = res.getString(R.string.greeting_name).trim();
        boolean reducedMotion = res.getBoolean(R.bool.reduced_motion);
        boolean bootToLiveTv = res.getBoolean(R.bool.boot_to_live_tv);
        boolean openOnStartChoice = res.getBoolean(R.bool.open_on_start_choice);
        int openOnStartNetworkWait = res.getInteger(R.integer.open_on_start_network_wait);
        boolean openOnStartOnWake = res.getBoolean(R.bool.open_on_start_on_wake);
        boolean topMenu = res.getBoolean(R.bool.top_menu);
        boolean launcherSettingsButton = res.getBoolean(R.bool.launcher_settings_button);
        boolean showNetworkPill = res.getBoolean(R.bool.show_network_pill);
        boolean showInputsPill = res.getBoolean(R.bool.show_inputs_pill);
        boolean iconOnlyButtons = res.getBoolean(R.bool.icon_only_buttons);
        boolean iconOnlyInputs = res.getBoolean(R.bool.icon_only_inputs);
        boolean iconOnlyVpn = res.getBoolean(R.bool.icon_only_vpn);
        boolean backOpensClock = res.getBoolean(R.bool.back_opens_clock);
        boolean settingsKeyClearTask = res.getBoolean(R.bool.settings_key_clear_task);
        int wifiLabelWidth = res.getInteger(R.integer.wifi_label_width);
        boolean wallpaperTrueColor = res.getBoolean(R.bool.wallpaper_true_color);
        boolean wallpaperCaptions = res.getBoolean(R.bool.wallpaper_captions);
        String vpn = res.getString(R.string.vpn_app).trim();
        boolean showStats = res.getBoolean(R.bool.show_stats);
        int background = 0xFF000000;
        int[] settingsKeycodes = res.getIntArray(R.array.settings_key_keycodes);
        boolean settingsKeyPanel = res.getBoolean(R.bool.settings_key_panel);
        boolean restoreKeyServices = res.getBoolean(R.bool.restore_key_services);
        int[] inputKeycodes = res.getIntArray(R.array.input_key_keycodes);
        boolean handleViewInputs = res.getBoolean(R.bool.handle_view_inputs);
        List<String> appKeys = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.app_keys)));
        String stockHomeRedirect = res.getString(R.string.stock_home_redirect).trim();
        int idleClockMinutes = res.getInteger(R.integer.home_idle_clock_minutes);
        boolean keyFilter = res.getBoolean(R.bool.key_filter);
        boolean gamepadPassthrough = res.getBoolean(R.bool.gamepad_passthrough);
        boolean consumeVolumeKeys = res.getBoolean(R.bool.consume_volume_keys);
        boolean volumePausesFilter = res.getBoolean(R.bool.volume_pauses_filter);
        boolean homeIgnoreChannelKeys = res.getBoolean(R.bool.home_ignore_channel_keys);
        boolean powerOnHome = res.getBoolean(R.bool.power_on_home);
        boolean homeLongPressHome = res.getBoolean(R.bool.home_long_press_home);
        boolean powerOnHomeAlways = res.getBoolean(R.bool.power_on_home_always);
        boolean inputsConnectedOnly = res.getBoolean(R.bool.inputs_connected_only);
        boolean inputsCecStateOnly = res.getBoolean(R.bool.inputs_cec_state_only);
        String clockFormat = res.getString(R.string.clock_format);
        boolean wifiStatusColor = res.getBoolean(R.bool.wifi_status_color);
        Map<String, Set<String>> wallpaperMoods = moods(res.getString(R.string.wallpaper_moods));
        boolean wifiStatusWarnings = res.getBoolean(R.bool.wifi_status_warnings);
        boolean dateBelowClock = res.getBoolean(R.bool.date_below_clock);
        boolean vpnPill = res.getBoolean(R.bool.vpn_pill);
        String locale = res.getString(R.string.locale).trim();
        List<String> topBarApps = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.top_bar_apps)));
        boolean showGreeting = res.getBoolean(R.bool.show_greeting);
        boolean headerWhite = res.getBoolean(R.bool.header_white);
        boolean clockWallpaper = res.getBoolean(R.bool.clock_wallpaper);
        int clockWallpaperDim = res.getInteger(R.integer.clock_wallpaper_dim);
        boolean clockGlow = res.getBoolean(R.bool.clock_glow);
        boolean wallpaperEnabled = res.getBoolean(R.bool.wallpaper);
        int rotationMinutes = Math.max(0, Math.min(1440, res.getInteger(R.integer.wallpaper_rotation_minutes)));
        int wallpaperDim = Math.max(0, Math.min(90, res.getInteger(R.integer.wallpaper_dim)));
        String wallpaperOrder = res.getString(R.string.wallpaper_order).trim();
        boolean applied = false;

        File dir = dir(ctx);
        File file = dir != null ? new File(dir, FILE_NAME) : null;
        boolean mounted = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
        JSONObject json = readOverlay(ctx, file, mounted);
        if (json != null) {
            applied = true;
            hidden = stringList(json, "hidden_apps", hidden);
            liveTv = string(json, "live_tv_package", liveTv);
            liveTvAction = string(json, "live_tv_action", liveTvAction);
            liveTvInputId = string(json, "live_tv_input_id", liveTvInputId);
            launchActions = stringList(json, "launch_actions", launchActions);
            inputAction = string(json, "input_action", inputAction);
            inputUriScheme = string(json, "input_uri_scheme", inputUriScheme).trim();
            animations = bool(json, "animations", animations);
            advancedEffects = bool(json, "advanced_effects", advancedEffects);
            greetingName = string(json, "greeting_name", greetingName).trim();
            reducedMotion = bool(json, "reduced_motion", reducedMotion);
            bootToLiveTv = bool(json, "boot_to_live_tv", bootToLiveTv);
            openOnStartChoice = bool(json, "open_on_start_choice", openOnStartChoice);
            openOnStartNetworkWait = integer(json, "open_on_start_network_wait", openOnStartNetworkWait, 0, 600);
            openOnStartOnWake = bool(json, "open_on_start_on_wake", openOnStartOnWake);
            topMenu = bool(json, "top_menu", topMenu);
            launcherSettingsButton = bool(json, "launcher_settings_button", launcherSettingsButton);
            showNetworkPill = bool(json, "show_network_pill", showNetworkPill);
            showInputsPill = bool(json, "show_inputs_pill", showInputsPill);
            iconOnlyButtons = bool(json, "icon_only_buttons", iconOnlyButtons);
            iconOnlyInputs = bool(json, "icon_only_inputs", iconOnlyInputs);
            iconOnlyVpn = bool(json, "icon_only_vpn", iconOnlyVpn);
            backOpensClock = bool(json, "back_opens_clock", backOpensClock);
            settingsKeyClearTask = bool(json, "settings_key_clear_task", settingsKeyClearTask);
            wifiLabelWidth = integer(json, "wifi_label_width", wifiLabelWidth, 0, 300);
            wallpaperTrueColor = bool(json, "wallpaper_true_color", wallpaperTrueColor);
            wallpaperCaptions = bool(json, "wallpaper_captions", wallpaperCaptions);
            vpn = string(json, "vpn_app", vpn);
            showStats = bool(json, "show_stats", showStats);
            background = color(json, "background_color", background);
            settingsKeycodes = intArray(json, "settings_key_keycodes", settingsKeycodes);
            settingsKeyPanel = bool(json, "settings_key_panel", settingsKeyPanel);
            restoreKeyServices = bool(json, "restore_key_services", restoreKeyServices);
            inputKeycodes = intArray(json, "input_key_keycodes", inputKeycodes);
            handleViewInputs = bool(json, "handle_view_inputs", handleViewInputs);
            appKeys = stringList(json, "app_keys", appKeys);
            stockHomeRedirect = string(json, "stock_home_redirect", stockHomeRedirect);
            idleClockMinutes = integer(json, "home_idle_clock_minutes", idleClockMinutes, 0, 1440);
            keyFilter = bool(json, "key_filter", keyFilter);
            gamepadPassthrough = bool(json, "gamepad_passthrough", gamepadPassthrough);
            consumeVolumeKeys = bool(json, "consume_volume_keys", consumeVolumeKeys);
            volumePausesFilter = bool(json, "volume_pauses_filter", volumePausesFilter);
            homeIgnoreChannelKeys = bool(json, "home_ignore_channel_keys", homeIgnoreChannelKeys);
            powerOnHome = bool(json, "power_on_home", powerOnHome);
            homeLongPressHome = bool(json, "home_long_press_home", homeLongPressHome);
            powerOnHomeAlways = bool(json, "power_on_home_always", powerOnHomeAlways);
            inputsConnectedOnly = bool(json, "inputs_connected_only", inputsConnectedOnly);
            inputsCecStateOnly = bool(json, "inputs_cec_state_only", inputsCecStateOnly);
            clockFormat = string(json, CLOCK_FORMAT_KEY, clockFormat);
            wifiStatusColor = bool(json, "wifi_status_color", wifiStatusColor);
            JSONObject moods = json.optJSONObject("wallpaper_moods");
            if (moods != null) wallpaperMoods = moods(moods);
            wifiStatusWarnings = bool(json, "wifi_status_warnings", wifiStatusWarnings);
            dateBelowClock = bool(json, "date_below_clock", dateBelowClock);
            vpnPill = bool(json, "vpn_pill", vpnPill);
            locale = string(json, "locale", locale).trim();
            topBarApps = stringList(json, "top_bar_apps", topBarApps);
            showGreeting = bool(json, "show_greeting", showGreeting);
            headerWhite = bool(json, "header_white", headerWhite);
            clockWallpaper = bool(json, "clock_wallpaper", clockWallpaper);
            clockWallpaperDim = integer(json, "clock_wallpaper_dim", clockWallpaperDim, 0, 90);
            clockGlow = bool(json, "clock_glow", clockGlow);
            wallpaperEnabled = bool(json, "wallpaper", wallpaperEnabled);
            rotationMinutes = integer(json, "wallpaper_rotation_minutes", rotationMinutes, 0, 1440);
            wallpaperDim = integer(json, "wallpaper_dim", wallpaperDim, 0, 90);
            wallpaperOrder = string(json, "wallpaper_order", wallpaperOrder);
        }
        // A top-bar app is moved there, not copied: it leaves the rows.
        for (String pkg : topBarApps) if (!hidden.contains(pkg)) hidden.add(pkg);
        // A theme push or reset invalidates choices made on the TV. On first use, remember the
        // current theme without clearing choices from an older launcher version.
        SharedPreferences choices = ctx.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
        // Unmounted storage looks like a theme reset; compare only once the file is readable.
        if (mounted) syncThemeChoices(choices, dir, file);
        animations = choices.getBoolean("animations", animations);
        advancedEffects = choices.getBoolean("advanced_effects", advancedEffects);
        File wallpaper = dir != null ? new File(dir, WALLPAPER_NAME) : null;
        if (wallpaper != null && !(wallpaper.isFile() && wallpaper.length() > 0)) wallpaper = null;
        // A theme can also switch the wallpaper off while the file is still there.
        if (!wallpaperEnabled) wallpaper = null;
        clockFormat = choices.getString(CLOCK_FORMAT_KEY, clockFormat);
        // An unknown order must not silently change how an existing collection rotates.
        if (!WallpaperRotation.validOrder(wallpaperOrder)) wallpaperOrder = WallpaperRotation.ORDER_SHUFFLE;
        // A theme's collection, else its wallpaper.jpg, else the pictures built into the launcher (copied out of
        // the APK only when no theme brings pictures of its own).
        List<File> themeImages = WallpaperRotation.images(dir == null ? null : new File(dir, "wallpapers"));
        List<File> collection = WallpaperRotation.collection(themeImages, wallpaper,
                themeImages.isEmpty() && wallpaper == null ? BundledWallpapers.files(ctx) : Collections.emptyList());
        List<File> wallpapers = new ArrayList<>();
        if (wallpaperEnabled) wallpapers.addAll(collection);
        // An on-TV wallpaper pick overrides the theme: a filename from the collection, or "none". Unset means the
        // theme, or the built-in rotation, decides.
        String wallpaperChoice = choices.getString(WALLPAPER_CHOICE_KEY, null);
        List<File> chosen = WallpaperRotation.chosen(collection, wallpaper, wallpaperChoice);
        if (chosen != null) {
            wallpapers.clear();
            wallpapers.addAll(chosen);
            wallpaper = chosen.isEmpty() ? null : chosen.get(0);
            wallpaperEnabled = !chosen.isEmpty();
        }
        return new Config(hidden, liveTv, liveTvAction, liveTvInputId, packageMap(launchActions), inputAction, inputUriScheme,
                animations, advancedEffects, greetingName, reducedMotion, bootToLiveTv,
                openOnStartChoice, topMenu, launcherSettingsButton, showNetworkPill, showInputsPill,
                iconOnlyButtons, iconOnlyInputs, wifiLabelWidth, wallpaperTrueColor,
                vpn, showStats, background,
                settingsKeycodes, settingsKeyPanel, restoreKeyServices, wallpaperEnabled, wallpaper,
                wallpapers, rotationMinutes, wallpaperDim, wallpaperOrder, applied, inputKeycodes, appKeys, stockHomeRedirect,
                Math.max(0, idleClockMinutes), keyFilter, wallpaperCaptions, handleViewInputs, wifiStatusColor, wallpaperMoods, gamepadPassthrough,
                consumeVolumeKeys, volumePausesFilter, homeIgnoreChannelKeys, powerOnHome, homeLongPressHome, wifiStatusWarnings, topBarApps, showGreeting, headerWhite, clockWallpaper,
                Math.max(0, Math.min(90, clockWallpaperDim)), clockGlow,
                dateBelowClock, vpnPill, locale, powerOnHomeAlways, inputsConnectedOnly, inputsCecStateOnly,
                Math.max(0, Math.min(600, openOnStartNetworkWait)), openOnStartOnWake, iconOnlyVpn, clockFormat, backOpensClock,
                settingsKeyClearTask);
    }

    private static void syncThemeChoices(SharedPreferences choices, File dir, File config) {
        File revision = dir == null ? null : new File(dir, THEME_REVISION_NAME);
        String token = revisionToken(revision);
        String signature = fileStamp(config) + ":" + token;
        String seen = choices.getString(THEME_SIGNATURE_KEY, null);
        if (seen == null) {
            // Existing preferences survive an app upgrade, unless the first launch follows an
            // explicit theme push or reset by the updated tools.
            SharedPreferences.Editor editor = choices.edit();
            if (!"absent".equals(token)) editor.remove("animations").remove("advanced_effects")
                    .remove(WALLPAPER_CHOICE_KEY).remove(CLOCK_FORMAT_KEY);
            editor.putString(THEME_SIGNATURE_KEY, signature).commit();
        } else if (!seen.equals(signature)) {
            choices.edit().remove("animations").remove("advanced_effects").remove(WALLPAPER_CHOICE_KEY)
                    .remove(CLOCK_FORMAT_KEY).putString(THEME_SIGNATURE_KEY, signature).commit();
        }
    }

    private static String fileStamp(File file) {
        return file != null && file.isFile() ? file.length() + "@" + file.lastModified() : "absent";
    }

    private static String revisionToken(File file) {
        if (file == null || !file.isFile() || file.length() > 64) return "absent";
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int n = in.read(bytes);
            return n > 0 ? new String(bytes, 0, n, StandardCharsets.US_ASCII) : "absent";
        } catch (Exception ignored) {
            return "absent";
        }
    }

    /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
    /**
     * The overlay, or before external storage is mounted, the copy kept from the last time it was read. Home can
     * start before the card is mounted (seen at every boot on a Xiaomi MiTV-MTEQ0), and without the copy it comes
     * up with the built-in settings. A missing file on mounted storage still means no overlay.
     */
    private static JSONObject readOverlay(Context ctx, File file, boolean mounted) {
        File copy = new File(ctx.getFilesDir(), FILE_NAME);
        if (!mounted) return read(copy);
        JSONObject json = read(file);
        if (json == null) {
            copy.delete();
        } else {
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            // Written only when it changed: every Home start used to rewrite the same bytes, on the main thread.
            if (copy.length() == bytes.length && Arrays.equals(bytes, readBytes(copy))) return json;
            try (FileOutputStream out = new FileOutputStream(copy)) {
                out.write(bytes);
            } catch (IOException e) {
                Log.w(TAG, "could not keep a copy of " + file + ": " + e.getMessage());
            }
        }
        return json;
    }

    /** The file's bytes, or null when it cannot be read whole. */
    private static byte[] readBytes(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return n == buf.length ? buf : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static JSONObject read(File file) {
        if (file == null || !file.isFile()) return null;
        if (file.length() > 64 * 1024) {
            Log.w(TAG, "ignoring " + file + ": larger than 64 KB");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);   // Windows editors add a BOM
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }

    /** wallpaper_moods as JSON text (config.xml); empty or unreadable is no groups. */
    private static Map<String, Set<String>> moods(String text) {
        if (text.trim().isEmpty()) return Collections.emptyMap();
        try {
            return moods(new JSONObject(text));
        } catch (JSONException e) {
            return Collections.emptyMap();
        }
    }

    /** {"night": ["03-moon-lake.jpg", ...], ...}: each mood's picture names; other keys and names are left out. */
    private static Map<String, Set<String>> moods(JSONObject o) {
        Map<String, Set<String>> groups = new HashMap<>();
        for (String mood : WallpaperMood.ALL) {
            JSONArray names = o.optJSONArray(mood);
            if (names == null) continue;
            Set<String> group = new HashSet<>();
            for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i, "").trim();
                if (WallpaperRotation.imageName(name)) group.add(name);
            }
            if (!group.isEmpty()) groups.put(mood, Collections.unmodifiableSet(group));
        }
        return Collections.unmodifiableMap(groups);
    }

    private static String string(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        Object v = o.opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) return fallback;
        int i = ((Number) v).intValue();
        return i < min || i > max ? fallback : i;
    }

    private static int color(JSONObject o, String key, int fallback) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return fallback;
        try {
            int c = Color.parseColor(((String) v).trim());
            return c | 0xFF000000;   // always opaque: the window behind is black anyway
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Positive keycodes only (vendor remotes use codes in the thousands); a list with anything else in it is ignored as a whole. */
    private static int[] intArray(JSONObject o, String key, int[] fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        int[] out = new int[a.length()];
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (!(item instanceof Number)) return fallback;
            out[i] = ((Number) item).intValue();
            if (out[i] < 1) return fallback;
        }
        return out;
    }

    /** "package=value" entries; an entry without both halves is skipped, and a later entry for a package wins. */
    static Map<String, String> packageMap(List<String> entries) {
        Map<String, String> out = new HashMap<>();
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String pkg = entry.substring(0, eq).trim(), value = entry.substring(eq + 1).trim();
            if (!pkg.isEmpty() && !value.isEmpty()) out.put(pkg, value);
        }
        return out;
    }

    private static List<String> stringList(JSONObject o, String key, List<String> fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (item instanceof String && !((String) item).trim().isEmpty()) out.add(((String) item).trim());
        }
        return out;
    }
}
