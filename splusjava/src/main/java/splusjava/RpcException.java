package splusjava;

/** An error returned by the Soroush Plus server for a request (rpc_error). */
public class RpcException extends SoroushException {
    private static final long serialVersionUID = 1L;

    private final int errorCode;
    private final String errorMessage;

    public RpcException(int errorCode, String errorMessage) {
        super("RPC error " + errorCode + ": " + errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /** Numeric code, e.g. 400, 401, 420, 500. */
    public int getErrorCode() {
        return errorCode;
    }

    /** Server message, e.g. "PHONE_CODE_INVALID" or "SESSION_PASSWORD_NEEDED". */
    public String getErrorMessage() {
        return errorMessage;
    }

    /** True when the server message equals or starts with the given text. */
    public boolean is(String message) {
        return errorMessage != null && errorMessage.startsWith(message);
    }

    /** Seconds to wait for FLOOD_WAIT_x errors, otherwise -1. */
    public int getFloodWaitSeconds() {
        return numberAfter("FLOOD_WAIT_");
    }

    /** Target data-center for *_MIGRATE_x errors, otherwise -1. */
    public int getMigrateDc() {
        int n = numberAfter("PHONE_MIGRATE_");
        if (n < 0) {
            n = numberAfter("USER_MIGRATE_");
        }
        if (n < 0) {
            n = numberAfter("NETWORK_MIGRATE_");
        }
        return n;
    }

    private int numberAfter(String prefix) {
        if (errorMessage != null && errorMessage.startsWith(prefix)) {
            try {
                return Integer.parseInt(errorMessage.substring(prefix.length()));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
}
