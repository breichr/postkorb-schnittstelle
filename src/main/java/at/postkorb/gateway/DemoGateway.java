package at.postkorb.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Simuliert den Postkorb mit einem lokalen Ordner – zum Testen der Abläufe ohne Zertifikat.
 * Jeder Unterordner von {@code demo.inbox} ist eine Zustellung, jede Datei darin ein Anhang.
 * Abgeschlossene Zustellungen werden nach {@code <inbox>/.abgeschlossen} verschoben,
 * gelöschte von dort nach {@code <inbox>/.geloescht}.
 */
public final class DemoGateway implements PostkorbGateway {

    private static final Logger LOG = Logger.getLogger(DemoGateway.class.getName());
    private final Path inbox;

    public DemoGateway(Path inbox) {
        this.inbox = inbox;
    }

    @Override
    public List<String> neueZustellungen() throws IOException {
        try (Stream<Path> dirs = Files.list(inbox)) {
            return dirs.filter(Files::isDirectory)
                    .map(d -> d.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .sorted()
                    .toList();
        }
    }

    @Override
    public Zustellung abrufen(String id) throws IOException {
        Path dir = inbox.resolve(id);
        List<Anhang> anhaenge;
        try (Stream<Path> files = Files.list(dir)) {
            anhaenge = files.filter(Files::isRegularFile).sorted()
                    .map(f -> new Anhang(f.getFileName().toString(), probe(f), f.toUri()))
                    .toList();
        }
        return new Zustellung(id, "Demo-Behörde", "Demo-Zustellung " + id,
                Files.getLastModifiedTime(dir).toInstant(), anhaenge);
    }

    @Override
    public InputStream oeffneAnhang(Zustellung zustellung, Anhang anhang) throws IOException {
        return Files.newInputStream(Path.of(anhang.downloadUri()));
    }

    @Override
    public void abschliessen(String id) throws IOException {
        move(inbox.resolve(id), inbox.resolve(".abgeschlossen"), id);
        LOG.info(() -> "Demo: Zustellung abgeschlossen: " + id);
    }

    @Override
    public void loeschen(String id) throws IOException {
        move(inbox.resolve(".abgeschlossen").resolve(id), inbox.resolve(".geloescht"), id);
        LOG.info(() -> "Demo: Zustellung gelöscht: " + id);
    }

    private static void move(Path from, Path targetDir, String id) throws IOException {
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(id);
        if (Files.exists(target)) {
            // gleiche ID schon einmal verarbeitet (z. B. erneut in die Demo-Inbox kopiert)
            Files.move(target, targetDir.resolve(id + "_" + Instant.now().toEpochMilli()));
        }
        Files.move(from, target);
    }

    private static String probe(Path f) {
        try {
            String t = Files.probeContentType(f);
            return t != null ? t : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }
}
