package at.postkorb.gateway;

import java.time.Instant;
import java.util.List;

/** Eine Nachricht (Zustellung) im Postkorb mit ihren Anhängen. */
public record Zustellung(
        String id,
        String absender,
        String betreff,
        Instant eingang,
        List<Anhang> anhaenge) {
}
