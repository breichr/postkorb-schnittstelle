package at.postkorb.elak;

import java.io.IOException;

/** Fehlermeldung des ELAK (otris DOCUMENTS, SOAP-Fault mit Code und Text). */
public final class ElakException extends IOException {

    private final Integer code;

    public ElakException(String operation, Integer code, String text) {
        super(operation + ": " + (code != null ? "Fehler " + code + " – " : "") + text);
        this.code = code;
    }

    public Integer code() {
        return code;
    }
}
