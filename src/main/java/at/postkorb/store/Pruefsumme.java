package at.postkorb.store;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** Prüft Anhänge gegen die vom Postkorb gelieferte Prüfsumme. */
public final class Pruefsumme {

    private static final Map<String, String> XML_DSIG = Map.of(
            "http://www.w3.org/2000/09/xmldsig#sha1", "SHA-1",
            "http://www.w3.org/2001/04/xmldsig-more#sha224", "SHA-224",
            "http://www.w3.org/2001/04/xmlenc#sha256", "SHA-256",
            "http://www.w3.org/2001/04/xmldsig-more#sha384", "SHA-384",
            "http://www.w3.org/2001/04/xmlenc#sha512", "SHA-512");

    private Pruefsumme() {
    }

    /** Liefert den MessageDigest zum Algorithmusnamen, z. B. "SHA-256", "sha256" oder eine XML-DSig-URI. */
    public static MessageDigest digest(String algorithmus) {
        String a = algorithmus == null || algorithmus.isBlank() ? "SHA-256" : algorithmus.strip();
        a = XML_DSIG.getOrDefault(a, a).toUpperCase(Locale.ROOT);
        if (a.matches("SHA\\d+")) {
            a = "SHA-" + a.substring(3);
        }
        try {
            return MessageDigest.getInstance(a);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unbekannter Prüfsummen-Algorithmus: " + algorithmus, e);
        }
    }

    /** Vergleicht den berechneten Hash mit der erwarteten Prüfsumme (hex oder Base64). */
    public static boolean stimmt(byte[] berechnet, String erwartet) {
        String e = erwartet.strip();
        if (e.equalsIgnoreCase(HexFormat.of().formatHex(berechnet))) {
            return true;
        }
        try {
            return MessageDigest.isEqual(berechnet, Base64.getDecoder().decode(e));
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }
}
