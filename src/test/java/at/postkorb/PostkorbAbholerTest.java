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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
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
        final List<String> geloescht = new ArrayList<>();
        String kaputterAnhang;
        boolean loeschenSchlaegtFehl;

        @Override
        public List<Zustellung> abholbereit() {
            return liste;
        }

        @Override
        public InputStream oeffneAnhang(Zustellung z, Anhang a) throws IOException {
            if (a.dateiname().equals(kaputterAnhang)) {
                throw new IOException("HTTP 500");
            }
            return new ByteArrayInputStream(inhalt(a.dateiname()));
        }

        @Override
        public void loescheZustellung(Zustellung z) throws IOException {
            if (loeschenSchlaegtFehl) {
                throw new IOException("SOAP-Fault");
            }
            geloescht.add(z.id());
        }
    }

    static byte[] inhalt(String dateiname) {
        return ("Inhalt " + dateiname).getBytes(StandardCharsets.UTF_8);
    }

    private static Zustellung zustellung(String id, String... dateien) {
        List<Anhang> a = Stream.of(dateien).map(d -> new Anhang(d, "application/pdf", URI.create("https://x/" + d))).toList();
        return new Zustellung(id, "Finanzamt Österreich", "Bescheid", Instant.parse("2026-09-25T08:00:00Z"), a);
    }

    private PostkorbAbholer abholer(FakeGateway gw) throws IOException {
        return abholer(gw, true);
    }

    private PostkorbAbholer abholer(FakeGateway gw, boolean loeschen) throws IOException {
        return new PostkorbAbholer(gw, new DocumentStore(tmp.resolve("out")), new ProcessedStore(tmp.resolve("state.txt")), loeschen);
    }

    private long ordner() throws IOException {
        try (Stream<Path> s = Files.list(tmp.resolve("out"))) {
            return s.filter(p -> !p.getFileName().toString().startsWith(".")).count();
        }
    }

    @Test
    void speichertUndLoescht() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.liste.add(zustellung("Z1", "Bescheid.pdf", "Bescheid.pdf", "Beilage:1.pdf"));

        PostkorbAbholer.Ergebnis r = abholer(gw).durchlauf();

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 0), r);
        assertEquals(List.of("Z1"), gw.geloescht);
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
    void fehlerhafterDownloadWirdNichtGeloeschtUndHinterlaesstNichts() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.liste.add(zustellung("Z1", "a.pdf", "b.pdf"));
        gw.liste.add(zustellung("Z2", "c.pdf"));
        gw.kaputterAnhang = "b.pdf";

        PostkorbAbholer.Ergebnis r = abholer(gw).durchlauf();

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 1), r);
        assertEquals(List.of("Z2"), gw.geloescht);
        assertEquals(1, ordner());
        assertFalse(Files.readString(tmp.resolve("state.txt")).contains("Z1"));
    }

    @Test
    void keinDoppelterDownloadWennLoeschenFehlschlaegt() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.liste.add(zustellung("Z1", "a.pdf"));
        gw.loeschenSchlaegtFehl = true;

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 1), abholer(gw).durchlauf());

        gw.loeschenSchlaegtFehl = false;
        assertEquals(new PostkorbAbholer.Ergebnis(0, 1, 0), abholer(gw).durchlauf());
        assertEquals(List.of("Z1"), gw.geloescht);
        assertEquals(1, ordner());
    }

    @Test
    void ohneLoeschenBleibtZustellungImPostkorbUndWirdNichtDoppeltGeladen() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.liste.add(zustellung("Z1", "a.pdf"));

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 0), abholer(gw, false).durchlauf());
        assertEquals(new PostkorbAbholer.Ergebnis(0, 1, 0), abholer(gw, false).durchlauf());
        assertTrue(gw.geloescht.isEmpty());
        assertEquals(1, ordner());
    }

    @Test
    void pruefsummeWirdGeprueft() throws Exception {
        String ok = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inhalt("a.pdf")));
        String okBase64 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(inhalt("b.pdf")));
        FakeGateway gw = new FakeGateway();
        gw.liste.add(new Zustellung("Z1", "BMF", "Bescheid", Instant.now(), List.of(
                new Anhang("a.pdf", "application/pdf", URI.create("https://x/a"), ok.toUpperCase(), "http://www.w3.org/2001/04/xmlenc#sha256"),
                new Anhang("b.pdf", "application/pdf", URI.create("https://x/b"), okBase64, "SHA256"))));
        gw.liste.add(new Zustellung("Z2", "BMF", "Bescheid", Instant.now(), List.of(
                new Anhang("c.pdf", "application/pdf", URI.create("https://x/c"), ok, "SHA-256"))));

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 1), abholer(gw).durchlauf());
        assertEquals(List.of("Z1"), gw.geloescht);
        assertEquals(1, ordner());
    }
}
