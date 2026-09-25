package at.postkorb.tray;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;

import at.postkorb.Main;
import at.postkorb.PostkorbAbholer;
import at.postkorb.config.Config;
import at.postkorb.store.DocumentStore;
import at.postkorb.store.ProcessedStore;
import at.postkorb.store.RunLock;
import at.postkorb.tls.TlsContextFactory;

/**
 * Programm im Windows-Infobereich: holt beim Start und danach alle {@code poll.interval.minutes}
 * ab, zeigt den Zustand als farbiges Symbol und meldet neue Post und Fehler.
 */
public final class TrayApp {

    private static final Logger LOG = Logger.getLogger(TrayApp.class.getName());
    private static final String TITEL = "USP Postkorb";

    private TrayApp() {
    }

    /** @return Exit-Code, falls das Programm nicht starten konnte; sonst läuft es bis "Beenden" */
    public static int start(Path configFile) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            LOG.severe("Der Infobereich (System Tray) wird auf diesem System nicht unterstützt");
            return 1;
        }
        Config cfg;
        try {
            cfg = Config.load(configFile);
            Main.setupFileLogging(cfg.outputDir().resolve("logs"));
        } catch (IOException | RuntimeException e) {
            fehlerDialog("Die Konfiguration konnte nicht geladen werden:\n" + configFile.toAbsolutePath() + "\n\n" + e.getMessage());
            return 1;
        }

        RunLock instanz;
        try {
            instanz = RunLock.tryAcquire(cfg.outputDir().resolve(".tray.lock"));
        } catch (IOException e) {
            fehlerDialog("Der Eingangsordner ist nicht beschreibbar:\n" + cfg.outputDir() + "\n\n" + e.getMessage());
            return 1;
        }
        if (instanz == null) {
            fehlerDialog("Das Programm läuft bereits – siehe Symbol im Infobereich neben der Uhr.");
            return 0;
        }

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "postkorb-abholung");
            t.setDaemon(false);
            return t;
        });
        TrayController[] controller = new TrayController[1];
        AwtTrayView[] view = new AwtTrayView[1];
        AwtTrayView.Aktionen aktionen = new AwtTrayView.Aktionen() {
            @Override
            public void jetztAbholen() {
                executor.execute(controller[0]::abholen);
            }

            @Override
            public void eingangOeffnen() {
                oeffne(cfg.outputDir());
                controller[0].eingangGeoeffnet();
            }

            @Override
            public void protokollOeffnen() {
                oeffne(cfg.outputDir().resolve("logs").resolve("postkorb-0.log"));
            }

            @Override
            public void beenden() {
                LOG.info("Programm wird beendet");
                executor.shutdown();
                try {
                    executor.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                view[0].entfernen();
                try {
                    instanz.close();
                } catch (IOException ignored) {
                    // wird beim Prozessende ohnehin freigegeben
                }
                System.exit(0);
            }
        };

        try {
            view[0] = new AwtTrayView(aktionen);
        } catch (Exception e) {
            fehlerDialog("Das Symbol im Infobereich konnte nicht angelegt werden:\n" + e.getMessage());
            return 1;
        }
        controller[0] = new TrayController(view[0],
                beiNeu -> new PostkorbAbholer(Main.createGateway(cfg), new DocumentStore(cfg.outputDir()),
                        new ProcessedStore(cfg.stateFile()), cfg.deleteAfterDownload(), beiNeu),
                () -> RunLock.tryAcquire(cfg.outputDir().resolve(".lock")),
                () -> "soap".equals(cfg.gateway())
                        ? TlsContextFactory.clientCertificate(cfg).getNotAfter().toInstant() : null,
                Clock.systemDefaultZone());

        long minuten = Math.max(1, cfg.pollInterval().toMinutes());
        LOG.info(() -> "Programm im Infobereich gestartet, Abholung alle " + minuten + " Minuten");
        executor.scheduleWithFixedDelay(controller[0]::abholen, 0, minuten, TimeUnit.MINUTES);
        return 0;
    }

    private static void oeffne(Path p) {
        try {
            if (Files.exists(p)) {
                Desktop.getDesktop().open(p.toFile());
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Konnte nicht geöffnet werden: " + p, e);
        }
    }

    private static void fehlerDialog(String text) {
        LOG.severe(text);
        JOptionPane.showMessageDialog(null, text, TITEL, JOptionPane.ERROR_MESSAGE);
    }
}
