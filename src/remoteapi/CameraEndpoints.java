package remoteapi;

import static remoteapi.Api.*;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

import de.wonderplanets.firecam.IFireCam;
import de.wonderplanets.firecam.IFireCam.Control;
import de.wonderplanets.firecapture.capture.Capture;
import de.wonderplanets.firecapture.gui.CamWorker;
import de.wonderplanets.firecapture.image.ImageData;

/** /v1/camera... */
final class CameraEndpoints {
    private CameraEndpoints() { }

    static Map<String, Object> camera() throws Exception {
        IFireCam cam = cam();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", cam.getName());
        m.put("color", cam.isColorCam());
        m.put("temperatureC", safe(cam::getTemperature));
        ImageData d = Capture.getInstance().currentData;
        if (d != null && d.camInfo != null) {
            m.put("sensor", d.camInfo.maxImageSize == null ? null : List.of(d.camInfo.maxImageSize.width, d.camInfo.maxImageSize.height));
            m.put("pixelSizeUm", d.camInfo.pixelSize);
            m.put("bitDepth", d.geBitDepth());
        }
        m.put("roi", rect(cam.getROI()));
        m.put("gain", range(cam.getGain(), cam.getGainMin(), cam.getGainMax()));
        m.put("exposureMs", range(cam.getShutterInMs(), cam.getShutterInMsMin(), cam.getShutterInMsMax()));
        m.put("timebase", String.valueOf(cam.getTimebase()));
        return m;
    }

    /** {"gain": 600, "exposureMs": 5.9, "roi": [x, y, w, h]} — any subset; applied through the GUI controls. */
    static Map<String, Object> putCamera(Map<String, Object> req) throws Exception {
        allowOnly(req, "gain", "exposureMs", "roi");
        IFireCam cam = cam();
        if (req.containsKey("roi")) {
            notRecording("change the ROI");
            Rectangle roi = parseRect(req.get("roi"));
            onEdt(() -> {
                CamWorker.INSTANCE.setROI(roi);
                panel().updateROIFields(roi);
                return null;
            });
        }
        if (req.containsKey("exposureMs")) {
            float ms = num(req.get("exposureMs"), "exposureMs").floatValue();
            if (ms < cam.getShutterInMsMin() || ms > cam.getShutterInMsMax())
                throw new ApiException(400, "exposureMs out of range " + cam.getShutterInMsMin() + ".." + cam.getShutterInMsMax());
            onEdt(() -> { panel().setShutterInMS(ms); return null; });
        }
        if (req.containsKey("gain")) setGain(cam, num(req.get("gain"), "gain").intValue());
        sleep(300);   // let CamWorker apply the queued commands before reading back
        Map<String, Object> result = camera();
        List<String> warnings = new ArrayList<>();
        if (req.containsKey("roi") && !rect(parseRect(req.get("roi"))).equals(result.get("roi")))
            warnings.add("ROI not applied by the camera (requested " + req.get("roi") + ")");
        if (req.containsKey("gain") && num(req.get("gain"), "gain").intValue() != cam.getGain())
            warnings.add("gain reads back as " + cam.getGain());
        if (req.containsKey("exposureMs") && Math.abs(num(req.get("exposureMs"), "exposureMs").floatValue() - cam.getShutterInMs()) > 0.01)
            warnings.add("exposure quantized to " + cam.getShutterInMs() + " ms");
        if (!warnings.isEmpty()) result.put("warnings", warnings);
        return result;
    }

    private static void setGain(IFireCam cam, int g) throws Exception {
        if (g < cam.getGainMin() || g > cam.getGainMax())
            throw new ApiException(400, "gain out of range " + cam.getGainMin() + ".." + cam.getGainMax());
        onEdt(() -> { panel().getPanelGain().getSlider().setValue(g); return null; });
    }

    // ---------------------------------------------------------------- generic controls

    private static List<Control> supported(IFireCam cam) {
        Set<Control> s = new LinkedHashSet<>();
        if (cam.getControls() != null) s.addAll(cam.getControls());
        if (cam.getAdditionalControls() != null) s.addAll(cam.getAdditionalControls());
        List<Control> l = new ArrayList<>();
        for (Control c : s) if (Boolean.TRUE.equals(safe(() -> cam.supportsControl(c)))) l.add(c);
        return l;
    }

