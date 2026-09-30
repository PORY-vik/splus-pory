package splusjava.net;

import splusjava.TransportException;
import splusjava.util.Bytes;

/**
 * The "abridged" MTProto packet framing: 1 byte length (in 4-byte words) for packets under
 * 508 bytes, or {@code 0x7f} followed by a 3-byte little-endian length otherwise. No tag byte is
 * sent in front of packets on the obfuscated transport (the obfuscation header replaces it).
 */
public final class AbridgedCodec {
    private static final int MAX_PACKET_WORDS = (1 << 24) - 1;

    private AbridgedCodec() {
    }

    public static byte[] encode(byte[] data) {
        if (data.length % 4 != 0) {
            throw new IllegalArgumentException("packet length must be a multiple of 4");
        }
        int words = data.length >> 2;
        if (words < 127) {
            byte[] out = new byte[1 + data.length];
            out[0] = (byte) words;
            System.arraycopy(data, 0, out, 1, data.length);
            return out;
        }
        if (words > MAX_PACKET_WORDS) {
            throw new IllegalArgumentException("packet too large");
        }
        byte[] out = new byte[4 + data.length];
        out[0] = 0x7f;
        out[1] = (byte) words;
        out[2] = (byte) (words >> 8);
        out[3] = (byte) (words >> 16);
        System.arraycopy(data, 0, out, 4, data.length);
        return out;
    }

    /** Reads one packet's length header and payload from a byte source. */
    public static byte[] decode(ByteSource in) {
        int first = in.readByte() & 0xff;
        int words;
        if (first < 127) {
            words = first;
        } else {
            words = (in.readByte() & 0xff) | ((in.readByte() & 0xff) << 8) | ((in.readByte() & 0xff) << 16);
        }
        int len = words << 2;
        if (len < 0) {
            throw new TransportException("Invalid abridged packet length");
        }
        return in.readExact(len);
    }

    /** Minimal pull source so {@link #decode} can work over sockets, buffers, or tests alike. */
    public interface ByteSource {
        byte readByte();

        byte[] readExact(int n);
    }
}
