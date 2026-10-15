package remoteapi;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class Response {
    final int status;
    final String contentType;
    final byte[] body;

    Response(int status, String contentType, byte[] body) {
        this.status = status; this.contentType = contentType; this.body = body;
    }

    static Response json(int status, Object value) {
        return new Response(status, "application/json", (Json.write(value) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    static Response ok(Object value) { return json(200, value); }

    static Response error(int status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        m.put("status", status);
        return json(status, m);
    }
}
