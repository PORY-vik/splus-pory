package splusjava.crypto;

import java.math.BigInteger;
import java.util.Random;

import splusjava.util.Bytes;

/** Pollard-Rho (Brent variant) factorization of the 64-bit "pq" number sent during key exchange. */
public final class Factorizer {
    private Factorizer() {
    }

    /** Returns {p, q} with p &lt; q and p*q == pq. */
    public static BigInteger[] factorize(BigInteger pq) {
        return factorize(pq, Bytes.secureRandom());
    }

    public static BigInteger[] factorize(BigInteger pq, Random rnd) {
        BigInteger one = BigInteger.ONE;
        BigInteger two = BigInteger.valueOf(2);
        if (pq.compareTo(two) <= 0) {
            throw new IllegalArgumentException("pq must be > 2");
        }
        if (!pq.testBit(0)) {
            return new BigInteger[] {two, pq.shiftRight(1)};
        }
        // Fast path for small factors (also keeps tiny pq values, e.g. in tests, from confusing Brent's cycle
        // detection below, which assumes pq is a large semiprime with no small factors).
        BigInteger limit = BigInteger.valueOf(1000000L);
        for (BigInteger d = BigInteger.valueOf(3); d.compareTo(limit) <= 0 && d.multiply(d).compareTo(pq) <= 0; d = d.add(two)) {
            if (pq.mod(d).signum() == 0) {
                return new BigInteger[] {d, pq.divide(d)};
            }
        }
        BigInteger range = pq.subtract(one);
        BigInteger y = new BigInteger(pq.bitLength() + 8, rnd).mod(range).add(one);
        BigInteger c = new BigInteger(pq.bitLength() + 8, rnd).mod(range).add(one);
        BigInteger m = new BigInteger(pq.bitLength() + 8, rnd).mod(range).add(one);
        long mSmall = m.min(BigInteger.valueOf(1L << 20)).longValue();
        BigInteger g = one;
        BigInteger q = one;
        BigInteger x = BigInteger.ZERO;
        BigInteger ys = BigInteger.ZERO;
        long r = 1;
        while (g.equals(one)) {
            x = y;
            for (long i = 0; i < r; i++) {
                y = y.multiply(y).add(c).mod(pq);
            }
            long k = 0;
            while (k < r && g.equals(one)) {
                ys = y;
                long steps = Math.min(mSmall, r - k);
                for (long i = 0; i < steps; i++) {
                    y = y.multiply(y).add(c).mod(pq);
                    q = q.multiply(x.subtract(y).abs()).mod(pq);
                }
                g = q.gcd(pq);
                k += mSmall;
            }
            r *= 2;
        }
        if (g.equals(pq)) {
            while (true) {
                ys = ys.multiply(ys).add(c).mod(pq);
                g = x.subtract(ys).abs().gcd(pq);
                if (g.compareTo(one) > 0) {
                    break;
                }
            }
        }
        BigInteger p = g;
        BigInteger other = pq.divide(g);
        return p.compareTo(other) < 0 ? new BigInteger[] {p, other} : new BigInteger[] {other, p};
    }
}
