package at.postkorb;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import at.postkorb.gateway.PostkorbGateway;
import at.postkorb.gateway.Zustellung;
import at.postkorb.store.DocumentStore;
import at.postkorb.store.ProcessedStore;

/**
 * Ein Abholdurchlauf: neue Zustellungen abfragen, abrufen, Anhänge speichern und prüfen,
 * danach im Postkorb abschließen (CloseDelivery) und optional löschen.
 * Fehler bei einer Zustellung brechen den Lauf nicht ab; sie wird beim nächsten Lauf erneut versucht.
 */
public final class PostkorbAbholer {

    private static final Logger LOG = Logger.getLogger(PostkorbAbholer.class.getName());
    /** Schutz vor Endlosschleifen, falls der Postkorb abgeschlossene Zustellungen weiter meldet. */
    private static final int MAX_ABFRAGEN = 50;

    public record Ergebnis(int neu, int uebersprungen, int fehler) {
    }

    private final PostkorbGateway gateway;
    private final DocumentStore store;
    private final ProcessedStore processed;
    private final boolean loeschen;

    public PostkorbAbholer(PostkorbGateway gateway, DocumentStore store, ProcessedStore processed, boolean loeschen) {
        this.gateway = gateway;
        this.store = store;
        this.processed = processed;
        this.loeschen = loeschen;
    }

    public Ergebnis durchlauf() throws IOException {
        int neu = 0, skip = 0, err = 0;
        Set<String> versucht = new HashSet<>();
        // Der Postkorb liefert pro Abfrage höchstens "Limit" neue Zustellungen – so lange nachfragen,
        // bis keine unbearbeiteten mehr kommen.
        for (int abfrage = 0; abfrage < MAX_ABFRAGEN; abfrage++) {
            List<String> ids = gateway.neueZustellungen().stream().filter(versucht::add).toList();
            LOG.info(() -> ids.size() + " neue Zustellung(en) im Postkorb");
            if (ids.isEmpty()) {
                break;
            }
            for (String id : ids) {
                try {
                    if (processed.contains(id)) {
                        skip++; // schon gespeichert, nur das Abschließen hatte damals nicht geklappt
                    } else {
                        Zustellung z = gateway.abrufen(id);
                        Path ziel = store.store(z, a -> gateway.oeffneAnhang(z, a));
                        processed.add(id);
                        neu++;
                        LOG.info(() -> "Gespeichert: " + id + " (" + z.anhaenge().size() + " Anhang/Anhänge) -> " + ziel);
                    }
                    gateway.abschliessen(id);
                    if (loeschen) {
                        gateway.loeschen(id);
                        LOG.info(() -> "Im Postkorb gelöscht: " + id);
                    }
                } catch (IOException | RuntimeException e) {
                    err++;
                    LOG.log(Level.SEVERE, "Fehler bei Zustellung " + id + " – wird beim nächsten Lauf erneut versucht", e);
                }
            }
        }
        Ergebnis r = new Ergebnis(neu, skip, err);
        LOG.info(() -> "Durchlauf beendet: " + r);
        return r;
    }
}
