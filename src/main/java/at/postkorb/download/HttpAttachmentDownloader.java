package at.postkorb.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;

/**
 * Lädt Anhänge per REST-GET. Für die Automatische Abholung wird derselbe mTLS-SSLContext
 * wie für die SOAP-Aufrufe verwendet.
 *
 * <p>Der Server antwortet bei manchen Anhängen mit HTTP 206 (Partial Content), auch ohne
 * Range-Anfrage. Deckt der Teil die ganze Datei ab, wird er wie 200 behandelt; sonst werden
 * die restlichen Bytes mit {@code Range}-Anfragen nachgeladen. Ob am Ende alles da ist,
 * prüft der DocumentStore über Größe und Prüfsumme.
 */
public final class HttpAttachmentDownloader {

    private static final Logger LOG = Logger.getLogger(HttpAttachmentDownloader.class.getName());
    private static final Pattern CONTENT_RANGE =
            Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_TEILE = 10_000;

    private final HttpClient client;
    private final Duration timeout;

    public HttpAttachmentDownloader(SSLContext sslContext, Duration timeout) {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (sslContext != null) {
            b.sslContext(sslContext);
        }
        this.client = b.build();
        this.timeout = timeout;
    }

    /** Öffnet den Download-Stream; der Aufrufer muss ihn schließen. */
    public InputStream open(URI uri) throws IOException {
        HttpResponse<InputStream> resp = send(uri, null);
        if (resp.statusCode() == 200) {
            return resp.body();
        }
        if (resp.statusCode() != 206) {
            resp.body().close();
            throw new IOException("Download fehlgeschlagen (HTTP " + resp.statusCode() + "): " + uri);
        }
        Teil teil = teil(resp, uri, 0);
        if (teil == null || teil.vollstaendig()) {
            return resp.body();
        }
        LOG.info(() -> "Anhang wird in Teilen geladen (" + teil.gesamt + " Bytes): " + uri);
        return new TeilStream(uri, resp.body(), teil);
    }

    private HttpResponse<InputStream> send(URI uri, String range) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        if (range != null) {
            b.header("Range", range);
        }
        try {
            return client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download unterbrochen: " + uri, e);
        }
    }

    /**
     * Liest den Content-Range einer 206-Antwort. {@code null}, wenn der Header fehlt – dann
     * gilt der Inhalt als ganze Datei.
     */
    private static Teil teil(HttpResponse<?> resp, URI uri, long erwarteterStart) throws IOException {
        String header = resp.headers().firstValue("Content-Range").orElse(null);
        if (header == null) {
            return null;
        }
        Matcher m = CONTENT_RANGE.matcher(header.trim());
        if (!m.matches()) {
            throw new IOException("Unbekannter Content-Range '" + header + "': " + uri);
        }
        long start = Long.parseLong(m.group(1));
        long ende = Long.parseLong(m.group(2));
        long gesamt = "*".equals(m.group(3)) ? -1 : Long.parseLong(m.group(3));
        if (start != erwarteterStart || ende < start) {
            throw new IOException("Unerwarteter Content-Range '" + header + "' (erwartet ab Byte "
                    + erwarteterStart + "): " + uri);
        }
        return new Teil(ende, gesamt);
    }

    private record Teil(long ende, long gesamt) {
        boolean vollstaendig() {
            return gesamt < 0 || ende + 1 >= gesamt;
        }
    }

    /** Hängt die Teile hintereinander; der nächste Teil wird erst angefordert, wenn er gebraucht wird. */
    private final class TeilStream extends InputStream {
        private final URI uri;
        private InputStream aktuell;
        private Teil teil;
        private int teile = 1;

        TeilStream(URI uri, InputStream erster, Teil teil) {
            this.uri = uri;
            this.aktuell = erster;
            this.teil = teil;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n < 0 ? -1 : b[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (aktuell != null) {
                int n = aktuell.read(b, off, len);
                if (n >= 0) {
                    return n;
                }
                aktuell.close();
                aktuell = null;
                naechsterTeil();
            }
            return -1;
        }

        private void naechsterTeil() throws IOException {
            if (teil.vollstaendig()) {
                return;
            }
            if (++teile > MAX_TEILE) {
                throw new IOException("Zu viele Teilantworten: " + uri);
            }
            long start = teil.ende + 1;
            HttpResponse<InputStream> resp = send(uri, "bytes=" + start + "-");
            if (resp.statusCode() != 206) {
                resp.body().close();
                throw new IOException("Teil-Download ab Byte " + start + " fehlgeschlagen (HTTP "
                        + resp.statusCode() + "): " + uri);
            }
            Teil naechster = teil(resp, uri, start);
            if (naechster == null) {
                resp.body().close();
                throw new IOException("Teilantwort ohne Content-Range ab Byte " + start + ": " + uri);
            }
            teil = naechster;
            aktuell = resp.body();
        }

        @Override
        public void close() throws IOException {
            if (aktuell != null) {
                aktuell.close();
                aktuell = null;
            }
        }
    }
}
