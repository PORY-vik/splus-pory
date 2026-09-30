package splusjava.mtproto;

import java.math.BigInteger;

import splusjava.SoroushException;
import splusjava.crypto.AesIge;
import splusjava.crypto.Factorizer;
import splusjava.crypto.RsaKeys;
import splusjava.tl.TLObject;
import splusjava.tl.TLReader;
import splusjava.util.Bytes;

/**
 * Performs the unencrypted key-exchange handshake (see
 * <a href="https://core.telegram.org/mtproto/auth_key">core.telegram.org/mtproto/auth_key</a>) that
 * produces a fresh {@link AuthKey} shared with the server. Needs a way to send a plain (unencrypted)
 * request and get its answer back; see {@link PlainSender}.
 */
public final class Authenticator {
    private static final int SAFETY_BITS = 2048 - 64;

    private Authenticator() {
    }

    /** Sends one plain request and returns the raw answer body (no encryption, but framed as msg_id+len+body). */
    public interface PlainSender {
        TLObject send(TLObject request);
    }

    public static final class Result {
        public final AuthKey authKey;
        public final int timeOffset;

        Result(AuthKey authKey, int timeOffset) {
            this.authKey = authKey;
            this.timeOffset = timeOffset;
        }
    }

    /** Runs the full handshake and returns the resulting {@link AuthKey} plus the server time offset. */
    public static Result authenticate(PlainSender sender) {
        byte[] nonceBytes = Bytes.random(16);
        BigInteger nonce = new BigInteger(nonceBytes);

        TLObject resPq = sender.send(TLObject.of("req_pq_multi", "nonce", toInt128(nonce)));
        requireName(resPq, "resPQ");
        BigInteger serverNonce = fromBytes(resPq.getBytes("server_nonce"));
        if (!nonce.equals(fromBytes(resPq.getBytes("nonce")))) {
            throw new SoroushException("Step 1: invalid nonce from server");
        }
        BigInteger pq = fromBytesUnsigned(resPq.getBytes("pq"));

        BigInteger[] pf = Factorizer.factorize(pq);
        byte[] pBytes = Bytes.toBytes(pf[0]);
        byte[] qBytes = Bytes.toBytes(pf[1]);
        byte[] newNonceBytes = Bytes.random(32);
        BigInteger newNonce = fromBytesLE(newNonceBytes);

        TLObject pqInner = TLObject.of("p_q_inner_data",
                "pq", Bytes.toBytes(pq), "p", pBytes, "q", qBytes,
                "nonce", toInt128(nonce), "server_nonce", toInt128(serverNonce), "new_nonce", toInt256(newNonce));
        byte[] pqInnerBytes = pqInner.toBytes();

        long[] fingerprints = toLongArray(resPq.getList("server_public_key_fingerprints"));
        byte[] cipherText = null;
        long targetFingerprint = 0;
        for (int i = 0; i < fingerprints.length; i++) {
            cipherText = RsaKeys.encrypt(fingerprints[i], pqInnerBytes);
            if (cipherText != null) {
                targetFingerprint = fingerprints[i];
                break;
            }
        }
        if (cipherText == null) {
            throw new SoroushException("Step 2: no known RSA key among the server fingerprints");
        }

        TLObject dhParams = sender.send(TLObject.of("req_DH_params",
                "nonce", toInt128(nonce), "server_nonce", toInt128(serverNonce), "p", pBytes, "q", qBytes,
                "public_key_fingerprint", Long.valueOf(targetFingerprint), "encrypted_data", cipherText));

        if (!nonce.equals(fromBytes(dhParams.getBytes("nonce")))) {
            throw new SoroushException("Step 2: invalid nonce from server");
        }
        if (!serverNonce.equals(fromBytes(dhParams.getBytes("server_nonce")))) {
            throw new SoroushException("Step 2: invalid server nonce from server");
        }
        if ("server_DH_params_fail".equals(dhParams.getName())) {
            byte[] expected = Bytes.sub(Bytes.sha1(newNonceBytes), 4, 16);
            if (!Bytes.toHex(expected).equals(Bytes.toHex(dhParams.getBytes("new_nonce_hash")))) {
                throw new SoroushException("Step 2: invalid DH-fail nonce from server");
            }
            throw new SoroushException("Step 2: server replied server_DH_params_fail");
        }
        if (!"server_DH_params_ok".equals(dhParams.getName())) {
            throw new SoroushException("Step 2: unexpected reply " + dhParams.getName());
        }

        byte[][] kv = generateKeyDataFromNonce(toBytesLE(serverNonce, 16), newNonceBytes);
        byte[] key = kv[0];
        byte[] iv = kv[1];
        byte[] encryptedAnswer = dhParams.getBytes("encrypted_answer");
        if (encryptedAnswer.length % 16 != 0) {
            throw new SoroushException("Step 3: AES block size mismatch");
        }
        byte[] plainAnswer = AesIge.decrypt(encryptedAnswer, key, iv);
        // first 20 bytes: sha1 hash of what follows (not re-verified here, mirrors the reference client)
        TLReader r = new TLReader(plainAnswer, 20, plainAnswer.length - 20);
        Object inner = splusjava.tl.TLCodec.readObject(r);
        if (!(inner instanceof TLObject) || !"server_DH_inner_data".equals(((TLObject) inner).getName())) {
            throw new SoroushException("Step 3: unexpected inner object");
        }
        TLObject serverDh = (TLObject) inner;
        if (!nonce.equals(fromBytes(serverDh.getBytes("nonce")))) {
            throw new SoroushException("Step 3: invalid nonce in encrypted answer");
        }
        if (!serverNonce.equals(fromBytes(serverDh.getBytes("server_nonce")))) {
            throw new SoroushException("Step 3: invalid server nonce in encrypted answer");
        }

        BigInteger dhPrime = fromBytesUnsigned(serverDh.getBytes("dh_prime"));
        int g = serverDh.getInt("g");
        BigInteger gBig = BigInteger.valueOf(g);
        BigInteger gA = fromBytesUnsigned(serverDh.getBytes("g_a"));
        int timeOffset = serverDh.getInt("server_time") - (int) (System.currentTimeMillis() / 1000L);

        BigInteger b = fromBytesUnsigned(Bytes.random(256));
        BigInteger gB = gBig.modPow(b, dhPrime);
        BigInteger gab = gA.modPow(b, dhPrime);

        BigInteger one = BigInteger.ONE;
        BigInteger dhPrimeMinus1 = dhPrime.subtract(one);
        if (!(gBig.compareTo(one) > 0 && gBig.compareTo(dhPrimeMinus1) < 0)) {
            throw new SoroushException("Step 3: g is not within (1, dh_prime - 1)");
        }
        if (!(gA.compareTo(one) > 0 && gA.compareTo(dhPrimeMinus1) < 0)) {
            throw new SoroushException("Step 3: g_a is not within (1, dh_prime - 1)");
        }
        if (!(gB.compareTo(one) > 0 && gB.compareTo(dhPrimeMinus1) < 0)) {
            throw new SoroushException("Step 3: g_b is not within (1, dh_prime - 1)");
        }
        BigInteger safety = BigInteger.ONE.shiftLeft(SAFETY_BITS);
        BigInteger upperBound = dhPrime.subtract(safety);
        if (!(gA.compareTo(safety) >= 0 && gA.compareTo(upperBound) <= 0)) {
            throw new SoroushException("Step 3: g_a is not within the safety range");
        }
        if (!(gB.compareTo(safety) >= 0 && gB.compareTo(upperBound) <= 0)) {
            throw new SoroushException("Step 3: g_b is not within the safety range");
        }

        TLObject clientDhInner = TLObject.of("client_DH_inner_data",
                "nonce", toInt128(nonce), "server_nonce", toInt128(serverNonce), "retry_id", Long.valueOf(0),
                "g_b", Bytes.toBytes(gB));
        byte[] clientDhInnerBytes = clientDhInner.toBytes();
        byte[] hashed = Bytes.concat(Bytes.sha1(clientDhInnerBytes), clientDhInnerBytes);
        byte[] clientDhEncrypted = AesIge.encrypt(hashed, key, iv);

        TLObject dhGen = sender.send(TLObject.of("set_client_DH_params",
                "nonce", toInt128(nonce), "server_nonce", toInt128(serverNonce), "encrypted_data", clientDhEncrypted));

        if (!nonce.equals(fromBytes(dhGen.getBytes("nonce")))) {
            throw new SoroushException("Step 3: invalid " + dhGen.getName() + " nonce from server");
        }
        if (!serverNonce.equals(fromBytes(dhGen.getBytes("server_nonce")))) {
            throw new SoroushException("Step 3: invalid " + dhGen.getName() + " server nonce from server");
        }

        int nonceNumber;
        if ("dh_gen_ok".equals(dhGen.getName())) {
            nonceNumber = 1;
        } else if ("dh_gen_retry".equals(dhGen.getName())) {
            nonceNumber = 2;
        } else if ("dh_gen_fail".equals(dhGen.getName())) {
            nonceNumber = 3;
        } else {
            throw new SoroushException("Step 3: unexpected reply " + dhGen.getName());
        }

        AuthKey authKey = new AuthKey(Bytes.toBytes(gab, 256));
        byte[] expectedHash = authKey.calcNewNonceHash(newNonceBytes, nonceNumber);
        byte[] actualHash = dhGen.getBytes("new_nonce_hash" + nonceNumber);
        if (!Bytes.toHex(expectedHash).equals(Bytes.toHex(actualHash))) {
            throw new SoroushException("Step 3: invalid new nonce hash");
        }
        if (nonceNumber != 1) {
            throw new SoroushException("Step 3: server replied " + dhGen.getName() + " (key exchange must be retried)");
        }
        return new Result(authKey, timeOffset);
    }

