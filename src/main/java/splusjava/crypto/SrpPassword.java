package splusjava.crypto;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.Arrays;

import splusjava.SoroushException;
import splusjava.tl.TLObject;
import splusjava.util.Bytes;

/**
 * Computes the {@code InputCheckPasswordSRP} needed for {@code auth.checkPassword} from the
 * {@code account.Password} the server returned and the user's plaintext password (SRP, the same
 * scheme Telegram uses - see
 * <a href="https://core.telegram.org/api/srp">core.telegram.org/api/srp</a>). The plaintext password
 * never leaves the device; only the SRP proof (A, M1) is sent.
 * <p>
 * Only the {@code PasswordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow} algorithm (the
 * only one Soroush/Telegram servers currently issue) and the standard 2048-bit SRP prime are
 * supported; anything else throws rather than silently doing the wrong thing.
 */
public final class SrpPassword {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int SIZE_FOR_HASH = 256;
    private static final int PBKDF2_ITERATIONS = 100000;

    // The standard 2048-bit SRP prime Telegram/Soroush use; g is only ever 3 alongside it.
    private static final byte[] GOOD_PRIME = Bytes.fromHex(
            "c71caeb9c6b1c9048e6c522f70f13f73980d40238e3e21c14934d037563d930f48198a0aa7c14058229493d22530f4d" +
            "bfa336f6e0ac925139543aed44cce7c3720fd51f69458705ac68cd4fe6b6b13abdc9746512969328454f18faf8c595f" +
            "642477fe96bb2a941d5bcd1d4ac8cc49880708fa9b378e3c4f3a9060bee67cf9a4a4a695811051907e162753b56b0f6" +
            "b410dba74d8a84b2a14b3144e0ef1284754fd17ed950d5965b4b9dd46582db1178d169c6bc465b0d6ff9ca3928fef5b" +
            "9ae4e418fc15e83ebea0f87fa9ff5eed70050ded2849f47bf959d956850ce929851f0d8115f635b105ee2e4e15d04b2" +
            "454bf6f4fadf034b10403119cd8e3b92fcc5b");

    private SrpPassword() {
    }

    /** Builds the {@code inputCheckPasswordSRP} object to send with {@code auth.checkPassword}. */
    public static TLObject computeCheck(TLObject accountPassword, String password) {
        return computeCheck(accountPassword, password, null);
    }

    /**
     * Same as {@link #computeCheck(TLObject, String)} but lets tests supply the 256 "random" bytes
     * used for the client's SRP secret {@code a} deterministically (production code always passes
     * {@code fixedRandom256 == null}, which uses a fresh {@link Bytes#random(int)} draw each retry).
     */
    public static TLObject computeCheck(TLObject accountPassword, String password, byte[] fixedRandom256) {
        TLObject algo = accountPassword.getObject("current_algo");
        if (algo == null || !"passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow".equals(algo.getName())) {
            throw new SoroushException("Unsupported password algorithm"
                    + (algo == null ? " (none set - this account has no password)" : ": " + algo.getName()));
        }
        byte[] salt1 = algo.getBytes("salt1");
        byte[] salt2 = algo.getBytes("salt2");
        int g = algo.getInt("g");
        byte[] pBytes = algo.getBytes("p");
        checkPrimeAndGood(pBytes, g);

        byte[] pwHash = computeHash(salt1, salt2, password);
        BigInteger p = new BigInteger(1, pBytes);
        BigInteger bigG = BigInteger.valueOf(g);
        byte[] srpBBytes = accountPassword.getBytes("srp_B");
        BigInteger bB = new BigInteger(1, srpBBytes);
        if (!isGoodLarge(bB, p)) {
            throw new SoroushException("Bad srp_B from server");
        }

        BigInteger x = new BigInteger(1, pwHash);
        byte[] pForHash = numBytesForHash(pBytes);
        byte[] gForHash = bigNumForHash(bigG);
        byte[] bForHash = numBytesForHash(srpBBytes);
        BigInteger gX = bigG.modPow(x, p);
        BigInteger k = new BigInteger(1, Bytes.sha256(pForHash, gForHash));
        BigInteger kgX = k.multiply(gX).mod(p);

        BigInteger a = null;
        byte[] aForHash = null;
        BigInteger u = null;
        while (true) {
            byte[] random = fixedRandom256 != null ? fixedRandom256 : Bytes.random(256);
            BigInteger candidateA = new BigInteger(1, random);
            BigInteger bigA = bigG.modPow(candidateA, p);
            if (isGoodModExpFirst(bigA, p)) {
                byte[] candidateAForHash = bigNumForHash(bigA);
                BigInteger candidateU = new BigInteger(1, Bytes.sha256(candidateAForHash, bForHash));
                if (candidateU.signum() > 0) {
                    a = candidateA;
                    aForHash = candidateAForHash;
                    u = candidateU;
                    break;
                }
            }
        }

        BigInteger gB = bB.subtract(kgX).mod(p);
        if (!isGoodModExpFirst(gB, p)) {
            throw new SoroushException("Bad g_b derived from srp_B");
        }
        BigInteger ux = u.multiply(x);
        BigInteger aUx = a.add(ux);
        BigInteger s = gB.modPow(aUx, p);
        byte[] kBytes = Bytes.sha256(bigNumForHash(s));
        byte[] m1 = Bytes.sha256(
                Bytes.xor(Bytes.sha256(pForHash), Bytes.sha256(gForHash)),
                Bytes.sha256(salt1), Bytes.sha256(salt2), aForHash, bForHash, kBytes);

        return TLObject.of("inputCheckPasswordSRP",
                "srp_id", Long.valueOf(accountPassword.getLong("srp_id")),
                "A", aForHash, "M1", m1);
    }

