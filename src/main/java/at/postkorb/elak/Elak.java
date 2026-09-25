package at.postkorb.elak;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Die Funktionen des ELAK, die für die Übergabe gebraucht werden (zum Testen austauschbar). */
public interface Elak extends AutoCloseable {

    void login(String benutzer, String mandant, char[] passwort) throws IOException;

    List<ElakClient.Eintrag> mappentypen() throws IOException;

    ElakClient.MappentypInfo beschreibe(String mappentyp, String id) throws IOException;

    List<ElakClient.Eintrag> workflows() throws IOException;

    String mappeAnlegen(String mappentyp, Map<String, String> feldwerte, List<ElakClient.Datei> dateien) throws IOException;

    void workflowStarten(String mappenId, String workflowId) throws IOException;

    @Override
    void close();
}
