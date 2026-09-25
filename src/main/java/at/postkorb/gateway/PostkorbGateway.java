package at.postkorb.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Fachliche Sicht auf die "Automatische Abholung" von Mein Postkorb
 * (SOAP-Funktionen QueryDeliveries, GetDelivery, CloseDelivery, DeleteDelivery).
 */
public interface PostkorbGateway {

    /** IDs der Zustellungen, die noch nicht über die Automatische Abholung abgeschlossen wurden. */
    List<String> neueZustellungen() throws IOException;

    /**
     * Ruft eine Zustellung samt Metadaten und Anhangsliste ab (GetDelivery).
     * Achtung: Das Abrufen gilt als Abholung – die Zustellung ist damit rechtlich bewirkt.
     */
    Zustellung abrufen(String id) throws IOException;

    /** Öffnet den Inhalt eines Anhangs (REST-GET über dieselbe mTLS-Verbindung). */
    InputStream oeffneAnhang(Zustellung zustellung, Anhang anhang) throws IOException;

    /**
     * Meldet dem Postkorb, dass die Zustellung vollständig übertragen und weiterverarbeitet wurde
     * (CloseDelivery). Danach erscheint sie nicht mehr unter {@link #neueZustellungen()}.
     */
    void abschliessen(String id) throws IOException;

    /** Löscht eine bereits abgeschlossene Zustellung im Postkorb (DeleteDelivery). */
    void loeschen(String id) throws IOException;
}
