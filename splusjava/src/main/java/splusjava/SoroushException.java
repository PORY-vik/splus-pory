package splusjava;

/**
 * Base class of every error raised by this library.
 * It is unchecked on purpose, so it can be used from Sketchware blocks without forced try/catch.
 */
public class SoroushException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SoroushException(String message) {
        super(message);
    }

    public SoroushException(String message, Throwable cause) {
        super(message, cause);
    }
}
