package remoteapi;

import java.awt.Rectangle;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.swing.SwingUtilities;

import de.wonderplanets.firecam.IFireCam;
import de.wonderplanets.firecapture.capture.Capture;
import de.wonderplanets.firecapture.gui.CameraPanel;
import de.wonderplanets.firecapture.gui.FireCapture;
import de.wonderplanets.firecapture.profile.SystemSettings;

/**
 * Router + shared helpers. Endpoint groups live in CameraEndpoints, CaptureEndpoints and DeviceEndpoints.
 * Calls into FireCapture internals (built against v2.7.15); a missing class/method surfaces as HTTP 501.
 */
final class Api {
    static final String API_VERSION = "0.3.0";
    static final String TESTED_FC_VERSION = "v2.7";
    static final String TESTED_FC_BUILD = "15";

    static final class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String msg) { super(msg); this.status = status; }
    }

    /** method, path, description — served at GET /v1 and kept in sync with route(). */
    static final String[][] ENDPOINTS = {
        {"GET", "/v1", "this list"},
        {"GET", "/v1/status", "overview: FireCapture version, camera, profile/filter, recording, configured devices"},
        {"GET", "/v1/camera", "camera name, colour, temperature, sensor size, ROI, gain/exposure with ranges, timebase"},
        {"PUT", "/v1/camera", "{gain, exposureMs, roi:[x,y,w,h]} - applied through the GUI controls"},
        {"GET", "/v1/camera/controls", "every control the camera supports: value, min, max, enabled, display string"},
        {"PUT", "/v1/camera/controls", "{\"Gamma\": 50, \"USBTraffic\": {\"value\": 80}, \"AutoExposure\": {\"enabled\": false}}"},
        {"POST", "/v1/camera/roi/center", "centre the ROI on the sensor"},
        {"GET", "/v1/camera/frame", "current frame as PNG"},
        {"GET", "/v1/capture", "recording state, limit, file type"},
        {"PUT", "/v1/capture", "{limit: \"180s\"|\"3m\"|\"5000f\"|\"500mb\", fileType: \"SER\"}"},
        {"POST", "/v1/capture/start", "start recording"},
        {"POST", "/v1/capture/stop", "stop recording"},
        {"POST", "/v1/capture/pause", "pause recording"},
        {"POST", "/v1/capture/resume", "resume a paused recording"},
        {"GET", "/v1/profiles", "capture profiles and the current one"},
        {"PUT", "/v1/profile", "{name} - switch profile (name or short name)"},
        {"GET", "/v1/filters", "filter slots and the current one"},
        {"PUT", "/v1/filter", "{name} - switch filter slot (moves the wheel if one is configured)"},
        {"GET", "/v1/mount", "mount/guider: type, status, capabilities, autoguiding state, guide error"},
        {"POST", "/v1/mount/pulse", "{direction: north|south|east|west, ms}"},
        {"POST", "/v1/mount/move", "{direction, ms, rate?} - slew an axis for ms at rate (default: FireCapture's slew rate)"},
        {"POST", "/v1/mount/stop", "stop all axis motion"},
        {"POST", "/v1/mount/park", "park the mount (if supported)"},
        {"POST", "/v1/mount/autoguide", "{enabled: true|false} - FireCapture's planetary autoguiding"},
        {"GET", "/v1/focuser", "connected, position, max, temperature, temp-comp, capabilities"},
        {"POST", "/v1/focuser/move", "{position} absolute or {by} relative"},
        {"PUT", "/v1/focuser", "{connected, tempComp}"},
        {"GET", "/v1/filterwheel", "type, connected, position, moving"},
        {"PUT", "/v1/filterwheel", "{position, connected}"},
        {"GET", "/v1/rotator", "connected, position, target, moving"},
        {"POST", "/v1/rotator/move", "{angle} absolute or {by} relative"},
        {"PUT", "/v1/rotator", "{connected}"},
        {"GET", "/v1/target", "bright target (planet/Sun/Moon) detected in the current frame: centre, radius, bbox, edge contact"},
        {"GET", "/v1/overlay", "guide markers currently drawn on the live view"},
        {"POST", "/v1/overlay", "{shape: planet|saturn|sun|moon|cross, x,y | dx,dy, radius?, label?, color?, ttlS?} - show where the target should go; off-view targets get an edge arrow"},
        {"DELETE", "/v1/overlay", "remove all markers, or ?id=N for one"},
    };

    private Api() { }

    static Response route(String method, String path, String query, String body) throws Exception {
        if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
        String key = method + " " + path;
        switch (key) {
            case "GET /v1":                    return Response.ok(index());
            case "GET /v1/status":             return Response.ok(status());

            case "GET /v1/camera":             return Response.ok(CameraEndpoints.camera());
            case "PUT /v1/camera":             return Response.ok(CameraEndpoints.putCamera(body(body)));
            case "GET /v1/camera/controls":    return Response.ok(CameraEndpoints.controls());
            case "PUT /v1/camera/controls":    return Response.ok(CameraEndpoints.putControls(body(body)));
            case "POST /v1/camera/roi/center": return Response.ok(CameraEndpoints.centerRoi());
            case "GET /v1/camera/frame":       return CameraEndpoints.frame();

            case "GET /v1/capture":            return Response.ok(CaptureEndpoints.capture());
            case "PUT /v1/capture":            return Response.ok(CaptureEndpoints.putCapture(body(body)));
            case "POST /v1/capture/start":     return Response.ok(CaptureEndpoints.start());
            case "POST /v1/capture/stop":      return Response.ok(CaptureEndpoints.stop());
            case "POST /v1/capture/pause":     return Response.ok(CaptureEndpoints.pause(true));
            case "POST /v1/capture/resume":    return Response.ok(CaptureEndpoints.pause(false));
            case "GET /v1/profiles":           return Response.ok(CaptureEndpoints.profiles());
            case "PUT /v1/profile":            return Response.ok(CaptureEndpoints.putProfile(body(body)));
            case "GET /v1/filters":            return Response.ok(CaptureEndpoints.filters());
            case "PUT /v1/filter":             return Response.ok(CaptureEndpoints.putFilter(body(body)));

            case "GET /v1/mount":              return Response.ok(DeviceEndpoints.mount());
            case "POST /v1/mount/pulse":       return Response.ok(DeviceEndpoints.pulse(body(body)));
            case "POST /v1/mount/move":        return Response.ok(DeviceEndpoints.move(body(body)));
            case "POST /v1/mount/stop":        return Response.ok(DeviceEndpoints.stopMount());
            case "POST /v1/mount/park":        return Response.ok(DeviceEndpoints.park());
            case "POST /v1/mount/autoguide":   return Response.ok(DeviceEndpoints.autoguide(body(body)));
            case "GET /v1/focuser":            return Response.ok(DeviceEndpoints.focuser());
            case "POST /v1/focuser/move":      return Response.ok(DeviceEndpoints.moveFocuser(body(body)));
            case "PUT /v1/focuser":            return Response.ok(DeviceEndpoints.putFocuser(body(body)));
            case "GET /v1/filterwheel":        return Response.ok(DeviceEndpoints.wheel());
            case "PUT /v1/filterwheel":        return Response.ok(DeviceEndpoints.putWheel(body(body)));
            case "GET /v1/rotator":            return Response.ok(DeviceEndpoints.rotator());
            case "POST /v1/rotator/move":      return Response.ok(DeviceEndpoints.moveRotator(body(body)));
            case "PUT /v1/rotator":            return Response.ok(DeviceEndpoints.putRotator(body(body)));

            case "GET /v1/target":             return Response.ok(OverlayEndpoints.target());
            case "GET /v1/overlay":            return Response.ok(OverlayEndpoints.overlay());
            case "POST /v1/overlay":           return Response.ok(OverlayEndpoints.addMarker(body(body)));
            case "DELETE /v1/overlay":         return Response.ok(OverlayEndpoints.delete(query));
            default:
                for (String[] e : ENDPOINTS)
                    if (e[1].equals(path)) throw new ApiException(405, method + " not allowed on " + path);
                throw new ApiException(404, "no such endpoint: " + path + "  (GET /v1 lists them)");
        }
    }

    // ---------------------------------------------------------------- overview

    private static Map<String, Object> index() {
        List<Object> l = new ArrayList<>();
        for (String[] e : ENDPOINTS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", e[0]); m.put("path", e[1]); m.put("description", e[2]);
            l.add(m);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("api", API_VERSION);
        m.put("endpoints", l);
        return m;
    }

    static Map<String, Object> status() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> fc = new LinkedHashMap<>();
        String ver = fcConstant("VERSION"), build = fcConstant("BUILD");
        fc.put("version", ver);
        fc.put("build", build);
        fc.put("testedWith", TESTED_FC_VERSION + " build " + TESTED_FC_BUILD);
        fc.put("compatible", TESTED_FC_VERSION.equals(ver) && TESTED_FC_BUILD.equals(build));
        m.put("api", API_VERSION);
        m.put("firecapture", fc);
        IFireCam cam = camOrNull();
        m.put("camera", cam == null ? null : cam.getName());
        SystemSettings s = SystemSettings.INSTANCE;
        m.put("profile", s == null || s.getCurrentProfile() == null ? null : s.getCurrentProfile().getName());
        m.put("filter", s == null || s.currentFilter == null ? null : s.currentFilter.getName());
        m.put("recording", CaptureEndpoints.capture());
        Map<String, Object> dev = new LinkedHashMap<>();
        dev.put("mount", s != null && s.useAutoguider);
        dev.put("focuser", s != null && s.useFocuser);
        dev.put("filterwheel", s != null && s.useFilterWheel);
        dev.put("rotator", s != null && s.useRotator);
        m.put("devicesConfigured", dev);
        return m;
    }

    // ---------------------------------------------------------------- FireCapture access

    static IFireCam camOrNull() {
        FireCapture fc = FireCapture.INSTANCE;
        return fc == null ? null : fc.getCam();
    }

    static IFireCam cam() {
        IFireCam c = camOrNull();
        if (c == null) throw new ApiException(503, "no camera connected");
        return c;
    }

    static CameraPanel panel() {
        FireCapture fc = FireCapture.INSTANCE;
        CameraPanel p = fc == null ? null : fc.getCamPanel();
        if (p == null) throw new ApiException(503, "camera panel not ready");
        return p;
    }

    static SystemSettings settings() {
        SystemSettings s = SystemSettings.INSTANCE;
        if (s == null) throw new ApiException(503, "FireCapture settings not loaded yet");
        return s;
    }

    static boolean recording() { return Capture.getInstance().captureStarted; }

    static void notRecording(String what) {
        if (recording()) throw new ApiException(409, "cannot " + what + " while recording");
    }

    /** VERSION/BUILD are compile-time constants; read them reflectively so we get the running FireCapture's values. */
    static String fcConstant(String name) {
        try {
            return String.valueOf(Class.forName("de.wonderplanets.firecapture.gui.FireCapture").getField(name).get(null)).trim();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- threading

    static <T> T onEdt(Callable<T> c) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return c.call();
        Object[] result = new Object[1];
        Throwable[] err = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { result[0] = c.call(); } catch (Throwable t) { err[0] = t; }
            });
        } catch (InvocationTargetException e) {
            err[0] = e.getCause();
        }
        if (err[0] instanceof Exception) throw (Exception) err[0];
        if (err[0] instanceof Error) throw (Error) err[0];
        @SuppressWarnings("unchecked") T t = (T) result[0];
        return t;
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ---------------------------------------------------------------- request parsing / JSON helpers

    static Map<String, Object> body(String body) {
        if (body == null || body.isBlank()) return new LinkedHashMap<>();
        Object parsed;
        try { parsed = Json.parse(body); } catch (RuntimeException e) { throw new ApiException(400, "invalid JSON: " + e.getMessage()); }
        if (!(parsed instanceof Map)) throw new ApiException(400, "expected a JSON object");
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) parsed;
        return m;
    }

    static void allowOnly(Map<String, Object> req, String... keys) {
        for (String k : req.keySet())
            if (!List.of(keys).contains(k)) throw new ApiException(400, "unknown field: " + k + " (allowed: " + String.join(", ", keys) + ")");
    }

    static Number num(Object o, String what) {
        if (!(o instanceof Number)) throw new ApiException(400, what + " must be a number");
        return (Number) o;
    }

    static String str(Object o, String what) {
        if (!(o instanceof String) || ((String) o).isEmpty()) throw new ApiException(400, what + " must be a non-empty string");
        return (String) o;
    }

    static boolean bool(Object o, String what) {
        if (!(o instanceof Boolean)) throw new ApiException(400, what + " must be true or false");
        return (Boolean) o;
    }

    static Map<String, Object> range(Number v, Number min, Number max) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", v); m.put("min", min); m.put("max", max);
        return m;
    }

    static List<Integer> rect(Rectangle r) {
        if (r == null) return null;
        List<Integer> l = new ArrayList<>();
        l.add(r.x); l.add(r.y); l.add(r.width); l.add(r.height);
        return l;
    }

    static Rectangle parseRect(Object o) {
        if (!(o instanceof List) || ((List<?>) o).size() != 4) throw new ApiException(400, "roi must be [x, y, width, height]");
        List<?> l = (List<?>) o;
        int x = num(l.get(0), "roi x").intValue(), y = num(l.get(1), "roi y").intValue();
        int w = num(l.get(2), "roi width").intValue(), h = num(l.get(3), "roi height").intValue();
        if (x < 0 || y < 0 || w <= 0 || h <= 0) throw new ApiException(400, "roi values must be positive");
        if (w % 8 != 0 || h % 2 != 0) throw new ApiException(400, "roi width must be a multiple of 8 and height even");
        return new Rectangle(x, y, w, h);
    }

    /** Read a value, turning any failure into null so one broken getter doesn't sink a whole response. */
    static Object safe(Callable<Object> c) {
        try { return c.call(); } catch (Throwable t) { return null; }
    }
}
