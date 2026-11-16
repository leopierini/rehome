package io.github.leopierini.rehome;

/**
 * The rows' titles on screen. category_rows_list and category_other_title name the rows in English (Media, Music,
 * Games, Tools, Other apps), and those names stay the rows' keys (the owner's row order file, HomeKeys' "Media"); the
 * screen shows them in the UI's language. Any other title (a theme's own, in any language) shows as written.
 */
final class RowTitles {

    private RowTitles() {}

    /** The string resource for a row title the launcher knows, else 0. */
    static int res(String title) {
        if (title == null) return 0;
        switch (title) {
            case "Media": return R.string.row_media;
            case "Music": return R.string.row_music;
            case "Games": return R.string.row_games;
            case "Tools": return R.string.row_tools;
            case "Other apps": return R.string.row_other;
            default: return 0;
        }
    }

    static String shown(Words w, String title) {
        int id = res(title);
        return id == 0 ? title : w.get(id);
    }
}
