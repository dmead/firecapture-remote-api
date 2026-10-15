package remoteapi;

import static remoteapi.Api.*;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.wonderplanets.firecapture.capture.Capture;
import de.wonderplanets.firecapture.image.ImageData;

/** /v1/target and /v1/overlay — where the target is now, and where it should go. */
final class OverlayEndpoints {
    private static final List<String> SHAPES = List.of("planet", "circle", "saturn", "sun", "moon", "cross");
    private static final Map<String, Color> COLORS = Map.of(
            "green", new Color(0x39ff14), "red", new Color(0xff3b3b), "yellow", new Color(0xffe14d),
            "cyan", new Color(0x3be8ff), "magenta", new Color(0xff4dff), "orange", new Color(0xff9f1a),
            "white", Color.WHITE);

    private OverlayEndpoints() { }

    private static BufferedImage currentImage() {
        ImageData d = Capture.getInstance().currentData;
        BufferedImage img = d == null ? null : d.getImage();
        if (img == null) throw new ApiException(503, "no frame yet");
        return img;
    }

    private static int[] roiOffset() {
        Rectangle r = (Rectangle) safe(() -> cam().getROI());
        return r == null ? new int[] {0, 0} : new int[] {r.x, r.y};
    }

    /** Detected target in the current frame (frame px), plus its sensor position. */
    static Map<String, Object> target() {
        BufferedImage img = currentImage();
        TargetDetector.Target t = TargetDetector.detect(img);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frame", List.of(img.getWidth(), img.getHeight()));
        int[] off = roiOffset();
        m.put("roiOffset", List.of(off[0], off[1]));
        m.put("found", t != null);
        if (t != null) {
            m.put("target", t.toJson());
            m.put("sensor", List.of(Math.round((t.x + off[0]) * 10) / 10.0, Math.round((t.y + off[1]) * 10) / 10.0));
        }
        return m;
    }

    static Map<String, Object> overlay() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("markers", Overlay.json());
        return m;
    }

    /**
     * Add a marker. Position: {x, y} (frame px; "coords": "sensor" for sensor px) or {dx, dy} relative to the
     * detected target, which also draws the path from where it is now.
     * Optional: shape, radius (frame px; default = detected target), label, color, angleDeg + ringRatio (saturn), ttlS.
     */
    static Map<String, Object> addMarker(Map<String, Object> req) throws Exception {
        allowOnly(req, "shape", "x", "y", "dx", "dy", "coords", "radius", "label", "color", "angleDeg", "ringRatio", "ttlS", "path");
        String shape = req.containsKey("shape") ? str(req.get("shape"), "shape").toLowerCase() : "planet";
        if (!SHAPES.contains(shape)) throw new ApiException(400, "shape must be one of " + SHAPES);
        if (shape.equals("circle")) shape = "planet";

        boolean abs = req.containsKey("x") || req.containsKey("y");
        boolean rel = req.containsKey("dx") || req.containsKey("dy");
        if (abs == rel) throw new ApiException(400, "give either x and y (absolute) or dx and dy (relative to the detected target)");

        TargetDetector.Target t = null;
        try { t = TargetDetector.detect(currentImage()); } catch (ApiException e) { if (rel) throw e; }
        double x, y;
        double[] from = null;
        if (rel) {
            if (t == null) throw new ApiException(409, "no target detected in the current frame; use absolute x and y");
            x = t.x + num(req.getOrDefault("dx", 0), "dx").doubleValue();
            y = t.y + num(req.getOrDefault("dy", 0), "dy").doubleValue();
            boolean path = !req.containsKey("path") || bool(req.get("path"), "path");
            if (path) from = new double[] {t.x, t.y};
        } else {
            if (!req.containsKey("x") || !req.containsKey("y")) throw new ApiException(400, "absolute position needs both x and y");
            x = num(req.get("x"), "x").doubleValue();
            y = num(req.get("y"), "y").doubleValue();
            String coords = req.containsKey("coords") ? str(req.get("coords"), "coords") : "frame";
            if (coords.equals("sensor")) { int[] off = roiOffset(); x -= off[0]; y -= off[1]; }
            else if (!coords.equals("frame")) throw new ApiException(400, "coords must be frame or sensor");
            if (Boolean.TRUE.equals(req.get("path")) && t != null) from = new double[] {t.x, t.y};
        }

        double radius;
        if (req.containsKey("radius")) {
            radius = num(req.get("radius"), "radius").doubleValue();
            if (radius <= 0) throw new ApiException(400, "radius must be positive");
        } else radius = t != null ? Math.max(4, t.radius()) : 40;

        Color color = parseColor(req.get("color"));
        double angle = req.containsKey("angleDeg") ? num(req.get("angleDeg"), "angleDeg").doubleValue() : 0;
        double ratio = req.containsKey("ringRatio") ? num(req.get("ringRatio"), "ringRatio").doubleValue() : 0.35;
        if (ratio <= 0 || ratio > 1) throw new ApiException(400, "ringRatio must be 0..1");
        double ttl = req.containsKey("ttlS") ? num(req.get("ttlS"), "ttlS").doubleValue() : 0;
        String label = req.containsKey("label") ? String.valueOf(req.get("label")) : null;

        Overlay.Marker mk = Overlay.add(shape, x, y, radius, angle, ratio, label, color, from, ttl);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("marker", mk.toJson());
        if (t != null) m.put("currentTarget", t.toJson());
        return m;
    }

    /** DELETE /v1/overlay (all) or /v1/overlay?id=N */
    static Map<String, Object> delete(String query) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (query != null && query.startsWith("id=")) {
            int id;
            try { id = Integer.parseInt(query.substring(3)); } catch (NumberFormatException e) { throw new ApiException(400, "id must be a number"); }
            if (!Overlay.remove(id)) throw new ApiException(404, "no marker " + id);
            m.put("removed", id);
        } else m.put("removed", Overlay.clear());
        m.put("markers", Overlay.json());
        return m;
    }

    private static Color parseColor(Object o) {
        if (o == null) return COLORS.get("green");
        String s = str(o, "color").toLowerCase();
        if (COLORS.containsKey(s)) return COLORS.get(s);
        if (s.matches("#[0-9a-f]{6}")) return new Color(Integer.parseInt(s.substring(1), 16));
        throw new ApiException(400, "color must be #rrggbb or one of " + COLORS.keySet());
    }
}
