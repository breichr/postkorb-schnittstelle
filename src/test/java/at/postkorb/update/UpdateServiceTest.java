package at.postkorb.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class UpdateServiceTest {

    @TempDir
    Path tmp;
    private HttpServer server;
    private final Map<String, byte[]> dateien = new ConcurrentHashMap<>();
    private KeyPair schluessel;

    @BeforeEach
    void start() throws Exception {
        schluessel = Signatur.erzeugeSchluesselpaar();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // wie GitHub: /releases/latest/download/<datei> leitet auf die eigentliche Datei um
        server.createContext("/latest/download/", ex -> {
            String name = ex.getRequestURI().getPath().substring("/latest/download/".length());
            ex.getResponseHeaders().add("Location", "/asset/" + name);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/asset/", ex -> {
            byte[] b = dateien.get(ex.getRequestURI().getPath().substring("/asset/".length()));
            if (b == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private UpdateService service(String aktuell) {
        return new UpdateService(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest/download"),
                aktuell, schluessel.getPublic(), tmp.resolve("updates"));
    }

    private static byte[] jar(String version) throws IOException {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream j = new JarOutputStream(bos, m)) {
            j.putNextEntry(new java.util.jar.JarEntry("x.txt"));
            j.write("Inhalt".getBytes());
        }
        return bos.toByteArray();
    }

    private void veroeffentliche(String version, byte[] jar, KeyPair signierer) throws Exception {
        dateien.put("version.txt", (version + "\n").getBytes(StandardCharsets.UTF_8));
        dateien.put("postkorb-schnittstelle.jar", jar);
        dateien.put("postkorb-schnittstelle.jar.sig",
                Signatur.signiere(jar, signierer.getPrivate()).getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void versionsvergleich() {
        assertTrue(Version.of("1.0.12").istNeuerAls(Version.of("1.0.9")));
        assertTrue(Version.of("1.1.0").istNeuerAls(Version.of("1.0.99")));
        assertTrue(Version.of("1.0.0").istNeuerAls(Version.of("1.0.0-SNAPSHOT")));
        assertTrue(Version.of("1.0.1").istNeuerAls(Version.of("0.0.0-dev")));
        assertFalse(Version.of("1.0.5").istNeuerAls(Version.of("1.0.5")));
        assertFalse(Version.of("v1.0.5").istNeuerAls(Version.of("1.0.6")));
    }

    @Test
    void findetUndLaedtNeuereVersion() throws Exception {
        byte[] jar = jar("1.0.7");
        veroeffentliche("1.0.7", jar, schluessel);

        UpdateService s = service("1.0.3");
        assertEquals(Optional.of("1.0.7"), s.neuereVersion());
        Path p = s.herunterladen("1.0.7");
        assertArrayEquals(jar, Files.readAllBytes(p));
        assertEquals("postkorb-schnittstelle-1.0.7.jar", p.getFileName().toString());
    }

    @Test
    void keinUpdateWennAktuell() throws Exception {
        veroeffentliche("1.0.7", jar("1.0.7"), schluessel);
        assertEquals(Optional.empty(), service("1.0.7").neuereVersion());
        assertEquals(Optional.empty(), service("1.0.8").neuereVersion());
    }

    @Test
    void fremdeSignaturWirdAbgelehnt() throws Exception {
        veroeffentliche("1.0.7", jar("1.0.7"), Signatur.erzeugeSchluesselpaar());
        IOException e = assertThrows(IOException.class, () -> service("1.0.3").herunterladen("1.0.7"));
        assertTrue(e.getMessage().contains("Signatur"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("updates/postkorb-schnittstelle-1.0.7.jar")));
    }

    @Test
    void veraenderteDateiWirdAbgelehnt() throws Exception {
        byte[] jar = jar("1.0.7");
        veroeffentliche("1.0.7", jar, schluessel);
        byte[] manipuliert = jar.clone();
        manipuliert[manipuliert.length - 30] ^= 1;
        dateien.put("postkorb-schnittstelle.jar", manipuliert);
        assertThrows(IOException.class, () -> service("1.0.3").herunterladen("1.0.7"));
    }

    @Test
    void alteSigniertVersionAlsNeueGetarntWirdAbgelehnt() throws Exception {
        // echte, signierte alte Version, aber version.txt behauptet eine neue (Downgrade-Angriff)
        veroeffentliche("1.0.9", jar("1.0.2"), schluessel);
        IOException e = assertThrows(IOException.class, () -> service("1.0.3").herunterladen("1.0.9"));
        assertTrue(e.getMessage().contains("1.0.2"), e.getMessage());
    }

    @Test
    void ohneSchluesselKeinDownload() throws Exception {
        veroeffentliche("1.0.7", jar("1.0.7"), schluessel);
        UpdateService s = new UpdateService(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/latest/download/"),
                "1.0.3", null, tmp.resolve("updates"));
        assertFalse(s.aktiv());
        assertThrows(IOException.class, () -> s.herunterladen("1.0.7"));
    }

    @Test
    void ungueltigeVersionTxt() {
        dateien.put("version.txt", "<html>Fehler</html>".getBytes());
        assertThrows(IOException.class, () -> service("1.0.3").neuereVersion());
    }

    @Test
    void signaturSchluesselUeberText() throws Exception {
        byte[] d = "abc".getBytes();
        String sig = Signatur.signiere(d, Signatur.privatSchluessel(Signatur.kodiere(schluessel.getPrivate())));
        assertTrue(Signatur.pruefe(d, sig, Signatur.oeffentlicherSchluessel(Signatur.kodiere(schluessel.getPublic()))));
        assertFalse(Signatur.pruefe(d, "kaputt", schluessel.getPublic()));
    }

    @Test
    void eingebauterSchluesselIstGueltig() {
        java.security.PublicKey k = Signatur.eingebauterSchluessel();
        assertTrue(k != null, "update-public-key.txt enthält keinen gültigen Ed25519-Schlüssel");
        assertEquals("MCowBQYDK2VwAyEApGzTuZy4d50SFRehLhhuXy/vGw8QP+cmC0+z3eW3+JI=", Signatur.kodiere(k));
    }

    @Test
    void installationsskriptEnthaeltRueckfall() {
        String cmd = UpdateInstaller.skript(Path.of("C:/Postkorb/postkorb-schnittstelle.jar"),
                Path.of("C:/Postkorb/Eingang/updates/postkorb-schnittstelle-1.0.7.jar"),
                Path.of("C:/Postkorb/runtime/bin/javaw.exe"), Path.of("C:/Postkorb/config/postkorb.properties"),
                Path.of("C:/Postkorb/Eingang/updates/ok-1.0.7"), Path.of("C:/Postkorb/Eingang/updates/update.log"));
        assertTrue(cmd.contains("move /y \"%JAR%\" \"%JAR%.bak\""));
        assertTrue(cmd.contains(":zurueck"));
        assertTrue(cmd.contains("copy /y \"%JAR%.bak\" \"%JAR%\""));
        assertTrue(cmd.contains("--tray"));
        assertFalse(cmd.contains("timeout"), "timeout funktioniert ohne Konsole nicht");
        assertTrue(cmd.contains("\r\n"));
    }
}
