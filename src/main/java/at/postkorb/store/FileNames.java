package at.postkorb.store;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Macht beliebige Texte zu gültigen Windows-Datei- und Ordnernamen. */
public final class FileNames {

    private static final Pattern INVALID = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1F]");
    private static final Set<String> RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    static final int MAX_LENGTH = 120;

    private FileNames() {
    }

    public static String sanitize(String name, String fallback) {
        String s = name == null ? "" : INVALID.matcher(name).replaceAll("_").strip();
        // Windows entfernt Punkte/Leerzeichen am Ende stillschweigend
        s = s.replaceAll("[. ]+$", "");
        if (s.isEmpty()) {
            s = fallback;
        }
        String stem = s.contains(".") ? s.substring(0, s.indexOf('.')) : s;
        if (RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
            s = "_" + s;
        }
        if (s.length() > MAX_LENGTH) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 10 ? s.substring(dot) : "";
            s = s.substring(0, MAX_LENGTH - ext.length()) + ext;
        }
        return s;
    }
}
