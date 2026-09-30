package splusjava.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Minimal logging facade (java.util.logging shows up in logcat on Android). */
public final class Log {
    private static final Logger LOGGER = Logger.getLogger("splusjava");
    private static volatile boolean debug;

    private Log() {
    }

    /** Enables verbose protocol logging. */
    public static void setDebug(boolean enabled) {
        debug = enabled;
    }

    public static boolean isDebug() {
        return debug;
    }

    public static void d(String msg) {
        if (debug) {
            LOGGER.log(Level.INFO, "[splusjava] " + msg);
        }
    }

    public static void i(String msg) {
        LOGGER.log(Level.INFO, "[splusjava] " + msg);
    }

    public static void w(String msg) {
        LOGGER.log(Level.WARNING, "[splusjava] " + msg);
    }

    public static void w(String msg, Throwable t) {
        LOGGER.log(Level.WARNING, "[splusjava] " + msg, t);
    }
}
