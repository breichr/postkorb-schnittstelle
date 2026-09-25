package at.postkorb.tray;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
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
import at.postkorb.update.Signatur;
import at.postkorb.update.UpdateInstaller;
import at.postkorb.update.UpdateService;
import at.postkorb.update.Version;

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
        String version = Version.aktuell();
        Path updateDir = cfg.outputDir().resolve("updates");
        UpdateService updates = new UpdateService(cfg.updateUrl(), version,
                cfg.updateAktiv() ? Signatur.eingebauterSchluessel() : null, updateDir);
        String[] verfuegbar = new String[1];
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
            public void updateSuchen() {
                executor.execute(() -> updateSuchen(true));
            }

            @Override
            public void updateSuchenAutomatisch() {
                updateSuchen(false);
            }

            @Override
            public void updateInstallieren() {
                executor.execute(() -> {
                    String v = verfuegbar[0];
                    if (v == null) {
                        return;
                    }
                    try {
                        Path neu = updates.herunterladen(v);
                        Path javaw = ProcessHandle.current().info().command().map(Path::of)
                                .orElseThrow(() -> new IOException("Pfad von javaw.exe nicht ermittelbar"));
                        UpdateInstaller.starte(eigeneJar(), neu, javaw, configFile.toAbsolutePath(), updateDir, v);
                        LOG.info(() -> "Update auf Version " + v + " wird installiert – Programm startet neu");
                        view[0].meldung(TITEL, "Update auf Version " + v + " wird installiert. Das Programm startet gleich neu.", false);
                        Thread.sleep(1500);
                        beendenOhneWarten();
                    } catch (Exception e) {
                        LOG.log(Level.SEVERE, "Update fehlgeschlagen", e);
                        view[0].meldung(TITEL + " – Update fehlgeschlagen", String.valueOf(e.getMessage()), true);
                    }
                });
            }

            private void updateSuchen(boolean manuell) {
                if (!updates.aktiv()) {
                    if (manuell) {
                        view[0].meldung(TITEL, "Updates sind in dieser Version noch nicht eingerichtet (kein Signaturschlüssel).", false);
                    }
                    return;
                }
                try {
                    Optional<String> v = updates.neuereVersion();
                    verfuegbar[0] = v.orElse(null);
                    view[0].updateVerfuegbar(verfuegbar[0]);
                    if (v.isPresent()) {
                        LOG.info(() -> "Update verfügbar: " + v.get());
                        view[0].meldung(TITEL + " – Update verfügbar",
                                "Version " + v.get() + " ist verfügbar (installiert: " + version + ").\n"
                                        + "Rechtsklick auf das Symbol > Update installieren", false);
                    } else if (manuell) {
                        view[0].meldung(TITEL, "Version " + version + " ist aktuell.", false);
                    }
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Update-Prüfung fehlgeschlagen", e);
                    if (manuell) {
                        view[0].meldung(TITEL, "Update-Prüfung fehlgeschlagen: " + e.getMessage(), true);
                    }
                }
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
                beendenOhneWarten();
            }

            private void beendenOhneWarten() {
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
            view[0] = new AwtTrayView(aktionen, version);
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

        // erfolgreicher Start nach einem Update bestätigen (sonst stellt update.cmd die alte Version wieder her)
        try {
            Files.createDirectories(updateDir);
            Files.writeString(UpdateInstaller.erfolgsMarke(updateDir, version), "ok");
            UpdateInstaller.aufraeumen(updateDir, version);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Erfolgsmarke für Update nicht schreibbar", e);
        }

        long minuten = Math.max(1, cfg.pollInterval().toMinutes());
        LOG.info(() -> "Programm im Infobereich gestartet (Version " + version + "), Abholung alle " + minuten + " Minuten"
                + (updates.aktiv() ? ", tägliche Update-Prüfung" : ", Updates deaktiviert"));
        executor.scheduleWithFixedDelay(controller[0]::abholen, 0, minuten, TimeUnit.MINUTES);
        executor.scheduleWithFixedDelay(aktionen::updateSuchenAutomatisch, 2, 24 * 60, TimeUnit.MINUTES);
        return 0;
    }

    private static Path eigeneJar() throws Exception {
        Path p = Path.of(TrayApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!p.toString().toLowerCase().endsWith(".jar")) {
            throw new IOException("Programm läuft nicht aus einer JAR-Datei – Update nicht möglich");
        }
        return p;
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
