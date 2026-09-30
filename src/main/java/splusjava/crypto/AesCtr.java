package splusjava.crypto;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import splusjava.SoroushException;

/** Streaming AES-256-CTR, used by the "obfuscated" transport layer. */
public final class AesCtr {
    private final Cipher cipher;

    public AesCtr(byte[] key, byte[] iv) {
        try {
            cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        } catch (GeneralSecurityException e) {
            throw new SoroushException("AES-CTR failure: " + e, e);
        }
    }

    /** Encrypts (or decrypts - CTR is symmetric) the next chunk of the stream. */
    public byte[] process(byte[] data) {
        return cipher.update(data);
    }
}
