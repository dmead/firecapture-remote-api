package remoteapi;

import static remoteapi.Api.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.wonderplanets.firecapture.capture.Capture;
import de.wonderplanets.firecapture.capture.CaptureStatistics;
import de.wonderplanets.firecapture.capture.Limit;
import de.wonderplanets.firecapture.common.FileType;
import de.wonderplanets.firecapture.common.Filter;
import de.wonderplanets.firecapture.profile.CaptureProfile;

/** /v1/capture..., /v1/profile(s), /v1/filter(s) */
final class CaptureEndpoints {
    private static final Pattern LIMIT = Pattern.compile("(?i)^\\s*(\\d+)\\s*(s|m|f|mb)\\s*$");

    private CaptureEndpoints() { }

    static Map<String, Object> capture() {
        Capture cap = Capture.getInstance();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("active", cap.captureStarted);
        r.put("paused", cap.capturePaused);
        r.put("file", cap.filename);
        CaptureStatistics st = cap.getStatistics();
        if (st != null) {
            r.put("framesCaptured", st.getFramesCaptured());
            r.put("elapsedS", st.getElapsedSeconds());
            r.put("fps", st.getCurrentFPS());
            r.put("avgFps", st.getAvgFPS());
        }
        // the limit belongs to the current filter slot; Capture.getLimit() is only the running capture's copy
        Limit l = (Limit) safe(() -> settings().currentFilter.getLimit());
        r.put("limit", l == null ? null : limitString(l));
        r.put("fileType", safe(() -> String.valueOf(settings().currentFileType)));
        r.put("captureRootDir", safe(() -> settings().captureRootDir));
        return r;
    }

    private static String limitString(Limit l) {
        if (l.getType() == null) return null;
        switch (l.getType()) {
            case SECONDS: return l.getValue() + "s";
            case MINUTES: return l.getValue() + "m";
            case FRAMES:  return l.getValue() + "f";
            case MB:      return l.getValue() + "mb";
            default:      return l.toString();
        }
    }

    /** {"limit": "180s"|"3m"|"5000f"|"500mb", "fileType": "SER"} */
    static Map<String, Object> putCapture(Map<String, Object> req) throws Exception {
        allowOnly(req, "limit", "fileType");
        notRecording("change capture settings");
        if (req.containsKey("fileType")) {
            String t = str(req.get("fileType"), "fileType").toUpperCase();
            FileType ft = null;
            for (FileType x : panel().getFileTypes()) if (x.name().equals(t)) ft = x;
            if (ft == null) {
                List<String> names = new ArrayList<>();
                for (FileType x : panel().getFileTypes()) names.add(x.name());
                throw new ApiException(400, "fileType must be one of " + names + " for this camera/mode");
            }
            FileType chosen = ft;
            onEdt(() -> { panel().setFileType(chosen); return null; });
        }
        if (req.containsKey("limit")) {
            Matcher mt = LIMIT.matcher(str(req.get("limit"), "limit"));
            if (!mt.matches()) throw new ApiException(400, "limit must look like 180s, 3m, 5000f or 500mb");
            int n = Integer.parseInt(mt.group(1));
            if (n <= 0) throw new ApiException(400, "limit must be positive");
            Limit.Type type;
            switch (mt.group(2).toLowerCase()) {
                case "s": type = Limit.Type.SECONDS; break;
                case "m": type = Limit.Type.MINUTES; break;
                case "f": type = Limit.Type.FRAMES; break;
                default:  type = Limit.Type.MB;
            }
            Limit limit = new Limit(type, n);
            onEdt(() -> { panel().setLimit(limit); return null; });
        }
        sleep(200);
        return capture();
    }

    static Map<String, Object> start() throws Exception {
        cam();
        if (recording()) throw new ApiException(409, "already recording");
        onEdt(() -> { panel().forceStartCapture(); return null; });
        sleep(300);
        return capture();
    }

    static Map<String, Object> stop() throws Exception {
        if (!recording()) throw new ApiException(409, "not recording");
        onEdt(() -> { panel().forceStopCapture(); return null; });
        sleep(300);
        return capture();
    }

    static Map<String, Object> pause(boolean pause) throws Exception {
        if (!recording()) throw new ApiException(409, "not recording");
        if (Capture.getInstance().capturePaused == pause) throw new ApiException(409, pause ? "already paused" : "not paused");
        onEdt(() -> { panel().togglePauseCapture(); return null; });
        sleep(200);
        return capture();
    }

    // ---------------------------------------------------------------- profiles / filters

    static Map<String, Object> profiles() {
        List<Object> l = new ArrayList<>();
        for (CaptureProfile p : settings().getCaptureProfiles()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("shortName", p.getShortName());
            l.add(m);
        }
        CaptureProfile cur = settings().getCurrentProfile();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", cur == null ? null : cur.getName());
        out.put("profiles", l);
        return out;
    }

    static Map<String, Object> putProfile(Map<String, Object> req) throws Exception {
        allowOnly(req, "name");
        notRecording("switch profile");
        String name = str(req.get("name"), "name");
        CaptureProfile found = null;
        for (CaptureProfile p : settings().getCaptureProfiles())
            if (name.equalsIgnoreCase(p.getName()) || name.equalsIgnoreCase(p.getShortName())) found = p;
        if (found == null) throw new ApiException(404, "no profile named " + name + " (GET /v1/profiles)");
        CaptureProfile p = found;
        onEdt(() -> { panel().setProfile(p); return null; });
        sleep(500);
        return profiles();
    }

    static Map<String, Object> filters() {
        List<Object> l = new ArrayList<>();
        for (Filter f : settings().getFilters()) l.add(f.getName());
        Filter cur = settings().currentFilter;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", cur == null ? null : cur.getName());
        out.put("filters", l);
        out.put("filterWheel", settings().useFilterWheel);
        return out;
    }

    static Map<String, Object> putFilter(Map<String, Object> req) throws Exception {
        allowOnly(req, "name");
        notRecording("switch filter");
        String name = str(req.get("name"), "name");
        Filter found = null;
        for (Filter f : settings().getFilters()) if (name.equalsIgnoreCase(f.getName())) found = f;
        if (found == null) throw new ApiException(404, "no filter named " + name + " (GET /v1/filters)");
        Filter f = found;
        onEdt(() -> { panel().selectFilter(f, true); return null; });
        sleep(500);
        return filters();
    }
}
