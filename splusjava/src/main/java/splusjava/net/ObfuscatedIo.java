package splusjava.net;

import splusjava.crypto.AesCtr;
import splusjava.util.Bytes;

/**
 * "obfuscated2" transport wrapper: a random 64-byte header (interpreted in reverse for the read
 * direction) seeds two independent AES-256-CTR streams that XOR-obfuscate everything sent and
 * received afterwards. Used here on top of the abridged packet codec, itself carried inside
 * WebSocket binary frames (matching SPlusthon's {@code ConnectionWebSocket}).
 */
public final class ObfuscatedIo {
    private static final byte[] OBFUSCATE_TAG = {(byte) 0xef, (byte) 0xef, (byte) 0xef, (byte) 0xef};
    private static final byte[][] FORBIDDEN_PREFIXES = {
            {(byte) 'P', (byte) 'V', (byte) 'r', (byte) 'G'},
            {(byte) 'G', (byte) 'E', (byte) 'T', (byte) ' '},
            {(byte) 'P', (byte) 'O', (byte) 'S', (byte) 'T'},
            {(byte) 0xee, (byte) 0xee, (byte) 0xee, (byte) 0xee},
    };

    private final byte[] header;
    private final AesCtr encryptor;
    private final AesCtr decryptor;

    private ObfuscatedIo(byte[] header, AesCtr encryptor, AesCtr decryptor) {
        this.header = header;
        this.encryptor = encryptor;
        this.decryptor = decryptor;
    }

    /** Builds a fresh, randomly seeded obfuscation context; {@link #getHeader()} must be sent first. */
    public static ObfuscatedIo create() {
        return create(randomAcceptableHeaderSeed());
    }

    /** Builds the context from an explicit 64-byte seed instead of a random one (mainly for tests). */
    public static ObfuscatedIo create(byte[] random64) {
        if (random64.length != 64) {
            throw new IllegalArgumentException("seed must be 64 bytes");
        }
        byte[] random = random64.clone();
        byte[] reversed = new byte[48]; // random[55:7:-1] -> 48 bytes, indices 55 downTo 8
        for (int i = 0; i < 48; i++) {
            reversed[i] = random[55 - i];
        }
        byte[] encryptKey = Bytes.sub(random, 8, 32);
        byte[] encryptIv = Bytes.sub(random, 40, 16);
        byte[] decryptKey = Bytes.sub(reversed, 0, 32);
        byte[] decryptIv = Bytes.sub(reversed, 32, 16);

        AesCtr encryptor = new AesCtr(encryptKey, encryptIv);
        AesCtr decryptor = new AesCtr(decryptKey, decryptIv);

        System.arraycopy(OBFUSCATE_TAG, 0, random, 56, 4);
        byte[] encryptedFull = encryptor.process(random);
        System.arraycopy(encryptedFull, 56, random, 56, 8);
        return new ObfuscatedIo(random, encryptor, decryptor);
    }

    private static byte[] randomAcceptableHeaderSeed() {
        byte[] random;
        while (true) {
            random = Bytes.random(64);
            if ((random[0] & 0xff) == 0xef) {
                continue;
            }
            if (hasPrefix(random) || (random[4] == 0 && random[5] == 0 && random[6] == 0 && random[7] == 0)) {
                continue;
            }
            return random;
        }
    }

    private static boolean hasPrefix(byte[] random) {
        for (int i = 0; i < FORBIDDEN_PREFIXES.length; i++) {
            byte[] p = FORBIDDEN_PREFIXES[i];
            if (random[0] == p[0] && random[1] == p[1] && random[2] == p[2] && random[3] == p[3]) {
                return true;
            }
        }
        return false;
    }

    /** The 64 bytes that must be sent to the server before anything else (already includes the encrypted tag). */
    public byte[] getHeader() {
        return header.clone();
    }

    public synchronized byte[] encrypt(byte[] data) {
        return encryptor.process(data);
    }

    public synchronized byte[] decrypt(byte[] data) {
        return decryptor.process(data);
    }
}
