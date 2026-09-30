package splusjava;

import java.util.Arrays;

/** Tiny assertion helper (no JUnit dependency, so the tests run with a plain JDK). */
final class T {
    static int passed;
    static int failed;

    private T() {
    }

    static void ok(boolean cond, String what) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAIL: " + what);
        }
    }

    static void eq(Object expected, Object actual, String what) {
        boolean same;
        if (expected instanceof byte[] && actual instanceof byte[]) {
            same = Arrays.equals((byte[]) expected, (byte[]) actual);
        } else {
            same = expected == null ? actual == null : expected.equals(actual);
        }
        if (same) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAIL: " + what + "\n    expected: " + show(expected) + "\n    actual:   " + show(actual));
        }
    }

    static String show(Object o) {
        if (o instanceof byte[]) {
            return splusjava.util.Bytes.toHex((byte[]) o);
        }
        return String.valueOf(o);
    }

    static void section(String name) {
        System.out.println("== " + name);
    }
}
