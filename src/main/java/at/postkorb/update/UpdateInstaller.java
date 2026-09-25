package at.postkorb.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tauscht unter Windows die laufende JAR-Datei aus. Da Windows eine geöffnete JAR sperrt, erledigt das
 * ein Hilfsskript nach dem Beenden des Programms:
 * <ol>
 *   <li>warten, bis die alte JAR frei ist, und sie als .bak sichern</li>
 *   <li>neue JAR einsetzen und das Programm im Infobereich starten</li>
 *   <li>startet die neue Version nicht innerhalb von 90 Sekunden (keine Erfolgsmarke),
 *       wird die alte wiederhergestellt und gestartet</li>
 * </ol>
 */
public final class UpdateInstaller {

    private UpdateInstaller() {
    }

    /** Datei, die eine neu gestartete Version anlegt, um den erfolgreichen Start zu bestätigen. */
    public static Path erfolgsMarke(Path updateDir, String version) {
        return updateDir.resolve("ok-" + version);
    }

    /**
     * Entfernt Überbleibsel früherer Updates: Erfolgsmarken anderer Versionen und heruntergeladene
     * JARs, die nicht neuer als die laufende Version sind. Die Marke der laufenden Version bleibt,
     * weil update.cmd nach dem Start noch bis zu 90 Sekunden darauf wartet.
     */
    public static void aufraeumen(Path updateDir, String laufendeVersion) throws IOException {
        if (!Files.isDirectory(updateDir)) {
            return;
        }
        Version aktuell = Version.of(laufendeVersion);
        try (var dateien = Files.list(updateDir)) {
            for (Path p : dateien.toList()) {
                String n = p.getFileName().toString();
                boolean alteMarke = n.startsWith("ok-") && !n.equals("ok-" + laufendeVersion);
                boolean alteJar = n.startsWith("postkorb-schnittstelle-") && n.endsWith(".jar")
                        && !Version.of(n.substring("postkorb-schnittstelle-".length(), n.length() - 4)).istNeuerAls(aktuell);
                if (alteMarke || alteJar) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    static String skript(Path jar, Path neu, Path javaw, Path config, Path marke, Path log) {
        return String.join("\r\n",
                "@echo off",
                "rem Automatisch erzeugt von der Postkorb-Schnittstelle (Update-Installation)",
                "setlocal",
                "set \"JAR=" + jar + "\"",
                "set \"NEU=" + neu + "\"",
                "set \"JAVAW=" + javaw + "\"",
                "set \"CFG=" + config + "\"",
                "set \"OK=" + marke + "\"",
                "set \"LOG=" + log + "\"",
                "del \"%OK%\" >nul 2>&1",
                "echo %date% %time% Update auf %NEU% gestartet>>\"%LOG%\"",
                "set /a n=0",
                ":warte",
                "move /y \"%JAR%\" \"%JAR%.bak\" >nul 2>&1",
                "if not errorlevel 1 goto ersetzen",
                "set /a n+=1",
                "if %n% geq 60 goto abbruch",
                "ping -n 2 127.0.0.1 >nul",
                "goto warte",
                ":ersetzen",
                "copy /y \"%NEU%\" \"%JAR%\" >nul",
                "if errorlevel 1 goto zurueck",
                "start \"\" \"%JAVAW%\" -jar \"%JAR%\" --config \"%CFG%\" --tray",
                "set /a n=0",
                ":pruefe",
                "if exist \"%OK%\" goto erfolg",
                "set /a n+=1",
                "if %n% geq 90 goto zurueck",
                "ping -n 2 127.0.0.1 >nul",
                "goto pruefe",
                ":zurueck",
                "echo %date% %time% Neue Version startet nicht - alte Version wird wiederhergestellt>>\"%LOG%\"",
                "copy /y \"%JAR%.bak\" \"%JAR%\" >nul",
                "start \"\" \"%JAVAW%\" -jar \"%JAR%\" --config \"%CFG%\" --tray",
                "goto ende",
                ":abbruch",
                "echo %date% %time% Alte Version blockiert die Datei - Update abgebrochen>>\"%LOG%\"",
                "start \"\" \"%JAVAW%\" -jar \"%JAR%\" --config \"%CFG%\" --tray",
                "goto ende",
                ":erfolg",
                "echo %date% %time% Update erfolgreich>>\"%LOG%\"",
                "del \"%OK%\" >nul 2>&1",
                ":ende",
                "endlocal",
                "");
    }

    /**
     * Schreibt das Hilfsskript und startet es losgelöst (minimiert). Der Aufrufer muss sich danach beenden.
     */
    public static void starte(Path jar, Path neu, Path javaw, Path config, Path updateDir, String version) throws IOException {
        Path cmd = updateDir.resolve("update.cmd");
        Files.writeString(cmd, skript(jar, neu, javaw, config, erfolgsMarke(updateDir, version),
                updateDir.resolve("update.log")), StandardCharsets.ISO_8859_1);
        new ProcessBuilder("cmd.exe", "/c", "start", "\"PostkorbUpdate\"", "/min", "cmd.exe", "/c", cmd.toString())
                .directory(jar.getParent().toFile())
                .start();
    }
}
