package remoteapi;

import static remoteapi.Api.*;

import java.awt.Point;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.wonderplanets.firecapture.autoguider.IAutoguider;
import de.wonderplanets.firecapture.autoguider.IAutoguider.Direction;
import de.wonderplanets.firecapture.autoguider.IAutoguider.MoveAxisCommand;
import de.wonderplanets.firecapture.filterwheel.FilterWheelHelper;
import de.wonderplanets.firecapture.filterwheel.IFilterWheel;
import de.wonderplanets.firecapture.focuser.IFocuser;
import de.wonderplanets.firecapture.rotator.IRotator;

/**
 * /v1/mount, /v1/focuser, /v1/filterwheel, /v1/rotator — whatever FireCapture itself has connected
 * (configured in FireCapture's settings). Motion commands return once issued; poll GET for progress.
 */
final class DeviceEndpoints {
    private static final int MAX_PULSE_MS = 10_000;
    private static final int MAX_MOVE_MS = 60_000;

    private DeviceEndpoints() { }

    // ---------------------------------------------------------------- mount / guider

    private static IAutoguider mountDevice() {
        if (!settings().useAutoguider) throw new ApiException(503, "no mount configured in FireCapture (Settings → Autoguide/Telescope)");
        IAutoguider ag = settings().autoguider;
        if (ag == null) throw new ApiException(503, "mount configured but not connected");
        return ag;
    }

