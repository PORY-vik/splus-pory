package splusjava.net;

import java.io.IOException;

import splusjava.SoroushException;
import splusjava.TransportException;

/**
 * One physical connection to a Soroush Plus data center: a {@link WebSocketClient} carrying the
 * "obfuscated2" transport ({@link ObfuscatedIo}) with abridged packet framing ({@link AbridgedCodec}).
 * This mirrors SPlusthon's {@code ConnectionWebSocket} class. Not thread safe for concurrent sends;
 * {@link splusjava.mtproto.MTProtoSender} serializes access.
 */
public final class SoroushConnection {
    private static final String ORIGIN = "https://web.splus.ir";
    private static final String PATH = "/apiws";

    private final WebSocketClient ws;
    private ObfuscatedIo io;
    private volatile boolean connected;

    public SoroushConnection(String host, int port) {
        this.ws = new WebSocketClient(host, port, PATH, ORIGIN);
    }

    public synchronized void connect() {
        try {
            ws.connect();
            io = ObfuscatedIo.create();
            ws.send(io.getHeader());
            connected = true;
        } catch (IOException e) {
            throw new TransportException("Could not connect: " + e, e);
        }
    }

    public boolean isConnected() {
        return connected;
    }

    /** Sends one MTProto packet (already fully assembled: plain header, or auth-key-id+msg-key+cipher). */
    public synchronized void sendPacket(byte[] data) {
        try {
            byte[] framed = AbridgedCodec.encode(data);
            ws.send(io.encrypt(framed));
        } catch (IOException e) {
            connected = false;
            throw new TransportException("Send failed: " + e, e);
        }
    }

    /** Blocks until one full MTProto packet has been received. */
    public byte[] receivePacket() {
        try {
            return AbridgedCodec.decode(source);
        } catch (SoroushException e) {
            throw e;
        } catch (Exception e) {
            connected = false;
            throw new TransportException("Receive failed: " + e, e);
        }
    }

    private final AbridgedCodec.ByteSource source = new AbridgedCodec.ByteSource() {
        public byte readByte() {
            return readExact(1)[0];
        }

        public byte[] readExact(int n) {
            try {
                byte[] raw = ws.readExact(n);
                return io.decrypt(raw);
            } catch (IOException e) {
                connected = false;
                throw new TransportException("Receive failed: " + e, e);
            }
        }
    };

    public synchronized void close() {
        connected = false;
        ws.close();
    }
}
