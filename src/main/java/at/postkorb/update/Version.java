package at.postkorb.update;

import java.util.ArrayList;
import java.util.List;

/** Versionsnummern wie "1.0.12" vergleichen; "-SNAPSHOT" (Entwicklerstand) ist älter als das Release. */
public final class Version implements Comparable<Version> {

    private final List<Integer> teile;
    private final boolean snapshot;
    private final String text;

    private Version(String text) {
        this.text = text;
        String t = text == null ? "0" : text.strip();
        if (t.startsWith("v")) {
            t = t.substring(1);
        }
        int dash = t.indexOf('-');
        snapshot = dash >= 0;
        List<Integer> l = new ArrayList<>();
        for (String s : (dash >= 0 ? t.substring(0, dash) : t).split("\\.")) {
            try {
                l.add(Integer.parseInt(s));
            } catch (NumberFormatException e) {
                l.add(0);
            }
        }
        teile = l;
    }

    public static Version of(String text) {
        return new Version(text);
    }

    /** Version des laufenden Programms laut Manifest, "0.0.0-dev" beim Start aus der IDE. */
    public static String aktuell() {
        String v = Version.class.getPackage().getImplementationVersion();
        return v != null ? v : "0.0.0-dev";
    }

    @Override
    public int compareTo(Version o) {
        for (int i = 0; i < Math.max(teile.size(), o.teile.size()); i++) {
            int a = i < teile.size() ? teile.get(i) : 0;
            int b = i < o.teile.size() ? o.teile.get(i) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return Boolean.compare(o.snapshot, snapshot);
    }

    public boolean istNeuerAls(Version o) {
        return compareTo(o) > 0;
    }

    @Override
    public String toString() {
        return text;
    }
}
