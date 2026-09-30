package splusjava.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import splusjava.SoroushException;
import splusjava.util.Bytes;

/** AES-256 in IGE mode (Infinite Garble Extension), the block mode used by MTProto. */
public final class AesIge {
    private AesIge() {
    }

    private static Cipher cipher(byte[] key, int mode) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"));
        return c;
    }

    /** Encrypts; if the input is not a multiple of 16 bytes it is padded with random bytes (like the Python library). */
    public static byte[] encrypt(byte[] plain, byte[] key, byte[] iv) {
        if (iv.length != 32) {
            throw new IllegalArgumentException("IGE iv must be 32 bytes");
        }
        int rem = plain.length % 16;
        if (rem != 0) {
            plain = Bytes.concat(plain, Bytes.random(16 - rem));
        }
        try {
            Cipher c = cipher(key, Cipher.ENCRYPT_MODE);
            byte[] out = new byte[plain.length];
            byte[] iv1 = Bytes.sub(iv, 0, 16);
            byte[] iv2 = Bytes.sub(iv, 16, 16);
            byte[] block = new byte[16];
            byte[] enc = new byte[16];
            for (int off = 0; off < plain.length; off += 16) {
                for (int i = 0; i < 16; i++) {
                    block[i] = (byte) (plain[off + i] ^ iv1[i]);
                }
                c.update(block, 0, 16, enc, 0);
                for (int i = 0; i < 16; i++) {
                    out[off + i] = (byte) (enc[i] ^ iv2[i]);
                }
                System.arraycopy(out, off, iv1, 0, 16);
                System.arraycopy(plain, off, iv2, 0, 16);
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new SoroushException("AES failure: " + e, e);
        }
    }

    public static byte[] decrypt(byte[] cipherText, byte[] key, byte[] iv) {
        if (iv.length != 32) {
            throw new IllegalArgumentException("IGE iv must be 32 bytes");
        }
        if (cipherText.length % 16 != 0) {
            throw new IllegalArgumentException("IGE data must be a multiple of 16 bytes");
        }
        try {
            Cipher c = cipher(key, Cipher.DECRYPT_MODE);
            byte[] out = new byte[cipherText.length];
            byte[] iv1 = Bytes.sub(iv, 0, 16);
            byte[] iv2 = Bytes.sub(iv, 16, 16);
            byte[] block = new byte[16];
            byte[] dec = new byte[16];
            for (int off = 0; off < cipherText.length; off += 16) {
                for (int i = 0; i < 16; i++) {
                    block[i] = (byte) (cipherText[off + i] ^ iv2[i]);
                }
                c.update(block, 0, 16, dec, 0);
                for (int i = 0; i < 16; i++) {
                    out[off + i] = (byte) (dec[i] ^ iv1[i]);
                }
                System.arraycopy(cipherText, off, iv1, 0, 16);
                System.arraycopy(out, off, iv2, 0, 16);
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new SoroushException("AES failure: " + e, e);
        }
    }
}
