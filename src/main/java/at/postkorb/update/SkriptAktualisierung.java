package at.postkorb.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Das automatische Update tauscht nur die JAR-Datei aus. Damit auch die Windows-Skripte aktuell bleiben,
 * liegen sie in der JAR ({@code /app-skripte/}) und werden beim Start in den Programmordner geschrieben,
 * wenn sie sich unterscheiden. Konfiguration, Zertifikat und Eingang werden nicht angefasst.
 */
public final class SkriptAktualisierung {

    private static final Logger LOG = Logger.getLogger(SkriptAktualisierung.class.getName());

    /** Skript → true, wenn es immer vorhanden sein soll; false: nur aktualisieren, falls der Benutzer es behalten hat. */
    static final Map<String, Boolean> SKRIPTE = Map.of(
            "postkorb.cmd", true,
            "postkorb-infobereich.cmd", true,
            "infobereich-einrichten.ps1", true,
            "java-einrichten.ps1", false,
            "aufgabe-einrichten.ps1", false);

    private SkriptAktualisierung() {
    }

    /** @return die Namen der geschriebenen Skripte */
    public static List<String> aktualisieren(Path programmOrdner) throws IOException {
        List<String> geaendert = new ArrayList<>();
        for (String name : SKRIPTE.keySet().stream().sorted().toList()) {
            byte[] neu;
            try (InputStream in = SkriptAktualisierung.class.getResourceAsStream("/app-skripte/" + name)) {
                if (in == null) {
                    continue; // z. B. beim Start aus der Entwicklungsumgebung
                }
                neu = in.readAllBytes();
            }
            Path ziel = programmOrdner.resolve(name);
            boolean vorhanden = Files.exists(ziel);
            if (!vorhanden && !SKRIPTE.get(name)) {
                continue; // absichtlich gelöscht – nicht wieder anlegen
            }
            if (vorhanden && Arrays.equals(Files.readAllBytes(ziel), neu)) {
                continue;
            }
            Path tmp = programmOrdner.resolve(name + ".neu");
            Files.write(tmp, neu);
            Files.move(tmp, ziel, StandardCopyOption.REPLACE_EXISTING);
            geaendert.add(name);
        }
        if (!geaendert.isEmpty()) {
            LOG.info(() -> "Skripte aktualisiert: " + geaendert);
        }
        return geaendert;
    }
}
