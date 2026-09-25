package at.postkorb.tray;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import at.postkorb.PostkorbAbholer;
import at.postkorb.gateway.Zustellung;
import at.postkorb.store.RunLock;

/**
 * Logik des Programms im Infobereich – ohne AWT, damit sie testbar ist.
 * Führt Abholungen aus, merkt sich den Zustand und entscheidet, welche Meldungen angezeigt werden.
 */
public final class TrayController {

    private static final Logger LOG = Logger.getLogger(TrayController.class.getName());
    static final int ZERTIFIKAT_WARNTAGE = 30;
    private static final int MAX_TOOLTIP = 127; // Grenze von Windows
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public enum Zustand { OK, NEUE_POST, LAEUFT, WARNUNG, FEHLER }

    public interface View {
        void zeige(Zustand zustand, String tooltip, String statusZeile);

        void meldung(String titel, String text, boolean fehler);
    }

    @FunctionalInterface
    public interface AbholerFactory {
        PostkorbAbholer create(Consumer<Zustellung> beiNeuerZustellung) throws Exception;
    }

    @FunctionalInterface
    public interface LockFactory {
        RunLock tryAcquire() throws Exception;
    }

    /** Liefert das Ablaufdatum des Client-Zertifikats oder {@code null}, wenn keines verwendet wird. */
    @FunctionalInterface
    public interface ZertifikatsAblauf {
        Instant get() throws Exception;
    }

    private final View view;
    private final AbholerFactory abholerFactory;
    private final LockFactory lockFactory;
    private final ZertifikatsAblauf zertifikatsAblauf;
    private final Clock clock;

    private int ungelesen;
    private String fehler;
    private String warnung;
    private boolean zertifikatAbgelaufen;
    private Instant warnungGemeldetAm;
    private String statusZeile = "Noch keine Abholung";

    public TrayController(View view, AbholerFactory abholerFactory, LockFactory lockFactory,
            ZertifikatsAblauf zertifikatsAblauf, Clock clock) {
        this.view = view;
        this.abholerFactory = abholerFactory;
        this.lockFactory = lockFactory;
        this.zertifikatsAblauf = zertifikatsAblauf;
        this.clock = clock;
    }

