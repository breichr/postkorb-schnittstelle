package at.postkorb.zuseaa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import at.postkorb.PostkorbAbholer;
import at.postkorb.config.Config;
import at.postkorb.gateway.Zustellung;
import at.postkorb.store.DocumentStore;
import at.postkorb.store.ProcessedStore;

/**
 * Simuliert den SOAP-Endpunkt der Automatischen Abholung. Jede Anfrage wird gegen die
 * offizielle zuseaa_p2.xsd validiert.
 */
class ZuseAaSoapGatewayTest {

    private static final String AA = "http://reference.e-government.gv.at/namespace/zustellung/autoabholung/phase2/20181206#";
    private static final String MSG = "http://reference.e-government.gv.at/namespace/zustellung/msg/phase2/20181206#";
    private static final String P = "http://reference.e-government.gv.at/namespace/persondata/phase2/20181206#";
    private static Schema schema;

    /** Beispielantwort aus dem USP-How-To "Einrichtung der Automatischen Abholung" (Stand März 2025). */
    private static final String HOWTO_ID = "434c18a0-b142-11ea-87f1-eb357aa289dc";
    private static final String HOWTO_GET_DELIVERY_RESPONSE = """
            <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">
                <env:Header/>
                <env:Body>
                    <ns2:GetDeliveryResponse
                            xmlns:ns2="http://reference.e-government.gv.at/namespace/zustellung/autoabholung/phase2/20181206#"
                            xmlns:ns3="http://reference.e-government.gv.at/namespace/persondata/phase2/20181206#"
                            xmlns:ns4="http://reference.e-government.gv.at/namespace/zustellung/msg/phase2/20181206#">
                        <ns2:Delivery>
                            <ns2:DeliveryID>434c18a0-b142-11ea-87f1-eb357aa289dc</ns2:DeliveryID>
                            <ns2:Sender>
                                <ns3:Identification>
                                    <ns3:Value>9110002628957</ns3:Value>
                                    <ns3:Type>urn:publicid:gv.at:baseid+XGLN</ns3:Type>
                                </ns3:Identification>
                                <ns3:CorporateBody>
                                    <ns3:FullName>Bundeskanzleramt</ns3:FullName>
                                </ns3:CorporateBody>
                                <ns4:AdditionalCriteria/>
                            </ns2:Sender>
                            <ns2:Receiver>
                                <ns3:Identification>
                                    <ns3:Value>123456a</ns3:Value>
                                    <ns3:Type>urn:publicid:gv.at:baseid+XFN</ns3:Type>
                                </ns3:Identification>
                                <ns3:CorporateBody>
                                    <ns3:FullName>Testfirma GmbH</ns3:FullName>
                                </ns3:CorporateBody>
                                <ns4:AdditionalCriteria/>
                            </ns2:Receiver>
                            <ns2:MetaData>
                                <ns4:AppDeliveryID>appid-12345678990u9</ns4:AppDeliveryID>
                                <ns2:DeliveryService>Meinpostkorb</ns2:DeliveryService>
                                <ns4:ZSDeliveryID>6bd6d62e-b45e-11ea-a592-07a65621d367</ns4:ZSDeliveryID>
                                <ns4:Origin>
                                    <ns4:ParticipantID>AT:X:999-MPK</ns4:ParticipantID>
                                </ns4:Origin>
                                <ns4:DeliveryTimestamp>2020-05-26T08:40:00.000+02:00</ns4:DeliveryTimestamp>
                                <ns4:Subject>Test-Nachricht: 03</ns4:Subject>
                                <ns4:DeliveryQuality>nonRSa</ns4:DeliveryQuality>
                            </ns2:MetaData>
                            <ns2:AttachmentList>
                                <ns2:Attachment>
                                    <ns2:AttachmentID>bae5afd0-b141-11ea-8a48-3748f327b4a2</ns2:AttachmentID>
                                    <ns2:FileName>mailbody</ns2:FileName>
                                    <ns4:MimeType>text/plain</ns4:MimeType>
                                    <ns2:Size>21</ns2:Size>
                                    <ns4:CheckSum>
                                        <ns4:AlgorithmID>SHA512</ns4:AlgorithmID>
                                        <ns4:Value>AB/lMiXFPZevSMB3dbBFkF8hFwT2t94ooQtb0Jt0pO0H4vh+3jJb2Yszvepc4io3YX5483/MN7QAHbKJADc7oQ==</ns4:Value>
                                    </ns4:CheckSum>
                                </ns2:Attachment>
                                <ns2:Attachment>
                                    <ns2:AttachmentID>bae8b9aa-b141-11ea-a50d-475cdc4d30d2</ns2:AttachmentID>
                                    <ns2:FileName>Example.pdf</ns2:FileName>
                                    <ns4:MimeType>application/pdf</ns4:MimeType>
                                    <ns2:Size>13158</ns2:Size>
                                    <ns4:CheckSum>
                                        <ns4:AlgorithmID>SHA512</ns4:AlgorithmID>
                                        <ns4:Value>jFmjL5w+wqLvPw4K7Xd2jlMHIoCLGZmhYzPaRhxE+npFz8Xp/uvEsiWNJPnuhi3QfxeIpfbQiJntbfyh1/A3tw==</ns4:Value>
                                    </ns4:CheckSum>
                                </ns2:Attachment>
                            </ns2:AttachmentList>
                        </ns2:Delivery>
                    </ns2:GetDeliveryResponse>
                </env:Body>
            </env:Envelope>
            """;

