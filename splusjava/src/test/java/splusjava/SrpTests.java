package splusjava;

import splusjava.crypto.Pbkdf2Sha512;
import splusjava.crypto.SrpPassword;
import splusjava.tl.TLObject;
import splusjava.util.Bytes;

final class SrpTests {
    private SrpTests() {
    }

    static void run() {
        T.section("PBKDF2-HMAC-SHA512 vs. reference python implementation");
        byte[] out = Pbkdf2Sha512.derive(SrpVectors.PBKDF2_PW.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                SrpVectors.PBKDF2_SALT.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                Integer.parseInt(SrpVectors.PBKDF2_ITER), 64);
        T.eq(SrpVectors.PBKDF2_OUT, Bytes.toHex(out), "pbkdf2-hmac-sha512");

        T.section("SRP password check vs. reference python implementation");
        byte[] hash = SrpPassword.computeHash(Bytes.fromHex(SrpVectors.SALT1), Bytes.fromHex(SrpVectors.SALT2), SrpVectors.PASSWORD);
        T.eq(SrpVectors.PW_HASH, Bytes.toHex(hash), "compute_hash");

        TLObject algo = TLObject.of("passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow",
                "salt1", Bytes.fromHex(SrpVectors.SALT1), "salt2", Bytes.fromHex(SrpVectors.SALT2),
                "g", Integer.parseInt(SrpVectors.G), "p", Bytes.fromHex(SrpVectors.GOOD_PRIME));
        TLObject accountPassword = TLObject.of("account.password",
                "has_password", Boolean.TRUE, "current_algo", algo,
                "new_algo", algo, "new_secure_algo", TLObject.of("securePasswordKdfAlgoUnknown"),
                "secure_random", new byte[0], "srp_B", Bytes.fromHex(SrpVectors.SRP_B),
                "srp_id", Long.parseLong(SrpVectors.SRP_ID));

        TLObject check = SrpPassword.computeCheck(accountPassword, SrpVectors.PASSWORD, Bytes.fromHex(SrpVectors.FIXED_A));
        T.eq(SrpVectors.RESULT_SRP_ID, Long.toString(check.getLong("srp_id")), "InputCheckPasswordSRP.srp_id");
        T.eq(SrpVectors.RESULT_A, Bytes.toHex(check.getBytes("A")), "InputCheckPasswordSRP.A");
        T.eq(SrpVectors.RESULT_M1, Bytes.toHex(check.getBytes("M1")), "InputCheckPasswordSRP.M1");

        T.section("SRP guardrails");
        TLObject badAlgo = TLObject.of("passwordKdfAlgoUnknown");
        TLObject badPw = TLObject.of("account.password", "current_algo", badAlgo, "srp_B", new byte[0], "srp_id", 0L);
        try {
            SrpPassword.computeCheck(badPw, "x");
            T.ok(false, "unsupported algo must be rejected");
        } catch (SoroushException e) {
            T.ok(true, "unsupported algo rejected");
        }
        TLObject nonStdAlgo = TLObject.of("passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow",
                "salt1", new byte[] {1}, "salt2", new byte[] {2}, "g", 3, "p", Bytes.random(256));
        TLObject nonStdPw = TLObject.of("account.password", "current_algo", nonStdAlgo, "srp_B", new byte[] {1}, "srp_id", 0L);
        try {
            SrpPassword.computeCheck(nonStdPw, "x");
            T.ok(false, "non-standard prime must be rejected");
        } catch (SoroushException e) {
            T.ok(true, "non-standard prime rejected");
        }
    }
}