    /** sha256(salt2 + pbkdf2_hmac_sha512(sha256(salt1+password+salt1), salt1, 100000) + salt2). */
    public static byte[] computeHash(byte[] salt1, byte[] salt2, String password) {
        byte[] passwordBytes = password.getBytes(UTF8);
        byte[] hash1 = Bytes.sha256(salt1, passwordBytes, salt1);
        byte[] hash2 = Bytes.sha256(salt2, hash1, salt2);
        byte[] hash3 = Pbkdf2Sha512.derive(hash2, salt1, PBKDF2_ITERATIONS, 64);
        return Bytes.sha256(salt2, hash3, salt2);
    }

    private static void checkPrimeAndGood(byte[] primeBytes, int g) {
        if (Arrays.equals(GOOD_PRIME, primeBytes) && (g == 3 || g == 4 || g == 5 || g == 7)) {
            return;
        }
        // A full from-scratch primality check on an arbitrary 2048-bit number the server sent is
        // exactly what the reference SPlusthon implementation flags as "awfully slow" (it factors the
        // number and (number-1)/2 with the same Pollard-rho code used for the 64-bit pq above, which is
        // not built for 2048-bit inputs). Soroush/Telegram servers have only ever issued the standard
        // prime above, so this throws instead of hanging or silently trusting an unverified prime.
        throw new SoroushException("Server sent a non-standard SRP prime/generator (g=" + g
                + "); only the standard 2048-bit prime is supported by this build");
    }

    private static boolean isGoodLarge(BigInteger number, BigInteger p) {
        return number.signum() > 0 && p.subtract(number).signum() > 0;
    }

    private static boolean isGoodModExpFirst(BigInteger modExp, BigInteger prime) {
        BigInteger diff = prime.subtract(modExp);
        int minDiffBits = 2048 - 64;
        int maxModExpBytes = 256;
        if (diff.signum() < 0 || diff.bitLength() < minDiffBits || modExp.bitLength() < minDiffBits) {
            return false;
        }
        return (modExp.bitLength() + 7) / 8 <= maxModExpBytes;
    }

    private static byte[] numBytesForHash(byte[] number) {
        return Bytes.toBytes(new BigInteger(1, number), SIZE_FOR_HASH);
    }

    private static byte[] bigNumForHash(BigInteger v) {
        return Bytes.toBytes(v, SIZE_FOR_HASH);
    }
}
