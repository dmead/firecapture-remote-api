package remoteapi;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import de.wonderplanets.firecapture.gui.FireCapture;
import de.wonderplanets.firecapture.gui.PanelLiveView;

/**
 * Guide markers drawn over FireCapture's live view: "the target should end up here". Markers are kept in frame
 * pixel coordinates and mapped through the live view's own scaling, so they stay on the right spot at any zoom.
 * When a marker's position is outside the visible part of the image, an arrow at the edge points toward it.
 *
 * The overlay is a mouse-transparent component in the window's JLayeredPane, above FireCapture's content, so it never
 * touches FireCapture's own painting or input handling.
 */
final class Overlay {

    static final class Marker {
        final int id;
        final String shape;            // planet | saturn | sun | moon | cross
        final double x, y;             // frame px
        final double radius;           // frame px
        final double angleDeg;         // saturn ring angle (screen, clockwise from horizontal)
        final double ringRatio;        // saturn ring minor/major axis
        final String label;
        final Color color;
        final double[] from;           // optional path start (frame px)
        final long expiresAt;          // 0 = never

        Marker(int id, String shape, double x, double y, double radius, double angleDeg, double ringRatio,
               String label, Color color, double[] from, long expiresAt) {
            this.id = id; this.shape = shape; this.x = x; this.y = y; this.radius = radius; this.angleDeg = angleDeg;
            this.ringRatio = ringRatio; this.label = label; this.color = color; this.from = from; this.expiresAt = expiresAt;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("shape", shape);
            m.put("x", Math.round(x * 10) / 10.0);
            m.put("y", Math.round(y * 10) / 10.0);
            m.put("radius", Math.round(radius * 10) / 10.0);
            if (shape.equals("saturn")) { m.put("angleDeg", angleDeg); m.put("ringRatio", ringRatio); }
            m.put("label", label);
            m.put("color", String.format("#%06x", color.getRGB() & 0xffffff));
            if (from != null) m.put("from", List.of(Math.round(from[0] * 10) / 10.0, Math.round(from[1] * 10) / 10.0));
            if (expiresAt > 0) m.put("expiresInS", Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000));
            Boolean v = visible(this);
            m.put("inView", v);
            return m;
        }
    }

    private static final List<Marker> MARKERS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static Layer layer;              // EDT only
    private static Timer timer;              // EDT only

    private Overlay() { }

    static List<Marker> markers() { return MARKERS; }

    static Marker add(String shape, double x, double y, double radius, double angleDeg, double ringRatio,
                      String label, Color color, double[] from, double ttlS) throws Exception {
        long exp = ttlS > 0 ? System.currentTimeMillis() + (long) (ttlS * 1000) : 0;
        Marker m = new Marker(IDS.incrementAndGet(), shape, x, y, radius, angleDeg, ringRatio, label, color, from, exp);
        MARKERS.add(m);
        Api.onEdt(() -> { ensureInstalled(); return null; });
        repaint();
        return m;
    }

    static boolean remove(int id) {
        boolean r = MARKERS.removeIf(m -> m.id == id);
        repaint();
        return r;
    }

    static int clear() {
        int n = MARKERS.size();
        MARKERS.clear();
        repaint();
        return n;
    }

    private static void repaint() {
        SwingUtilities.invokeLater(() -> { if (layer != null) layer.repaint(); });
    }

    // ---------------------------------------------------------------- live view geometry

    private static PanelLiveView liveView() {
        FireCapture fc = FireCapture.INSTANCE;
        return fc == null ? null : fc.getPanelLiveView();
    }

    private static Method scaledMethod;

    /** Frame px -> live view px factor, from FireCapture's own scaled(int) so we match its drawing exactly. */
    private static double scaleFactor(PanelLiveView lv) {
        try {
            if (scaledMethod == null) {
                scaledMethod = PanelLiveView.class.getDeclaredMethod("scaled", int.class);
                scaledMethod.setAccessible(true);
            }
            return ((Integer) scaledMethod.invoke(lv, 1_000_000)) / 1_000_000.0;
        } catch (Exception e) {
            return lv.getScale();
        }
    }

    /** Frame size in px (the displayed image), or null before the first frame. */
    private static Rectangle frameBox(PanelLiveView lv) {
        Rectangle r = lv.getImgBox();
        return r == null || r.width <= 0 ? null : r;
    }

    /** null if the live view isn't available; else whether the marker centre is in the visible image area. */
    static Boolean visible(Marker m) {
        try {
            return Api.onEdt(() -> {
                PanelLiveView lv = liveView();
                if (lv == null || frameBox(lv) == null) return null;
                Rectangle vis = visibleImageArea(lv);
                double f = scaleFactor(lv);
                return vis != null && vis.contains(m.x * f, m.y * f);
            });
        } catch (Exception e) {
            return null;
        }
    }

    /** Visible part of the image, in live view coordinates. */
    private static Rectangle visibleImageArea(PanelLiveView lv) {
        Rectangle box = frameBox(lv);
        if (box == null) return null;
        double f = scaleFactor(lv);
        Rectangle img = new Rectangle(0, 0, (int) (box.width * f), (int) (box.height * f));
        Rectangle vis = lv.getVisibleRect().intersection(img);
        return vis.isEmpty() ? null : vis;
    }

    // ---------------------------------------------------------------- installation

    /** Put the overlay layer into the live view's window (re-homes it if the preview was detached/re-attached). */
    private static void ensureInstalled() {
        PanelLiveView lv = liveView();
        if (lv == null) return;
        JRootPane root = SwingUtilities.getRootPane(lv);
        if (root == null) return;
        JLayeredPane lp = root.getLayeredPane();
        if (layer == null) layer = new Layer();
        if (layer.getParent() != lp) {
            if (layer.getParent() != null) layer.getParent().remove(layer);
            lp.add(layer, JLayeredPane.PALETTE_LAYER);
            layer.setBounds(0, 0, lp.getWidth(), lp.getHeight());
            lp.addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) {
                    if (layer.getParent() == lp) layer.setBounds(0, 0, lp.getWidth(), lp.getHeight());
                }
            });
        }
        if (timer == null) {
            timer = new Timer(250, e -> {
                long now = System.currentTimeMillis();
                MARKERS.removeIf(m -> m.expiresAt > 0 && m.expiresAt < now);
                if (!MARKERS.isEmpty()) ensureInstalled();
                if (layer != null) layer.repaint();
            });
            timer.start();
        }
    }

    // ---------------------------------------------------------------- painting

    private static final class Layer extends JComponent {
        Layer() { setOpaque(false); }

        @Override public boolean contains(int x, int y) { return false; }   // never takes mouse events

        @Override protected void paintComponent(Graphics g0) {
            if (MARKERS.isEmpty()) return;
            PanelLiveView lv = liveView();
            if (lv == null || !lv.isShowing() || frameBox(lv) == null) return;
            Rectangle visLv = visibleImageArea(lv);
            if (visLv == null) return;
            double f = scaleFactor(lv);
            Point o = SwingUtilities.convertPoint(lv, 0, 0, this);
            Rectangle vis = new Rectangle(visLv.x + o.x, visLv.y + o.y, visLv.width, visLv.height);
            Graphics2D g = (Graphics2D) g0.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.clip(vis);
                for (Marker m : MARKERS) {
                    double cx = o.x + m.x * f, cy = o.y + m.y * f, r = Math.max(4, m.radius * f);
                    if (m.from != null) drawPath(g, o.x + m.from[0] * f, o.y + m.from[1] * f, cx, cy, m.color, vis);
                    if (vis.contains(cx, cy)) drawMarker(g, m, cx, cy, r);
                    else drawEdgeArrow(g, m, cx, cy, vis, f);
                }
            } finally {
                g.dispose();
            }
        }
    }

    private static final Stroke DASH = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {8f, 6f}, 0f);
    private static final Stroke SOLID = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Stroke HALO = new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final Color SHADOW = new Color(0, 0, 0, 170);
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);

    /** Draw with a dark halo underneath so it reads on both black sky and a bright disc. */
    private static void stroke(Graphics2D g, Shape s, Color c, Stroke st) {
        g.setColor(SHADOW); g.setStroke(HALO); g.draw(s);
        g.setColor(c); g.setStroke(st); g.draw(s);
    }

    private static void drawMarker(Graphics2D g, Marker m, double cx, double cy, double r) {
        Graphics2D t = (Graphics2D) g.create();
        try {
            // faint fill so the destination reads as a "ghost" of the body
            t.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.12f));
            t.setColor(m.color);
            if (!m.shape.equals("cross")) t.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        } finally {
            t.dispose();
        }
        switch (m.shape) {
            case "saturn": {
                stroke(g, new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r), m.color, DASH);
                double a = r * 2.25, b = Math.max(2, a * m.ringRatio);
                Shape ring = new Ellipse2D.Double(cx - a, cy - b, 2 * a, 2 * b);
                ring = AffineTransform.getRotateInstance(Math.toRadians(m.angleDeg), cx, cy).createTransformedShape(ring);
                stroke(g, ring, m.color, DASH);
                break;
            }
            case "cross":
                break;
            case "sun":
            case "moon": {
                stroke(g, new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r), m.color, SOLID);
                double r2 = r * 0.92;
                stroke(g, new Ellipse2D.Double(cx - r2, cy - r2, 2 * r2, 2 * r2), m.color, DASH);
                break;
            }
            default:
                stroke(g, new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r), m.color, DASH);
        }
        double c = Math.max(6, Math.min(14, r * 0.25));        // centre cross
        stroke(g, new Line2D.Double(cx - c, cy, cx + c, cy), m.color, SOLID);
        stroke(g, new Line2D.Double(cx, cy - c, cx, cy + c), m.color, SOLID);
        if (m.label != null && !m.label.isEmpty()) label(g, m.label, cx, cy + r + 8, m.color, true);
    }

    private static void drawPath(Graphics2D g, double x0, double y0, double x1, double y1, Color c, Rectangle vis) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        if (len < 12) return;
        stroke(g, new Line2D.Double(x0, y0, x1, y1), c, DASH);
        // small arrowhead at the destination end (only if it's visible)
        if (vis.contains(x1, y1)) arrowHead(g, x1, y1, Math.atan2(y1 - y0, x1 - x0), 12, c);
    }

    private static void drawEdgeArrow(Graphics2D g, Marker m, double tx, double ty, Rectangle vis, double f) {
        double cx = vis.getCenterX(), cy = vis.getCenterY();
        double ang = Math.atan2(ty - cy, tx - cx);
        double inset = 34;
        // where the ray from the view centre toward the target leaves the (inset) visible area
        double hw = vis.width / 2.0 - inset, hh = vis.height / 2.0 - inset;
        double dx = Math.cos(ang), dy = Math.sin(ang);
        double s = Math.min(hw / Math.max(1e-9, Math.abs(dx)), hh / Math.max(1e-9, Math.abs(dy)));
        double ex = cx + dx * s, ey = cy + dy * s;
        double tail = 70;
        double sx = ex - dx * tail, sy = ey - dy * tail;
        stroke(g, new Line2D.Double(sx, sy, ex - dx * 10, ey - dy * 10), m.color, new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        arrowHead(g, ex, ey, ang, 24, m.color);
        // distance from the view edge in frame px and in "views" (multiples of the visible width/height)
        double offPx = Math.hypot(tx - ex, ty - ey) / f;
        double views = Math.hypot((tx - ex) / vis.width, (ty - ey) / vis.height);
        String txt = (m.label == null || m.label.isEmpty() ? m.shape : m.label)
                + String.format("  (%.0f px off view%s)", offPx, views >= 0.95 ? String.format(", ~%.1f views", views) : "");
        // label beside the shaft, on the side facing the view centre, so it never covers the arrow
        g.setFont(FONT);
        FontMetrics fm = g.getFontMetrics();
        double lw = fm.stringWidth(txt) + 12, lh = fm.getHeight() + 4;
        double mx = (sx + ex) / 2, my = (sy + ey) / 2, lx, ly;
        if (Math.abs(dx) >= Math.abs(dy)) {                     // mostly horizontal arrow: label above/below the shaft
            lx = mx - dx * lw / 2;
            ly = my + (my <= cy ? 1 : -1) * (lh / 2 + 16);
        } else {                                                // mostly vertical arrow: label left/right of the shaft
            lx = mx + (mx <= cx ? 1 : -1) * (lw / 2 + 16);
            ly = my - dy * lh;
        }
        label(g, txt, lx, ly, m.color, false);
    }

    private static void arrowHead(Graphics2D g, double x, double y, double ang, double size, Color c) {
        Path2D p = new Path2D.Double();
        p.moveTo(x, y);
        p.lineTo(x - size * Math.cos(ang - 0.45), y - size * Math.sin(ang - 0.45));
        p.lineTo(x - size * Math.cos(ang + 0.45), y - size * Math.sin(ang + 0.45));
        p.closePath();
        g.setColor(SHADOW); g.setStroke(HALO); g.draw(p);
        g.setColor(c); g.fill(p);
    }

    /** Label in a dark box; anchored below (x centred) or centred on (x, y). Kept inside the clip. */
    private static void label(Graphics2D g, String text, double x, double y, Color c, boolean below) {
        g.setFont(FONT);
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(text) + 12, h = fm.getHeight() + 4;
        int bx = (int) (x - w / 2.0), by = (int) (below ? y : y - h / 2.0);
        Rectangle clip = g.getClipBounds();
        if (clip != null) {
            bx = Math.max(clip.x + 2, Math.min(clip.x + clip.width - w - 2, bx));
            by = Math.max(clip.y + 2, Math.min(clip.y + clip.height - h - 2, by));
        }
        g.setColor(SHADOW);
        g.fillRoundRect(bx, by, w, h, 8, 8);
        g.setColor(c);
        g.drawString(text, bx + 6, by + fm.getAscent() + 2);
    }

    static List<Object> json() {
        List<Object> l = new ArrayList<>();
        for (Marker m : MARKERS) l.add(m.toJson());
        return l;
    }
}
