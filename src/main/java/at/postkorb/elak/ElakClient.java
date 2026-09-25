package at.postkorb.elak;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Minimaler Client für die SOAP-Schnittstelle von otris DOCUMENTS ("DOCUMENTS-4.0.wsdl"),
 * so wie sie auch das Outlook-Add-In von gemdat verwendet.
 *
 * <p>SOAP 1.2, document/literal; die Kindelemente sind nicht namespace-qualifiziert.
 * Nach {@code login} wird die Session-ID in jedem Aufruf als SOAP-Header
 * {@code <DOCUMENTS:sessionID>} mitgeschickt.
 */
public final class ElakClient implements AutoCloseable {

    static final String SOAP12 = "http://www.w3.org/2003/05/soap-envelope";
    static final String NS = "http://xml.otris.de/ws/DOCUMENTS.xsd";
    private static final String ACTION = "http://xml.otris.de/ws/DOCUMENTS.wsdl#";
    private static final Logger LOG = Logger.getLogger(ElakClient.class.getName());

    public record Eintrag(String id, String name) {
    }

    public record MappentypInfo(String id, String name, List<Eintrag> register, List<String> felder) {
    }

    /** Ein Dokument für eine neue Mappe. */
    public record Datei(String name, String register, byte[] inhalt) {
    }

    private final URI endpoint;
    private final HttpClient http;
    private final Duration timeout;
    private String session;

    public ElakClient(URI endpoint, Duration timeout) {
        this.endpoint = endpoint;
        this.timeout = timeout;
        // gSOAP-Server von DOCUMENTS sprechen nur HTTP/1.1
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
    }

    public void login(String benutzer, String mandant, char[] passwort) throws IOException {
        Element antwort = call("login", null, felder(
                "user", benutzer,
                "principal", mandant,
                "passwd", new String(passwort),
                "code", "",
                "locale", "de"));
        String s = text(antwort, "session");
        if (session == null && s != null && !s.isBlank()) {
            session = s.strip(); // kein Session-Header in der Antwort – ID aus dem Body nehmen
        }
        if (session == null) {
            throw new IOException("login: keine Session-ID erhalten");
        }
        LOG.fine("ELAK-Anmeldung erfolgreich");
    }

    public boolean sessionGueltig() throws IOException {
        return "true".equalsIgnoreCase(text(call("testSession", session, Map.of()), "valid"));
    }

    public List<Eintrag> mappentypen() throws IOException {
        Element r = call("getFileTypes", session, felder("ignoreRight", "false"));
        return eintraege(r, "filetype");
    }

    public MappentypInfo beschreibe(String mappentyp) throws IOException {
        Element r = call("describeFileType", session, felder("name", mappentyp, "categories", ""));
        Element d = child(r, "description");
        if (d == null) {
            throw new IOException("describeFileType: keine Beschreibung für " + mappentyp);
        }
        List<Eintrag> register = new ArrayList<>();
        Element regs = child(d, "docregisters");
        if (regs != null) {
            register.addAll(eintraege(regs, "docregister"));
        }
        List<String> felder = new ArrayList<>();
        Element f = child(d, "fields");
        if (f != null) {
            for (Element e : children(f)) {
                felder.add(text(e, "name"));
            }
        }
        return new MappentypInfo(text(d, "id"), text(d, "name"), register, felder);
    }

    public List<Eintrag> workflows() throws IOException {
        Element r = call("getWorkflowPattern", session, Map.of());
        Element list = child(r, "workflowPattern");
        List<Eintrag> out = new ArrayList<>();
        if (list != null) {
            for (Element p : children(list)) {
                out.add(new Eintrag(text(p, "idWorkflowPattern"), text(p, "nameWorkflowPattern")));
            }
        }
        return out;
    }

