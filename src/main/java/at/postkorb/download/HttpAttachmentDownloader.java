package at.postkorb.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.net.ssl.SSLContext;

/**
 * Lädt Anhänge per REST-GET. Für die Automatische Abholung wird derselbe mTLS-SSLContext
 * wie für die SOAP-Aufrufe verwendet.
 */
public final class HttpAttachmentDownloader {

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
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download unterbrochen: " + uri, e);
        }
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException("Download fehlgeschlagen (HTTP " + resp.statusCode() + "): " + uri);
        }
        return resp.body();
    }
}
