package splusjava.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * PBKDF2 with HMAC-SHA512 as the pseudo-random function, implemented from scratch on top of
 * {@link MessageDigest} ("SHA-512", present on every JDK/Android since forever). Not used through
 * {@code javax.crypto.SecretKeyFactory} on purpose: the "PBKDF2WithHmacSHA512" algorithm name is only
 * guaranteed from Java 8 onward, which would break the Java 7 / older-Android compatibility goal.
 */
public final class Pbkdf2Sha512 {
    private static final int HASH_LEN = 64;  // SHA-512 output
    private static final int BLOCK_LEN = 128; // SHA-512 block size

    private Pbkdf2Sha512() {
    }

    public static byte[] derive(byte[] password, byte[] salt, int iterations, int dkLen) {
        byte[] out = new byte[dkLen];
        int blocks = (dkLen + HASH_LEN - 1) / HASH_LEN;
        HmacSha512 hmac = new HmacSha512(password);
        for (int i = 1; i <= blocks; i++) {
            byte[] u = hmac.compute(concat(salt, intToBytes(i)));
            byte[] t = u.clone();
            for (int j = 1; j < iterations; j++) {
                u = hmac.compute(u);
                for (int k = 0; k < t.length; k++) {
                    t[k] ^= u[k];
                }
            }
            int off = (i - 1) * HASH_LEN;
            int len = Math.min(HASH_LEN, dkLen - off);
            System.arraycopy(t, 0, out, off, len);
        }
        return out;
    }

    private static byte[] intToBytes(int v) {
        return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Minimal HMAC-SHA512 built directly on MessageDigest, reused across PBKDF2 blocks for speed. */
    private static final class HmacSha512 {
        private final byte[] innerPad; // (key xor ipad) precomputed prefix
        private final byte[] outerPad; // (key xor opad) precomputed prefix

        HmacSha512(byte[] key) {
            if (key.length > BLOCK_LEN) {
                key = sha512(key);
            }
            byte[] k = new byte[BLOCK_LEN];
            System.arraycopy(key, 0, k, 0, key.length);
            innerPad = new byte[BLOCK_LEN];
            outerPad = new byte[BLOCK_LEN];
            for (int i = 0; i < BLOCK_LEN; i++) {
                innerPad[i] = (byte) (k[i] ^ 0x36);
                outerPad[i] = (byte) (k[i] ^ 0x5c);
            }
        }

        byte[] compute(byte[] message) {
            MessageDigest md = digest();
            md.update(innerPad);
            md.update(message);
            byte[] inner = md.digest();
            md.reset();
            md.update(outerPad);
            md.update(inner);
            return md.digest();
        }

        private static MessageDigest digest() {
            try {
                return MessageDigest.getInstance("SHA-512");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static byte[] sha512(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
