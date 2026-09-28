package at.postkorb.update;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkriptAktualisierungTest {

    @TempDir
    Path app;

    @Test
    void alleSkripteSindEingepacktUndBekannt() throws IOException {
        try (Stream<Path> s = Files.list(Path.of("windows"))) {
            for (Path f : s.toList()) {
                String n = f.getFileName().toString();
                assertTrue(SkriptAktualisierung.SKRIPTE.containsKey(n), n + " fehlt in SkriptAktualisierung.SKRIPTE");
                assertTrue(getClass().getResource("/app-skripte/" + n) != null, n + " ist nicht in der JAR");
            }
        }
    }

    @Test
    void aktualisiertVeralteteUndLegtFehlendePflichtskripteAn() throws IOException {
        Files.writeString(app.resolve("infobereich-einrichten.ps1"), "alte Version");
        Files.writeString(app.resolve("java-einrichten.ps1"), "alte Version");
        Files.writeString(app.resolve("config.properties"), "bleibt");

        List<String> geaendert = SkriptAktualisierung.aktualisieren(app);

        assertEquals(List.of("infobereich-einrichten.ps1", "java-einrichten.ps1", "postkorb-infobereich.cmd", "postkorb.cmd"),
                geaendert);
        assertArrayEquals(Files.readAllBytes(Path.of("windows/infobereich-einrichten.ps1")),
                Files.readAllBytes(app.resolve("infobereich-einrichten.ps1")));
        assertFalse(Files.exists(app.resolve("aufgabe-einrichten.ps1")), "gelöschtes optionales Skript nicht wieder anlegen");
        assertEquals("bleibt", Files.readString(app.resolve("config.properties")));
        assertTrue(Files.readString(app.resolve("infobereich-einrichten.ps1"), java.nio.charset.StandardCharsets.UTF_8)
                .contains("POSTKORB_ELAK_PASSWORD"));

        assertEquals(List.of(), SkriptAktualisierung.aktualisieren(app), "zweiter Start ändert nichts");
    }
}
