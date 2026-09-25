package at.postkorb.elak;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import at.postkorb.gateway.Zustellung;
import at.postkorb.store.FileNames;

/**
 * Zustellungen, die noch in den ELAK müssen – als je eine Datei in {@code <Eingang>\.elak\}, damit nichts
 * verloren geht (Neustart, Fehler, "Später"). Pro Anhang wird festgehalten: Art (sobald zugeordnet),
 * Mappen-ID (sobald angelegt) und ob der Workflow gestartet ist. Fertige Einträge wandern nach
 * {@code .elak\erledigt}.
 */
public final class Warteliste {

    /** Ein Anhang einer Zustellung. {@code art == null}: noch nicht zugeordnet. */
    public record Datei(int nr, String name, Art art, String mappenId, boolean workflowGestartet, String fehler) {

        public boolean zugeordnet() {
            return art != null;
        }

        public boolean offeneUebergabe() {
            return art != null && art != Art.KEINE && (mappenId == null || !workflowGestartet);
        }
    }

    public record Eintrag(String id, Path ordner, String absender, String betreff, String geschaeftszahl,
            String zustellqualitaet, String eingang, List<Datei> dateien) {

        public boolean wartetAufZuordnung() {
            return dateien.stream().anyMatch(d -> !d.zugeordnet());
        }

        public boolean wartetAufUebergabe() {
            return dateien.stream().anyMatch(Datei::offeneUebergabe);
        }

        public boolean fertig() {
            return !wartetAufZuordnung() && !wartetAufUebergabe();
        }
    }

    private final Path dir;

    public Warteliste(Path eingang) {
        this.dir = eingang.resolve(".elak");
    }

    public synchronized void hinzufuegen(Zustellung z, Path ordner) throws IOException {
        Properties p = new Properties();
        p.setProperty("id", z.id());
        p.setProperty("ordner", ordner.toAbsolutePath().toString());
        setze(p, "absender", z.absender());
        setze(p, "betreff", z.betreff());
        setze(p, "geschaeftszahl", z.weitereAngaben().get("Geschäftszahl"));
        setze(p, "zustellqualitaet", z.weitereAngaben().get("Zustellqualität"));
        setze(p, "eingang", z.eingang() != null ? z.eingang().toString() : null);
        List<String> dateien;
        try (Stream<Path> s = Files.list(ordner)) {
            dateien = s.filter(Files::isRegularFile).map(f -> f.getFileName().toString())
                    .filter(n -> !n.equals("zustellung.txt")).sorted().toList();
        }
        for (int i = 0; i < dateien.size(); i++) {
            p.setProperty("datei." + i + ".name", dateien.get(i));
        }
        speichern(z.id(), p);
    }

