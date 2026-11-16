package io.github.leopierini.rehome;

/**
 * Which Home tiles are dimmed while the TV is offline: apps that are no use without the internet.
 * Pure logic, tested on the JVM; CategoryHome reads the inputs off the main thread.
 *
 * An app needs the internet when it asks for the network (android.permission.INTERNET, or ACCESS_NETWORK_STATE:
 * YouTube Music for TV asks only for the latter, as it streams through the YouTube app), and
 * - its row is not the tools row (the Play Store and the browser need the network too, but that row is where the
 *   TV is set up and fixed, and a dimmed "Play Store" reads as broken);
 * - it does not bring a TV input of its own that plays without a network: a passthrough (HDMI, AV) or one behind an
 *   HDMI device. A software input does not count: Google Play Film registers two (playback and previews) and streams
 *   through both, so it stayed bright offline (final review M9). The TV's own tuner is in WORKS_OFFLINE;
 * - it is not one of the TV's own system packages or a local media player, which ask for INTERNET for extras
 *   (EPG, streaming add-ons) but play the tuner, HDMI or a USB stick without it.
 * The permission alone is too wide: almost every app asks for it, the Philips channel list included.
 */
final class NeedsInternet {

    /** Package names (and their sub-packages) that work without the internet even though they ask for it. */
    private static final String[] WORKS_OFFLINE = {
            // The TV's own: Philips (TP Vision), the MediaTek TV stack, Android TV settings and Live Channels.
            "org.droidtv", "com.mediatek", "com.android.tv", "com.google.android.tv",
            // Local players: files on a USB stick or the home network.
            "org.videolan.vlc", "org.xbmc.kodi",
    };

    private NeedsInternet() {}

    /** Whether an app's requested permissions (PackageInfo.requestedPermissions, null for none) ask for the network. */
    static boolean asksNetwork(String[] requestedPermissions) {
        if (requestedPermissions == null) return false;
        for (String p : requestedPermissions) {
            if ("android.permission.INTERNET".equals(p) || "android.permission.ACCESS_NETWORK_STATE".equals(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * For each app, whether it is in the tools row as config.xml and AutoCategory place it ({@code defaultRows}), not
     * where the owner moved it (RowEdits): moving Netflix into "Strumenti" must not stop it dimming offline, nor moving
     * a local player into "Media" start it. An app in no row is not in the tools row.
     */
    static java.util.Map<String, Boolean> inToolsRow(java.util.Map<String, java.util.List<String>> defaultRows,
                                                    String toolsTitle) {
        java.util.Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        if (defaultRows == null) return out;
        for (java.util.Map.Entry<String, java.util.List<String>> e : defaultRows.entrySet()) {
            boolean tools = e.getKey() != null && e.getKey().equals(toolsTitle);
            for (String pkg : e.getValue()) if (pkg != null && !out.containsKey(pkg)) out.put(pkg, tools);
        }
        return out;
    }

    /**
     * Whether a TV input makes its app work offline (TvInputInfo.isPassthroughInput, or getParentId: an HDMI device's
     * input under its port): hardware inputs only. A software TvInputService (Google Play Film's) streams like the rest of its app.
     */
    static boolean inputPlaysOffline(boolean passthrough, boolean underAPort) {
        return passthrough || underAPort;
    }

    /** @param asksNetwork the app asks for INTERNET or ACCESS_NETWORK_STATE */
    static boolean needs(String pkg, boolean asksNetwork, boolean inToolsRow, boolean providesTvInput) {
        if (pkg == null || !asksNetwork || inToolsRow || providesTvInput) return false;
        for (String p : WORKS_OFFLINE) {
            if (pkg.equals(p) || pkg.startsWith(p + ".")) return false;
        }
        return true;
    }
}