    /** sha1(new_nonce+server_nonce)[0:20] + sha1(server_nonce+new_nonce)[0:12] as key, and the matching iv. */
    public static byte[][] generateKeyDataFromNonce(byte[] serverNonce16, byte[] newNonce32) {
        byte[] h1 = Bytes.sha1(Bytes.concat(newNonce32, serverNonce16));
        byte[] h2 = Bytes.sha1(Bytes.concat(serverNonce16, newNonce32));
        byte[] h3 = Bytes.sha1(Bytes.concat(newNonce32, newNonce32));
        byte[] key = Bytes.concat(h1, Bytes.sub(h2, 0, 12));
        byte[] iv = Bytes.concat(Bytes.sub(h2, 12, 8), h3, Bytes.sub(newNonce32, 0, 4));
        return new byte[][] {key, iv};
    }

    private static void requireName(TLObject o, String name) {
        if (!name.equals(o.getName())) {
            throw new SoroushException("Expected " + name + ", got " + o.getName());
        }
    }

    // int128/int256 fields are little-endian, signed two's complement 16/32 byte blocks.
    private static byte[] toInt128(BigInteger v) {
        return toBytesLE(v, 16);
    }

    private static byte[] toInt256(BigInteger v) {
        return toBytesLE(v, 32);
    }

