package remoteapi;

/** Writes to FireCapture's own log (~/FireCapture/log.txt) when available, else stderr. */
final class Log {
    private Log() { }

    static void info(String msg) {
        try {
            de.wonderplanets.firecapture.log.Log.logInfo(msg);
        } catch (LinkageError e) {
            System.err.println(msg);
        }
    }

    static void error(String msg, Throwable t) {
        try {
            de.wonderplanets.firecapture.log.Log.logError(msg, t);
        } catch (LinkageError e) {
            System.err.println(msg + ": " + t);
        }
    }
}
