package splusjava.tl;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import splusjava.util.Bytes;

/**
 * A generic TL object: either a server type (a message, a user, an update...) or an API request.
 * Fields are addressed by the same names used in the TL schema, e.g.
 * <pre>
 *   TLObject req = TLObject.create("messages.sendMessage")
 *           .set("peer", TLObject.create("inputPeerSelf"))
 *           .set("message", "hello")
 *           .set("random_id", System.nanoTime());
 * </pre>
 * Value types: int -&gt; Integer, long -&gt; Long, double -&gt; Double, string -&gt; String,
 * bytes -&gt; byte[], Bool/true -&gt; Boolean, Vector -&gt; List, nested types -&gt; TLObject.
 */
public final class TLObject {
    private final TLConstructor ctor;
    private final LinkedHashMap<String, Object> values;

    TLObject(TLConstructor ctor, LinkedHashMap<String, Object> values) {
        this.ctor = ctor;
        this.values = values;
    }

    /** Creates an empty object (or request) by its TL name, e.g. "inputPeerUser" or "messages.sendMessage". */
    public static TLObject create(String name) {
        TLConstructor c = TLSchema.get().getByName(name);
        if (c == null) {
            throw new IllegalArgumentException("Unknown TL constructor or method: " + name);
        }
        return new TLObject(c, new LinkedHashMap<String, Object>());
    }

    /** Shortcut: {@code TLObject.of("inputPeerUser", "user_id", 5L, "access_hash", 7L)}. */
    public static TLObject of(String name, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must be name/value pairs");
        }
        TLObject o = create(name);
        for (int i = 0; i < keyValues.length; i += 2) {
            o.set(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return o;
    }

    public TLConstructor constructor() {
        return ctor;
    }

    /** TL name, e.g. "updateNewMessage". */
    public String getName() {
        return ctor.name;
    }

    public int getConstructorId() {
        return ctor.id;
    }

    /** True when this object is an API request rather than a data type. */
    public boolean isFunction() {
        return ctor.function;
    }

    /** Sets a field (null removes it). The "flags" fields are computed automatically. */
    public TLObject set(String field, Object value) {
        TLParam p = ctor.param(field);
        if (p == null) {
            throw new IllegalArgumentException("'" + ctor.name + "' has no field '" + field
                    + "'. Fields: " + ctor.paramNames());
        }
        if (p.type.kind == TLType.FLAGS) {
            return this;
        }
        if (value == null) {
            values.remove(field);
        } else {
            values.put(field, value);
        }
        return this;
    }

    public Object get(String field) {
        return values.get(field);
    }

    public boolean has(String field) {
        return values.containsKey(field);
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }

    public int getInt(String field) {
        Object v = values.get(field);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    public long getLong(String field) {
        Object v = values.get(field);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    public double getDouble(String field) {
        Object v = values.get(field);
        return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
    }

    public boolean getBool(String field) {
        Object v = values.get(field);
        return v instanceof Boolean && ((Boolean) v).booleanValue();
    }

    public String getString(String field) {
        Object v = values.get(field);
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof byte[]) {
            try {
                return new String((byte[]) v, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return null;
    }

    public byte[] getBytes(String field) {
        Object v = values.get(field);
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
        return null;
    }

    public TLObject getObject(String field) {
        Object v = values.get(field);
        return v instanceof TLObject ? (TLObject) v : null;
    }

    /** Vector field; never null (an absent field gives an empty list). */
    @SuppressWarnings("unchecked")
    public List<Object> getList(String field) {
        Object v = values.get(field);
        if (v instanceof List) {
            return (List<Object>) v;
        }
        return new ArrayList<Object>();
    }

    /** Serialized form (constructor id + fields), ready to be sent. */
    public byte[] toBytes() {
        return TLCodec.serialize(this);
    }

    /** Type expected for the answer of this request (null when unknown). */
    public TLType effectiveResultType() {
        TLType t = ctor.getResultType();
        if (ctor.function && t.kind == TLType.OBJECT && t.name.equals("X")) {
            for (int i = 0; i < ctor.params.length; i++) {
                TLParam p = ctor.params[i];
                if (p.type.kind == TLType.OBJECT && p.type.name.equals("!X")) {
                    Object q = values.get(p.name);
                    if (q instanceof TLObject) {
                        return ((TLObject) q).effectiveResultType();
                    }
                }
            }
            return null;
        }
        return t;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        append(sb, this, false);
        return sb.toString();
    }

    /** JSON text of this object ("_" holds the TL name; byte arrays are hex strings). */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        append(sb, this, true);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object v, boolean json) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof TLObject) {
            TLObject o = (TLObject) v;
            if (json) {
                sb.append("{\"_\":");
                quote(sb, o.ctor.name);
            } else {
                sb.append(o.ctor.name).append('{');
            }
            boolean needComma = json; // in JSON mode the "_" entry was already written
            for (Map.Entry<String, Object> e : o.values.entrySet()) {
                if (needComma) {
                    sb.append(',');
                }
                needComma = true;
                if (json) {
                    quote(sb, e.getKey());
                    sb.append(':');
                } else {
                    sb.append(e.getKey()).append('=');
                }
                append(sb, e.getValue(), json);
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object x : (List<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                append(sb, x, json);
            }
            sb.append(']');
        } else if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            if (json) {
                quote(sb, Bytes.toHex(b));
            } else if (b.length > 32) {
                sb.append("bytes[").append(b.length).append("]:").append(Bytes.toHex(Bytes.sub(b, 0, 32))).append("...");
            } else {
                sb.append("bytes:").append(Bytes.toHex(b));
            }
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else {
            sb.append(v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
