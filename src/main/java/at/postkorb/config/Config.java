package at.postkorb.config;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Konfiguration aus einer Properties-Datei (siehe config/postkorb.properties.example).
 * Passwörter können statt in der Datei auch über Umgebungsvariablen gesetzt werden.
 */
public record Config(
        String gateway,
        URI soapEndpoint,
        String attachmentUrlTemplate,
        int queryLimit,
        String keystoreType,
        Path keystorePath,
        char[] keystorePassword,
        String keyAlias,
        Path truststorePath,
        char[] truststorePassword,
        Path outputDir,
        Path stateFile,
        boolean deleteAfterDownload,
        Duration pollInterval,
        Duration httpTimeout,
        Path demoInbox) {

    public static final URI DEFAULT_SOAP_ENDPOINT = URI.create("https://autoabholung.meinpostkorb.brz.gv.at/soap");
    /** Testzugang laut USP-How-To: liefert immer dieselben Mockdaten, Close/Delete ändern nichts. */
    public static final URI DEMO_SOAP_ENDPOINT = URI.create("https://demo-autoabholung.meinpostkorb.brz.gv.at/soap");

    /** Download-Adresse der Anhänge laut USP-How-To, auf demselben Host wie der SOAP-Endpoint. */
    public static String defaultAttachmentUrl(URI soapEndpoint) {
        return soapEndpoint.getScheme() + "://" + soapEndpoint.getRawAuthority()
                + "/attachment?delivery_id={deliveryId}&attachment_id={attachmentId}";
    }

    public static Config load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        Path base = file.toAbsolutePath().getParent();

        String gateway = get(p, "gateway", "soap");
        URI endpoint = URI.create(get(p, "soap.endpoint", DEFAULT_SOAP_ENDPOINT.toString()));
        Path outputDir = path(base, get(p, "output.dir", "downloads"));

        return new Config(
                gateway,
                endpoint,
                get(p, "attachment.url", defaultAttachmentUrl(endpoint)),
                Integer.parseInt(get(p, "query.limit", "100")),
                get(p, "tls.keystore.type", "PKCS12"),
                optionalPath(base, get(p, "tls.keystore.path", null)),
                secret(p, "tls.keystore.password", "POSTKORB_KEYSTORE_PASSWORD"),
                get(p, "tls.key.alias", null),
                optionalPath(base, get(p, "tls.truststore.path", null)),
                secret(p, "tls.truststore.password", "POSTKORB_TRUSTSTORE_PASSWORD"),
                outputDir,
                path(base, get(p, "state.file", outputDir.resolve(".abgeholt.txt").toString())),
                Boolean.parseBoolean(get(p, "delete.after.download", "false")),
                Duration.ofMinutes(Long.parseLong(get(p, "poll.interval.minutes", "60"))),
                Duration.ofSeconds(Long.parseLong(get(p, "http.timeout.seconds", "60"))),
                optionalPath(base, get(p, "demo.inbox", null)));
    }

    private static String get(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static char[] secret(Properties p, String key, String envVar) {
        String env = System.getenv(envVar);
        if (env != null && !env.isEmpty()) {
            return env.toCharArray();
        }
        String v = p.getProperty(key);
        return v == null ? null : v.toCharArray();
    }

    private static Path path(Path base, String value) {
        Path p = Path.of(value);
        return p.isAbsolute() ? p : base.resolve(p).normalize();
    }

    private static Path optionalPath(Path base, String value) {
        return value == null ? null : path(base, value);
    }
}
