package splusjava.tl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import splusjava.TLException;

/** Binary (de)serialization of {@link TLObject}s following the TL/MTProto rules. */
public final class TLCodec {
    public static final int VECTOR_ID = 0x1cb5c415;
    public static final int BOOL_TRUE_ID = 0x997275b5;
    public static final int BOOL_FALSE_ID = 0xbc799737;
    public static final int NULL_ID = 0x56730bcc;
    public static final int GZIP_PACKED_ID = 0x3072cfa1;

    private TLCodec() {
    }

    // ------------------------------------------------------------------ writing

    /** Serializes an object including its constructor id. */
    public static byte[] serialize(TLObject o) {
        TLWriter w = new TLWriter();
        writeBoxed(w, o);
        return w.toByteArray();
    }

    static void writeBoxed(TLWriter w, TLObject o) {
        TLConstructor c = o.constructor();
        w.writeInt(c.id);
        writeFields(w, o);
    }

    private static void writeFields(TLWriter w, TLObject o) {
        TLConstructor c = o.constructor();
        Map<String, Integer> flags = null;
        for (int i = 0; i < c.params.length; i++) {
            TLParam p = c.params[i];
            if (p.type.kind == TLType.FLAGS) {
                if (flags == null) {
                    flags = new HashMap<String, Integer>();
                }
                flags.put(p.name, Integer.valueOf(0));
            }
        }
        if (flags != null) {
            for (int i = 0; i < c.params.length; i++) {
                TLParam p = c.params[i];
                if (p.isConditional() && isPresent(p, o.get(p.name))) {
                    int cur = flags.get(p.flagName).intValue();
                    flags.put(p.flagName, Integer.valueOf(cur | (1 << p.flagBit)));
                }
            }
        }
        for (int i = 0; i < c.params.length; i++) {
            TLParam p = c.params[i];
            if (p.type.kind == TLType.FLAGS) {
                w.writeInt(flags.get(p.name).intValue());
                continue;
            }
            Object v = o.get(p.name);
            if (p.isConditional()) {
                if (!isPresent(p, v)) {
                    continue;
                }
                if (p.type.kind == TLType.TRUE) {
                    continue; // only the flag bit is transmitted
                }
            } else if (v == null) {
                throw new IllegalStateException("Missing required field '" + p.name + "' of '" + c.name + "'");
            }
            writeValue(w, p.type, v, c, p.name);
        }
    }

    private static boolean isPresent(TLParam p, Object v) {
        if (v == null) {
            return false;
        }
        if (p.type.kind == TLType.TRUE) {
            return Boolean.TRUE.equals(v);
        }
        return true;
    }

    private static void writeValue(TLWriter w, TLType t, Object v, TLConstructor c, String field) {
        switch (t.kind) {
            case TLType.INT:
                w.writeInt(asNumber(v, c, field).intValue());
                break;
            case TLType.LONG:
                w.writeLong(asNumber(v, c, field).longValue());
                break;
            case TLType.DOUBLE:
                w.writeDouble(asNumber(v, c, field).doubleValue());
                break;
            case TLType.STRING:
            case TLType.BYTES:
                w.writeTLBytes(asBytes(v, c, field));
                break;
            case TLType.INT128:
                w.writeRaw(fixed(v, 16, c, field));
                break;
            case TLType.INT256:
                w.writeRaw(fixed(v, 32, c, field));
                break;
            case TLType.BOOL:
                if (!(v instanceof Boolean)) {
                    throw bad(c, field, "Boolean");
                }
                w.writeInt(((Boolean) v).booleanValue() ? BOOL_TRUE_ID : BOOL_FALSE_ID);
                break;
            case TLType.OBJECT:
                if (v instanceof TLObject) {
                    writeBoxed(w, (TLObject) v);
                } else if (v instanceof byte[]) {
                    w.writeRaw((byte[]) v); // already serialized (e.g. the query of invokeWithLayer)
                } else {
                    throw bad(c, field, "TLObject");
                }
                break;
            case TLType.BARE:
                if (!(v instanceof TLObject)) {
                    throw bad(c, field, "TLObject");
                }
                writeFields(w, (TLObject) v);
                break;
            case TLType.VECTOR:
                List<?> list = asList(v, c, field);
                if (!t.bareVector) {
                    w.writeInt(VECTOR_ID);
                }
                w.writeInt(list.size());
                for (int i = 0; i < list.size(); i++) {
                    writeValue(w, t.elem, list.get(i), c, field);
                }
                break;
            default:
                throw new TLException("Cannot write type " + t + " of field " + field);
        }
    }