    static Map<String, Object> mount() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", settings().useAutoguider);
        IAutoguider ag = settings().autoguider;
        m.put("connected", ag != null);
        if (ag == null) return m;
        m.put("interface", Boolean.TRUE.equals(safe(ag::isINDI)) ? "INDI" : Boolean.TRUE.equals(safe(ag::isASCOM)) ? "ASCOM" : ag.getClass().getSimpleName());
        m.put("status", safe(() -> String.valueOf(ag.getStatus())));
        m.put("canMoveAxis", safe(ag::supportsMoveAxis));
        m.put("canPark", safe(ag::parkSupported));
        m.put("autoguiding", settings().autoguide);
        Point err = (Point) safe(ag::getError);
        m.put("guideErrorPx", err == null ? null : List.of(err.x, err.y));
        Point t = (Point) safe(ag::getTargetSpot);
        m.put("targetSpot", t == null ? null : List.of(t.x, t.y));
        Map<String, Object> rates = new LinkedHashMap<>();
        for (Direction d : Direction.values()) rates.put(d.name().toLowerCase(), safe(() -> ag.getSlewRate(d, false)));
        m.put("slewRates", rates);
        return m;
    }

    private static Direction direction(Object o) {
        String s = str(o, "direction");
        for (Direction d : Direction.values()) if (d.name().equalsIgnoreCase(s)) return d;
        throw new ApiException(400, "direction must be north, south, east or west");
    }

    private static int ms(Object o, int max) {
        int ms = num(o, "ms").intValue();
        if (ms < 1 || ms > max) throw new ApiException(400, "ms must be 1.." + max);
        return ms;
    }

    /** {direction, ms} — guide-rate pulse. */
    static Map<String, Object> pulse(Map<String, Object> req) {
        allowOnly(req, "direction", "ms");
        IAutoguider ag = mountDevice();
        Direction d = direction(req.get("direction"));
        int ms = ms(req.get("ms"), MAX_PULSE_MS);
        Thread t = new Thread(() -> ag.pulseGuide(d, ms), "RemoteApi-pulse");
        t.setDaemon(true);
        t.start();
        return issued("pulse " + d.name().toLowerCase() + " " + ms + " ms");
    }

    /** {direction, ms, rate?} — slew one axis at rate (FireCapture's slew-rate units) for ms. */
    static Map<String, Object> move(Map<String, Object> req) {
        allowOnly(req, "direction", "ms", "rate");
        IAutoguider ag = mountDevice();
        if (!Boolean.TRUE.equals(safe(ag::supportsMoveAxis))) throw new ApiException(409, "this mount interface cannot move axes (use /v1/mount/pulse)");
        Direction d = direction(req.get("direction"));
        int ms = ms(req.get("ms"), MAX_MOVE_MS);
        double rate = req.containsKey("rate") ? num(req.get("rate"), "rate").doubleValue() : ag.getSlewRate(d, false);
        if (rate <= 0) throw new ApiException(400, "rate must be positive");
        new MoveAxisCommand(d, rate, ms).runThreaded(ag);
        return issued("move " + d.name().toLowerCase() + " at rate " + rate + " for " + ms + " ms");
    }

    static Map<String, Object> stopMount() {
        IAutoguider ag = mountDevice();
        for (Direction d : Direction.values()) ag.stopSlewing(d, true);   // true = stop the axis and restore tracking
        return issued("stop all axes");
    }

    static Map<String, Object> park() {
        IAutoguider ag = mountDevice();
        if (!Boolean.TRUE.equals(safe(ag::parkSupported))) throw new ApiException(409, "this mount interface cannot park");
        ag.park();
        return issued("park");
    }

    /** {enabled} — FireCapture's planetary autoguiding (keeps the planet at its target spot). */
    static Map<String, Object> autoguide(Map<String, Object> req) {
        allowOnly(req, "enabled");
        IAutoguider ag = mountDevice();
        boolean on = bool(req.get("enabled"), "enabled");
        settings().autoguide = on;
        if (on) ag.startAutoguiding(); else ag.stopAutoguiding();
        return mount();
    }

    // ---------------------------------------------------------------- focuser

    private static IFocuser focuserDevice() {
        if (!settings().useFocuser) throw new ApiException(503, "no focuser configured in FireCapture (Settings → Focuser)");
        IFocuser f = settings().getFocuser();
        if (f == null) throw new ApiException(503, "focuser configured but not connected");
        return f;
    }

    static Map<String, Object> focuser() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", settings().useFocuser);
        IFocuser f = settings().useFocuser ? settings().getFocuser() : null;
        m.put("present", f != null);
        if (f == null) return m;
        m.put("interface", f.getClass().getSimpleName());
        m.put("connected", safe(f::connected));
        m.put("position", Boolean.TRUE.equals(safe(f::supportsPosition)) ? safe(f::position) : null);
        m.put("max", Boolean.TRUE.equals(safe(f::supportsMaxStep)) ? safe(f::maxStep) : null);
        m.put("maxIncrement", Boolean.TRUE.equals(safe(f::supportsMaxIncrement)) ? safe(f::maxIncrement) : null);
        m.put("stepSize", Boolean.TRUE.equals(safe(f::supportsStepSize)) ? safe(f::stepSize) : null);
        m.put("absolute", safe(f::supportsAbsolute));
        m.put("relative", safe(f::supportsRelative));
        m.put("temperatureC", Boolean.TRUE.equals(safe(f::temperatureSupported)) ? safe(f::temperature) : null);
        m.put("tempCompAvailable", safe(f::isTempCompAvailable));
        m.put("tempComp", safe(f::tempComp));
        return m;
    }

    /** {position} absolute or {by} relative (steps). */
    static Map<String, Object> moveFocuser(Map<String, Object> req) {
        allowOnly(req, "position", "by");
        if (req.containsKey("position") == req.containsKey("by")) throw new ApiException(400, "give exactly one of position or by");
        IFocuser f = focuserDevice();
        if (!Boolean.TRUE.equals(safe(f::connected))) throw new ApiException(409, "focuser not connected");
        // an INDI focuser whose device is unplugged still reports connected (the server is up) but no capabilities
        if (!Boolean.TRUE.equals(safe(f::supportsAbsolute)) && !Boolean.TRUE.equals(safe(f::supportsRelative)))
            throw new ApiException(409, "focuser is not responding (it reports neither absolute nor relative moves) - is the device plugged in?");
        if (req.containsKey("position")) {
            long p = num(req.get("position"), "position").longValue();
            if (!Boolean.TRUE.equals(safe(f::supportsAbsolute))) throw new ApiException(409, "focuser does not support absolute moves; use {by}");
            Object max = Boolean.TRUE.equals(safe(f::supportsMaxStep)) ? safe(f::maxStep) : null;
            if (p < 0 || (max instanceof Long && p > (Long) max)) throw new ApiException(400, "position must be 0.." + max);
            f.moveAbsolute(p);
        } else {
            long by = num(req.get("by"), "by").longValue();
            f.moveBy(by);
        }
        Map<String, Object> m = focuser();
        m.put("issued", req);
        return m;
    }

    /** {connected, tempComp} */
    static Map<String, Object> putFocuser(Map<String, Object> req) {
        allowOnly(req, "connected", "tempComp");
        IFocuser f = focuserDevice();
        if (req.containsKey("connected")) f.setConnected(bool(req.get("connected"), "connected"));
        if (req.containsKey("tempComp")) {
            if (!Boolean.TRUE.equals(safe(f::isTempCompAvailable))) throw new ApiException(409, "temperature compensation not available");
            f.setTempComp(bool(req.get("tempComp"), "tempComp"));
        }
        return focuser();
    }

    // ---------------------------------------------------------------- filter wheel

    private static IFilterWheel wheelDevice() {
        if (!settings().useFilterWheel) throw new ApiException(503, "no filter wheel configured in FireCapture (Settings → Filters)");
        IFilterWheel w = FilterWheelHelper.getWheel();
        if (w == null) throw new ApiException(503, "filter wheel configured but not connected");
        return w;
    }

    static Map<String, Object> wheel() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", settings().useFilterWheel);
        IFilterWheel w = settings().useFilterWheel ? FilterWheelHelper.getWheel() : null;
        m.put("present", w != null);
        if (w == null) return m;
        m.put("type", safe(() -> String.valueOf(w.getType())));
        m.put("connected", safe(w::connected));
        m.put("position", safe(w::position));
        m.put("moving", Boolean.TRUE.equals(safe(w::isMovingSupported)) ? safe(w::isMoving) : null);
        m.put("note", "PUT /v1/filter {name} switches slot AND FireCapture's per-filter camera settings; this endpoint only moves the wheel");
        return m;
    }

    /** {position, connected} */
    static Map<String, Object> putWheel(Map<String, Object> req) {
        allowOnly(req, "position", "connected");
        notRecording("move the filter wheel");
        IFilterWheel w = wheelDevice();
        if (req.containsKey("connected")) w.setConnected(bool(req.get("connected"), "connected"));
        if (req.containsKey("position")) {
            int p = num(req.get("position"), "position").intValue();
            if (p < 0) throw new ApiException(400, "position must be >= 0");
            w.setPosition(p);
        }
        return wheel();
    }

    // ---------------------------------------------------------------- rotator

    private static IRotator rotatorDevice() {
        if (!settings().useRotator) throw new ApiException(503, "no rotator configured in FireCapture");
        IRotator r = settings().getRotator();
        if (r == null) throw new ApiException(503, "rotator configured but not connected");
        return r;
    }

    static Map<String, Object> rotator() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", settings().useRotator);
        IRotator r = settings().useRotator ? settings().getRotator() : null;
        m.put("present", r != null);
        if (r == null) return m;
        m.put("connected", Boolean.TRUE.equals(safe(r::supportsConnected)) ? safe(r::connected) : null);
        m.put("positionDeg", safe(r::position));
        m.put("targetDeg", safe(r::targetPosition));
        m.put("moving", safe(r::isMoving));
        m.put("stepSizeDeg", Boolean.TRUE.equals(safe(r::supportsStepSize)) ? safe(r::stepSize) : null);
        return m;
    }

    /** {angle} absolute or {by} relative (degrees). */
    static Map<String, Object> moveRotator(Map<String, Object> req) {
        allowOnly(req, "angle", "by");
        if (req.containsKey("angle") == req.containsKey("by")) throw new ApiException(400, "give exactly one of angle or by");
        IRotator r = rotatorDevice();
        if (req.containsKey("angle")) {
            float a = num(req.get("angle"), "angle").floatValue();
            if (a < 0 || a >= 360) throw new ApiException(400, "angle must be 0..<360");
            r.moveAbsolute(a);
        } else r.move(num(req.get("by"), "by").floatValue());
        Map<String, Object> m = rotator();
        m.put("issued", req);
        return m;
    }

    static Map<String, Object> putRotator(Map<String, Object> req) {
        allowOnly(req, "connected");
        IRotator r = rotatorDevice();
        if (req.containsKey("connected")) r.setConnected(bool(req.get("connected"), "connected"));
        return rotator();
    }

    private static Map<String, Object> issued(String what) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issued", what);
        return m;
    }
}
