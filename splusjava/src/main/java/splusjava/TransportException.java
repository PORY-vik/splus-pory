package splusjava;

/**
 * Connection level failure. {@link #getCode()} is the negative "transport error" number sent by the
 * server (for example 404 = auth key unknown) or 0 when it is a plain I/O problem.
 */
public class TransportException extends SoroushException {
    private static final long serialVersionUID = 1L;

    private final int code;

    public TransportException(String message, Throwable cause) {
        super(message, cause);
        this.code = 0;
    }

    public TransportException(String message) {
        super(message);
        this.code = 0;
    }

    public TransportException(int code) {
        super("Transport error " + code);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
