package at.postkorb.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Merkt sich bereits vollständig abgeholte Zustellungs-IDs (eine pro Zeile),
 * damit nichts doppelt heruntergeladen wird – auch wenn die Bestätigung fehlschlug.
 */
public final class ProcessedStore {

    private final Path file;
    private final Set<String> ids = new HashSet<>();

    public ProcessedStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    ids.add(line.strip());
                }
            }
        }
    }

    public boolean contains(String id) {
        return ids.contains(id);
    }

    public void add(String id) throws IOException {
        if (ids.add(id)) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, id + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        }
    }
}
