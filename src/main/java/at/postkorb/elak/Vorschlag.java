package at.postkorb.elak;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Schlägt pro Anhang vor, ob er als Dokument oder Rechnung in den ELAK soll. Nur ein Vorschlag –
 * entschieden wird im Zuordnungsfenster. Hinweise sind Stichwörter in Dateiname und Betreff und
 * mitgeschickte elektronische Rechnungen (ebInterface/XRechnung als XML).
 */
public final class Vorschlag {

    private static final Pattern RECHNUNG = Pattern.compile(
            "rechnung|vorschreibung|zahlungsaufforderung|zahlungserinnerung|mahnung|faktura|invoice|gutschrift|beitragsvorschreibung");
    private static final Pattern DOKUMENT = Pattern.compile(
            "beschluss|bescheid|ladung|urteil|verständigung|verstaendigung|mitteilung|erkenntnis|verfügung|verfuegung|schreiben|protokoll");

    private Vorschlag() {
    }

    /** Anhänge, die standardmäßig nicht als eigene Mappe angelegt werden (Nachrichtentext, Metadaten). */
    public static boolean istBeiwerk(String dateiname) {
        String n = dateiname.toLowerCase(Locale.ROOT);
        return n.startsWith("mailbody") || n.equals("zustellung.txt");
    }

    public static Art fuer(String dateiname, String betreff, List<String> alleDateien) {
        String n = dateiname.toLowerCase(Locale.ROOT);
        if (istBeiwerk(dateiname)) {
            return Art.KEINE;
        }
        boolean pdf = n.endsWith(".pdf");
        boolean zustellungHatPdf = alleDateien.stream().anyMatch(d -> d.toLowerCase(Locale.ROOT).endsWith(".pdf"));
        if (!pdf && zustellungHatPdf) {
            return Art.KEINE; // z. B. XML-Rechnungsdaten oder Word-Dateien neben dem PDF
        }
        if (RECHNUNG.matcher(n).find()) {
            return Art.RECHNUNG;
        }
        if (DOKUMENT.matcher(n).find()) {
            return Art.DOKUMENT;
        }
        boolean eRechnung = alleDateien.stream().map(d -> d.toLowerCase(Locale.ROOT))
                .anyMatch(d -> d.endsWith(".xml") && (d.contains("ebinterface") || d.contains("xrechnung") || d.contains("rechnung")));
        String b = betreff == null ? "" : betreff.toLowerCase(Locale.ROOT);
        if (eRechnung || RECHNUNG.matcher(b).find()) {
            return Art.RECHNUNG;
        }
        return Art.DOKUMENT;
    }
}
