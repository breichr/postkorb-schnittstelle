package at.postkorb.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519-Signaturen für Updates. Der private Schlüssel liegt nur als GitHub-Secret
 * {@code POSTKORB_SIGNING_KEY} vor, der öffentliche ist im Programm eingebaut
 * ({@code /update-public-key.txt}). Ein Update wird nur installiert, wenn die Signatur passt.
 *
 * <pre>
 *   java -cp postkorb-schnittstelle.jar at.postkorb.update.Signatur erzeugen signatur-privat.txt
 *   java -cp postkorb-schnittstelle.jar at.postkorb.update.Signatur signieren datei   (Schlüssel aus POSTKORB_SIGNING_KEY)
 * </pre>
 */
public final class Signatur {

    private static final String ALG = "Ed25519";
    static final String PUBLIC_KEY_RESOURCE = "/update-public-key.txt";

    private Signatur() {
    }

    public static KeyPair erzeugeSchluesselpaar() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance(ALG).generateKeyPair();
    }

    public static String kodiere(java.security.Key key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PrivateKey privatSchluessel(String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance(ALG).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
    }

    public static PublicKey oeffentlicherSchluessel(String base64) throws GeneralSecurityException {
        return KeyFactory.getInstance(ALG).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.strip())));
    }

    /** Der im Programm eingebaute öffentliche Schlüssel oder {@code null}, wenn noch keiner hinterlegt ist. */
    public static PublicKey eingebauterSchluessel() {
        try (InputStream in = Signatur.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.US_ASCII).lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("#")).findFirst().orElse(null);
            return text == null ? null : oeffentlicherSchluessel(text);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    /** @return Signatur als Base64-Text */
    public static String signiere(byte[] daten, PrivateKey key) throws GeneralSecurityException {
        Signature s = Signature.getInstance(ALG);
        s.initSign(key);
        s.update(daten);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    public static boolean pruefe(byte[] daten, String signaturBase64, PublicKey key) {
        try {
            Signature s = Signature.getInstance(ALG);
            s.initVerify(key);
            s.update(daten);
            return s.verify(Base64.getDecoder().decode(signaturBase64.strip()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("erzeugen")) {
            Path privat = Path.of(args[1]);
            if (Files.exists(privat)) {
                throw new IllegalStateException(privat + " existiert bereits – nicht überschrieben");
            }
            KeyPair kp = erzeugeSchluesselpaar();
            Files.writeString(privat, kodiere(kp.getPrivate()) + System.lineSeparator(), StandardCharsets.US_ASCII);
            System.out.println("Privater Schlüssel gespeichert in: " + privat.toAbsolutePath());
            System.out.println("  -> Inhalt als GitHub-Secret POSTKORB_SIGNING_KEY eintragen, danach die Datei sicher verwahren oder löschen.");
            System.out.println();
            System.out.println("Öffentlicher Schlüssel (darf weitergegeben werden, kommt ins Programm):");
            System.out.println(kodiere(kp.getPublic()));
        } else if (args.length == 2 && args[0].equals("signieren")) {
            String key = System.getenv("POSTKORB_SIGNING_KEY");
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("Umgebungsvariable POSTKORB_SIGNING_KEY fehlt");
            }
            Path datei = Path.of(args[1]);
            String sig = signiere(Files.readAllBytes(datei), privatSchluessel(key));
            Path sigDatei = datei.resolveSibling(datei.getFileName() + ".sig");
            Files.writeString(sigDatei, sig + "\n", StandardCharsets.US_ASCII);
            PublicKey eingebaut = eingebauterSchluessel();
            if (eingebaut != null && !pruefe(Files.readAllBytes(datei), sig, eingebaut)) {
                throw new IllegalStateException("Signaturschlüssel passt nicht zum öffentlichen Schlüssel im Programm");
            }
            System.out.println("Signiert: " + sigDatei);
        } else {
            System.err.println("Aufruf: Signatur erzeugen <privat-datei> | Signatur signieren <datei>");
            System.exit(1);
        }
    }
}
