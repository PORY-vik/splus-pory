package splusjava.tl;

import java.io.UnsupportedEncodingException;

/** Growable little-endian byte buffer with the TL serialization primitives. */
public final class TLWriter {
    private byte[] buf;
    private int len;

    public TLWriter() {
        this(256);
    }

    public TLWriter(int capacity) {
        buf = new byte[Math.max(16, capacity)];
    }

    private void ensure(int extra) {
        int need = len + extra;
        if (need > buf.length) {
            int n = Math.max(need, buf.length * 2);
            byte[] t = new byte[n];
            System.arraycopy(buf, 0, t, 0, len);
            buf = t;
        }
    }

    public int size() {
        return len;
    }

    public void writeByte(int b) {
        ensure(1);
        buf[len++] = (byte) b;
    }

    public void writeInt(int v) {
        ensure(4);
        buf[len++] = (byte) v;
        buf[len++] = (byte) (v >> 8);
        buf[len++] = (byte) (v >> 16);
        buf[len++] = (byte) (v >> 24);
    }

    public void writeLong(long v) {
        writeInt((int) v);
        writeInt((int) (v >> 32));
    }

    public void writeDouble(double v) {
        writeLong(Double.doubleToLongBits(v));
    }

    public void writeRaw(byte[] b) {
        writeRaw(b, 0, b.length);
    }

    public void writeRaw(byte[] b, int off, int n) {
        ensure(n);
        System.arraycopy(b, off, buf, len, n);
        len += n;
    }

    /** Writes a TL "bytes"/"string" value: length prefix, data and zero padding to 4 bytes. */
    public void writeTLBytes(byte[] data) {
        int l = data.length;
        int pad;
        if (l < 254) {
            writeByte(l);
            pad = (l + 1) % 4;
        } else {
            writeByte(254);
            writeByte(l & 0xff);
            writeByte((l >> 8) & 0xff);
            writeByte((l >> 16) & 0xff);
            pad = l % 4;
        }
        writeRaw(data);
        if (pad != 0) {
            for (int i = 0; i < 4 - pad; i++) {
                writeByte(0);
            }
        }
    }

    public void writeTLString(String s) {
        try {
            writeTLBytes(s.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] toByteArray() {
        byte[] out = new byte[len];
        System.arraycopy(buf, 0, out, 0, len);
        return out;
    }
}
