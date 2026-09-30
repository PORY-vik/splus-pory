package splusjava.net;

import java.io.IOException;

/** Moves whole MTProto packets between client and server. */
public interface Transport {
    void connect() throws IOException;

    /** Sends one packet (its length must be a multiple of 4). */
    void send(byte[] packet) throws IOException;

    /** Blocks until the next packet arrives. */
    byte[] receive() throws IOException;

    /** Sends a lightweight keep-alive frame if the transport supports it. */
    void keepAlive() throws IOException;

    void close();
}
