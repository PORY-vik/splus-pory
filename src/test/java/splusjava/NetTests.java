package splusjava;

import splusjava.net.AbridgedCodec;
import splusjava.net.ObfuscatedIo;
import splusjava.util.Bytes;

final class NetTests {
    private NetTests() {
    }

    static void run() {
        T.section("Obfuscated transport (obfuscated2) vs. reference python implementation");
        ObfuscatedIo io = ObfuscatedIo.create(Bytes.fromHex(ObfsVectors.RANDOM64));
        T.eq(ObfsVectors.HEADER, Bytes.toHex(io.getHeader()), "obfuscation header");
        T.eq(ObfsVectors.ENCRYPTED, Bytes.toHex(io.encrypt(Bytes.fromHex(ObfsVectors.SAMPLE))), "obfuscation encrypt stream");

        T.section("Abridged packet codec vs. reference python implementation");
        T.eq(AbridgedVectors.SMALL_ENCODED, Bytes.toHex(AbridgedCodec.encode(Bytes.fromHex(AbridgedVectors.SMALL_DATA))), "abridged small packet");
        T.eq(AbridgedVectors.BIG_ENCODED, Bytes.toHex(AbridgedCodec.encode(Bytes.fromHex(AbridgedVectors.BIG_DATA))), "abridged big packet (>=508 bytes)");

        byte[] encodedSmall = Bytes.fromHex(AbridgedVectors.SMALL_ENCODED);
        T.eq(AbridgedVectors.SMALL_DATA, Bytes.toHex(AbridgedCodec.decode(source(encodedSmall))), "abridged small packet decode");
        byte[] encodedBig = Bytes.fromHex(AbridgedVectors.BIG_ENCODED);
        T.eq(AbridgedVectors.BIG_DATA, Bytes.toHex(AbridgedCodec.decode(source(encodedBig))), "abridged big packet decode");
    }


    private static AbridgedCodec.ByteSource source(final byte[] data) {
        return new AbridgedCodec.ByteSource() {
            int pos;

            public byte readByte() {
                return data[pos++];
            }

            public byte[] readExact(int n) {
                byte[] out = Bytes.sub(data, pos, n);
                pos += n;
                return out;
            }
        };
    }
}
