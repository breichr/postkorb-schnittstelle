package at.postkorb.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FileNamesTest {

    @Test
    void ersetztUngueltigeZeichen() {
        assertEquals("Bescheid_2026_ Finanzamt_.pdf", FileNames.sanitize("Bescheid/2026: Finanzamt?.pdf", "x"));
    }

    @Test
    void entferntPunkteUndLeerzeichenAmEnde() {
        assertEquals("Brief", FileNames.sanitize("Brief. . ", "x"));
    }

    @Test
    void ergaenztEndungAusMimeTyp() {
        assertEquals("mailbody.txt", FileNames.withExtension("mailbody", "text/plain"));
        assertEquals("mailbody.html", FileNames.withExtension("mailbody", "text/html; charset=UTF-8"));
        assertEquals("Bescheid.pdf", FileNames.withExtension("Bescheid.pdf", "application/pdf"));
        assertEquals("daten", FileNames.withExtension("daten", "application/octet-stream"));
    }

    @Test
    void reservierteNamen() {
        assertEquals("_CON.pdf", FileNames.sanitize("CON.pdf", "x"));
        assertEquals("_nul", FileNames.sanitize("nul", "x"));
    }

    @Test
    void fallbackBeiLeeremNamen() {
        assertEquals("anhang_1", FileNames.sanitize("  ", "anhang_1"));
        assertEquals("anhang_1", FileNames.sanitize(null, "anhang_1"));
    }

    @Test
    void kuerztLangeNamenUndBehaeltEndung() {
        String s = FileNames.sanitize("a".repeat(300) + ".pdf", "x");
        assertEquals(FileNames.MAX_LENGTH, s.length());
        assertTrue(s.endsWith(".pdf"));
    }
}
