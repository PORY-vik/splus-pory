package splusjava.tl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The TL schema (Soroush Plus, layer {@value #LAYER}), parsed once from the text embedded in
 * {@link SchemaData}. Every API method and every type of the server is described here, so the whole
 * API can be used through {@link TLObject} without one Java class per constructor.
 */
public final class TLSchema {
    public static final int LAYER = SchemaData.LAYER;

    private static TLSchema instance;

    private final Map<Integer, TLConstructor> byId = new HashMap<Integer, TLConstructor>(4096);
    private final Map<String, TLConstructor> byName = new HashMap<String, TLConstructor>(4096);

    public static synchronized TLSchema get() {
        if (instance == null) {
            instance = new TLSchema();
        }
        return instance;
    }

    private TLSchema() {
        for (int c = 0; c < SchemaData.CHUNKS.length; c++) {
            String chunk = SchemaData.CHUNKS[c];
            int start = 0;
            while (start < chunk.length()) {
                int end = chunk.indexOf('\n', start);
                if (end < 0) {
                    end = chunk.length();
                }
                if (end > start) {
                    parseLine(chunk.substring(start, end));
                }
                start = end + 1;
            }
        }
    }

    private void parseLine(String line) {
        char kind = line.charAt(0);
        boolean function = kind == 'F' || kind == 'f';
        boolean bytesStrings = kind == 't' || kind == 'f';
        String def = line.substring(2);
        int eq = def.lastIndexOf(" = ");
        String left = def.substring(0, eq);
        String result = def.substring(eq + 3).trim();
        String[] tokens = left.split(" ");
        String head = tokens[0];
        int hash = head.indexOf('#');
        String name = head.substring(0, hash);
        int id = (int) Long.parseLong(head.substring(hash + 1), 16);
        List<TLParam> params = new ArrayList<TLParam>();
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.length() == 0 || t.charAt(0) == '{') {
                continue; // generic declaration such as {X:Type}
            }
            int colon = t.indexOf(':');
            String pname = t.substring(0, colon);
            String ptype = t.substring(colon + 1);
            String flagName = null;
            int flagBit = -1;
            int q = ptype.indexOf('?');
            if (q >= 0) {
                String fp = ptype.substring(0, q);
                int dot = fp.indexOf('.');
                flagName = fp.substring(0, dot);
                flagBit = Integer.parseInt(fp.substring(dot + 1));
                ptype = ptype.substring(q + 1);
            }
            params.add(new TLParam(pname, TLType.parse(ptype), flagName, flagBit));
        }
        TLConstructor c = new TLConstructor(name, id, function, bytesStrings,
                params.toArray(new TLParam[params.size()]), result);
        byId.put(Integer.valueOf(id), c);
        byName.put(name, c);
    }

    /** Looks a constructor up by its numeric id, or returns null. */
    public TLConstructor getById(int id) {
        return byId.get(Integer.valueOf(id));
    }

    /** Looks a constructor/function up by name (e.g. "messages.sendMessage"), or returns null. */
    public TLConstructor getByName(String name) {
        return byName.get(name);
    }

    public int size() {
        return byId.size();
    }
}
