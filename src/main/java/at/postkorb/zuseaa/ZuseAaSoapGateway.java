package at.postkorb.zuseaa;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
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

import at.gv.e_government.reference.namespace.persondata.phase2._20181206_.AbstractPersonType;
import at.gv.e_government.reference.namespace.persondata.phase2._20181206_.AuthorityType;
import at.gv.e_government.reference.namespace.persondata.phase2._20181206_.CorporateBodyType;
import at.gv.e_government.reference.namespace.persondata.phase2._20181206_.PersonNameType;
import at.gv.e_government.reference.namespace.persondata.phase2._20181206_.PhysicalPersonType;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.AttachmentType;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.ContactType;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.DeliveryType;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.ErrorType;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.GetDeliveryResponseType;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.MetaData;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.NewDeliveriesOnlyType;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.ObjectFactory;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.QueryDeliveriesRequestType;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.QueryDeliveriesResponseType;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.SimpleRequestType;
import at.gv.e_government.reference.namespace.zustellung.autoabholung.phase2._20181206_.SimpleResponseType;
import at.gv.e_government.reference.namespace.zustellung.msg.phase2._20181206_.CheckSumType;
import at.postkorb.config.Config;
import at.postkorb.download.HttpAttachmentDownloader;
import at.postkorb.gateway.Anhang;
import at.postkorb.gateway.PostkorbGateway;
import at.postkorb.gateway.Zustellung;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;

/**
 * Client für die "Automatische Abholung" von Mein Postkorb (zuseaa_p2.wsdl, SOAP 1.2, document/literal).
 *
 * <p>Die SOAP-Aufrufe werden direkt über {@link HttpClient} mit dem mTLS-{@link SSLContext} geschickt;
 * die Nutzdaten werden mit den aus der WSDL generierten JAXB-Klassen (de)serialisiert.
 */
public final class ZuseAaSoapGateway implements PostkorbGateway {

    /** Muss laut zuseaa_p2.xsd mit deren Versionsnummer übereinstimmen. */
    public static final String SCHEMA_VERSION = "2.4.0-004";
    static final String SOAP12_NS = "http://www.w3.org/2003/05/soap-envelope";

    private static final Logger LOG = Logger.getLogger(ZuseAaSoapGateway.class.getName());
    private static final ObjectFactory AA = new ObjectFactory();

    private final URI endpoint;
    private final String attachmentUrlTemplate;
    private final int limit;
    private final Duration timeout;
    private final HttpClient http;
    private final HttpAttachmentDownloader downloader;
    private final JAXBContext jaxb;

    public ZuseAaSoapGateway(Config cfg, SSLContext ssl) {
        this(cfg.soapEndpoint(), cfg.attachmentUrlTemplate(), cfg.queryLimit(), cfg.httpTimeout(), ssl);
    }

    public ZuseAaSoapGateway(URI endpoint, String attachmentUrlTemplate, int limit, Duration timeout, SSLContext ssl) {
        if (attachmentUrlTemplate == null || !attachmentUrlTemplate.contains("{attachmentId}")) {
            throw new IllegalArgumentException("attachment.url muss den Platzhalter {attachmentId} enthalten, z. B. "
                    + Config.defaultAttachmentUrl(endpoint));
        }
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("query.limit muss zwischen 1 und 1000 liegen");
        }
        this.endpoint = endpoint;
        this.attachmentUrlTemplate = attachmentUrlTemplate;
        this.limit = limit;
        this.timeout = timeout;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(timeout);
        if (ssl != null) {
            b.sslContext(ssl);
        }
        this.http = b.build();
        this.downloader = new HttpAttachmentDownloader(ssl, timeout);
        try {
            this.jaxb = JAXBContext.newInstance(ObjectFactory.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("JAXB-Kontext konnte nicht erstellt werden", e);
        }
    }

