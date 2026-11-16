package io.github.leopierini.rehome;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The six pictures built into the launcher, shown when no theme on the TV brings its own (credits in
 * docs/wallpapers.md). Home, the clock screensaver and Launcher settings all work on picture files, so the
 * pictures are copied once from the APK into the app's private files (about 2 MB) and read from there like a
 * theme's collection.
 */
final class BundledWallpapers {
    private static final String TAG = "Rehome.Wallpaper";
    /** The resource files in res/drawable-nodpi, in the same order as {@link #IDS}; also the copies' names. */
    static final String[] NAMES = {
            "wallpaper_briones_hills.jpg", "wallpaper_misty_forest.jpg", "wallpaper_moody_beach.jpg",
            "wallpaper_seceda_dusk.jpg", "wallpaper_seiser_alm.jpg", "wallpaper_yosemite_stars.jpg"};
    static final int[] IDS = {
            R.drawable.wallpaper_briones_hills, R.drawable.wallpaper_misty_forest, R.drawable.wallpaper_moody_beach,
            R.drawable.wallpaper_seceda_dusk, R.drawable.wallpaper_seiser_alm, R.drawable.wallpaper_yosemite_stars};

    private BundledWallpapers() {}

    /**
     * The pictures as files, copied out of the APK where missing or of another size (a launcher update that
     * changed one); copies no longer built in are deleted. A picture that cannot be copied is left out.
     */
    static List<File> files(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "wallpapers_builtin");
        if (!dir.isDirectory() && !dir.mkdirs()) return new ArrayList<>();
        Resources res = ctx.getResources();
        List<File> out = new ArrayList<>();
        for (int i = 0; i < IDS.length; i++) {
            File file = new File(dir, NAMES[i]);
            if (file.length() != size(res, IDS[i]) && !copy(res, IDS[i], file)) continue;
            out.add(file);
        }
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) if (!Arrays.asList(NAMES).contains(f.getName())) f.delete();
        }
        return out;
    }

    /** The picture's size in the APK; -1 when it is unknown, which copies it again. */
    private static long size(Resources res, int id) {
        try (AssetFileDescriptor fd = res.openRawResourceFd(id)) {
            return fd == null ? -1 : fd.getLength();
        } catch (IOException | Resources.NotFoundException e) {
            return -1;
        }
    }

    /** Written beside the target and renamed, so a copy cut short never looks complete. */
    private static boolean copy(Resources res, int id, File target) {
        File part = new File(target.getPath() + ".part");
        try (InputStream in = res.openRawResource(id); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } catch (IOException | Resources.NotFoundException e) {
            Log.w(TAG, "could not copy " + target.getName() + ": " + e);
            part.delete();
            return false;
        }
        return part.renameTo(target);
    }
}
