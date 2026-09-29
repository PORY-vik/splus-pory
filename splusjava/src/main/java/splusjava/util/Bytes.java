package splusjava.util;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** Small byte[] helpers (Java 7 / Android friendly, no external dependencies). */
public final class Bytes {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Bytes() {
    }

    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (int i = 0; i < parts.length; i++) {
            total += parts[i].length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            System.arraycopy(parts[i], 0, out, pos, parts[i].length);
            pos += parts[i].length;
        }
        return out;
    }

    public static byte[] sub(byte[] a, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(a, off, out, 0, len);
        return out;
    }

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    public static long randomLong() {
        return RANDOM.nextLong();
    }

    public static SecureRandom secureRandom() {
        return RANDOM;
    }

    public static String toHex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = HEX[(b[i] >> 4) & 0xf];
            c[i * 2 + 1] = HEX[b[i] & 0xf];
        }
        return new String(c);
    }

    public static byte[] fromHex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static byte[] sha1(byte[]... parts) {
        return digest("SHA-1", parts);
    }

    public static byte[] sha256(byte[]... parts) {
        return digest("SHA-256", parts);
    }

    private static byte[] digest(String algo, byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            for (int i = 0; i < parts.length; i++) {
                md.update(parts[i]);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal big-endian, unsigned representation (empty array for zero, like the Python library). */
    public static byte[] toBytes(BigInteger v) {
        if (v.signum() == 0) {
            return new byte[0];
        }
        byte[] b = v.toByteArray();
        if (b[0] == 0 && b.length > 1) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    /** Big-endian, unsigned, left-padded with zeros to exactly {@code len} bytes. */
    public static byte[] toBytes(BigInteger v, int len) {
        byte[] m = toBytes(v);
        if (m.length > len) {
            throw new IllegalArgumentException("number does not fit in " + len + " bytes");
        }
        byte[] out = new byte[len];
        System.arraycopy(m, 0, out, len - m.length, m.length);
        return out;
    }

    public static BigInteger toBig(byte[] b) {
        return new BigInteger(1, b);
    }

    public static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    public static long readLongLE(byte[] b, int off) {
        return (readIntLE(b, off) & 0xffffffffL) | ((long) readIntLE(b, off + 4) << 32);
    }

    public static void writeIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    public static void writeLongLE(byte[] b, int off, long v) {
        writeIntLE(b, off, (int) v);
        writeIntLE(b, off + 4, (int) (v >> 32));
    }

    public static byte[] longToBytesLE(long v) {
        byte[] b = new byte[8];
        writeLongLE(b, 0, v);
        return b;
    }

    public static byte[] intToBytesLE(int v) {
        byte[] b = new byte[4];
        writeIntLE(b, 0, v);
        return b;
    }

    public static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }
}