    private static byte[] toBytesLE(BigInteger v, int len) {
        byte[] be = signedBigEndian(v, len);
        byte[] le = new byte[len];
        for (int i = 0; i < len; i++) {
            le[i] = be[len - 1 - i];
        }
        return le;
    }

    private static byte[] signedBigEndian(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        if (b.length == len) {
            return b;
        }
        byte[] out = new byte[len];
        byte fill = (byte) (v.signum() < 0 ? 0xff : 0x00);
        for (int i = 0; i < len; i++) {
            out[i] = fill;
        }
        int srcStart = Math.max(0, b.length - len);
        int dstStart = Math.max(0, len - b.length);
        System.arraycopy(b, srcStart, out, dstStart, Math.min(b.length, len));
        return out;
    }

    private static BigInteger fromBytes(byte[] leSigned16or32) {
        byte[] be = new byte[leSigned16or32.length];
        for (int i = 0; i < be.length; i++) {
            be[i] = leSigned16or32[leSigned16or32.length - 1 - i];
        }
        return new BigInteger(be);
    }

    private static BigInteger fromBytesLE(byte[] le) {
        return fromBytes(le);
    }

    private static BigInteger fromBytesUnsigned(byte[] be) {
        return new BigInteger(1, be);
    }

    private static long[] toLongArray(java.util.List<Object> list) {
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((Number) list.get(i)).longValue();
        }
        return out;
    }
}
