package at.postkorb.tray;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.EnumMap;
import java.util.Map;

/** Symbol im Windows-Infobereich mit Menü und Benachrichtigungen. */
final class AwtTrayView implements TrayController.View {

    interface Aktionen {
        void jetztAbholen();

        void eingangOeffnen();

        void protokollOeffnen();

        void updateSuchen();

        void updateInstallieren();

        /** tägliche Prüfung im Hintergrund – meldet nur, wenn es ein Update gibt */
        void updateSuchenAutomatisch();

        void beenden();
    }

    private final Map<TrayController.Zustand, java.awt.Image> icons = new EnumMap<>(TrayController.Zustand.class);
    private final TrayIcon trayIcon;
    private final MenuItem status;
    private final MenuItem update;

    AwtTrayView(Aktionen aktionen, String version) throws AWTException {
        for (TrayController.Zustand z : TrayController.Zustand.values()) {
            icons.put(z, TrayIcons.fuer(z));
        }
        PopupMenu menu = new PopupMenu();
        status = new MenuItem("Noch keine Abholung");
        status.setEnabled(false);
        menu.add(status);
        menu.addSeparator();
        menu.add(item("Jetzt abholen", aktionen::jetztAbholen));
        menu.add(item("Eingang öffnen", aktionen::eingangOeffnen));
        menu.add(item("Protokoll öffnen", aktionen::protokollOeffnen));
        menu.addSeparator();
        MenuItem versionItem = new MenuItem("Version " + version);
        versionItem.setEnabled(false);
        menu.add(versionItem);
        menu.add(item("Nach Updates suchen", aktionen::updateSuchen));
        update = item("Kein Update verfügbar", aktionen::updateInstallieren);
        update.setEnabled(false);
        menu.add(update);
        menu.addSeparator();
        menu.add(item("Beenden", aktionen::beenden));

        trayIcon = new TrayIcon(icons.get(TrayController.Zustand.LAEUFT), "USP Postkorb – startet …", menu);
        trayIcon.setImageAutoSize(true);
        // Doppelklick auf das Symbol bzw. Klick auf eine Benachrichtigung
        trayIcon.addActionListener(e -> aktionen.eingangOeffnen());
        SystemTray.getSystemTray().add(trayIcon);
    }

    private static MenuItem item(String text, Runnable aktion) {
        MenuItem mi = new MenuItem(text);
        mi.addActionListener(e -> aktion.run());
        return mi;
    }

    @Override
    public void zeige(TrayController.Zustand zustand, String tooltip, String statusZeile) {
        EventQueue.invokeLater(() -> {
            trayIcon.setImage(icons.get(zustand));
            trayIcon.setToolTip(tooltip);
            status.setLabel(statusZeile);
        });
    }

    @Override
    public void meldung(String titel, String text, boolean fehler) {
        EventQueue.invokeLater(() -> trayIcon.displayMessage(titel, text,
                fehler ? TrayIcon.MessageType.ERROR : TrayIcon.MessageType.INFO));
    }

    void updateVerfuegbar(String version) {
        EventQueue.invokeLater(() -> {
            update.setLabel(version == null ? "Kein Update verfügbar" : "Update auf Version " + version + " installieren");
            update.setEnabled(version != null);
        });
    }

    void entfernen() {
        SystemTray.getSystemTray().remove(trayIcon);
    }
}
