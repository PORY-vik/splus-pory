package splusjava;

import java.math.BigInteger;

import splusjava.crypto.AesIge;
import splusjava.crypto.Factorizer;
import splusjava.crypto.RsaKeys;
import splusjava.mtproto.AuthKey;
import splusjava.mtproto.MTProtoState;
import splusjava.util.Bytes;

final class CryptoTests {
    private CryptoTests() {
    }

    static void run() {
        T.section("AES-IGE vs. reference python implementation");
        byte[] key = Bytes.fromHex(CryptoVectors.IGE_KEY);
        byte[] iv = Bytes.fromHex(CryptoVectors.IGE_IV);
        byte[] plain = Bytes.fromHex(CryptoVectors.IGE_PLAIN);
        byte[] cipher = AesIge.encrypt(plain, key, iv);
        T.eq(CryptoVectors.IGE_CIPHER, Bytes.toHex(cipher), "IGE encrypt");
        T.eq(CryptoVectors.IGE_PLAIN, Bytes.toHex(AesIge.decrypt(cipher, key, iv)), "IGE decrypt");

        T.section("RSA public keys vs. reference python implementation");
        String[] fps = CryptoVectors.FINGERPRINTS.split(",");
        T.eq(Integer.valueOf(fps.length), Integer.valueOf(RsaKeys.count()), "key count");
        for (int i = 0; i < fps.length; i++) {
            T.ok(RsaKeys.hasKey(Long.parseLong(fps[i])), "has key fingerprint #" + i);
        }
        byte[] rsaCipher = RsaKeys.encrypt(Long.parseLong(CryptoVectors.RSA_FP), Bytes.fromHex(CryptoVectors.RSA_DATA),
                rsaPadding(Bytes.fromHex(CryptoVectors.RSA_DATA).length));
        T.eq(CryptoVectors.RSA_CIPHER, Bytes.toHex(rsaCipher), "RSA encrypt (matches server's public key exactly)");

        T.section("Factorization (Pollard-rho) vs. reference python implementation");
        for (String triplet : CryptoVectors.FACTOR.split(",")) {
            String[] parts = triplet.split(":");
            BigInteger pq = new BigInteger(parts[0]);
            BigInteger[] pf = Factorizer.factorize(pq);
            T.eq(parts[1], pf[0].toString(), "factor p of " + pq);
            T.eq(parts[2], pf[1].toString(), "factor q of " + pq);
        }

        T.section("Key derivation (generateKeyDataFromNonce) vs. reference python implementation");
        byte[] serverNonce = seqBytes(16, 16);
        byte[] newNonce = seqBytes(32, 32);
        byte[][] kv = splusjava.mtproto.Authenticator.generateKeyDataFromNonce(serverNonce, newNonce);
        T.eq(CryptoVectors.KEX_KEY, Bytes.toHex(kv[0]), "kex key");
        T.eq(CryptoVectors.KEX_IV, Bytes.toHex(kv[1]), "kex iv");

        T.section("AuthKey vs. reference python implementation");
        byte[] authKeyBytes = Bytes.fromHex(CryptoVectors.AUTHKEY_BYTES);
        AuthKey ak = new AuthKey(authKeyBytes);
        T.eq(CryptoVectors.AUTHKEY_ID, Long.toString(ak.getKeyId()), "auth key id");
        T.eq(CryptoVectors.AUTHKEY_AUX, Long.toString(ak.getAuxHash()), "auth key aux hash");
        byte[] nnh = ak.calcNewNonceHash(newNonce, 1);
        T.eq(CryptoVectors.NNH1.substring(0, 32), Bytes.toHex(nnh), "new_nonce_hash1");

        T.section("MTProtoState encrypt/decrypt vs. reference python implementation");
        MTProtoState st = new MTProtoState(ak);
        st.setSessionId(0x1122334455667788L);
        st.setSalt(0x0102030405060708L);
        byte[] clientPlain = Bytes.fromHex(CryptoVectors.STATE_CLIENT_PLAIN);
        int padLen = Integer.parseInt(CryptoVectors.STATE_CLIENT_PADLEN);
        T.eq(Integer.valueOf(padLen), Integer.valueOf(MTProtoState.paddingLength(8 + 8 + clientPlain.length)), "computed padding length");
        byte[] padding = statePadding(padLen);
        byte[] wire = st.encrypt(clientPlain, padding);
        T.eq(CryptoVectors.STATE_CLIENT_WIRE, Bytes.toHex(wire), "client encrypt");

        byte[] serverWire = Bytes.fromHex(CryptoVectors.STATE_SERVER_WIRE);
        st.setTimeOffset(1700000005 - (int) (System.currentTimeMillis() / 1000L)); // vector's server clock, for the freshness check
        MTProtoState.Incoming in = st.decrypt(serverWire);
        T.ok(in != null, "server message decrypts");
        T.eq(CryptoVectors.STATE_SERVER_MSG_ID, Long.toString(in.msgId), "server msg_id");
        T.eq(CryptoVectors.STATE_SERVER_BODY, Bytes.toHex(in.body), "server body");

        T.section("MTProtoState security checks");
        MTProtoState bad = new MTProtoState(new AuthKey(Bytes.random(256)));
        try {
            bad.decrypt(serverWire);
            T.ok(false, "wrong auth key must be rejected");
        } catch (splusjava.mtproto.MessageSecurityException e) {
            T.ok(true, "wrong auth key rejected");
        }
        try {
            new MTProtoState(ak).decrypt(new byte[] {1, 2, 3, 4});
            T.ok(false, "4-byte body must raise a TransportException");
        } catch (splusjava.TransportException e) {
            T.ok(true, "4-byte transport error code raised");
        }
    }

    private static byte[] seqBytes(int start, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ((start + i) & 0xff);
        }
        return b;
    }

    /** Reproduces gen_crypto_vectors.py's deterministic stand-in for os.urandom() used for message padding. */
    private static byte[] statePadding(int len) {
        byte[] p = new byte[len];
        for (int i = 0; i < len; i++) {
            p[i] = (byte) ((i * 11 + 7) & 0xff);
        }
        return p;
    }

    /** Reproduces gen_crypto_vectors.py's deterministic stand-in for os.urandom() used for RSA padding. */
    private static byte[] rsaPadding(int dataLen) {
        int padLen = 235 - dataLen;
        byte[] p = new byte[padLen];
        for (int i = 0; i < padLen; i++) {
            p[i] = (byte) ((i * 5 + 2) & 0xff);
        }
        return p;
    }
}
