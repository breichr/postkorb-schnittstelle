package at.postkorb.gateway;

import java.net.URI;

/**
 * Ein Dokument (Anhang) einer Zustellung. Laut USP wird jeder Anhang über einen
 * eigenen REST-GET-Aufruf geladen – {@code downloadUri} ist dessen Adresse.
 *
 * @param pruefsumme             erwartete Prüfsumme (hex oder Base64) laut ZUSEMSG 4.4, {@code null} = keine Prüfung
 * @param pruefsummenAlgorithmus z. B. {@code SHA-256} oder eine XML-DSig-URI wie
 *                               {@code http://www.w3.org/2001/04/xmlenc#sha256}
 */
public record Anhang(String dateiname, String mimeType, URI downloadUri, String pruefsumme, String pruefsummenAlgorithmus) {

    public Anhang(String dateiname, String mimeType, URI downloadUri) {
        this(dateiname, mimeType, downloadUri, null, null);
    }
}