    @TempDir
    Path tmp;
    private HttpServer server;
    private final Map<String, byte[]> dateien = new ConcurrentHashMap<>();
    private final List<String> offen = new ArrayList<>(List.of("d-1"));
    private final List<String> aufrufe = new ArrayList<>();
    private volatile boolean fehlerBeiClose;

    @BeforeAll
    static void loadSchema() throws Exception {
        URL xsd = ZuseAaSoapGatewayTest.class.getResource("/wsdl/zuseaa_p2.xsd");
        schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd);
    }

    @BeforeEach
    void start() throws IOException {
        dateien.put("att-1", "Sehr geehrte Damen und Herren ...".getBytes(StandardCharsets.UTF_8));
        dateien.put("att-2", "%PDF-1.7 Bescheid".getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/soap", this::soap);
        server.createContext("/attachment", ex -> {
            // Format laut How-To: /attachment?delivery_id=<uuid>&attachment_id=<uuid>, sonst HTTP 400
            String q = ex.getRequestURI().getQuery();
            byte[] data = q != null && q.startsWith("delivery_id=d-1&attachment_id=")
                    ? dateien.get(q.substring(q.indexOf("attachment_id=") + "attachment_id=".length())) : null;
            if (data == null) {
                ex.sendResponseHeaders(400, -1);
            } else {
                ex.sendResponseHeaders(200, data.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(data);
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

    private ZuseAaSoapGateway gateway() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        URI endpoint = URI.create(base + "/soap");
        return new ZuseAaSoapGateway(endpoint, Config.defaultAttachmentUrl(endpoint), 100, Duration.ofSeconds(5), null);
    }

    @Test
    void kompletterAblauf() throws IOException {
        PostkorbAbholer abholer = new PostkorbAbholer(gateway(), new DocumentStore(tmp.resolve("out")),
                new ProcessedStore(tmp.resolve("state.txt")), true);

        assertEquals(new PostkorbAbholer.Ergebnis(1, 0, 0), abholer.durchlauf());

        assertEquals(List.of("QueryDeliveriesRequest", "GetDeliveryRequest d-1", "CloseDeliveryRequest d-1",
                "DeleteDeliveryRequest d-1", "QueryDeliveriesRequest"), aufrufe);
        Path dir;
        try (Stream<Path> s = Files.list(tmp.resolve("out"))) {
            dir = s.filter(Files::isDirectory).filter(p -> p.getFileName().toString().endsWith("_d-1")).findFirst().orElseThrow();
        }
        assertEquals("2026-09-24_Finanzamt Österreich_d-1", dir.getFileName().toString().replaceFirst("^\\d{4}-\\d{2}-\\d{2}", "2026-09-24"));
        assertTrue(Files.exists(dir.resolve("mailbody.txt")));
        assertTrue(Files.exists(dir.resolve("Bescheid 2025.pdf")));
        String meta = Files.readString(dir.resolve("zustellung.txt"));
        assertTrue(meta.contains("Betreff: Einkommensteuerbescheid 2025"), meta);
        assertTrue(meta.contains("Geschäftszahl: GZ-123/2026"), meta);
        assertTrue(meta.contains("Zustellqualität: RSa"), meta);
        assertTrue(meta.contains("Empfänger: Max Mustermann"), meta);
        assertTrue(meta.contains("Prüfsumme ok"), meta);
    }

    @Test
    void abrufenBildetMetadatenAb() throws IOException {
        Zustellung z = gateway().abrufen("d-1");
        assertEquals("Finanzamt Österreich", z.absender());
        assertEquals(Instant.parse("2026-09-24T10:15:00Z"), z.eingang());
        assertEquals(2, z.anhaenge().size());
        assertEquals("SHA256", z.anhaenge().get(1).pruefsummenAlgorithmus());
        assertEquals(17L, z.anhaenge().get(1).groesse());
        assertTrue(z.anhaenge().get(1).downloadUri().toString().endsWith("/attachment?delivery_id=d-1&attachment_id=att-2"));
    }

    @Test
    void beispielAntwortAusDemHowTo() throws IOException {
        Zustellung z = gateway().abrufen(HOWTO_ID);
        assertEquals("Bundeskanzleramt", z.absender());
        assertEquals("Test-Nachricht: 03", z.betreff());
        assertEquals(Instant.parse("2020-05-26T06:40:00Z"), z.eingang());
        assertEquals("nonRSa", z.weitereAngaben().get("Zustellqualität"));
        assertEquals("Testfirma GmbH", z.weitereAngaben().get("Empfänger"));
        assertEquals(2, z.anhaenge().size());
        assertEquals("mailbody", z.anhaenge().get(0).dateiname());
        assertEquals("text/plain", z.anhaenge().get(0).mimeType());
        assertEquals(21L, z.anhaenge().get(0).groesse());
        assertEquals("SHA512", z.anhaenge().get(0).pruefsummenAlgorithmus());
        assertEquals("AB/lMiXFPZevSMB3dbBFkF8hFwT2t94ooQtb0Jt0pO0H4vh+3jJb2Yszvepc4io3YX5483/MN7QAHbKJADc7oQ==",
                z.anhaenge().get(0).pruefsumme());
        assertEquals("Example.pdf", z.anhaenge().get(1).dateiname());
        assertTrue(z.anhaenge().get(1).downloadUri().toString().endsWith(
                "/attachment?delivery_id=434c18a0-b142-11ea-87f1-eb357aa289dc&attachment_id=bae8b9aa-b141-11ea-a50d-475cdc4d30d2"));
    }

    @Test
    void attachmentUrlLautHowTo() {
        assertEquals("https://autoabholung.meinpostkorb.brz.gv.at/attachment?delivery_id={deliveryId}&attachment_id={attachmentId}",
                Config.defaultAttachmentUrl(Config.DEFAULT_SOAP_ENDPOINT));
        assertEquals("https://demo-autoabholung.meinpostkorb.brz.gv.at/attachment?delivery_id={deliveryId}&attachment_id={attachmentId}",
                Config.defaultAttachmentUrl(Config.DEMO_SOAP_ENDPOINT));
    }

    @Test
    void fehlerDesPostkorbsWirdGemeldet() {
        fehlerBeiClose = true;
        ZuseAaException e = assertThrows(ZuseAaException.class, () -> gateway().abschliessen("d-1"));
        assertEquals("000", e.errorCode());
        assertTrue(e.getMessage().contains("Unkown uuid."), e.getMessage());
    }

    @Test
    void soapFaultWirdGemeldet() {
        IOException e = assertThrows(IOException.class, () -> gateway().abrufen("kaputt"));
        assertTrue(e.getMessage().contains("SOAP-Fault"), e.getMessage());
        assertTrue(e.getMessage().contains("Unbekannte Zustellung"), e.getMessage());
    }

    @Test
    void attachmentUrlIstPflicht() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZuseAaSoapGateway(URI.create("https://x/soap"), null, 100, Duration.ofSeconds(1), null));
    }

    // ---- simulierter Server ----

    private void soap(HttpExchange ex) throws IOException {
        try {
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            if (ct == null || !ct.startsWith("application/soap+xml")) {
                throw new IllegalStateException("falscher Content-Type: " + ct);
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Element payload = payload(body);
            schema.newValidator().validate(new DOMSource(payload));
            if (!ZuseAaSoapGateway.SCHEMA_VERSION.equals(payload.getAttributeNS(AA, "Version"))) {
                throw new IllegalStateException("Version-Attribut fehlt");
            }
            String op = payload.getLocalName();
            String id = text(payload, "DeliveryID");
            aufrufe.add(id == null ? op : op + " " + id);
            String antwort = switch (op) {
                case "QueryDeliveriesRequest" -> {
                    StringBuilder sb = new StringBuilder("<aa:QueryDeliveriesResponse><aa:ResultsList><aa:HitCount>")
                            .append(offen.size()).append("</aa:HitCount>");
                    offen.forEach(d -> sb.append("<aa:DeliveryID>").append(d).append("</aa:DeliveryID>"));
                    yield sb.append("</aa:ResultsList></aa:QueryDeliveriesResponse>").toString();
                }
                case "GetDeliveryRequest" -> {
                    if (HOWTO_ID.equals(id)) {
                        schema.newValidator().validate(new DOMSource(payload(HOWTO_GET_DELIVERY_RESPONSE)));
                        send(ex, 200, HOWTO_GET_DELIVERY_RESPONSE);
                        yield null;
                    }
                    if (!"d-1".equals(id)) {
                        fault(ex, "Unbekannte Zustellung");
                        yield null;
                    }
                    yield delivery();
                }
                case "CloseDeliveryRequest" -> {
                    if (fehlerBeiClose) {
                        yield "<aa:CloseDeliveryResponse><aa:Error><aa:ErrorCode>000</aa:ErrorCode>"
                                + "<aa:ErrorMessage>Unkown uuid.</aa:ErrorMessage></aa:Error></aa:CloseDeliveryResponse>";
                    }
                    offen.remove(id);
                    yield "<aa:CloseDeliveryResponse><aa:Success>true</aa:Success></aa:CloseDeliveryResponse>";
                }
                case "DeleteDeliveryRequest" -> "<aa:DeleteDeliveryResponse><aa:Success>true</aa:Success></aa:DeleteDeliveryResponse>";
                default -> throw new IllegalStateException("unbekannte Operation " + op);
            };
            if (antwort != null) {
                send(ex, 200, envelope(antwort));
            }
        } catch (Exception e) {
            e.printStackTrace();
            fault(ex, "Testserver: " + e);
        }
    }

    private String delivery() throws Exception {
        byte[] pdf = dateien.get("att-2");
        String sha = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(pdf));
        String xml = "<aa:GetDeliveryResponse><aa:Delivery>"
                + "<aa:DeliveryID>d-1</aa:DeliveryID>"
                + "<aa:Sender><p:CorporateBody><p:FullName>Finanzamt Österreich</p:FullName></p:CorporateBody></aa:Sender>"
                + "<aa:Receiver><p:PhysicalPerson><p:Name><p:GivenName>Max</p:GivenName><p:FamilyName>Mustermann</p:FamilyName></p:Name></p:PhysicalPerson></aa:Receiver>"
                + "<aa:MetaData><msg:AppDeliveryID>app-1</msg:AppDeliveryID><aa:DeliveryService>ZS-BRZ</aa:DeliveryService>"
                + "<msg:ZSDeliveryID>zs-1</msg:ZSDeliveryID><msg:DeliveryTimestamp>2026-09-24T12:15:00+02:00</msg:DeliveryTimestamp>"
                + "<msg:Subject>Einkommensteuerbescheid 2025</msg:Subject><msg:GZ>GZ-123/2026</msg:GZ>"
                + "<msg:DeliveryQuality>RSa</msg:DeliveryQuality></aa:MetaData>"
                + "<aa:AttachmentList>"
                + "<aa:Attachment><aa:AttachmentID>att-1</aa:AttachmentID><aa:FileName>mailbody</aa:FileName>"
                + "<msg:MimeType>text/plain</msg:MimeType><aa:Size>" + dateien.get("att-1").length + "</aa:Size>"
                + "<msg:CheckSum><msg:AlgorithmID>SHA256</msg:AlgorithmID><msg:Value>"
                + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(dateien.get("att-1")))
                + "</msg:Value></msg:CheckSum></aa:Attachment>"
                + "<aa:Attachment><aa:AttachmentID>att-2</aa:AttachmentID><aa:FileName>Bescheid 2025.pdf</aa:FileName>"
                + "<msg:MimeType>application/pdf</msg:MimeType><aa:Size>" + pdf.length + "</aa:Size>"
                + "<msg:CheckSum><msg:AlgorithmID>SHA256</msg:AlgorithmID><msg:Value>" + sha + "</msg:Value></msg:CheckSum></aa:Attachment>"
                + "</aa:AttachmentList></aa:Delivery></aa:GetDeliveryResponse>";
        // auch die Antwort muss schema-konform sein, sonst testen wir gegen Phantasie-XML
        schema.newValidator().validate(new DOMSource(payload(envelope(xml))));
        return xml;
    }

    private static String envelope(String payload) {
        return "<soap:Envelope xmlns:soap=\"" + ZuseAaSoapGateway.SOAP12_NS + "\" xmlns:aa=\"" + AA + "\" xmlns:msg=\"" + MSG
                + "\" xmlns:p=\"" + P + "\"><soap:Body>" + payload + "</soap:Body></soap:Envelope>";
    }

    private static void fault(HttpExchange ex, String reason) throws IOException {
        send(ex, 500, "<soap:Envelope xmlns:soap=\"" + ZuseAaSoapGateway.SOAP12_NS + "\"><soap:Body><soap:Fault>"
                + "<soap:Code><soap:Value>soap:Receiver</soap:Value></soap:Code>"
                + "<soap:Reason><soap:Text xml:lang=\"de\">" + reason + "</soap:Text></soap:Reason>"
                + "</soap:Fault></soap:Body></soap:Envelope>");
    }

    private static void send(HttpExchange ex, int status, String xml) throws IOException {
        byte[] b = xml.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/soap+xml; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static Element payload(String envelope) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(envelope)));
        Element body = (Element) doc.getDocumentElement().getElementsByTagNameNS(ZuseAaSoapGateway.SOAP12_NS, "Body").item(0);
        for (Node n = body.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        throw new IllegalStateException("leerer Body");
    }

    private static String text(Element e, String local) {
        var nl = e.getElementsByTagNameNS(AA, local);
        return nl.getLength() == 0 ? null : nl.item(0).getTextContent();
    }
}
