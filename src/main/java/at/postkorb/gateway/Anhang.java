package at.postkorb.gateway;

import java.net.URI;

/**
 * Ein Dokument (Anhang) einer Zustellung. Laut USP wird jeder Anhang über einen
 * eigenen REST-GET-Aufruf geladen – {@code downloadUri} ist dessen Adresse.
 */
public record Anhang(String dateiname, String mimeType, URI downloadUri) {
}
