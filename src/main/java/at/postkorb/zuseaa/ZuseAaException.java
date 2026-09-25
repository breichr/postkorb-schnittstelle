package at.postkorb.zuseaa;

import java.io.IOException;

/** Fehlermeldung des Postkorbs (Element {@code aa:Error} in der Antwort). */
public final class ZuseAaException extends IOException {

    private final String errorCode;

    public ZuseAaException(String operation, String errorCode, String errorMessage) {
        super(operation + ": Fehler " + errorCode + " – " + errorMessage);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
