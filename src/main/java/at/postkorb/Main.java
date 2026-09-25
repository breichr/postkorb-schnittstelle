package at.postkorb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import at.postkorb.config.Config;
import at.postkorb.elak.ElakWerkzeug;
import at.postkorb.gateway.DemoGateway;
import at.postkorb.gateway.PostkorbGateway;
import at.postkorb.store.DocumentStore;
import at.postkorb.store.ProcessedStore;
import at.postkorb.store.RunLock;
import at.postkorb.tls.TlsContextFactory;
import at.postkorb.tray.TrayApp;
import at.postkorb.zuseaa.ZuseAaSoapGateway;

/**
 * Aufruf:
 * <pre>
 *   java -jar postkorb-schnittstelle.jar [--config pfad\postkorb.properties] [--once | --loop | --check-tls | --tray]
 * </pre>
 * <ul>
 *   <li>{@code --once} (Standard): ein Durchlauf, z. B. für die Windows-Aufgabenplanung.
 *       Exit-Code 0 = ok, 1 = Konfigurations-/Verbindungsfehler, 2 = einzelne Zustellungen fehlgeschlagen.</li>
 *   <li>{@code --loop}: läuft dauerhaft und holt alle {@code poll.interval.minutes} ab.</li>
 *   <li>{@code --check-tls}: prüft nur, ob Client-Zertifikat und Truststore geladen werden können.</li>
 *   <li>{@code --tray}: Programm im Windows-Infobereich (mit javaw.exe starten).</li>
 *   <li>{@code --elak-pruefen}: ELAK-Anbindung nur lesend prüfen.</li>
 *   <li>{@code --elak-testmappe datei.pdf}: eine Testmappe ohne Workflow im ELAK anlegen.</li>
 *   <li>{@code --elak-nachtragen ordner}: bereits abgeholte Zustellung in die ELAK-Warteliste stellen.</li>
 * </ul>
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    private static final String ANDERE_ABHOLUNG =
            "Es läuft gerade eine andere Abholung (z. B. das Programm im Infobereich) – dieser Aufruf wird übersprungen.";

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s %3$s - %5$s%6$s%n");
        Path configFile = Path.of("config", "postkorb.properties");
        String mode = "--once";
        Path testdatei = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> configFile = Path.of(args[++i]);
                case "--elak-pruefen" -> mode = args[i];
                case "--elak-nachtragen" -> {
                    mode = args[i];
                    testdatei = Path.of(args[++i]);
                }
                case "--elak-testmappe" -> {
                    mode = args[i];
                    testdatei = Path.of(args[++i]);
                }
                case "--once", "--loop", "--check-tls", "--tray" -> mode = args[i];
                case "--help", "-h" -> {
                    System.out.println("java -jar postkorb-schnittstelle.jar [--config datei] [--once|--loop|--check-tls|--tray]");
                    return;
                }
                default -> {
                    System.err.println("Unbekanntes Argument: " + args[i]);
                    System.exit(1);
                }
            }
        }
        if (mode.equals("--elak-pruefen")) {
            System.exit(ElakWerkzeug.pruefen(configFile, System.out));
        }
        if (mode.equals("--elak-nachtragen")) {
            try {
                System.exit(ElakWerkzeug.nachtragen(Config.load(configFile).outputDir(), testdatei, System.out));
            } catch (IOException e) {
                System.out.println("FEHLER: " + e.getMessage());
                System.exit(1);
            }
        }
        if (mode.equals("--elak-testmappe")) {
            System.exit(ElakWerkzeug.testmappe(configFile, testdatei, System.out));
        }
        if (mode.equals("--tray")) {
            int rc = TrayApp.start(configFile);
            if (rc != 0) {
                System.exit(rc);
            }
            return; // läuft im Hintergrund weiter, bis "Beenden" gewählt wird
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
            Path lockFile = cfg.outputDir().resolve(".lock");
            if (mode.equals("--loop")) {
                while (!Thread.currentThread().isInterrupted()) {
                    try (RunLock lock = RunLock.tryAcquire(lockFile)) {
                        if (lock == null) {
                            LOG.info(ANDERE_ABHOLUNG);
                        } else {
                            abholer.durchlauf();
                        }
                    } catch (IOException | RuntimeException e) {
                        LOG.log(Level.SEVERE, "Durchlauf fehlgeschlagen", e);
                    }
                    Thread.sleep(cfg.pollInterval().toMillis());
                }
                return 0;
            }
            try (RunLock lock = RunLock.tryAcquire(lockFile)) {
                if (lock == null) {
                    LOG.info(ANDERE_ABHOLUNG);
                    return 0;
                }
                return abholer.durchlauf().fehler() > 0 ? 2 : 0;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Abholung fehlgeschlagen", e);
            return 1;
        }
    }

    public static PostkorbGateway createGateway(Config cfg) throws Exception {
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

    public static synchronized void setupFileLogging(Path logDir) throws IOException {
        Files.createDirectories(logDir);
        FileHandler fh = new FileHandler(logDir.resolve("postkorb-%g.log").toString(), 5_000_000, 5, true);
        fh.setEncoding("UTF-8");
        fh.setFormatter(new SimpleFormatter());
        Logger.getLogger("").addHandler(fh);
    }
}
