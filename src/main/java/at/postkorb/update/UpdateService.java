package at.postkorb.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Optional;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * Sucht und lädt Updates von den GitHub-Releases. Erwartete Dateien im neuesten Release:
 * {@code version.txt}, {@code postkorb-schnittstelle.jar}, {@code postkorb-schnittstelle.jar.sig}.
 * GitHub leitet {@code .../releases/latest/download/<datei>} auf das neueste Release um.
 */
public final class UpdateService {

    public static final URI DEFAULT_BASIS = URI.create("https://github.com/breichr/postkorb-schnittstelle/releases/latest/download/");
    static final String JAR = "postkorb-schnittstelle.jar";

    private final URI basis;
    private final Version aktuell;
    private final PublicKey schluessel;
    private final Path updateDir;
    private final HttpClient http;

    public UpdateService(URI basis, String aktuelleVersion, PublicKey schluessel, Path updateDir) {
        this.basis = basis.toString().endsWith("/") ? basis : URI.create(basis + "/");
        this.aktuell = Version.of(aktuelleVersion);
        this.schluessel = schluessel;
        this.updateDir = updateDir;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean aktiv() {
        return schluessel != null;
    }

    /** @return die Versionsnummer, falls eine neuere als die laufende veröffentlicht ist */
    public Optional<String> neuereVersion() throws IOException {
        String v = new String(get("version.txt"), StandardCharsets.UTF_8).strip();
        if (!v.matches("[0-9][0-9A-Za-z.\\-]{0,30}")) {
            throw new IOException("Ungültige version.txt: " + v);
        }
        return Version.of(v).istNeuerAls(aktuell) ? Optional.of(v) : Optional.empty();
    }

    /**
     * Lädt die neue Version herunter und prüft sie. Liefert die geprüfte JAR-Datei im Update-Ordner.
     * Wirft eine Exception, wenn Signatur oder Versionsnummer nicht stimmen.
     */
    public Path herunterladen(String version) throws IOException {
        if (schluessel == null) {
            throw new IOException("Kein öffentlicher Update-Schlüssel im Programm – Updates sind deaktiviert");
        }
        byte[] jar = get(JAR);
        String sig = new String(get(JAR + ".sig"), StandardCharsets.US_ASCII);
        if (!Signatur.pruefe(jar, sig, schluessel)) {
            throw new IOException("Signatur des Updates ist ungültig – Update wird NICHT installiert");
        }
        String imJar = implementationVersion(jar);
        if (!version.equals(imJar)) {
            throw new IOException("Update enthält Version " + imJar + " statt " + version + " – nicht installiert");
        }
        if (!Version.of(imJar).istNeuerAls(aktuell)) {
            throw new IOException("Update " + imJar + " ist nicht neuer als " + aktuell);
        }
        Files.createDirectories(updateDir);
        Path tmp = Files.createTempFile(updateDir, "download", ".tmp");
        Files.write(tmp, jar);
        Path ziel = updateDir.resolve("postkorb-schnittstelle-" + version + ".jar");
        Files.move(tmp, ziel, StandardCopyOption.REPLACE_EXISTING);
        return ziel;
    }

    static String implementationVersion(byte[] jar) throws IOException {
        try (JarInputStream in = new JarInputStream(new java.io.ByteArrayInputStream(jar))) {
            Manifest m = in.getManifest();
            return m == null ? null : m.getMainAttributes().getValue("Implementation-Version");
        }
    }

    private byte[] get(String datei) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(basis.resolve(datei))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "postkorb-schnittstelle/" + aktuell)
                .GET().build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) {
                    throw new IOException("Update-Server: HTTP " + resp.statusCode() + " für " + datei);
                }
                byte[] data = in.readNBytes(50_000_000);
                return data;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Update-Download unterbrochen", e);
        }
    }
}
