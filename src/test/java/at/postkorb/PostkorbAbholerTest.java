package at.postkorb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.postkorb.gateway.Anhang;
import at.postkorb.gateway.PostkorbGateway;
import at.postkorb.gateway.Zustellung;
import at.postkorb.store.DocumentStore;
import at.postkorb.store.ProcessedStore;

class PostkorbAbholerTest {

    @TempDir
    Path tmp;

    static final class FakeGateway implements PostkorbGateway {
        final List<Zustellung> liste = new ArrayList<>();
        final List<String> bestaetigt = new ArrayList<>();
        String kaputterAnhang;
        boolean bestaetigungSchlaegtFehl;

        @Override
        public List<Zustellung> abholbereit() {
            return liste;
        }

        @Override
        public InputStream oeffneAnhang(Zustellung z, Anhang a) throws IOException {
            if (a.dateiname().equals(kaputterAnhang)) {
                throw new IOException("HTTP 500");
            }
            return new ByteArrayInputStream(("Inhalt " + a.dateiname()).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void bestaetigeAbholung(Zustellung z) throws IOException {
            if (bestaetigungSchlaegtFehl) {
                throw new IOException("SOAP-Fault");
            }
            bestaetigt.add(z.id());
        }
    }

    private static Zustellung zustellung(String id, String... dateien) {
        List<Anhang> a = Stream.of(dateien).map(d -> new Anhang(d, "application/pdf", URI.create("https://x/" + d))).toList();
        return new Zustellung(id, "Finanzamt Österreich", "Bescheid", Instant.parse("2026-09-25T08:00:00Z"), a);
    }

    private PostkorbAbholer abholer(FakeGateway gw) throws IOException {
        return new PostkorbAbholer(gw, new DocumentStore(tmp.resolve("out")), new ProcessedStore(tmp.resolve("state.txt")), true);
    }

    private long ordner() throws IOException {
        try (Stream<Path> s = Files.list(tmp.resolve("out"))) {
            return s.filter(p -> !p.getFileName().toString().startsWith(".")).count();
        }
    }

    @Test
    void speichertUndBestaetigt() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.liste.add(zustellung("Z1", "Bescheid.pdf", "Bescheid.pdf", "Beilage:1.pdf"));

        PostkorbAbholer.Ergebnis r = abholer(gw).durchlauf();

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 0), r);
        assertEquals(List.of("Z1"), gw.bestaetigt);
        Path dir;
        try (Stream<Path> s = Files.list(tmp.resolve("out"))) {
            dir = s.filter(p -> p.getFileName().toString().endsWith("_Z1")).findFirst().orElseThrow();
        }
        assertTrue(Files.exists(dir.resolve("Bescheid.pdf")));
        assertTrue(Files.exists(dir.resolve("Bescheid (2).pdf")));
        assertTrue(Files.exists(dir.resolve("Beilage_1.pdf")));
        assertTrue(Files.readString(dir.resolve("zustellung.txt")).contains("Finanzamt Österreich"));
    }

    @Test
    void fehlerhafterDownloadWirdNichtBestaetigtUndHinterlaesstNichts() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.liste.add(zustellung("Z1", "a.pdf", "b.pdf"));
        gw.liste.add(zustellung("Z2", "c.pdf"));
        gw.kaputterAnhang = "b.pdf";

        PostkorbAbholer.Ergebnis r = abholer(gw).durchlauf();

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 1), r);
        assertEquals(List.of("Z2"), gw.bestaetigt);
        assertEquals(1, ordner());
        assertFalse(Files.readString(tmp.resolve("state.txt")).contains("Z1"));
    }

    @Test
    void keinDoppelterDownloadWennBestaetigungFehlschlaegt() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.liste.add(zustellung("Z1", "a.pdf"));
        gw.bestaetigungSchlaegtFehl = true;

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 1), abholer(gw).durchlauf());

        gw.bestaetigungSchlaegtFehl = false;
        assertEquals(new PostkorbAbholer.Ergebnis(0, 1, 0), abholer(gw).durchlauf());
        assertEquals(List.of("Z1"), gw.bestaetigt);
        assertEquals(1, ordner());
    }
}
