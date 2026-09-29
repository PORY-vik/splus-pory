package splusjava.mtproto;

import java.util.Arrays;

import splusjava.util.Bytes;

/** The 256-byte authorization key shared with the server, plus the ids derived from it. */
public final class AuthKey {
    private final byte[] key;
    private final long auxHash;
    private final long keyId;

    public AuthKey(byte[] key) {
        if (key.length != 256) {
            throw new IllegalArgumentException("auth key must be 256 bytes, got " + key.length);
        }
        this.key = key.clone();
        byte[] h = Bytes.sha1(key);
        this.auxHash = Bytes.readLongLE(h, 0);
        this.keyId = Bytes.readLongLE(h, 12);
    }

    public byte[] getKey() {
        return key.clone();
    }

    byte[] raw() {
        return key;
    }

    public long getKeyId() {
        return keyId;
    }

    public long getAuxHash() {
        return auxHash;
    }

    /** sha1(new_nonce + number + aux_hash)[4:20], as used to validate dh_gen_* answers. */
    public byte[] calcNewNonceHash(byte[] newNonce, int number) {
        byte[] data = Bytes.concat(newNonce, new byte[] {(byte) number}, Bytes.longToBytesLE(auxHash));
        return Bytes.sub(Bytes.sha1(data), 4, 16);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AuthKey && Arrays.equals(key, ((AuthKey) o).key);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(key);
    }
}
