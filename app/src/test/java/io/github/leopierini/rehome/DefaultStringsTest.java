package io.github.leopierini.rehome;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The default resources are English; Italian lives in values-it. Reads the real res files (the working directory of
 * a unit test is the module).
 */
public class DefaultStringsTest {

    private static final Pattern ITALIAN = Pattern.compile("\\b(Riprendi|Pioggia|Vento|Salvata|Nessuna|Oggi|Altre|"
            + "Musica|Giochi|Strumenti|Impostazioni|Ingressi|Ascoltato|Guardato|Stagione|episodio|dalle|accendi)\\b");

    private static final File RES = new File("src/main/res");

    @Test
    public void noItalianInDefaultResources() throws Exception {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> e : texts(new File(RES, "values")).entrySet()) {
            if (ITALIAN.matcher(e.getValue()).find()) found.add(e.getKey() + " = " + e.getValue());
        }
        for (String title : rowTitles()) {
            if (ITALIAN.matcher(title).find()) found.add("category_rows_list = " + title);
        }
        assertTrue("Italian in the default resources: " + found, found.isEmpty());
    }

    @Test
    public void everyTranslatableStringHasItalian() throws Exception {
        Map<String, String> it = texts(new File(RES, "values-it"));
        List<String> missing = new ArrayList<>();
        for (String key : texts(new File(RES, "values"), true).keySet()) {
            if (!it.containsKey(key)) missing.add(key);
        }
        assertTrue("No Italian for: " + missing, missing.isEmpty());
    }

    @Test
    public void onlyEnglishAndItalianAreLeft() {
        List<String> others = new ArrayList<>();
        File[] dirs = RES.listFiles();
        assertTrue(dirs != null && dirs.length > 0);
        for (File d : dirs) {
            String n = d.getName();
            // A language qualifier is two or three letters (or b+…) right after the type; nodpi, v26… are not.
            if (n.matches("[a-z]+-([a-z]{2,3}|b\\+.*)(-.*)?") && !n.equals("values-it")) {
                others.add(n);
            }
        }
        assertTrue("Other languages left: " + others, others.isEmpty());
    }

    @Test
    public void localeConfigListsOnlyEnglishAndItalian() throws Exception {
        NodeList locales = parse(new File(RES, "xml/locales_config.xml")).getElementsByTagName("locale");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < locales.getLength(); i++) {
            names.add(((Element) locales.item(i)).getAttribute("android:name"));
        }
        assertEquals(java.util.Arrays.asList("en", "it"), names);
    }

    /** The row titles of config.xml's category_rows_list ("Title|pkg,…") and the fallback row's. */
    private static List<String> rowTitles() throws Exception {
        List<String> titles = new ArrayList<>();
        Element root = parse(new File(RES, "values/config.xml"));
        NodeList arrays = root.getElementsByTagName("string-array");
        for (int i = 0; i < arrays.getLength(); i++) {
            Element a = (Element) arrays.item(i);
            if (!a.getAttribute("name").equals("category_rows_list")) continue;
            NodeList items = a.getElementsByTagName("item");
            for (int k = 0; k < items.getLength(); k++) {
                String s = items.item(k).getTextContent().trim();
                titles.add(s.contains("|") ? s.substring(0, s.indexOf('|')) : s);
            }
        }
        NodeList strings = root.getElementsByTagName("string");
        for (int i = 0; i < strings.getLength(); i++) {
            Element s = (Element) strings.item(i);
            if (s.getAttribute("name").equals("category_other_title")) titles.add(s.getTextContent().trim());
        }
        assertTrue(titles.size() > 1);
        return titles;
    }

    private static Map<String, String> texts(File dir) throws Exception {
        return texts(dir, false);
    }

    /**
     * Every string, plurals item and string-array item of the folder's files, "name" / "name#quantity" / "name[i]" to
     * its text. config.xml is settings, not words: only its row titles count (rowTitles).
     */
    private static Map<String, String> texts(File dir, boolean translatableOnly) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".xml") && !n.equals("config.xml"));
        assertTrue(dir + " has no files", files != null && files.length > 0);
        for (File f : files) {
            Element root = parse(f);
            NodeList all = root.getChildNodes();
            for (int i = 0; i < all.getLength(); i++) {
                if (!(all.item(i) instanceof Element)) continue;
                Element e = (Element) all.item(i);
                String name = e.getAttribute("name");
                if (translatableOnly && e.getAttribute("translatable").equals("false")) continue;
                switch (e.getTagName()) {
                    case "string":
                        out.put(name, e.getTextContent());
                        break;
                    case "plurals": {
                        NodeList items = e.getElementsByTagName("item");
                        for (int k = 0; k < items.getLength(); k++) {
                            Element item = (Element) items.item(k);
                            out.put(name + "#" + item.getAttribute("quantity"), item.getTextContent());
                        }
                        out.put(name, "");
                        break;
                    }
                    case "string-array": {
                        NodeList items = e.getElementsByTagName("item");
                        for (int k = 0; k < items.getLength(); k++) {
                            out.put(name + "[" + k + "]", items.item(k).getTextContent());
                        }
                        break;
                    }
                    default:
                }
            }
        }
        return out;
    }

    private static Element parse(File f) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getDocumentElement();
    }
}
