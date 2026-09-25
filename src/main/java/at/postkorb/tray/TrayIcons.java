package at.postkorb.tray;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

/** Zeichnet das Symbol (Briefumschlag) in der Farbe des Zustands, in mehreren Auflösungen für hohe DPI. */
final class TrayIcons {

    private static final int[] GROESSEN = {16, 20, 24, 32, 40, 48, 64};
    private static final Map<TrayController.Zustand, Color> FARBEN = new EnumMap<>(Map.of(
            TrayController.Zustand.OK, new Color(0x2E7D32),
            TrayController.Zustand.NEUE_POST, new Color(0x1565C0),
            TrayController.Zustand.LAEUFT, new Color(0x607D8B),
            TrayController.Zustand.WARNUNG, new Color(0xEF6C00),
            TrayController.Zustand.FEHLER, new Color(0xC62828)));

    private TrayIcons() {
    }

    static Image fuer(TrayController.Zustand zustand) {
        BufferedImage[] bilder = new BufferedImage[GROESSEN.length];
        for (int i = 0; i < GROESSEN.length; i++) {
            bilder[i] = zeichne(GROESSEN[i], FARBEN.get(zustand));
        }
        return new BaseMultiResolutionImage(bilder);
    }

    static BufferedImage zeichne(int s, Color farbe) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            float r = s * 0.22f;
            g.setColor(farbe);
            g.fill(new java.awt.geom.RoundRectangle2D.Float(0, 0, s, s, r, r));
            // Briefumschlag
            float m = s * 0.18f;
            float top = s * 0.28f;
            float w = s - 2 * m;
            float h = s * 0.46f;
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(Math.max(1f, s / 12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new java.awt.geom.Rectangle2D.Float(m, top, w, h));
            java.awt.geom.Path2D.Float klappe = new java.awt.geom.Path2D.Float();
            klappe.moveTo(m, top);
            klappe.lineTo(s / 2f, top + h * 0.6f);
            klappe.lineTo(m + w, top);
            g.draw(klappe);
        } finally {
            g.dispose();
        }
        return img;
    }
}