    /** Legt eine Mappe an und liefert deren ID. */
    public String mappeAnlegen(String mappentyp, Map<String, String> feldwerte, List<Datei> dateien) throws IOException {
        Element r = call("createFile", session, body -> {
            add(body, "fileType", mappentyp);
            Element fields = add(body, "fields", null);
            feldwerte.forEach((k, v) -> {
                Element f = add(fields, "field", null);
                add(f, "name", k);
                add(f, "value", v);
            });
            Element docs = add(body, "addDocs", null);
            for (Datei d : dateien) {
                Element doc = add(docs, "document", null);
                add(doc, "name", d.name());
                add(doc, "register", d.register());
                add(doc, "data", Base64.getEncoder().encodeToString(d.inhalt()));
            }
        });
        String id = text(r, "fileId");
        if (id == null || id.isBlank()) {
            throw new IOException("createFile: keine Mappen-ID erhalten");
        }
        return id.strip();
    }

    public void workflowStarten(String mappenId, String workflowId) throws IOException {
        call("startWorkflow", session, felder("fileId", mappenId, "idWorkflowPattern", workflowId));
    }

    @Override
    public void close() {
        if (session != null) {
            try {
                call("logout", session, Map.of());
            } catch (IOException e) {
                LOG.fine(() -> "ELAK-Abmeldung fehlgeschlagen: " + e.getMessage());
            }
            session = null;
        }
    }

    // ---- SOAP ----

    @FunctionalInterface
    private interface BodyWriter {
        void write(Element operation);
    }

    private static Map<String, String> felder(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private Element call(String operation, String sessionId, Map<String, String> params) throws IOException {
        return call(operation, sessionId, body -> params.forEach((k, v) -> add(body, k, v)));
    }

    private Element call(String operation, String sessionId, BodyWriter writer) throws IOException {
        if (session == null && !operation.equals("login")) {
            throw new IllegalStateException("Nicht am ELAK angemeldet");
        }
        String xml = envelope(operation, operation.equals("login") ? null : session, writer);
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/soap+xml; charset=utf-8; action=\"" + ACTION + operation + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(operation + " unterbrochen", e);
        }
        debug(operation, xml, resp.body());
        Element payload = antwort(operation, resp.statusCode(), resp.body());
        // DOCUMENTS liefert die (ggf. neue) Session-ID in jeder Antwort im Header – für den nächsten Aufruf übernehmen
        String neu = sessionAusHeader(payload);
        if (neu != null && !neu.isBlank()) {
            session = neu.strip();
        }
        return payload;
    }

    static String sessionAusHeader(Element payload) {
        Element env = payload.getOwnerDocument().getDocumentElement();
        Element header = child(env, "Header");
        Element sid = header == null ? null : child(header, "sessionID");
        return sid == null ? null : sid.getTextContent();
    }

    /** Mit Umgebungsvariable POSTKORB_ELAK_DEBUG=1: SOAP-Verkehr (ohne Passwort/Session/Dateiinhalt) in elak-debug.log. */
    private static void debug(String operation, String request, byte[] response) {
        if (!"1".equals(System.getenv("POSTKORB_ELAK_DEBUG"))) {
            return;
        }
        try {
            String log = "===== " + java.time.LocalDateTime.now() + " " + operation + "\n--> " + maskiere(request)
                    + "\n<-- " + maskiere(new String(response, StandardCharsets.UTF_8)) + "\n";
            java.nio.file.Files.writeString(java.nio.file.Path.of("elak-debug.log"), log, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // nur Diagnose
        }
    }

    static String maskiere(String xml) {
        return xml.replaceAll("(<passwd>)[^<]*(</passwd>)", "$1***$2")
                .replaceAll("(<(?:\\w+:)?sessionID>)[^<]*(</)", "$1***$2")
                .replaceAll("(<session>)[^<]*(</session>)", "$1***$2")
                .replaceAll("(<data>)[^<]{0,1000000}(</data>)", "$1…$2");
    }

    static String envelope(String operation, String sessionId, BodyWriter writer) throws IOException {
        try {
            Document doc = newBuilder().newDocument();
            Element env = doc.createElementNS(SOAP12, "SOAP-ENV:Envelope");
            env.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:DOCUMENTS", NS);
            doc.appendChild(env);
            Element header = doc.createElementNS(SOAP12, "SOAP-ENV:Header");
            env.appendChild(header);
            if (sessionId != null) {
                Element sid = doc.createElementNS(NS, "DOCUMENTS:sessionID");
                sid.setTextContent(sessionId);
                header.appendChild(sid);
            }
            Element body = doc.createElementNS(SOAP12, "SOAP-ENV:Body");
            env.appendChild(body);
            Element op = doc.createElementNS(NS, "DOCUMENTS:" + operation);
            body.appendChild(op);
            writer.write(op);
            var t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(w));
            return w.toString();
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IOException("SOAP-Anfrage " + operation + " konnte nicht erzeugt werden", e);
        }
    }

