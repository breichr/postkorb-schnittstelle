package at.postkorb.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collections;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;

import at.postkorb.config.Config;

/**
 * Baut den SSLContext für die beidseitig zertifikatsgesicherte Verbindung (mTLS)
 * zur "Automatischen Abholung".
 *
 * <p>Client-Zertifikat:
 * <ul>
 *   <li>{@code tls.keystore.type=PKCS12} – .p12/.pfx-Datei (aus dem USP heruntergeladen)</li>
 *   <li>{@code tls.keystore.type=Windows-MY} – Zertifikatsspeicher des angemeldeten Windows-Benutzers</li>
 * </ul>
 * Server-Vertrauen: {@code tls.truststore.path} – entweder ein CA-Zertifikat (.cer/.crt/.pem,
 * z. B. die BRZ-StammCA aus dem USP) oder ein Truststore (.p12/.jks); ohne Angabe gelten die
 * Standard-CAs der Java-Laufzeit.
 */
public final class TlsContextFactory {

    private TlsContextFactory() {
    }

    public static SSLContext create(Config cfg) throws IOException, GeneralSecurityException {
        KeyStore ks = loadKeyStore(cfg.keystoreType(), cfg.keystorePath(), cfg.keystorePassword());
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, cfg.keystorePassword());
        KeyManager[] keyManagers = kmf.getKeyManagers();
        if (cfg.keyAlias() != null) {
            requireAlias(ks, cfg.keyAlias());
            for (int i = 0; i < keyManagers.length; i++) {
                if (keyManagers[i] instanceof X509KeyManager km) {
                    keyManagers[i] = new FixedAliasKeyManager(km, cfg.keyAlias());
                }
            }
        } else {
            requireSingleKey(ks);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (cfg.truststorePath() != null) {
            String name = cfg.truststorePath().toString().toLowerCase();
            if (name.endsWith(".cer") || name.endsWith(".crt") || name.endsWith(".pem")) {
                tmf.init(loadCertificates(cfg.truststorePath()));
            } else {
                tmf.init(loadKeyStore(name.endsWith(".jks") ? "JKS" : "PKCS12", cfg.truststorePath(), cfg.truststorePassword()));
            }
        } else {
            tmf.init((KeyStore) null);
        }

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(keyManagers, tmf.getTrustManagers(), null);
        return ctx;
    }

    /** Das Client-Zertifikat (für die Warnung vor dessen Ablauf). */
    public static X509Certificate clientCertificate(Config cfg) throws IOException, GeneralSecurityException {
        KeyStore ks = loadKeyStore(cfg.keystoreType(), cfg.keystorePath(), cfg.keystorePassword());
        String alias = cfg.keyAlias();
        if (alias == null) {
            requireSingleKey(ks);
            for (String a : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(a)) {
                    alias = a;
                }
            }
        }
        if (ks.getCertificate(alias) instanceof X509Certificate x) {
            return x;
        }
        throw new IllegalArgumentException("Kein X.509-Zertifikat zum Schlüssel '" + alias + "'");
    }

    static KeyStore loadKeyStore(String type, Path path, char[] password) throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(type);
        if (type.startsWith("Windows-")) {
            ks.load(null, null);
            return ks;
        }
        if (path == null) {
            throw new IllegalArgumentException("Keystore-Pfad fehlt (tls.keystore.path)");
        }
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, password);
        }
        return ks;
    }

    /** Lädt CA-Zertifikate (PEM oder DER, z. B. brz_ca.cer aus dem USP) als Truststore. */
    static KeyStore loadCertificates(Path path) throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        int i = 0;
        try (InputStream in = Files.newInputStream(path)) {
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                ks.setCertificateEntry("ca-" + i++, c);
            }
        }
        if (i == 0) {
            throw new IllegalArgumentException("Keine Zertifikate in " + path);
        }
        return ks;
    }

    private static void requireAlias(KeyStore ks, String alias) throws GeneralSecurityException {
        if (!ks.isKeyEntry(alias)) {
            throw new IllegalArgumentException("Kein privater Schlüssel mit Alias '" + alias + "' im Keystore. Vorhanden: "
                    + Collections.list(ks.aliases()));
        }
    }

    private static void requireSingleKey(KeyStore ks) throws GeneralSecurityException {
        long keys = Collections.list(ks.aliases()).stream().filter(a -> {
            try {
                return ks.isKeyEntry(a);
            } catch (GeneralSecurityException e) {
                return false;
            }
        }).count();
        if (keys == 0) {
            throw new IllegalArgumentException("Keystore enthält keinen privaten Schlüssel (Client-Zertifikat)");
        }
        if (keys > 1) {
            throw new IllegalArgumentException("Keystore enthält mehrere Schlüssel – bitte tls.key.alias setzen. Vorhanden: "
                    + Collections.list(ks.aliases()));
        }
    }

    /** Erzwingt ein bestimmtes Client-Zertifikat, z. B. im Windows-Zertifikatsspeicher mit vielen Einträgen. */
    private static final class FixedAliasKeyManager extends X509ExtendedKeyManager {
        private final X509KeyManager delegate;
        private final String alias;

        FixedAliasKeyManager(X509KeyManager delegate, String alias) {
            this.delegate = delegate;
            this.alias = alias;
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, java.net.Socket socket) {
            return alias;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return alias;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[] {alias};
        }

        @Override
        public X509Certificate[] getCertificateChain(String a) {
            return delegate.getCertificateChain(a);
        }

        @Override
        public PrivateKey getPrivateKey(String a) {
            return delegate.getPrivateKey(a);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, java.net.Socket socket) {
            return null;
        }
    }
}