    @Override
    public List<String> neueZustellungen() throws IOException {
        NewDeliveriesOnlyType query = AA.createNewDeliveriesOnlyType();
        query.setLimit(limit);
        QueryDeliveriesRequestType req = AA.createQueryDeliveriesRequestType();
        req.setVersion(SCHEMA_VERSION);
        req.setQuery(AA.createNewDeliveriesOnly(query));

        QueryDeliveriesResponseType resp = call(AA.createQueryDeliveriesRequest(req), QueryDeliveriesResponseType.class);
        checkError("QueryDeliveries", resp.getError());
        if (resp.getResultsList() == null) {
            throw new IOException("QueryDeliveries: Antwort ohne ResultsList");
        }
        BigInteger hits = resp.getResultsList().getHitCount();
        List<String> ids = List.copyOf(resp.getResultsList().getDeliveryID());
        LOG.fine(() -> "QueryDeliveries: HitCount=" + hits + ", geliefert=" + ids.size());
        return ids;
    }

    @Override
    public Zustellung abrufen(String id) throws IOException {
        GetDeliveryResponseType resp = call(AA.createGetDeliveryRequest(simple(id)), GetDeliveryResponseType.class);
        checkError("GetDelivery " + id, resp.getError());
        DeliveryType d = resp.getDelivery();
        if (d == null) {
            throw new IOException("GetDelivery " + id + ": Antwort ohne Delivery");
        }
        return toZustellung(d);
    }

    @Override
    public InputStream oeffneAnhang(Zustellung zustellung, Anhang anhang) throws IOException {
        return downloader.open(anhang.downloadUri());
    }

    @Override
    public void abschliessen(String id) throws IOException {
        SimpleResponseType resp = call(AA.createCloseDeliveryRequest(simple(id)), SimpleResponseType.class);
        checkSuccess("CloseDelivery " + id, resp);
    }

    @Override
    public void loeschen(String id) throws IOException {
        SimpleResponseType resp = call(AA.createDeleteDeliveryRequest(simple(id)), SimpleResponseType.class);
        checkSuccess("DeleteDelivery " + id, resp);
    }

    // ---- Abbildung der ZUSE-Typen auf das eigene Modell ----

    Zustellung toZustellung(DeliveryType d) {
        MetaData m = d.getMetaData();
        Map<String, String> extra = new LinkedHashMap<>();
        if (m != null) {
            put(extra, "Geschäftszahl", m.getGZ());
            put(extra, "Zustellqualität", m.getDeliveryQuality() != null ? m.getDeliveryQuality() : m.getPrivateMessageQuality());
            put(extra, "Absender-Zustellungs-ID", m.getAppDeliveryID());
            put(extra, "Zustellsystem", m.getDeliveryService());
            put(extra, "Zustellsystem-ID", m.getZSDeliveryID());
        }
        put(extra, "Empfänger", name(d.getReceiver()));

        List<Anhang> anhaenge = new ArrayList<>();
        if (d.getAttachmentList() != null) {
            for (AttachmentType a : d.getAttachmentList().getAttachment()) {
                CheckSumType cs = a.getCheckSum();
                anhaenge.add(new Anhang(
                        a.getFileName(),
                        a.getMimeType(),
                        attachmentUri(d.getDeliveryID(), a.getAttachmentID()),
                        a.getSize() != null ? a.getSize().longValueExact() : null,
                        cs != null && cs.getValue() != null ? Base64.getEncoder().encodeToString(cs.getValue()) : null,
                        cs != null ? cs.getAlgorithmID() : null));
            }
        }
        Instant eingang = m != null && m.getDeliveryTimestamp() != null
                ? m.getDeliveryTimestamp().toGregorianCalendar().toInstant() : null;
        return new Zustellung(d.getDeliveryID(), name(d.getSender()), m != null ? m.getSubject() : null,
                eingang, anhaenge, extra);
    }

    URI attachmentUri(String deliveryId, String attachmentId) {
        return URI.create(attachmentUrlTemplate
                .replace("{deliveryId}", URLEncoder.encode(deliveryId, StandardCharsets.UTF_8))
                .replace("{attachmentId}", URLEncoder.encode(attachmentId, StandardCharsets.UTF_8)));
    }