    /** Kindelemente sind laut WSDL nicht qualifiziert (elementFormDefault="unqualified"). */
    private static Element add(Element parent, String name, String text) {
        Element e = parent.getOwnerDocument().createElementNS(null, name);
        if (text != null) {
            e.setTextContent(text);
        }
        parent.appendChild(e);
        return e;
    }

    static Element antwort(String operation, int status, byte[] content) throws IOException {
        Document doc;
        try {
            doc = newBuilder().parse(new ByteArrayInputStream(content));
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException(operation + ": keine gültige SOAP-Antwort (HTTP " + status + "): "
                    + new String(content, 0, Math.min(content.length, 300), StandardCharsets.UTF_8), e);
        }
        Element body = null;
        for (Element e : children(doc.getDocumentElement())) {
            if ("Body".equals(e.getLocalName())) {
                body = e;
            }
        }
        if (body == null) {
            throw new IOException(operation + ": SOAP-Antwort ohne Body (HTTP " + status + ")");
        }
        List<Element> inhalt = children(body);
        if (inhalt.isEmpty()) {
            throw new IOException(operation + ": leerer SOAP-Body (HTTP " + status + ")");
        }
        Element payload = inhalt.get(0);
        if ("Fault".equals(payload.getLocalName())) {
            Integer code = null;
            String info = null;
            // DOCUMENTS legt <code> und <info> in das Detail-Element des Faults
            Element c = findUnqualified(payload, "code");
            Element i = findUnqualified(payload, "info");
            if (c != null) {
                try {
                    code = Integer.valueOf(c.getTextContent().strip());
                } catch (NumberFormatException ignored) {
                    // SOAP-Code statt DOCUMENTS-Code
                }
            }
            if (i != null) {
                info = i.getTextContent().strip();
            }
            if (info == null) {
                Element reason = find(payload, "Reason");
                info = reason != null ? reason.getTextContent().strip() : payload.getTextContent().strip();
            }
            throw new ElakException(operation, code, info);
        }
        if (status != 200) {
            throw new IOException(operation + ": HTTP " + status);
        }
        return payload;
    }

    // ---- DOM-Hilfen ----

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }

    private static Element child(Element parent, String local) {
        for (Element e : children(parent)) {
            if (local.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static String text(Element parent, String local) {
        Element e = child(parent, local);
        return e == null ? null : e.getTextContent();
    }

    private static List<Eintrag> eintraege(Element parent, String local) {
        List<Eintrag> out = new ArrayList<>();
        for (Element e : children(parent)) {
            if (local.equals(e.getLocalName())) {
                out.add(new Eintrag(text(e, "id"), text(e, "name")));
            }
        }
        return out;
    }

    private static Element find(Element root, String local) {
        if (local.equals(root.getLocalName())) {
            return root;
        }
        for (Element c : children(root)) {
            Element f = find(c, local);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** Sucht ein nicht-SOAP-Element (DOCUMENTS-Fault-Details), nicht den SOAP-Code. */
    private static Element findUnqualified(Element root, String local) {
        for (Element c : children(root)) {
            if (local.equals(c.getLocalName()) && !SOAP12.equals(c.getNamespaceURI())) {
                return c;
            }
            Element f = findUnqualified(c, local);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    private static DocumentBuilder newBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }
}
