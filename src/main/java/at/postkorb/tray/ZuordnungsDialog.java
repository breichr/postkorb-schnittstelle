package at.postkorb.tray;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

import at.postkorb.elak.Art;
import at.postkorb.elak.Vorschlag;
import at.postkorb.elak.Warteliste;

/**
 * Fenster zur Zuordnung: pro Anhang Dokument, Rechnung oder nicht übernehmen, mit Vorschlag.
 * "Später" lässt alles in der Warteliste.
 */
final class ZuordnungsDialog {

    private static final Logger LOG = Logger.getLogger(ZuordnungsDialog.class.getName());
    private static JDialog offen;

    private ZuordnungsDialog() {
    }

    /** Zeigt das Fenster (oder holt ein bereits offenes nach vorne). Muss nicht auf dem EDT aufgerufen werden. */
    static void zeigen(List<Warteliste.Eintrag> eintraege, Consumer<Map<String, Map<String, Art>>> uebernehmen) {
        SwingUtilities.invokeLater(() -> {
            if (offen != null && offen.isShowing()) {
                offen.toFront();
                return;
            }
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Standard-Aussehen
            }
            offen = baue(eintraege, uebernehmen);
            offen.setVisible(true);
        });
    }

    private static JDialog baue(List<Warteliste.Eintrag> eintraege, Consumer<Map<String, Map<String, Art>>> uebernehmen) {
        JDialog d = new JDialog((java.awt.Frame) null, "USP Postkorb – Zuordnung für den ELAK", false);
        d.setAlwaysOnTop(true);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        Map<String, Map<String, ButtonGroup>> auswahl = new LinkedHashMap<>();
        Map<ButtonGroup, Map<javax.swing.ButtonModel, Art>> werte = new LinkedHashMap<>();

        JPanel liste = new JPanel();
        liste.setLayout(new BoxLayout(liste, BoxLayout.Y_AXIS));
        for (Warteliste.Eintrag e : eintraege) {
            JPanel p = new JPanel(new GridBagLayout());
            String rsa = e.zustellqualitaet() != null && e.zustellqualitaet().startsWith("RSa") ? "  [" + e.zustellqualitaet() + "]" : "";
            p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6),
                    BorderFactory.createTitledBorder(nz(e.absender()) + rsa)));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(2, 6, 2, 6);
            c.anchor = GridBagConstraints.WEST;
            c.gridy = 0;
            c.gridx = 0;
            c.gridwidth = 5;
            JLabel betreff = new JLabel("<html><b>" + html(e.betreff()) + "</b>"
                    + (e.geschaeftszahl() != null ? "<br>GZ " + html(e.geschaeftszahl()) : "") + "</html>");
            p.add(betreff, c);
            c.gridwidth = 1;
            c.gridy++;
            for (String kopf : new String[] {"Anhang", "", "Dokument", "Rechnung", "nicht übernehmen"}) {
                JLabel l = new JLabel(kopf);
                l.setFont(l.getFont().deriveFont(Font.ITALIC));
                l.setForeground(Color.GRAY);
                p.add(l, c);
                c.gridx++;
            }
            List<String> alle = e.dateien().stream().map(Warteliste.Datei::name).toList();
            Map<String, ButtonGroup> proDatei = new LinkedHashMap<>();
            for (Warteliste.Datei datei : e.dateien()) {
                if (datei.zugeordnet()) {
                    continue;
                }
                c.gridy++;
                c.gridx = 0;
                p.add(new JLabel(datei.name()), c);
                c.gridx++;
                JButton oeffnen = new JButton("öffnen");
                oeffnen.addActionListener(a -> oeffne(e.ordner().resolve(datei.name())));
                p.add(oeffnen, c);
                ButtonGroup g = new ButtonGroup();
                Map<javax.swing.ButtonModel, Art> modelle = new LinkedHashMap<>();
                Art vorschlag = Vorschlag.fuer(datei.name(), e.betreff(), alle);
                for (Art a : Art.values()) {
                    c.gridx++;
                    JRadioButton rb = new JRadioButton();
                    rb.setToolTipText(a.text());
                    rb.setSelected(a == vorschlag);
                    g.add(rb);
                    modelle.put(rb.getModel(), a);
                    p.add(rb, c);
                }
                proDatei.put(datei.name(), g);
                werte.put(g, modelle);
            }
            auswahl.put(e.id(), proDatei);
            liste.add(p);
        }

        JButton ok = new JButton("Übernehmen");
        JButton spaeter = new JButton("Später");
        ok.addActionListener(a -> {
            Map<String, Map<String, Art>> ergebnis = new LinkedHashMap<>();
            auswahl.forEach((id, dateien) -> {
                Map<String, Art> m = new LinkedHashMap<>();
                dateien.forEach((name, g) -> {
                    Art art = werte.get(g).get(g.getSelection());
                    if (art != null) {
                        m.put(name, art);
                    }
                });
                ergebnis.put(id, m);
            });
            d.dispose();
            uebernehmen.accept(ergebnis);
        });
        spaeter.addActionListener(a -> d.dispose());

        JPanel knoepfe = new JPanel();
        knoepfe.add(ok);
        knoepfe.add(Box.createHorizontalStrut(12));
        knoepfe.add(spaeter);
        JLabel hinweis = new JLabel("  Pro PDF wird eine eigene Mappe angelegt. Vorschläge bitte prüfen.");
        hinweis.setBorder(BorderFactory.createEmptyBorder(8, 4, 0, 4));

        d.getContentPane().setLayout(new BorderLayout());
        d.getContentPane().add(hinweis, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(liste);
        scroll.setBorder(null);
        d.getContentPane().add(scroll, BorderLayout.CENTER);
        d.getContentPane().add(knoepfe, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(ok);
        d.pack();
        Dimension s = d.getSize();
        d.setSize(Math.min(Math.max(s.width, 560), 900), Math.min(s.height, 700));
        d.setLocationRelativeTo(null);
        return d;
    }

    private static void oeffne(java.nio.file.Path p) {
        try {
            Desktop.getDesktop().open(p.toFile());
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Konnte nicht geöffnet werden: " + p, e);
        }
    }

    private static String nz(String s) {
        return s == null ? "Unbekannter Absender" : s;
    }

    private static String html(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
