package io.github.leopierini.rehome;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * {@link Words} from the real res files, for JVM tests: values/ for English, values/ then values-it/ for Italian, ids
 * matched to names through R. Quantities follow English and Italian alike: "one" for 1, "other" for the rest.
 */
final class TestWords implements Words {

    private static final File RES = new File("src/main/res");
    private static TestWords en, it;

    private final Locale locale;
    private final Map<String, String> strings = new HashMap<>();
    private final Map<String, Map<String, String>> plurals = new HashMap<>();
    private final Map<String, String[]> arrays = new HashMap<>();

    static synchronized TestWords en() {
        if (en == null) en = new TestWords(Locale.ENGLISH, "values");
        return en;
    }

    /** English words on a TV whose system language is {@code configured} (Words.of picks the formatting locale). */
    static TestWords en(Locale configured) {
        return new TestWords(Words.formatLocale(configured), "values");
    }

    static synchronized TestWords it() {
        if (it == null) it = new TestWords(Locale.ITALIAN, "values", "values-it");
        return it;
    }

    private TestWords(Locale locale, String... dirs) {
        this.locale = locale;
        try {
            for (String d : dirs) {
                File[] files = new File(RES, d).listFiles((f, n) -> n.endsWith(".xml"));
                if (files == null) throw new IllegalStateException("no " + d + " in " + RES.getAbsolutePath());
                for (File f : files) read(f);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String get(int id, Object... args) {
        String s = strings.get(name(R.string.class, id));
        if (s == null) throw new IllegalArgumentException("no string " + name(R.string.class, id));
        s = resolve(s);
        return args.length == 0 ? s : String.format(locale, s, args);
    }

    @Override
    public String plural(int id, int count, Object... args) {
        Map<String, String> q = plurals.get(name(R.plurals.class, id));
        if (q == null) throw new IllegalArgumentException("no plurals " + name(R.plurals.class, id));
        String s = q.get(count == 1 && q.containsKey("one") ? "one" : "other");
        return String.format(locale, s, args);
    }

    @Override
    public String[] array(int id) {
        String[] a = arrays.get(name(R.array.class, id));
        if (a == null) throw new IllegalArgumentException("no array " + name(R.array.class, id));
        return a.clone();
    }

    @Override
    public Locale locale() {
        return locale;
    }

    private String resolve(String s) {
        return s.startsWith("@string/") ? resolve(strings.get(s.substring(8))) : s;
    }

    private static String name(Class<?> type, int id) {
        try {
            for (Field f : type.getFields()) if (f.getInt(null) == id) return f.getName();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalArgumentException("no " + type.getSimpleName() + " with id " + id);
    }

    private void read(File f) throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getDocumentElement();
        NodeList all = root.getChildNodes();
        for (int i = 0; i < all.getLength(); i++) {
            if (!(all.item(i) instanceof Element)) continue;
            Element e = (Element) all.item(i);
            String name = e.getAttribute("name");
            switch (e.getTagName()) {
                case "string":
                    strings.put(name, text(e.getTextContent()));
                    break;
                case "plurals": {
                    Map<String, String> q = new HashMap<>();
                    NodeList items = e.getElementsByTagName("item");
                    for (int k = 0; k < items.getLength(); k++) {
                        Element item = (Element) items.item(k);
                        q.put(item.getAttribute("quantity"), text(item.getTextContent()));
                    }
                    plurals.put(name, q);
                    break;
                }
                case "string-array": {
                    List<String> a = new ArrayList<>();
                    NodeList items = e.getElementsByTagName("item");
                    for (int k = 0; k < items.getLength(); k++) a.add(text(items.item(k).getTextContent()));
                    arrays.put(name, a.toArray(new String[0]));
                    break;
                }
                default:
            }
        }
    }

    /** What aapt makes of a resource's text: whitespace runs collapsed, the ends trimmed, escapes undone. */
    static String text(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            if (n == 'n') out.append('\n');
            else if (n == 't') out.append('\t');
            else if (n == 'u' && i + 4 < s.length()) {
                out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                i += 4;
            } else out.append(n);
        }
        return out.toString();
    }
}
