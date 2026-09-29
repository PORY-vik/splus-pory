package splusjava.mtproto;

import splusjava.SoroushException;

/** An incoming packet failed one of the MTProto security checks (bad msg_key, wrong session...). */
public class MessageSecurityException extends SoroushException {
    private static final long serialVersionUID = 1L;

    public MessageSecurityException(String message) {
        super(message);
    }
}
