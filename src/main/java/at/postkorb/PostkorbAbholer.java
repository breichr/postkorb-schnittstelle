package at.postkorb;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import at.postkorb.gateway.PostkorbGateway;
import at.postkorb.gateway.Zustellung;
import at.postkorb.store.DocumentStore;
import at.postkorb.store.ProcessedStore;

/**
 * Ein Abholdurchlauf: abholbereite Zustellungen abfragen, Anhänge speichern,
 * danach die Abholung bestätigen. Fehler bei einer Zustellung brechen den Lauf
 * nicht ab; die Zustellung wird beim nächsten Lauf erneut versucht.
 */
public final class PostkorbAbholer {

    private static final Logger LOG = Logger.getLogger(PostkorbAbholer.class.getName());

    public record Ergebnis(int neu, int uebersprungen, int fehler) {
    }

    private final PostkorbGateway gateway;
    private final DocumentStore store;
    private final ProcessedStore processed;
    private final boolean bestaetigen;

    public PostkorbAbholer(PostkorbGateway gateway, DocumentStore store, ProcessedStore processed, boolean bestaetigen) {
        this.gateway = gateway;
        this.store = store;
        this.processed = processed;
        this.bestaetigen = bestaetigen;
    }

    public Ergebnis durchlauf() throws IOException {
        List<Zustellung> liste = gateway.abholbereit();
        LOG.info(() -> liste.size() + " Zustellung(en) abholbereit");
        int neu = 0, skip = 0, err = 0;
        for (Zustellung z : liste) {
            try {
                if (processed.contains(z.id())) {
                    skip++;
                } else {
                    Path ziel = store.store(z, a -> gateway.oeffneAnhang(z, a));
                    processed.add(z.id());
                    neu++;
                    LOG.info(() -> "Gespeichert: " + z.id() + " (" + z.anhaenge().size() + " Anhang/Anhänge) -> " + ziel);
                }
                // Auch bereits gespeicherte, aber noch nicht bestätigte Zustellungen bestätigen,
                // sonst würden sie dauerhaft in der Liste bleiben.
                if (bestaetigen) {
                    gateway.bestaetigeAbholung(z);
                }
            } catch (IOException | RuntimeException e) {
                err++;
                LOG.log(Level.SEVERE, "Fehler bei Zustellung " + z.id() + " – wird beim nächsten Lauf erneut versucht", e);
            }
        }
        Ergebnis r = new Ergebnis(neu, skip, err);
        LOG.info(() -> "Durchlauf beendet: " + r);
        return r;
    }
}
