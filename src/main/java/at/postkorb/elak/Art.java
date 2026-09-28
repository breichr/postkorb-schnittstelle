package at.postkorb.elak;

/** Wohin ein Anhang im ELAK soll. */
public enum Art {
    DOKUMENT("Dokument"),
    RECHNUNG("Rechnung"),
    KEINE("nicht übernehmen");

    private final String text;

    Art(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
