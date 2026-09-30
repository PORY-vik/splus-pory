package splusjava.util;

/** Minimal Base64 codec (java.util.Base64 needs Java 8 / Android 26, so we ship our own). */
public final class Base64Util {
    private static final String STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final String URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private Base64Util() {
    }

    public static String encode(byte[] data) {
        return encode(data, STD);
    }

    /** URL-safe alphabet WITH '=' padding (what Python's urlsafe_b64encode produces). */
    public static String encodeUrl(byte[] data) {
        return encode(data, URL);
    }

    private static String encode(byte[] d, String alphabet) {
        StringBuilder sb = new StringBuilder(((d.length + 2) / 3) * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xff;
            int b1 = i + 1 < d.length ? d[i + 1] & 0xff : 0;
            int b2 = i + 2 < d.length ? d[i + 2] & 0xff : 0;
            sb.append(alphabet.charAt(b0 >> 2));
            sb.append(alphabet.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < d.length ? alphabet.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < d.length ? alphabet.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }

    /** Decodes both the standard and the URL-safe alphabet; padding is optional. */
    public static byte[] decode(String s) {
        int len = s.length();
        while (len > 0 && s.charAt(len - 1) == '=') {
            len--;
        }
        byte[] out = new byte[len * 3 / 4];
        int acc = 0;
        int bits = 0;
        int pos = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') {
                v = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                v = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                v = c - '0' + 52;
            } else if (c == '+' || c == '-') {
                v = 62;
            } else if (c == '/' || c == '_') {
                v = 63;
            } else if (c == '\n' || c == '\r' || c == ' ') {
                continue;
            } else {
                throw new IllegalArgumentException("Invalid base64 character: " + c);
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[pos++] = (byte) ((acc >> bits) & 0xff);
            }
        }
        if (pos == out.length) {
            return out;
        }
        byte[] t = new byte[pos];
        System.arraycopy(out, 0, t, 0, pos);
        return t;
    }
}
