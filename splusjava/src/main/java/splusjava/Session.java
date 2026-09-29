package splusjava;

import java.io.UnsupportedEncodingException;

import splusjava.util.Base64Util;
import splusjava.util.Bytes;

/**
 * Where to connect and the authorization key obtained from the server. Can be converted to a string
 * ({@link #save()}) and back ({@link #parse(String)}). The format is identical to the StringSession of
 * the Python SPlusthon library, so sessions can be exchanged between both libraries.
 */
public final class Session {
    public static final String DEFAULT_HOST = "im-server.splus.ir";
    public static final int DEFAULT_PORT = 443;
    public static final int DEFAULT_DC_ID = 3;

    private int dcId = DEFAULT_DC_ID;
    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;
    private byte[] authKey;

    public Session() {
    }

    public int getDcId() {
        return dcId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /** The 256 byte auth key or null when not authenticated yet. */
    public byte[] getAuthKey() {
        return authKey;
    }

    public void setServer(int dcId, String host, int port) {
        this.dcId = dcId;
        this.host = host;
        this.port = port;
    }

    public void setAuthKey(byte[] key) {
        this.authKey = key;
    }

    /** Session string, or an empty string while there is no auth key. */
    public String save() {
        if (authKey == null) {
            return "";
        }
        try {
            byte[] addr = host.getBytes("UTF-8");
            byte[] data = new byte[1 + 2 + addr.length + 2 + 256];
            int p = 0;
            data[p++] = (byte) dcId;
            data[p++] = (byte) (addr.length >> 8);
            data[p++] = (byte) addr.length;
            System.arraycopy(addr, 0, data, p, addr.length);
            p += addr.length;
            data[p++] = (byte) (port >> 8);
            data[p++] = (byte) port;
            System.arraycopy(authKey, 0, data, p, 256);
            return "1" + Base64Util.encodeUrl(data);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses a session string (null/empty gives a fresh default session). */
    public static Session parse(String s) {
        Session session = new Session();
        if (s == null || s.trim().length() == 0) {
            return session;
        }
        s = s.trim();
        if (s.charAt(0) != '1') {
            throw new SoroushException("Not a valid session string");
        }
        byte[] d = Base64Util.decode(s.substring(1));
        try {
            if (d.length == 1 + 4 + 2 + 256) { // old format: raw IPv4 address
                session.dcId = d[0] & 0xff;
                session.host = (d[1] & 0xff) + "." + (d[2] & 0xff) + "." + (d[3] & 0xff) + "." + (d[4] & 0xff);
                session.port = ((d[5] & 0xff) << 8) | (d[6] & 0xff);
                session.authKey = keyOrNull(Bytes.sub(d, 7, 256));
            } else {
                session.dcId = d[0] & 0xff;
                int len = ((d[1] & 0xff) << 8) | (d[2] & 0xff);
                if (d.length != 3 + len + 2 + 256) {
                    throw new SoroushException("Not a valid session string");
                }
                session.host = new String(d, 3, len, "UTF-8");
                session.port = ((d[3 + len] & 0xff) << 8) | (d[4 + len] & 0xff);
                session.authKey = keyOrNull(Bytes.sub(d, 5 + len, 256));
            }
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new SoroushException("Not a valid session string");
        }
        return session;
    }

    private static byte[] keyOrNull(byte[] key) {
        for (int i = 0; i < key.length; i++) {
            if (key[i] != 0) {
                return key;
            }
        }
        return null;
    }
}