    /**
     * Stellt eine bereits abgeholte Zustellung (Ordner im Eingang) nachträglich in die Warteliste.
     * Die Angaben werden aus deren zustellung.txt gelesen.
     */
    public void nachtragen(Path ordner) throws IOException {
        Path meta = ordner.resolve("zustellung.txt");
        if (!Files.exists(meta)) {
            throw new IOException("Kein Zustellungsordner (zustellung.txt fehlt): " + ordner);
        }
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String zeile : Files.readAllLines(meta, StandardCharsets.UTF_8)) {
            int i = zeile.indexOf(": ");
            if (i > 0 && !zeile.startsWith(" ")) {
                m.put(zeile.substring(0, i), zeile.substring(i + 2).strip());
            }
        }
        String id = m.get("ID");
        if (id == null || id.isBlank()) {
            throw new IOException("zustellung.txt ohne ID: " + meta);
        }
        java.util.Map<String, String> weitere = new java.util.HashMap<>();
        if (m.get("Geschäftszahl") != null) {
            weitere.put("Geschäftszahl", m.get("Geschäftszahl"));
        }
        if (m.get("Zustellqualität") != null) {
            weitere.put("Zustellqualität", m.get("Zustellqualität"));
        }
        java.time.Instant eingang = null;
        try {
            eingang = m.get("Eingang") == null || m.get("Eingang").isBlank() ? null : java.time.Instant.parse(m.get("Eingang"));
        } catch (java.time.format.DateTimeParseException ignored) {
            // ohne Eingangsdatum
        }
        hinzufuegen(new Zustellung(id, m.get("Absender"), m.get("Betreff"), eingang, List.of(), weitere), ordner);
    }

    public synchronized List<Eintrag> alle() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Eintrag> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(f -> f.getFileName().toString().endsWith(".properties"))
                    .sorted(Comparator.comparing(Path::getFileName)).toList()) {
                out.add(lies(laden(f)));
            }
        }
        return out;
    }

    public List<Eintrag> wartenAufZuordnung() throws IOException {
        return alle().stream().filter(Eintrag::wartetAufZuordnung).toList();
    }

    public List<Eintrag> wartenAufUebergabe() throws IOException {
        return alle().stream().filter(Eintrag::wartetAufUebergabe).toList();
    }

    /** Speichert die Entscheidung aus dem Zuordnungsfenster (Dateiname → Art). */
    public synchronized void zuordnen(String id, Map<String, Art> arten) throws IOException {
        Properties p = laden(datei(id));
        for (int i = 0; p.containsKey("datei." + i + ".name"); i++) {
            Art a = arten.get(p.getProperty("datei." + i + ".name"));
            if (a != null && !p.containsKey("datei." + i + ".mappe")) {
                p.setProperty("datei." + i + ".art", a.name());
            }
        }
        speichernOderAbschliessen(id, p);
    }

    public synchronized void mappeAngelegt(String id, int nr, String mappenId) throws IOException {
        Properties p = laden(datei(id));
        p.setProperty("datei." + nr + ".mappe", mappenId);
        p.remove("datei." + nr + ".fehler");
        speichern(id, p);
    }

    public synchronized void workflowGestartet(String id, int nr) throws IOException {
        Properties p = laden(datei(id));
        p.setProperty("datei." + nr + ".workflow", "true");
        p.remove("datei." + nr + ".fehler");
        speichernOderAbschliessen(id, p);
    }

    public synchronized void fehler(String id, int nr, String text) throws IOException {
        Properties p = laden(datei(id));
        setze(p, "datei." + nr + ".fehler", text);
        speichern(id, p);
    }

    private void speichernOderAbschliessen(String id, Properties p) throws IOException {
        speichern(id, p);
        if (lies(p).fertig()) {
            Path erledigt = Files.createDirectories(dir.resolve("erledigt"));
            Files.move(datei(id), erledigt.resolve(datei(id).getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Eintrag lies(Properties p) {
        List<Datei> dateien = new ArrayList<>();
        for (int i = 0; p.containsKey("datei." + i + ".name"); i++) {
            String art = p.getProperty("datei." + i + ".art");
            dateien.add(new Datei(i, p.getProperty("datei." + i + ".name"), art == null ? null : Art.valueOf(art),
                    p.getProperty("datei." + i + ".mappe"), "true".equals(p.getProperty("datei." + i + ".workflow")),
                    p.getProperty("datei." + i + ".fehler")));
        }
        return new Eintrag(p.getProperty("id"), Path.of(p.getProperty("ordner")), p.getProperty("absender"),
                p.getProperty("betreff"), p.getProperty("geschaeftszahl"), p.getProperty("zustellqualitaet"),
                p.getProperty("eingang"), dateien);
    }

    private Path datei(String id) {
        return dir.resolve(FileNames.sanitize(id, "zustellung") + ".properties");
    }

    private static void setze(Properties p, String k, String v) {
        if (v != null) {
            p.setProperty(k, v);
        }
    }

    private static Properties laden(Path f) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private void speichern(String id, Properties p) throws IOException {
        Files.createDirectories(dir);
        Path tmp = dir.resolve(FileNames.sanitize(id, "zustellung") + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "ELAK-Übergabe einer USP-Zustellung");
        }
        Files.move(tmp, datei(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