    private static IllegalArgumentException bad(TLConstructor c, String field, String expected) {
        return new IllegalArgumentException("Field '" + field + "' of '" + c.name + "' expects " + expected);
    }

    private static Number asNumber(Object v, TLConstructor c, String field) {
        if (v instanceof Number) {
            return (Number) v;
        }
        throw bad(c, field, "a number");
    }

    private static byte[] asBytes(Object v, TLConstructor c, String field) {
        if (v instanceof byte[]) {
            return (byte[]) v;
        }
        if (v instanceof String) {
            try {
                return ((String) v).getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        throw bad(c, field, "String or byte[]");
    }

    private static byte[] fixed(Object v, int len, TLConstructor c, String field) {
        if (v instanceof byte[] && ((byte[]) v).length == len) {
            return (byte[]) v;
        }
        throw bad(c, field, "byte[" + len + "]");
    }

    private static List<?> asList(Object v, TLConstructor c, String field) {
        if (v instanceof List) {
            return (List<?>) v;
        }
        if (v instanceof Object[]) {
            return Arrays.asList((Object[]) v);
        }
        if (v instanceof long[]) {
            long[] a = (long[]) v;
            List<Object> l = new ArrayList<Object>(a.length);
            for (int i = 0; i < a.length; i++) {
                l.add(Long.valueOf(a[i]));
            }
            return l;
        }
        if (v instanceof int[]) {
            int[] a = (int[]) v;
            List<Object> l = new ArrayList<Object>(a.length);
            for (int i = 0; i < a.length; i++) {
                l.add(Integer.valueOf(a[i]));
            }
            return l;
        }
        throw bad(c, field, "a List or array");
    }

    // ------------------------------------------------------------------ reading

    /** Parses one boxed object (constructor id first) from the given bytes. */
    public static Object deserialize(byte[] data) {
        return readObject(new TLReader(data));
    }

    /**
     * Parses the body of an RPC answer. {@code hint} (may be null) is the result type declared by the
     * request: it is needed to read vectors of bare values such as {@code Vector<long>}.
     */
    public static Object readResult(byte[] body, TLType hint) {
        TLReader r = new TLReader(body);
        if (hint != null && hint.kind == TLType.VECTOR && !hint.bareVector && r.peekInt() == VECTOR_ID) {
            r.readInt();
            int n = r.readInt();
            List<Object> out = new ArrayList<Object>(Math.min(n, 1024));
            for (int i = 0; i < n; i++) {
                out.add(readValue(r, hint.elem, false));
            }
            return out;
        }
        return readObject(r);
    }

    /** Reads a boxed value: bool, null, vector, gzip_packed or any schema object. */
    public static Object readObject(TLReader r) {
        return readBoxed(r, r.readInt());
    }

    private static Object readBoxed(TLReader r, int id) {
        if (id == BOOL_TRUE_ID) {
            return Boolean.TRUE;
        }
        if (id == BOOL_FALSE_ID) {
            return Boolean.FALSE;
        }
        if (id == NULL_ID) {
            return null;
        }
        if (id == VECTOR_ID) {
            int n = r.readInt();
            List<Object> out = new ArrayList<Object>(Math.min(n, 1024));
            for (int i = 0; i < n; i++) {
                out.add(readObject(r));
            }
            return out;
        }
        if (id == GZIP_PACKED_ID) {
            return readObject(new TLReader(gunzip(r.readTLBytes())));
        }
        TLConstructor c = TLSchema.get().getById(id);
        if (c == null) {
            throw new TLException(String.format("Unknown TL constructor 0x%08x", Integer.valueOf(id)), id);
        }
        return readFields(r, c);
    }

    private static TLObject readFields(TLReader r, TLConstructor c) {
        LinkedHashMap<String, Object> vals = new LinkedHashMap<String, Object>();
        Map<String, Integer> flags = null;
        for (int i = 0; i < c.params.length; i++) {
            TLParam p = c.params[i];
            if (p.type.kind == TLType.FLAGS) {
                int f = r.readInt();
                if (flags == null) {
                    flags = new HashMap<String, Integer>();
                }
                flags.put(p.name, Integer.valueOf(f));
                vals.put(p.name, Integer.valueOf(f));
                continue;
            }
            if (p.isConditional()) {
                Integer fv = flags == null ? null : flags.get(p.flagName);
                if (fv == null || (fv.intValue() & (1 << p.flagBit)) == 0) {
                    continue;
                }
                if (p.type.kind == TLType.TRUE) {
                    vals.put(p.name, Boolean.TRUE);
                    continue;
                }
            }
            vals.put(p.name, readValue(r, p.type, c.bytesStrings));
        }
        return new TLObject(c, vals);
    }

    private static Object readValue(TLReader r, TLType t, boolean bytesStrings) {
        switch (t.kind) {
            case TLType.INT:
                return Integer.valueOf(r.readInt());
            case TLType.LONG:
                return Long.valueOf(r.readLong());
            case TLType.DOUBLE:
                return Double.valueOf(r.readDouble());
            case TLType.STRING: {
                byte[] b = r.readTLBytes();
                if (bytesStrings) {
                    return b;
                }
                try {
                    return new String(b, "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    throw new IllegalStateException(e);
                }
            }
            case TLType.BYTES:
                return r.readTLBytes();
            case TLType.INT128:
                return r.readRaw(16);
            case TLType.INT256:
                return r.readRaw(32);
            case TLType.BOOL: {
                int id = r.readInt();
                if (id == BOOL_TRUE_ID) {
                    return Boolean.TRUE;
                }
                if (id == BOOL_FALSE_ID) {
                    return Boolean.FALSE;
                }
                throw new TLException(String.format("Bool expected, got 0x%08x", Integer.valueOf(id)), id);
            }
            case TLType.TRUE:
                return Boolean.TRUE;
            case TLType.OBJECT:
                return readObject(r);
            case TLType.BARE: {
                TLConstructor c = TLSchema.get().getByName(t.name);
                if (c == null) {
                    throw new TLException("Unknown bare type " + t.name);
                }
                return readFields(r, c);
            }
            case TLType.VECTOR: {
                if (!t.bareVector) {
                    int id = r.readInt();
                    if (id != VECTOR_ID) {
                        throw new TLException(String.format("Vector expected, got 0x%08x", Integer.valueOf(id)), id);
                    }
                }
                int n = r.readInt();
                if (n < 0) {
                    throw new TLException("Negative vector length");
                }
                List<Object> out = new ArrayList<Object>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) {
                    out.add(readValue(r, t.elem, bytesStrings));
                }
                return out;
            }
            default:
                throw new TLException("Cannot read type " + t);
        }
    }

    // ------------------------------------------------------------------ gzip

    public static byte[] gzip(byte[] data) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2 + 16);
            GZIPOutputStream gz = new GZIPOutputStream(bos);
            gz.write(data);
            gz.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new TLException("gzip failed: " + e);
        }
    }

    public static byte[] gunzip(byte[] data) {
        try {
            GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data));
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 3);
            byte[] buf = new byte[4096];
            int n;
            while ((n = gz.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > (16 << 20)) {
                    throw new TLException("gzip payload too large");
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new TLException("gunzip failed: " + e);
        }
    }
}
