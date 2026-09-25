package at.postkorb.gateway;

import java.net.URI;

/**
 * Ein Dokument (Anhang) einer Zustellung. Laut USP wird jeder Anhang über einen
 * eigenen REST-GET-Aufruf geladen – {@code downloadUri} ist dessen Adresse.
 *
 * @param groesse                erwartete Größe in Bytes, {@code null} = keine Prüfung
 * @param pruefsumme             erwartete Prüfsumme (hex oder Base64), {@code null} = keine Prüfung
 * @param pruefsummenAlgorithmus z. B. {@code SHA256}, {@code SHA-512} oder eine XML-DSig-URI
 */
public record Anhang(String dateiname, String mimeType, URI downloadUri, Long groesse,
        String pruefsumme, String pruefsummenAlgorithmus) {

    public Anhang(String dateiname, String mimeType, URI downloadUri) {
        this(dateiname, mimeType, downloadUri, null, null, null);
    }
}
