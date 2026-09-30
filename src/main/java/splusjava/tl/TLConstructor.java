package splusjava.tl;

import java.util.HashMap;
import java.util.Map;

/** Definition of one TL constructor (a type variant or an API function). */
public final class TLConstructor {
    public final String name;
    public final int id;
    public final boolean function;
    /** True for MTProto service constructors, where "string" really means raw bytes. */
    public final boolean bytesStrings;
    public final TLParam[] params;
    public final String resultTypeName;
    private final Map<String, TLParam> byName = new HashMap<String, TLParam>();
    private TLType resultType;

    TLConstructor(String name, int id, boolean function, boolean bytesStrings, TLParam[] params, String resultTypeName) {
        this.name = name;
        this.id = id;
        this.function = function;
        this.bytesStrings = bytesStrings;
        this.params = params;
        this.resultTypeName = resultTypeName;
        for (int i = 0; i < params.length; i++) {
            byName.put(params[i].name, params[i]);
        }
    }

    public TLParam param(String fieldName) {
        return byName.get(fieldName);
    }

    public synchronized TLType getResultType() {
        if (resultType == null) {
            resultType = TLType.parse(resultTypeName);
        }
        return resultType;
    }

    public String paramNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (params[i].type.kind == TLType.FLAGS) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(params[i].name);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return name + "#" + Integer.toHexString(id);
    }
}
