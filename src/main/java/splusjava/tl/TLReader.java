package splusjava.tl;

import splusjava.TLException;

/** Cursor over a byte array with the TL deserialization primitives (little-endian). */
public final class TLReader {
    private final byte[] data;
    private final int end;
    private int pos;

    public TLReader(byte[] data) {
        this(data, 0, data.length);
    }

    public TLReader(byte[] data, int off, int len) {
        this.data = data;
        this.pos = off;
        this.end = off + len;
    }

    public int remaining() {
        return end - pos;
    }

    public int position() {
        return pos;
    }

    public void setPosition(int p) {
        if (p < 0 || p > end) {
            throw new TLException("Invalid position " + p);
        }
        pos = p;
    }

    private void need(int n) {
        if (n < 0 || pos + n > end) {
            throw new TLException("Unexpected end of data (need " + n + " bytes, have " + (end - pos) + ")");
        }
    }

    public int readByte() {
        need(1);
        return data[pos++] & 0xff;
    }

    public int readInt() {
        need(4);
        int v = (data[pos] & 0xff) | ((data[pos + 1] & 0xff) << 8) | ((data[pos + 2] & 0xff) << 16)
                | ((data[pos + 3] & 0xff) << 24);
        pos += 4;
        return v;
    }

    public int peekInt() {
        int p = pos;
        int v = readInt();
        pos = p;
        return v;
    }

    public long readLong() {
        long lo = readInt() & 0xffffffffL;
        long hi = readInt();
        return lo | (hi << 32);
    }

    public double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    public byte[] readRaw(int n) {
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    public byte[] readRemaining() {
        return readRaw(remaining());
    }

    /** Reads a TL "bytes"/"string" value (length prefix, data, padding). */
    public byte[] readTLBytes() {
        int first = readByte();
        int length;
        int pad;
        if (first == 254) {
            length = readByte() | (readByte() << 8) | (readByte() << 16);
            pad = length % 4;
        } else {
            length = first;
            pad = (length + 1) % 4;
        }
        byte[] out = readRaw(length);
        if (pad > 0) {
            need(4 - pad);
            pos += 4 - pad;
        }
        return out;
    }
}
