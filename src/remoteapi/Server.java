package remoteapi;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP/1.1 server on plain sockets (FireCapture's bundled JRE has no jdk.httpserver).
 * One request per connection, loopback only. Port from ~/FireCapture/remoteapi.properties (port=...), default 1899.
 */
final class Server {
    static final int DEFAULT_PORT = 1899;
    private static volatile boolean started;
    private static int port = DEFAULT_PORT;
    private static final int MAX_BODY = 1 << 20;

    static synchronized void startOnce() {
        if (started) return;
        started = true;
        port = readPort();
        Thread t = new Thread(Server::serve, "RemoteApi-http");
        t.setDaemon(true);
        t.start();
    }

    static String address() { return "127.0.0.1:" + port; }

    private static int readPort() {
        File f = new File(System.getProperty("user.home"), "FireCapture/remoteapi.properties");
        if (!f.exists()) return DEFAULT_PORT;
        try (InputStream in = new FileInputStream(f)) {
            Properties p = new Properties(); p.load(in);
            return Integer.parseInt(p.getProperty("port", "" + DEFAULT_PORT).trim());
        } catch (Exception e) {
            return DEFAULT_PORT;
        }
    }

    private static void serve() {
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "RemoteApi-worker"); t.setDaemon(true); return t; });
        try (ServerSocket ss = new ServerSocket(port, 16, InetAddress.getLoopbackAddress())) {
            Log.info("Remote API listening on http://" + address() + "/v1/");
            while (true) {
                Socket s = ss.accept();
                pool.execute(() -> handle(s));
            }
        } catch (IOException e) {
            Log.error("Remote API server stopped (port " + port + " in use?)", e);
        }
    }

    private static void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) { write(sock.getOutputStream(), Response.error(400, "bad request line")); return; }
            Map<String, String> headers = new LinkedHashMap<>();
            for (String l; (l = readLine(in)) != null && !l.isEmpty(); ) {
                int i = l.indexOf(':');
                if (i > 0) headers.put(l.substring(0, i).trim().toLowerCase(), l.substring(i + 1).trim());
            }
            int len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            if (len > MAX_BODY) { write(sock.getOutputStream(), Response.error(413, "body too large")); return; }
            byte[] body = in.readNBytes(len);
            String path = parts[1];
            int q = path.indexOf('?');
            String query = q >= 0 ? path.substring(q + 1) : "";
            if (q >= 0) path = path.substring(0, q);
            Response r;
            try {
                r = Api.route(parts[0].toUpperCase(), path, query, new String(body, StandardCharsets.UTF_8));
            } catch (LinkageError e) {          // FireCapture internals changed in this version
                r = Response.error(501, "not supported by this FireCapture version: " + e);
            } catch (Api.ApiException e) {
                r = Response.error(e.status, e.getMessage());
            } catch (Throwable e) {
                Log.error("Remote API request failed: " + requestLine, e);
                r = Response.error(500, e.toString());
            }
            write(sock.getOutputStream(), r);
        } catch (Exception e) {
            // client went away or timed out
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("header line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    private static void write(OutputStream out, Response r) throws IOException {
        String head = "HTTP/1.1 " + r.status + " " + reason(r.status) + "\r\n"
                + "Content-Type: " + r.contentType + "\r\n"
                + "Content-Length: " + r.body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(r.body);
        out.flush();
    }

    private static String reason(int s) {
        switch (s) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 501: return "Not Implemented";
            case 503: return "Service Unavailable";
            default: return "Error";
        }
    }
}
