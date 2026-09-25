package at.postkorb.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Simuliert den Postkorb mit einem lokalen Ordner – zum Testen der Abläufe ohne Zertifikat.
 * Jeder Unterordner von {@code demo.inbox} ist eine Zustellung, jede Datei darin ein Anhang.
 * Gelöschte Zustellungen werden in {@code <inbox>/.geloescht} verschoben.
 */
public final class DemoGateway implements PostkorbGateway {

    private static final Logger LOG = Logger.getLogger(DemoGateway.class.getName());
    private final Path inbox;

    public DemoGateway(Path inbox) {
        this.inbox = inbox;
    }

    @Override
    public List<Zustellung> abholbereit() throws IOException {
        List<Zustellung> result = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(inbox)) {
            for (Path dir : dirs.filter(Files::isDirectory).filter(d -> !d.getFileName().toString().startsWith(".")).sorted().toList()) {
                List<Anhang> anhaenge;
                try (Stream<Path> files = Files.list(dir)) {
                    anhaenge = files.filter(Files::isRegularFile).sorted()
                            .map(f -> new Anhang(f.getFileName().toString(), probe(f), f.toUri()))
                            .toList();
                }
                String id = dir.getFileName().toString();
                result.add(new Zustellung(id, "Demo-Behörde", "Demo-Zustellung " + id,
                        Files.getLastModifiedTime(dir).toInstant(), anhaenge));
            }
        }
        return result;
    }

    @Override
    public InputStream oeffneAnhang(Zustellung zustellung, Anhang anhang) throws IOException {
        return Files.newInputStream(Path.of(anhang.downloadUri()));
    }

    @Override
    public void loescheZustellung(Zustellung zustellung) throws IOException {
        Path done = Files.createDirectories(inbox.resolve(".geloescht"));
        Files.move(inbox.resolve(zustellung.id()), done.resolve(zustellung.id() + "_" + Instant.now().toEpochMilli()));
        LOG.info(() -> "Demo: Zustellung gelöscht: " + zustellung.id());
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
