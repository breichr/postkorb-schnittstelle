package at.postkorb.gateway;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Eine Nachricht (Zustellung) im Postkorb mit ihren Anhängen.
 *
 * @param weitereAngaben zusätzliche Metadaten (z. B. Geschäftszahl, Zustellqualität) für {@code zustellung.txt}
 */
public record Zustellung(
        String id,
        String absender,
        String betreff,
        Instant eingang,
        List<Anhang> anhaenge,
        Map<String, String> weitereAngaben) {

    public Zustellung(String id, String absender, String betreff, Instant eingang, List<Anhang> anhaenge) {
        this(id, absender, betreff, eingang, anhaenge, Map.of());
    }
}
