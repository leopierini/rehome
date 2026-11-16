package io.github.leopierini.rehome;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The owner's own changes to the category rows (CategoryHome), made from the couch with the tile menu (hold OK on
 * a tile): an order for the rows they touched, and the apps they hid. Kept in the launcher's private files
 * (category_order.txt), written through an AtomicFile.
 *
 * The rules, in {@link #arrange}:
 * - A row the file lists shows its listed apps first, in the file's order, wherever config.xml or AutoCategory
 *   would put them: the file wins over category_rows_list and AutoCategory.
 * - Apps the file does not mention are placed as before (config.xml, then AutoCategory) and follow the listed ones
 *   in their row. So an app installed later still lands in its row on its own.
 * - Packages in the file that are not on the rows any more (uninstalled, disabled) are skipped, not errors; rows the
 *   file names but the Home does not have are skipped too.
 * - A hidden app is not shown, wherever it is.
 * An edit saves only the rows it touched (both rows of a move), so rows the owner never edited keep following
 * config.xml.
 *
 * Format, UTF-8 text: {@code rehome-rows 1}, then any number of {@code hide <package>} lines, then for each edited
 * row a {@code row <title>} line followed by one package per line. A file that is not exactly this is ignored as a
 * whole (the defaults show), never half applied.
 */
final class RowEdits {

    private static final String TAG = "Rehome.RowEdits";
    static final String FILE_NAME = "category_order.txt";
    private static final String MAGIC = "rehome-rows 1";
    private static final String HIDE = "hide ";
    private static final String ROW = "row ";

    /** Row title to the owner's order for it, in the order the rows were first edited. */
    final Map<String, List<String>> rows;
    final Set<String> hidden;

    RowEdits(Map<String, List<String>> rows, Set<String> hidden) {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : rows.entrySet()) {
            copy.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        this.rows = Collections.unmodifiableMap(copy);
        this.hidden = Collections.unmodifiableSet(new LinkedHashSet<>(hidden));
    }

    static RowEdits empty() {
        return new RowEdits(new LinkedHashMap<>(), new LinkedHashSet<>());
    }

    boolean isEmpty() {
        return rows.isEmpty() && hidden.isEmpty();
    }

    // ---- arranging ---------------------------------------------------------------------------------

    /**
     * The rows to show. {@code defaults} is the Home without edits: each row's title (in screen order) to its apps
     * in their usual order, every app once. Rows that end up empty are left out.
     */
    static LinkedHashMap<String, List<String>> arrange(LinkedHashMap<String, List<String>> defaults, RowEdits edits) {
        if (edits == null) edits = empty();
        Set<String> onHome = new HashSet<>();
        for (List<String> pkgs : defaults.values()) onHome.addAll(pkgs);
        // Each app listed by the file goes to the first row that lists it (a valid file lists it once).
        Set<String> claimed = new HashSet<>();
        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();
        for (String title : defaults.keySet()) {
            List<String> row = new ArrayList<>();
            List<String> listed = edits.rows.get(title);
            if (listed != null) {
                for (String p : listed) {
                    if (onHome.contains(p) && !edits.hidden.contains(p) && claimed.add(p)) row.add(p);
                }
            }
            out.put(title, row);
        }
        for (Map.Entry<String, List<String>> e : defaults.entrySet()) {
            List<String> row = out.get(e.getKey());
            for (String p : e.getValue()) {
                if (!claimed.contains(p) && !edits.hidden.contains(p)) row.add(p);
            }
        }
        out.values().removeIf(List::isEmpty);
        return out;
    }

    // ---- edits (each returns the new state to save; the shown rows are what arrange gave) ---------------------

    /** The app one place left (delta -1) or right (+1) in its row; unchanged at the row's ends. */
    static RowEdits move(LinkedHashMap<String, List<String>> shown, RowEdits edits, String pkg, int delta) {
        String title = rowOf(shown, pkg);
        if (title == null) return edits;
        List<String> row = new ArrayList<>(shown.get(title));
        int from = row.indexOf(pkg), to = from + delta;
        if (to < 0 || to >= row.size()) return edits;
        Collections.swap(row, from, to);
        LinkedHashMap<String, List<String>> rows = new LinkedHashMap<>(edits.rows);
        rows.put(title, row);
        return new RowEdits(rows, edits.hidden);
    }

    /** The app to the end of another shown row. Both rows are saved as they now are. */
    static RowEdits moveToRow(LinkedHashMap<String, List<String>> shown, RowEdits edits, String pkg, String toTitle) {
        String from = rowOf(shown, pkg);
        if (from == null || from.equals(toTitle) || !shown.containsKey(toTitle)) return edits;
        List<String> left = new ArrayList<>(shown.get(from));
        left.remove(pkg);
        List<String> into = new ArrayList<>(shown.get(toTitle));
        into.add(pkg);
        LinkedHashMap<String, List<String>> rows = new LinkedHashMap<>(edits.rows);
        rows.put(from, left);
        rows.put(toTitle, into);
        return new RowEdits(rows, edits.hidden);
    }

    static RowEdits hide(RowEdits edits, String pkg) {
        Set<String> hidden = new LinkedHashSet<>(edits.hidden);
        hidden.add(pkg);
        return new RowEdits(edits.rows, hidden);
    }

    /** Every hidden app back (where the rows would put it); the order stays. */
    static RowEdits unhideAll(RowEdits edits) {
        return new RowEdits(edits.rows, new LinkedHashSet<>());
    }

    static String rowOf(Map<String, List<String>> shown, String pkg) {
        for (Map.Entry<String, List<String>> e : shown.entrySet()) if (e.getValue().contains(pkg)) return e.getKey();
        return null;
    }

    // ---- format --------------------------------------------------------------------------------------

    static String encode(RowEdits edits) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        for (String p : edits.hidden) sb.append(HIDE).append(p).append('\n');
        for (Map.Entry<String, List<String>> e : edits.rows.entrySet()) {
            sb.append(ROW).append(e.getKey()).append('\n');
            for (String p : e.getValue()) sb.append(p).append('\n');
        }
        return sb.toString();
    }

    /**
     * The edits in this text, or null when it is not a valid file: wrong header, a blank line inside, a package
     * before any row or listed twice, a row named twice, a hide line after the rows, or a line that cannot be a
     * package name.
     */
    static RowEdits decode(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        if (lines.length < 1 || !lines[0].equals(MAGIC)) return null;
        Set<String> hidden = new LinkedHashSet<>();
        LinkedHashMap<String, List<String>> rows = new LinkedHashMap<>();
        Set<String> listed = new HashSet<>();
        List<String> current = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                if (i == lines.length - 1) break;   // the trailing newline
                return null;
            }
            if (line.startsWith(HIDE)) {
                String p = line.substring(HIDE.length());
                if (current != null || !isPackage(p) || !hidden.add(p)) return null;
            } else if (line.startsWith(ROW)) {
                String title = line.substring(ROW.length()).trim();
                if (title.isEmpty() || rows.containsKey(title)) return null;
                current = new ArrayList<>();
                rows.put(title, current);
            } else {
                if (current == null || !isPackage(line) || !listed.add(line)) return null;
                current.add(line);
            }
        }
        return new RowEdits(rows, hidden);
    }

    /** Letters, digits, '_' and '.', the way package names are written; nothing else is taken for one. */
    static boolean isPackage(String s) {
        if (s == null || s.isEmpty() || s.startsWith(".") || s.endsWith(".")) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.')) return false;
        }
        return true;
    }

    // ---- file ----------------------------------------------------------------------------------------

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** The saved edits; none when there is no file, and none (logged, the file left alone) when it is not valid. */
    static RowEdits load(File file) {
        if (!file.exists()) return empty();
        try {
            RowEdits e = decode(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
            if (e == null) {
                Log.w(TAG, file.getName() + " is not valid; ignored");
                return empty();
            }
            return e;
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file.getName() + ": " + e);
            return empty();
        }
    }

    /** Writes the edits atomically; no edits at all removes the file. False (and a log line) when that failed. */
    static boolean save(File file, RowEdits edits) {
        AtomicFile af = new AtomicFile(file);
        if (edits.isEmpty()) {
            af.delete();
            return !file.exists();
        }
        FileOutputStream out = null;
        try {
            out = af.startWrite();
            out.write(encode(edits).getBytes(StandardCharsets.UTF_8));
            af.finishWrite(out);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + file.getName() + ": " + e);
            if (out != null) af.failWrite(out);
            return false;
        }
    }
}
