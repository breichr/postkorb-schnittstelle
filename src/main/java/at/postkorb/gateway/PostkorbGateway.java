package at.postkorb.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Fachliche Sicht auf die "Automatische Abholung" von Mein Postkorb.
 *
 * <p>Die konkrete SOAP-Implementierung wird gegen die WSDL {@code zuseaa_p2.wsdl}
 * generiert (siehe README). Damit bleibt der Rest der Anwendung unabhängig vom
 * generierten Code.
 */
public interface PostkorbGateway {

    /** Liefert alle Zustellungen, die zur Abholung bereitliegen. */
    List<Zustellung> abholbereit() throws IOException;

    /** Öffnet den Inhalt eines Anhangs (REST-GET über dieselbe mTLS-Verbindung). */
    InputStream oeffneAnhang(Zustellung zustellung, Anhang anhang) throws IOException;

    /**
     * Bestätigt die erfolgreiche Abholung gegenüber dem Postkorb.
     * Wird erst aufgerufen, nachdem alle Anhänge sicher gespeichert sind.
     */
    void bestaetigeAbholung(Zustellung zustellung) throws IOException;
}
