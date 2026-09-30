package splusjava.crypto;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import splusjava.tl.TLWriter;
import splusjava.util.Bytes;

/** The server's RSA public keys and the (raw, unpadded) RSA encryption used by MTProto key exchange. */
public final class RsaKeys {
    private static final Map<Long, BigInteger[]> KEYS = new LinkedHashMap<Long, BigInteger[]>();

    static {
        for (int i = 0; i < RsaKeyData.KEYS.length; i++) {
            addKey(new BigInteger(RsaKeyData.KEYS[i][0], 16), new BigInteger(RsaKeyData.KEYS[i][1], 16));
        }
    }

    private RsaKeys() {
    }

    /** Fingerprint used by the server to name a key: low 64 bits of sha1(tl_bytes(n) + tl_bytes(e)). */
    public static long fingerprint(BigInteger n, BigInteger e) {
        TLWriter w = new TLWriter();
        w.writeTLBytes(Bytes.toBytes(n));
        w.writeTLBytes(Bytes.toBytes(e));
        byte[] h = Bytes.sha1(w.toByteArray());
        return Bytes.readLongLE(h, 12);
    }

    public static synchronized long addKey(BigInteger n, BigInteger e) {
        long fp = fingerprint(n, e);
        KEYS.put(Long.valueOf(fp), new BigInteger[] {n, e});
        return fp;
    }

    public static synchronized boolean hasKey(long fingerprint) {
        return KEYS.containsKey(Long.valueOf(fingerprint));
    }

    public static synchronized int count() {
        return KEYS.size();
    }

    /**
     * Encrypts sha1(data) + data + random padding (255 bytes) with the key having the given fingerprint.
     *
     * @return 256 bytes, or null when no such key is known
     */
    public static byte[] encrypt(long fingerprint, byte[] data) {
        return encrypt(fingerprint, data, null);
    }

    /** Same as {@link #encrypt(long, byte[])} with an explicit padding (for tests); null = random. */
    public static byte[] encrypt(long fingerprint, byte[] data, byte[] padding) {
        BigInteger[] key;
        synchronized (RsaKeys.class) {
            key = KEYS.get(Long.valueOf(fingerprint));
        }
        if (key == null) {
            return null;
        }
        if (data.length > 235) {
            throw new IllegalArgumentException("data too long for RSA block");
        }
        if (padding == null) {
            padding = Bytes.random(235 - data.length);
        }
        byte[] block = Bytes.concat(Bytes.sha1(data), data, padding);
        BigInteger m = new BigInteger(1, block);
        return Bytes.toBytes(m.modPow(key[1], key[0]), 256);
    }
}