    /** Ein Abholdurchlauf mit Aktualisierung von Symbol, Status und Meldungen. */
    public synchronized void abholen() {
        view.zeige(Zustand.LAEUFT, "USP Postkorb – Abholung läuft …", "Abholung läuft …");
        List<Zustellung> neue = new ArrayList<>();
        String fehlerNeu = null;
        String ergebnis;
        try (RunLock lock = lockFactory.tryAcquire()) {
            if (lock == null) {
                ergebnis = "übersprungen, andere Abholung läuft";
            } else {
                PostkorbAbholer.Ergebnis r = abholerFactory.create(neue::add).durchlauf();
                if (r.fehler() > 0) {
                    fehlerNeu = r.fehler() + " Zustellung(en) konnten nicht abgeholt werden – siehe Protokoll";
                }
                ergebnis = neue.isEmpty() ? "keine neue Post" : neue.size() + " neu";
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Abholung fehlgeschlagen", e);
            fehlerNeu = kurz(e);
            ergebnis = "Fehler";
        }
        statusZeile = "Letzte Abholung " + ZEIT.format(clock.instant().atZone(ZoneId.systemDefault())) + ": " + ergebnis;

        pruefeZertifikat();
        if (!neue.isEmpty()) {
            ungelesen += neue.size();
            meldeNeue(neue);
        }
        if (fehlerNeu != null && !fehlerNeu.equals(fehler)) {
            view.meldung("USP Postkorb – Abholung fehlgeschlagen", fehlerNeu, true);
        }
        fehler = fehlerNeu;
        aktualisieren();
    }

    /** Der Benutzer hat den Eingangsordner geöffnet – neue Post gilt als gesehen. */
    public synchronized void eingangGeoeffnet() {
        ungelesen = 0;
        aktualisieren();
    }

    synchronized Zustand zustand() {
        if (fehler != null || zertifikatAbgelaufen) {
            return Zustand.FEHLER;
        }
        if (ungelesen > 0) {
            return Zustand.NEUE_POST;
        }
        return warnung != null ? Zustand.WARNUNG : Zustand.OK;
    }

    private void aktualisieren() {
        Zustand z = zustand();
        String text = switch (z) {
            case FEHLER -> zertifikatAbgelaufen && fehler == null ? warnung : fehler;
            case NEUE_POST -> ungelesen + " neue Zustellung(en) im Eingang";
            case WARNUNG -> warnung;
            default -> "alles in Ordnung";
        };
        String tooltip = "USP Postkorb – " + text + "\n" + statusZeile;
        view.zeige(z, tooltip.length() > MAX_TOOLTIP ? tooltip.substring(0, MAX_TOOLTIP - 1) + "…" : tooltip, statusZeile);
    }

    private void pruefeZertifikat() {
        Instant ablauf;
        try {
            ablauf = zertifikatsAblauf.get();
        } catch (Exception e) {
            LOG.log(Level.FINE, "Ablaufdatum des Zertifikats nicht lesbar", e);
            return; // der Fehler zeigt sich ohnehin bei der Abholung
        }
        if (ablauf == null) {
            return;
        }
        Instant jetzt = clock.instant();
        String datum = DATUM.format(ablauf.atZone(ZoneId.systemDefault()));
        long tage = Duration.between(jetzt, ablauf).toDays();
        zertifikatAbgelaufen = ablauf.isBefore(jetzt);
        if (zertifikatAbgelaufen) {
            warnung = "Client-Zertifikat ist am " + datum + " abgelaufen – im USP ein neues erzeugen";
        } else if (tage < ZERTIFIKAT_WARNTAGE) {
            warnung = "Client-Zertifikat läuft am " + datum + " ab (in " + tage + " Tagen) – im USP ein neues erzeugen";
        } else {
            warnung = null;
        }
        // höchstens einmal täglich melden
        if (warnung != null && (warnungGemeldetAm == null || Duration.between(warnungGemeldetAm, jetzt).toHours() >= 24)) {
            view.meldung("USP Postkorb – Zertifikat", warnung, zertifikatAbgelaufen);
            warnungGemeldetAm = jetzt;
        }
    }

    private void meldeNeue(List<Zustellung> neue) {
        boolean rsa = neue.stream().anyMatch(TrayController::istRsa);
        String titel = rsa ? "Neue RSa-Zustellung – Frist beachten!"
                : neue.size() == 1 ? "Neue Zustellung im Postkorb" : neue.size() + " neue Zustellungen im Postkorb";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(3, neue.size()); i++) {
            Zustellung z = neue.get(i);
            String zeile = (z.absender() != null ? z.absender() : "Unbekannter Absender")
                    + (z.betreff() != null ? ": " + z.betreff() : "")
                    + (istRsa(z) ? " [" + z.weitereAngaben().get("Zustellqualität") + "]" : "");
            text.append(zeile.length() > 70 ? zeile.substring(0, 69) + "…" : zeile).append('\n');
        }
        if (neue.size() > 3) {
            text.append("… und ").append(neue.size() - 3).append(" weitere\n");
        }
        text.append("Klicken, um den Eingang zu öffnen.");
        view.meldung(titel, text.toString(), false);
    }

    static boolean istRsa(Zustellung z) {
        String q = z.weitereAngaben().get("Zustellqualität");
        return q != null && q.startsWith("RSa");
    }

    private static String kurz(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (root != e && root.getMessage() != null && !msg.contains(root.getMessage())) {
            msg += " (" + root.getMessage() + ")";
        }
        return msg.length() > 200 ? msg.substring(0, 199) + "…" : msg;
    }
}
