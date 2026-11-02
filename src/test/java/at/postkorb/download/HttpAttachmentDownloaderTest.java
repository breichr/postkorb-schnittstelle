package at.postkorb.download;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

class HttpAttachmentDownloaderTest {

    private HttpServer server;
    private final byte[] pdf = "%PDF-1.7 test".getBytes();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/anhang/1", ex -> {
            ex.sendResponseHeaders(200, pdf.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(pdf);
            }
        });
        // 206 ohne Range-Anfrage, ganze Datei in einem Teil (so antwortet der Postkorb)
        server.createContext("/anhang/206-ganz", ex -> {
            ex.getResponseHeaders().add("Content-Range", "bytes 0-" + (pdf.length - 1) + "/" + pdf.length);
            ex.sendResponseHeaders(206, pdf.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(pdf);
            }
        });
        server.createContext("/anhang/206-ohne-header", ex -> {
            ex.sendResponseHeaders(206, pdf.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(pdf);
            }
        });
        // liefert höchstens 4 Bytes je Antwort, beachtet "Range: bytes=n-"
        server.createContext("/anhang/206-teile", ex -> {
            String range = ex.getRequestHeaders().getFirst("Range");
            int start = range == null ? 0 : Integer.parseInt(range.replaceAll("\\D", ""));
            int ende = Math.min(start + 4, pdf.length) - 1;
            ex.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + ende + "/" + pdf.length);
            ex.sendResponseHeaders(206, ende - start + 1);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(pdf, start, ende - start + 1);
            }
        });
        server.createContext("/anhang/206-falsch", ex -> {
            ex.getResponseHeaders().add("Content-Range", "bytes 5-8/" + pdf.length);
            ex.sendResponseHeaders(206, 4);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(pdf, 5, 4);
            }
        });
        server.createContext("/anhang/404", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void laedtInhalt() throws IOException {
        HttpAttachmentDownloader d = new HttpAttachmentDownloader(null, Duration.ofSeconds(5));
        try (InputStream in = d.open(uri("/anhang/1"))) {
            assertArrayEquals(pdf, in.readAllBytes());
        }
    }

    @Test
    void akzeptiertTeilantwortMitGanzerDatei() throws IOException {
        HttpAttachmentDownloader d = new HttpAttachmentDownloader(null, Duration.ofSeconds(5));
        try (InputStream in = d.open(uri("/anhang/206-ganz"))) {
            assertArrayEquals(pdf, in.readAllBytes());
        }
        try (InputStream in = d.open(uri("/anhang/206-ohne-header"))) {
            assertArrayEquals(pdf, in.readAllBytes());
        }
    }

    @Test
    void laedtFehlendeTeileNach() throws IOException {
        HttpAttachmentDownloader d = new HttpAttachmentDownloader(null, Duration.ofSeconds(5));
        try (InputStream in = d.open(uri("/anhang/206-teile"))) {
            assertArrayEquals(pdf, in.readAllBytes());
        }
    }

    @Test
    void fehlerBeiTeilantwortMitFalschemStart() {
        HttpAttachmentDownloader d = new HttpAttachmentDownloader(null, Duration.ofSeconds(5));
        assertThrows(IOException.class, () -> d.open(uri("/anhang/206-falsch")));
    }

    @Test
    void fehlerBeiHttpStatus() {
        HttpAttachmentDownloader d = new HttpAttachmentDownloader(null, Duration.ofSeconds(5));
        assertThrows(IOException.class, () -> d.open(uri("/anhang/404")));
    }
}
