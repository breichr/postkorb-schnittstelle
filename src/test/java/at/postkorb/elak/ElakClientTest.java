package at.postkorb.elak;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Simulierter otris-DOCUMENTS-SOAP-Server (Antwortformat wie gSOAP/DOCUMENTS-4.0.wsdl). */
class ElakClientTest {

    private static final String S = ElakClient.SOAP12;
    private static final String D = ElakClient.NS;

    @TempDir
    Path tmp;
    private HttpServer server;
    private final List<String> aufrufe = new ArrayList<>();
    private final List<Element> createFiles = new ArrayList<>();
    private String falschesPasswort = null;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ElakClient client() {
        return new ElakClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(5));
    }

    private ElakKonfiguration konfig(String register) {
        return new ElakKonfiguration(URI.create("http://x"), "breichr", "DA41226", "geheim".toCharArray(),
                "Dokument", "Dokument", "Beleg", "Outlook_ER", register);
    }

    @Test
    void anmeldenMappeAnlegenWorkflowAbmelden() throws IOException {
        byte[] pdf = "%PDF-1.7 Beschluss".getBytes();
        try (ElakClient c = client()) {
            c.login("breichr", "DA41226", "geheim".toCharArray());
            String id = c.mappeAnlegen("Dokument", Map.of("Betreff", "Beschluss"),
                    List.of(new ElakClient.Datei("Beschluss.pdf", "Anlagen", pdf)));
            assertEquals("dok_4711", id);
            c.workflowStarten(id, "wf_12");
        }
        assertEquals(List.of("login", "createFile 4242", "startWorkflow 4242", "logout 4242"), aufrufe);
        Element cf = createFiles.get(0);
        assertEquals("Dokument", text(cf, "fileType"));
        Element doc = child(child(cf, "addDocs"), "document");
        assertEquals("Beschluss.pdf", text(doc, "name"));
        assertEquals("Anlagen", text(doc, "register"));
        assertArrayEquals(pdf, Base64.getDecoder().decode(text(doc, "data")));
        Element field = child(child(cf, "fields"), "field");
        assertEquals("Betreff", text(field, "name"));
        assertNull(child(cf, "fileType").getNamespaceURI(), "Kindelemente müssen unqualifiziert sein");
    }

    @Test
    void falschesPasswortWirdAlsElakFehlerGemeldet() {
        falschesPasswort = "geheim";
        ElakException e = assertThrows(ElakException.class, () -> {
            try (ElakClient c = client()) {
                c.login("breichr", "DA41226", "falsch".toCharArray());
            }
        });
        assertEquals(20, e.code());
        assertTrue(e.getMessage().contains("Login fehlgeschlagen"), e.getMessage());
    }

    @Test
    void pruefenMeldetMappentypenRegisterUndWorkflows() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int rc;
        try (ElakClient c = client()) {
            rc = ElakWerkzeug.pruefen(c, konfig(null), new PrintStream(bos, true, StandardCharsets.UTF_8));
        }
        String out = bos.toString(StandardCharsets.UTF_8);
        assertEquals(0, rc, out);
        assertTrue(out.contains("[OK] Mappentyp 'Dokument'"), out);
        assertTrue(out.contains("Register: [Anlagen (reg_1), Schriftverkehr (reg_2)]"), out);
        assertTrue(out.contains("[OK] Workflow 'Outlook_ER' (id wf_13)"), out);
        assertTrue(aufrufe.stream().noneMatch(a -> a.startsWith("createFile") || a.startsWith("startWorkflow")),
                "Prüfen darf nichts verändern: " + aufrufe);
    }

    @Test
    void pruefenFindetFehlendenWorkflow() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ElakKonfiguration k = new ElakKonfiguration(URI.create("http://x"), "b", "m", "geheim".toCharArray(),
                "Dokument", "Gibtsnicht", "Beleg", "Outlook_ER", null);
        try (ElakClient c = client()) {
            assertEquals(2, ElakWerkzeug.pruefen(c, k, new PrintStream(bos, true, StandardCharsets.UTF_8)));
        }
        assertTrue(bos.toString(StandardCharsets.UTF_8).contains("[!!] Workflow 'Gibtsnicht' nicht gefunden"));
    }

    @Test
    void testmappeNimmtErstesRegisterUndStartetKeinenWorkflow() throws IOException {
        Path pdf = Files.write(tmp.resolve("Test.pdf"), "%PDF".getBytes());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ElakClient c = client()) {
            assertEquals(0, ElakWerkzeug.testmappe(c, konfig(null), pdf, new PrintStream(bos, true, StandardCharsets.UTF_8)));
        }
        assertEquals("Anlagen", text(child(child(createFiles.get(0), "addDocs"), "document"), "register"));
        assertTrue(aufrufe.stream().noneMatch(a -> a.startsWith("startWorkflow")), aufrufe.toString());
        assertTrue(bos.toString(StandardCharsets.UTF_8).contains("Mappen-ID dok_4711 (ohne Workflow)"));
    }

    // ---- simulierter Server ----

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!ex.getRequestHeaders().getFirst("Content-Type").startsWith("application/soap+xml")) {
                throw new IllegalStateException("Content-Type");
            }
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
            Element env = doc.getDocumentElement();
            Element op = first(child(env, "Body"));
            if (!D.equals(op.getNamespaceURI())) {
                throw new IllegalStateException("Operation nicht im DOCUMENTS-Namespace");
            }
            Element header = child(env, "Header");
            Element sid = header == null ? null : child(header, "sessionID");
            String name = op.getLocalName();
            if (!name.equals("login") && (sid == null || !"4242".equals(sid.getTextContent()) || !D.equals(sid.getNamespaceURI()))) {
                fault(ex, 12, "Invalid session");
                return;
            }
            aufrufe.add(name.equals("login") ? name : name + " " + sid.getTextContent());
            String antwort = switch (name) {
                case "login" -> {
                    if (falschesPasswort != null && !falschesPasswort.equals(text(op, "passwd"))) {
                        fault(ex, 20, "Login fehlgeschlagen");
                        yield null;
                    }
                    yield "<DOCUMENTS:loginResponse><session>4242</session></DOCUMENTS:loginResponse>";
                }
                case "getFileTypes" -> "<DOCUMENTS:getFileTypesResponse>"
                        + "<filetype><id>ft_1</id><name>Dokument</name></filetype>"
                        + "<filetype><id>ft_2</id><name>Beleg</name></filetype></DOCUMENTS:getFileTypesResponse>";
                case "describeFileType" -> "<DOCUMENTS:describeFileTypeResponse><description><name>" + text(op, "name")
                        + "</name><id>ft</id><fields><field><name>Betreff</name><id>f1</id><type>STRING</type></field></fields>"
                        + "<docregisters><docregister><name>Anlagen</name><id>reg_1</id></docregister>"
                        + "<docregister><name>Schriftverkehr</name><id>reg_2</id></docregister></docregisters>"
                        + "</description></DOCUMENTS:describeFileTypeResponse>";
                case "getWorkflowPattern" -> "<DOCUMENTS:getWorkflowPatternResponse><workflowPattern>"
                        + "<workflowPattern><idWorkflowPattern>wf_12</idWorkflowPattern><nameWorkflowPattern>Dokument</nameWorkflowPattern></workflowPattern>"
                        + "<workflowPattern><idWorkflowPattern>wf_13</idWorkflowPattern><nameWorkflowPattern>Outlook_ER</nameWorkflowPattern></workflowPattern>"
                        + "</workflowPattern></DOCUMENTS:getWorkflowPatternResponse>";
                case "createFile" -> {
                    createFiles.add(op);
                    yield "<DOCUMENTS:createFileResponse><fileId>dok_4711</fileId></DOCUMENTS:createFileResponse>";
                }
                case "startWorkflow" -> "<DOCUMENTS:startWorkflowResponse/>";
                case "logout" -> "<DOCUMENTS:logoutResponse/>";
                default -> throw new IllegalStateException(name);
            };
            if (antwort != null) {
                send(ex, 200, "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"" + S + "\" xmlns:DOCUMENTS=\"" + D + "\">"
                        + "<SOAP-ENV:Header><DOCUMENTS:sessionID>4242</DOCUMENTS:sessionID></SOAP-ENV:Header>"
                        + "<SOAP-ENV:Body>" + antwort + "</SOAP-ENV:Body></SOAP-ENV:Envelope>");
            }
        } catch (Exception e) {
            e.printStackTrace();
            fault(ex, 999, "Testserver: " + e);
        }
    }

    private static void fault(HttpExchange ex, int code, String info) throws IOException {
        send(ex, 500, "<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"" + S + "\" xmlns:DOCUMENTS=\"" + D + "\"><SOAP-ENV:Body>"
                + "<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>SOAP-ENV:Receiver</SOAP-ENV:Value></SOAP-ENV:Code>"
                + "<SOAP-ENV:Reason><SOAP-ENV:Text xml:lang=\"en\">DOCUMENTS error</SOAP-ENV:Text></SOAP-ENV:Reason>"
                + "<SOAP-ENV:Detail><DOCUMENTS:Fault><code>" + code + "</code><info>" + info + "</info></DOCUMENTS:Fault></SOAP-ENV:Detail>"
                + "</SOAP-ENV:Fault></SOAP-ENV:Body></SOAP-ENV:Envelope>");
    }

    private static void send(HttpExchange ex, int status, String xml) throws IOException {
        byte[] b = xml.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static Element first(Element p) {
        for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        return null;
    }

    private static Element child(Element p, String local) {
        for (Node n = p.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static String text(Element p, String local) {
        Element e = child(p, local);
        return e == null ? null : e.getTextContent();
    }
}
