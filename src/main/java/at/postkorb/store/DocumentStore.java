package at.postkorb.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import at.postkorb.gateway.Anhang;
import at.postkorb.gateway.Zustellung;

/**
 * Legt jede Zustellung in einem eigenen Ordner ab:
 * <pre>
 * &lt;output.dir&gt;\2026-09-25_Finanzamt Österreich_&lt;id&gt;\
 *     Bescheid.pdf
 *     zustellung.txt   (Metadaten)
 * </pre>
 * Es wird zuerst in einen temporären Ordner geschrieben und dieser erst am Ende umbenannt,
 * sodass nachgelagerte Systeme (ERP, DMS) nie halbfertige Zustellungen sehen.
 */
public final class DocumentStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private final Path root;

    public DocumentStore(Path root) {
        this.root = root;
    }

    @FunctionalInterface
    public interface ContentSource {
        InputStream open(Anhang anhang) throws IOException;
    }

    public Path store(Zustellung z, ContentSource source) throws IOException {
        Files.createDirectories(root);
        String folder = FileNames.sanitize(
                (z.eingang() != null ? DAY.format(z.eingang()) + "_" : "")
                        + (z.absender() != null ? z.absender() + "_" : "") + z.id(),
                z.id());
        Path target = root.resolve(folder);
        if (Files.exists(target)) {
            return target; // bereits vollständig gespeichert (z. B. nach fehlgeschlagener Bestätigung)
        }
        Path tmp = root.resolve(".tmp_" + FileNames.sanitize(z.id(), "zustellung"));
        deleteRecursively(tmp);
        Files.createDirectories(tmp);
        try {
            Set<String> used = new HashSet<>();
            StringBuilder meta = new StringBuilder()
                    .append("ID: ").append(z.id()).append('\n')
                    .append("Absender: ").append(nullToEmpty(z.absender())).append('\n')
                    .append("Betreff: ").append(nullToEmpty(z.betreff())).append('\n')
                    .append("Eingang: ").append(z.eingang() != null ? z.eingang() : "").append('\n');
            z.weitereAngaben().forEach((k, v) -> meta.append(k).append(": ").append(nullToEmpty(v)).append('\n'));
            meta.append("Anhänge:\n");
            int n = 0;
            for (Anhang a : z.anhaenge()) {
                n++;
                String name = unique(FileNames.sanitize(a.dateiname(), "anhang_" + n), used);
                MessageDigest md = a.pruefsumme() != null ? Pruefsumme.digest(a.pruefsummenAlgorithmus()) : null;
                long bytes;
                try (InputStream raw = source.open(a);
                        InputStream in = md != null ? new DigestInputStream(raw, md) : raw) {
                    bytes = Files.copy(in, tmp.resolve(name));
                }
                if (a.groesse() != null && a.groesse() != bytes) {
                    throw new IOException("Anhang '" + a.dateiname() + "' der Zustellung " + z.id() + " ist unvollständig: "
                            + bytes + " statt " + a.groesse() + " Bytes");
                }
                if (md != null && !Pruefsumme.stimmt(md.digest(), a.pruefsumme())) {
                    throw new IOException("Prüfsumme stimmt nicht für Anhang '" + a.dateiname() + "' der Zustellung " + z.id());
                }
                meta.append("  - ").append(name).append(" (").append(nullToEmpty(a.mimeType()))
                        .append(md != null ? ", Prüfsumme ok" : "").append(")\n");
            }
            Files.writeString(tmp.resolve("zustellung.txt"), meta.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            }
            return target;
        } catch (IOException | RuntimeException e) {
            deleteRecursively(tmp);
            throw e;
        }
    }

    private static String unique(String name, Set<String> used) {
        String candidate = name;
        int dot = name.lastIndexOf('.');
        for (int i = 2; !used.add(candidate.toLowerCase()) || candidate.equalsIgnoreCase("zustellung.txt"); i++) {
            candidate = dot > 0 ? name.substring(0, dot) + " (" + i + ")" + name.substring(dot) : name + " (" + i + ")";
        }
        return candidate;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(x);
            }
        }
    }
}
