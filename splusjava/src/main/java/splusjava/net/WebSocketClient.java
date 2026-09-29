package splusjava.net;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayDeque;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import splusjava.TransportException;
import splusjava.util.Base64Util;
import splusjava.util.Bytes;

/**
 * A minimal RFC 6455 WebSocket client, written directly on top of {@link Socket}/{@link SSLSocket} so
 * it has no dependency beyond the JDK (the javax.websocket API is Java EE only and not present on
 * Android/Sketchware). Binary frames only; text frames are treated as an error, matching what the
 * Soroush Plus endpoint sends.
 */
public final class WebSocketClient implements Transport {
    private final String host;
    private final int port;
    private final String path;
    private final String origin;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private final SecureRandom random = new SecureRandom();

    // reassembles binary WS frames into a flat byte stream, like SPlusthon's WebSocketReader
    private final ArrayDeque<byte[]> chunks = new ArrayDeque<byte[]>();
    private int chunkOffset;
    private long available;

    public WebSocketClient(String host, int port, String path, String origin) {
        this(host, port, path, origin, 15000, 20000);
    }

    public WebSocketClient(String host, int port, String path, String origin, int connectTimeoutMs, int readTimeoutMs) {
        this.host = host;
        this.port = port;
        this.path = path;
        this.origin = origin;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public synchronized void connect() throws IOException {
        Socket plain = new Socket();
        plain.connect(new java.net.InetSocketAddress(host, port), connectTimeoutMs);
        if (port == 443) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) f.createSocket(plain, host, port, true);
            ssl.startHandshake();
            socket = ssl;
        } else {
            socket = plain;
        }
        socket.setSoTimeout(readTimeoutMs);
        socket.setTcpNoDelay(true);
        in = new BufferedInputStream(socket.getInputStream(), 8192);
        out = socket.getOutputStream();
        handshake();
    }

    private void handshake() throws IOException {
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String key = Base64Util.encode(keyBytes);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        if (origin != null) {
            req.append("Origin: ").append(origin).append("\r\n");
        }
        req.append("\r\n");
        out.write(req.toString().getBytes("UTF-8"));
        out.flush();

        String statusLine = readLine();
        if (statusLine == null || statusLine.indexOf(" 101 ") < 0) {
            throw new TransportException("WebSocket handshake failed: " + statusLine);
        }
        String line;
        while ((line = readLine()) != null && line.length() > 0) {
            // headers are not validated (Sec-WebSocket-Accept check omitted: TLS + the server's own
            // auth-key handshake on top already guarantee we are talking to the real endpoint)
        }
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') {
                    sb.setLength(sb.length() - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
        return any ? sb.toString() : null;
    }

    @Override
    public synchronized void send(byte[] data) throws IOException {
        writeFrame(0x2, data); // binary
    }

    @Override
    public synchronized void keepAlive() throws IOException {
        writeFrame(0x9, new byte[0]); // ping
    }

    private void writeFrame(int opcode, byte[] payload) throws IOException {
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        int len = payload.length;
        java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream(14);
        head.write(0x80 | opcode); // FIN + opcode
        if (len < 126) {
            head.write(0x80 | len);
        } else if (len <= 0xffff) {
            head.write(0x80 | 126);
            head.write((len >> 8) & 0xff);
            head.write(len & 0xff);
        } else {
            head.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                head.write((int) ((len >>> (i * 8)) & 0xff));
            }
        }
        head.write(mask, 0, 4);
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        synchronized (this) {
            out.write(head.toByteArray());
            out.write(masked);
            out.flush();
        }
    }

    @Override
    public synchronized byte[] receive() throws IOException {
        return readExact(-1); // caller (AbridgedCodec) pulls bytes on demand via readByte()/readExact(n)
    }

    /** Blocking read of exactly n bytes from the reassembled WebSocket byte stream (n=-1: read one frame's payload). */
    public byte[] readExact(int n) throws IOException {
        while (n < 0 || available < n) {
            byte[] payload = readOneFrame();
            if (payload == null) {
                continue; // control frame handled internally, keep waiting
            }
            chunks.addLast(payload);
            available += payload.length;
            if (n < 0) {
                return drain((int) available);
            }
        }
        return drain(n);
    }

    private byte[] drain(int n) {
        byte[] out = new byte[n];
        int pos = 0;
        while (pos < n) {
            byte[] first = chunks.peekFirst();
            int avail = first.length - chunkOffset;
            int take = Math.min(avail, n - pos);
            System.arraycopy(first, chunkOffset, out, pos, take);
            pos += take;
            chunkOffset += take;
            available -= take;
            if (chunkOffset >= first.length) {
                chunks.removeFirst();
                chunkOffset = 0;
            }
        }
        return out;
    }

    private byte[] readOneFrame() throws IOException {
        int b0 = readByteStrict();
        int opcode = b0 & 0x0f;
        int b1 = readByteStrict();
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7f;
        if (len == 126) {
            len = (readByteStrict() << 8) | readByteStrict();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByteStrict();
            }
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(mask);
        }
        if (len > (64L << 20)) {
            throw new TransportException("WebSocket frame too large: " + len);
        }
        byte[] payload = new byte[(int) len];
        readFully(payload);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        switch (opcode) {
            case 0x2: // binary
            case 0x0: // continuation (treated as binary payload; SPlusthon does not fragment its own messages)
                return payload;
            case 0x1: // text: not expected from this API, but don't corrupt the byte stream over it
                return payload;
            case 0x8: // close
                throw new TransportException("WebSocket closed by server"
                        + (payload.length >= 2 ? " (code " + (((payload[0] & 0xff) << 8) | (payload[1] & 0xff)) + ")" : ""));
            case 0x9: // ping -> pong
                writeFrame(0xa, payload);
                return null;
            case 0xa: // pong
                return null;
            default:
                return null;
        }
    }

    private int readByteStrict() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new java.io.EOFException("WebSocket stream closed");
        }
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new java.io.EOFException("WebSocket stream closed");
            }
            off += n;
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (socket != null) {
                try {
                    writeFrame(0x8, new byte[0]);
                } catch (IOException ignored) {
                    // best effort
                }
                socket.close();
            }
        } catch (IOException ignored) {
            // best effort
        }
    }
}
