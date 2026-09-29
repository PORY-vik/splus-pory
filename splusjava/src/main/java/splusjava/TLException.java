package splusjava;

/** Raised when TL data cannot be serialized or parsed (unknown constructor, truncated data...). */
public class TLException extends SoroushException {
    private static final long serialVersionUID = 1L;

    private final int constructorId;

    public TLException(String message) {
        super(message);
        this.constructorId = 0;
    }

    public TLException(String message, int constructorId) {
        super(message);
        this.constructorId = constructorId;
    }

    /** The unknown constructor id, when that was the problem (otherwise 0). */
    public int getConstructorId() {
        return constructorId;
    }
}