    static Map<String, Object> controls() {
        IFireCam cam = cam();
        Map<String, Object> m = new LinkedHashMap<>();
        for (Control c : supported(cam)) {
            Map<String, Object> e = new LinkedHashMap<>();
            Object v = safe(() -> cam.getValue(c));
            e.put("value", v);
            e.put("min", safe(() -> cam.getMinValue(c)));
            e.put("max", safe(() -> cam.getMaxValue(c)));
            e.put("enabled", safe(() -> cam.isEnabled(c)));
            e.put("display", v instanceof Integer ? safe(() -> cam.getValueString(c, (Integer) v)) : null);
            m.put(c.name(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("camera", cam.getName());
        out.put("note", "enabled = the control's checkbox (auto mode for Gain/Exposure, on/off for Gamma, SoftwareGain, ...)");
        out.put("controls", m);
        return out;
    }

    /** {"Gamma": 50} or {"Gamma": {"value": 50, "enabled": true}}. Shutter/Exposure: use PUT /v1/camera exposureMs. */
    static Map<String, Object> putControls(Map<String, Object> req) throws Exception {
        IFireCam cam = cam();
        List<Control> ok = supported(cam);
        List<Object[]> plan = new ArrayList<>();       // validate everything before changing anything
        for (Map.Entry<String, Object> en : req.entrySet()) {
            Control c = null;
            for (Control x : ok) if (x.name().equalsIgnoreCase(en.getKey())) c = x;
            if (c == null) throw new ApiException(400, "camera does not support control " + en.getKey() + " (GET /v1/camera/controls lists them)");
            if (c == Control.Shutter || c == Control.Exposure) throw new ApiException(400, "set exposure with PUT /v1/camera {\"exposureMs\": ...}");
            Integer value = null; Boolean enabled = null;
            Object o = en.getValue();
            if (o instanceof Number) value = ((Number) o).intValue();
            else if (o instanceof Map) {
                Map<?, ?> mm = (Map<?, ?>) o;
                for (Object k : mm.keySet()) if (!"value".equals(k) && !"enabled".equals(k)) throw new ApiException(400, c.name() + ": unknown field " + k);
                if (mm.containsKey("value")) value = num(mm.get("value"), c.name() + ".value").intValue();
                if (mm.containsKey("enabled")) enabled = bool(mm.get("enabled"), c.name() + ".enabled");
            } else throw new ApiException(400, c.name() + " must be a number or {value, enabled}");
            if (value != null) {
                int min = cam.getMinValue(c), max = cam.getMaxValue(c);
                if (value < min || value > max) throw new ApiException(400, c.name() + " out of range " + min + ".." + max);
            }
            plan.add(new Object[] {c, value, enabled});
        }
        for (Object[] p : plan) {
            Control c = (Control) p[0]; Integer value = (Integer) p[1]; Boolean enabled = (Boolean) p[2];
            if (enabled != null) onEdt(() -> { cam.setEnabled(c, enabled); return null; });
            if (value != null) {
                if (c == Control.Gain) setGain(cam, value);
                else CamWorker.INSTANCE.setValue(c, value);
            }
        }
        sleep(300);
        Map<String, Object> all = controls();
        @SuppressWarnings("unchecked") Map<String, Object> cs = (Map<String, Object>) all.get("controls");
        Map<String, Object> changed = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (Object[] p : plan) {
            String n = ((Control) p[0]).name();
            @SuppressWarnings("unchecked") Map<String, Object> now = (Map<String, Object>) cs.get(n);
            changed.put(n, now);
            if (now == null) continue;
            if (p[1] != null && !p[1].equals(now.get("value"))) warnings.add(n + " value reads back as " + now.get("value") + " (requested " + p[1] + ")");
            if (p[2] != null && !p[2].equals(now.get("enabled"))) warnings.add(n + " enabled reads back as " + now.get("enabled"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("controls", changed);
        if (!warnings.isEmpty()) out.put("warnings", warnings);
        out.put("note", "controls other than Gain go through FireCapture's camera worker; its 'More' panel may not redraw until reopened");
        return out;
    }

    static Map<String, Object> centerRoi() throws Exception {
        notRecording("centre the ROI");
        onEdt(() -> { panel().centerROI(); return null; });
        sleep(300);
        return camera();
    }

    static Response frame() throws Exception {
        ImageData d = Capture.getInstance().currentData;
        BufferedImage img = d == null ? null : d.getImage();
        if (img == null) throw new ApiException(503, "no frame yet");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return new Response(200, "image/png", out.toByteArray());
    }
}
