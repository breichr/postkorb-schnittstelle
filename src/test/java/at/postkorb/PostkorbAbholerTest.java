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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Verhält sich wie der Postkorb: NewDeliveriesOnly liefert alles, was noch nicht abgeschlossen ist. */
    static final class FakeGateway implements PostkorbGateway {
        final Map<String, Zustellung> postkorb = new LinkedHashMap<>();
        final List<String> abgerufen = new ArrayList<>();
        final List<String> abgeschlossen = new ArrayList<>();
        final List<String> geloescht = new ArrayList<>();
        String kaputterAnhang;
        boolean abschliessenSchlaegtFehl;
        int limit = 100;

        void add(Zustellung z) {
            postkorb.put(z.id(), z);
        }

        @Override
        public List<String> neueZustellungen() {
            return postkorb.keySet().stream().filter(id -> !abgeschlossen.contains(id)).limit(limit).toList();
        }

        @Override
        public Zustellung abrufen(String id) {
            abgerufen.add(id);
            return postkorb.get(id);
        }

        @Override
        public InputStream oeffneAnhang(Zustellung z, Anhang a) throws IOException {
            if (a.dateiname().equals(kaputterAnhang)) {
                throw new IOException("HTTP 500");
            }
            return new ByteArrayInputStream(inhalt(a.dateiname()));
        }

        @Override
        public void abschliessen(String id) throws IOException {
            if (abschliessenSchlaegtFehl) {
                throw new IOException("SOAP-Fault");
            }
            abgeschlossen.add(id);
        }

        @Override
        public void loeschen(String id) {
            geloescht.add(id);
        }
    }

    static byte[] inhalt(String dateiname) {
        return ("Inhalt " + dateiname).getBytes(StandardCharsets.UTF_8);
    }

    private static Zustellung zustellung(String id, String... dateien) {
        List<Anhang> a = Stream.of(dateien).map(d -> new Anhang(d, "application/pdf", URI.create("https://x/" + d))).toList();
        return new Zustellung(id, "Finanzamt Österreich", "Bescheid", Instant.parse("2026-09-25T08:00:00Z"), a,
                Map.of("Geschäftszahl", "GZ-4711"));
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
    void speichertUndSchliesstAb() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.add(zustellung("Z1", "Bescheid.pdf", "Bescheid.pdf", "Beilage:1.pdf"));

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 0), abholer(gw, false).durchlauf());

        assertEquals(List.of("Z1"), gw.abgeschlossen);
        assertTrue(gw.geloescht.isEmpty());
        Path dir;
        try (Stream<Path> s = Files.list(tmp.resolve("out"))) {
            dir = s.filter(p -> p.getFileName().toString().endsWith("_Z1")).findFirst().orElseThrow();
        }
        assertTrue(Files.exists(dir.resolve("Bescheid.pdf")));
        assertTrue(Files.exists(dir.resolve("Bescheid (2).pdf")));
        assertTrue(Files.exists(dir.resolve("Beilage_1.pdf")));
        String meta = Files.readString(dir.resolve("zustellung.txt"));
        assertTrue(meta.contains("Finanzamt Österreich"));
        assertTrue(meta.contains("Geschäftszahl: GZ-4711"));
    }

    @Test
    void loeschtNurWennKonfiguriert() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.add(zustellung("Z1", "a.pdf"));

        abholer(gw, true).durchlauf();

        assertEquals(List.of("Z1"), gw.abgeschlossen);
        assertEquals(List.of("Z1"), gw.geloescht);
    }

    @Test
    void fehlerhafterDownloadWirdNichtAbgeschlossenUndHinterlaesstNichts() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.add(zustellung("Z1", "a.pdf", "b.pdf"));
        gw.add(zustellung("Z2", "c.pdf"));
        gw.kaputterAnhang = "b.pdf";

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 1), abholer(gw, false).durchlauf());

        assertEquals(List.of("Z2"), gw.abgeschlossen);
        assertEquals(1, ordner());
        assertFalse(Files.readString(tmp.resolve("state.txt")).contains("Z1"));

        // nächster Lauf: Anhang wieder verfügbar
        gw.kaputterAnhang = null;
        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 0), abholer(gw, false).durchlauf());
        assertEquals(List.of("Z2", "Z1"), gw.abgeschlossen);
    }

    @Test
    void keinDoppelterDownloadWennAbschliessenFehlschlaegt() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.add(zustellung("Z1", "a.pdf"));
        gw.abschliessenSchlaegtFehl = true;

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 1), abholer(gw, false).durchlauf());

        gw.abschliessenSchlaegtFehl = false;
        assertEquals(new PostkorbAbholer.Ergebnis(0, 1, 0), abholer(gw, false).durchlauf());
        assertEquals(List.of("Z1"), gw.abgerufen);
        assertEquals(List.of("Z1"), gw.abgeschlossen);
        assertEquals(1, ordner());
    }

    @Test
    void fragtNachBisAlleAbgeholtSind() throws IOException {
        FakeGateway gw = new FakeGateway();
        gw.limit = 2;
        for (int i = 1; i <= 5; i++) {
            gw.add(zustellung("Z" + i, "a.pdf"));
        }

        assertEquals(new PostkorbAbholer.Ergebnis(5, 0, 0), abholer(gw, false).durchlauf());
        assertEquals(5, gw.abgeschlossen.size());
    }

    @Test
    void pruefsummeUndGroesseWerdenGeprueft() throws Exception {
        byte[] a = inhalt("a.pdf");
        String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(a));
        String b64 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(inhalt("b.pdf")));
        FakeGateway gw = new FakeGateway();
        gw.add(new Zustellung("Z1", "BMF", "Bescheid", Instant.now(), List.of(
                new Anhang("a.pdf", "application/pdf", URI.create("https://x/a"), (long) a.length, hex.toUpperCase(), "SHA256"),
                new Anhang("b.pdf", "application/pdf", URI.create("https://x/b"), null, b64, "SHA512"))));
        gw.add(new Zustellung("Z2", "BMF", "falsche Prüfsumme", Instant.now(), List.of(
                new Anhang("c.pdf", "application/pdf", URI.create("https://x/c"), null, hex, "SHA256"))));
        gw.add(new Zustellung("Z3", "BMF", "falsche Größe", Instant.now(), List.of(
                new Anhang("d.pdf", "application/pdf", URI.create("https://x/d"), 9999L, null, null))));

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 2), abholer(gw, false).durchlauf());
        assertEquals(List.of("Z1"), gw.abgeschlossen);
        assertEquals(1, ordner());
    }
}
