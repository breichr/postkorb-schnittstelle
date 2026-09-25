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

    /**
     * Liefert die Zustellungen im Postkorb samt Metadaten und Anhängen.
     *
     * <p>Achtung: Das Abrufen einer Nachricht (SOAP-Funktion GetDelivery) gilt als Abholung –
     * die Nachricht ist danach gelesen (geschlossen), und die Zustellung ist rechtlich bewirkt.
     */
    List<Zustellung> abholbereit() throws IOException;

    /** Öffnet den Inhalt eines Anhangs (REST-GET über dieselbe mTLS-Verbindung). */
    InputStream oeffneAnhang(Zustellung zustellung, Anhang anhang) throws IOException;

    /**
     * Löscht die Zustellung im Postkorb (SOAP-Funktion DeleteDelivery). Das geht laut USP nur
     * für bereits gelesene (= geschlossene) Nachrichten. Wird nur aufgerufen, wenn
     * {@code delete.after.download=true} gesetzt ist und alle Anhänge sicher gespeichert sind.
     */
    void loescheZustellung(Zustellung zustellung) throws IOException;
}
