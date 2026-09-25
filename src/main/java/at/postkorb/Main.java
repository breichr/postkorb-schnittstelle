package at.postkorb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import at.postkorb.config.Config;
import at.postkorb.gateway.DemoGateway;
import at.postkorb.gateway.PostkorbGateway;
import at.postkorb.store.DocumentStore;
import at.postkorb.store.ProcessedStore;
import at.postkorb.tls.TlsContextFactory;
import at.postkorb.zuseaa.ZuseAaSoapGateway;

/**
 * Aufruf:
 * <pre>
 *   java -jar postkorb-schnittstelle.jar [--config pfad\postkorb.properties] [--once | --loop | --check-tls]
 * </pre>
 * <ul>
 *   <li>{@code --once} (Standard): ein Durchlauf, z. B. für die Windows-Aufgabenplanung.
 *       Exit-Code 0 = ok, 1 = Konfigurations-/Verbindungsfehler, 2 = einzelne Zustellungen fehlgeschlagen.</li>
 *   <li>{@code --loop}: läuft dauerhaft und holt alle {@code poll.interval.minutes} ab.</li>
 *   <li>{@code --check-tls}: prüft nur, ob Client-Zertifikat und Truststore geladen werden können.</li>
 * </ul>
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s %3$s - %5$s%6$s%n");
        Path configFile = Path.of("config", "postkorb.properties");
        String mode = "--once";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configFile = Path.of(args[++i]);
                case "--once", "--loop", "--check-tls" -> mode = args[i];
                case "--help", "-h" -> {
                    System.out.println("java -jar postkorb-schnittstelle.jar [--config datei] [--once|--loop|--check-tls]");
                    return;
                }
                default -> {
                    System.err.println("Unbekanntes Argument: " + args[i]);
                    System.exit(1);
                }
            }
        }
        System.exit(run(configFile, mode));
    }

    static int run(Path configFile, String mode) {
        Config cfg;
        try {
            cfg = Config.load(configFile);
            setupFileLogging(cfg.outputDir().resolve("logs"));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.SEVERE, "Konfiguration konnte nicht geladen werden: " + configFile.toAbsolutePath(), e);
            return 1;
        }

        try {
            if (mode.equals("--check-tls")) {
                TlsContextFactory.create(cfg);
                LOG.info("Client-Zertifikat und Truststore erfolgreich geladen.");
                return 0;
            }
            PostkorbGateway gateway = createGateway(cfg);
            PostkorbAbholer abholer = new PostkorbAbholer(gateway, new DocumentStore(cfg.outputDir()),
                    new ProcessedStore(cfg.stateFile()), cfg.deleteAfterDownload());
            if (mode.equals("--loop")) {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        abholer.durchlauf();
                    } catch (IOException | RuntimeException e) {
                        LOG.log(Level.SEVERE, "Durchlauf fehlgeschlagen", e);
                    }
                    Thread.sleep(cfg.pollInterval().toMillis());
                }
                return 0;
            }
            return abholer.durchlauf().fehler() > 0 ? 2 : 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Abholung fehlgeschlagen", e);
            return 1;
        }
    }

    static PostkorbGateway createGateway(Config cfg) throws Exception {
        switch (cfg.gateway()) {
            case "demo":
                if (cfg.demoInbox() == null) {
                    throw new IllegalArgumentException("gateway=demo benötigt demo.inbox");
                }
                return new DemoGateway(cfg.demoInbox());
            case "soap":
                return new ZuseAaSoapGateway(cfg, TlsContextFactory.create(cfg));
            default:
                throw new IllegalArgumentException("Unbekanntes gateway: " + cfg.gateway() + " (erlaubt: soap, demo)");
        }
    }

    private static void setupFileLogging(Path logDir) throws IOException {
        Files.createDirectories(logDir);
        FileHandler fh = new FileHandler(logDir.resolve("postkorb-%g.log").toString(), 5_000_000, 5, true);
        fh.setEncoding("UTF-8");
        fh.setFormatter(new SimpleFormatter());
        Logger.getLogger("").addHandler(fh);
    }
}