    static String name(ContactType c) {
        if (c == null || c.getPerson() == null) {
            return null;
        }
        AbstractPersonType p = c.getPerson().getValue();
        if (p instanceof CorporateBodyType cb) {
            return cb.getFullName();
        }
        if (p instanceof AuthorityType au) {
            return au.getFullName();
        }
        if (p instanceof PhysicalPersonType pp && pp.getName() != null) {
            PersonNameType n = pp.getName();
            String family = n.getFamilyName() != null ? n.getFamilyName().getValue() : "";
            return ((n.getGivenName() != null ? n.getGivenName() + " " : "") + family).strip();
        }
        return null;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }

    private static SimpleRequestType simple(String id) {
        SimpleRequestType r = AA.createSimpleRequestType();
        r.setDeliveryID(id);
        r.setVersion(SCHEMA_VERSION);
        return r;
    }

    private static void checkError(String op, ErrorType e) throws IOException {
        if (e != null) {
            throw new ZuseAaException(op, e.getErrorCode(), e.getErrorMessage());
        }
    }

    private static void checkSuccess(String op, SimpleResponseType r) throws IOException {
        checkError(op, r.getError());
        if (!Boolean.TRUE.equals(r.isSuccess())) {
            throw new IOException(op + ": Postkorb meldet keinen Erfolg");
        }
    }

    // ---- SOAP 1.2 ----

    private <T> T call(JAXBElement<?> request, Class<T> responseType) throws IOException {
        String body = envelope(request);
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/soap+xml; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SOAP-Aufruf unterbrochen", e);
        }
        Element payload = parseBody(resp.statusCode(), resp.body(), request.getName().getLocalPart());
        try {
            return jaxb.createUnmarshaller().unmarshal(payload, responseType).getValue();
        } catch (JAXBException e) {
            throw new IOException("SOAP-Antwort konnte nicht gelesen werden (" + request.getName().getLocalPart() + ")", e);
        }
    }

    private String envelope(JAXBElement<?> payload) throws IOException {
        try {
            Document doc = newDocumentBuilder().newDocument();
            Element env = doc.createElementNS(SOAP12_NS, "soap:Envelope");
            doc.appendChild(env);
            env.appendChild(doc.createElementNS(SOAP12_NS, "soap:Header"));
            Element body = doc.createElementNS(SOAP12_NS, "soap:Body");
            env.appendChild(body);
            jaxb.createMarshaller().marshal(payload, body);

            var t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(w));
            return w.toString();
        } catch (JAXBException | TransformerException | ParserConfigurationException e) {
            throw new IOException("SOAP-Anfrage konnte nicht erzeugt werden", e);
        }
    }

    static Element parseBody(int status, byte[] content, String operation) throws IOException {
        Document doc;
        try {
            doc = newDocumentBuilder().parse(new ByteArrayInputStream(content));
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException(operation + ": keine gültige SOAP-Antwort (HTTP " + status + "): "
                    + new String(content, 0, Math.min(content.length, 500), StandardCharsets.UTF_8), e);
        }
        Element body = firstChild(doc.getDocumentElement(), SOAP12_NS, "Body");
        if (body == null) {
            throw new IOException(operation + ": SOAP-Antwort ohne Body (HTTP " + status + ")");
        }
        Element payload = firstElement(body);
        if (payload == null) {
            throw new IOException(operation + ": leerer SOAP-Body (HTTP " + status + ")");
        }
        if (SOAP12_NS.equals(payload.getNamespaceURI()) && "Fault".equals(payload.getLocalName())) {
            throw new IOException(operation + ": SOAP-Fault (HTTP " + status + "): " + faultText(payload));
        }
        if (status != 200) {
            throw new IOException(operation + ": HTTP " + status);
        }
        return payload;
    }

    private static String faultText(Element fault) {
        StringBuilder sb = new StringBuilder();
        Element code = firstChild(fault, SOAP12_NS, "Code");
        Element reason = firstChild(fault, SOAP12_NS, "Reason");
        if (code != null) {
            sb.append(code.getTextContent().strip()).append(" – ");
        }
        sb.append(reason != null ? reason.getTextContent().strip() : fault.getTextContent().strip());
        return sb.toString();
    }

    private static Element firstChild(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static Element firstElement(Element parent) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) {
                return e;
            }
        }
        return null;
    }

    private static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }
}
