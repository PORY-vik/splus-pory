package splusjava.tl;

/** A parsed TL type expression such as {@code int}, {@code Vector<long>} or {@code messages.Messages}. */
public final class TLType {
    public static final int FLAGS = 0;   // '#'
    public static final int INT = 1;
    public static final int LONG = 2;
    public static final int DOUBLE = 3;
    public static final int STRING = 4;
    public static final int BYTES = 5;
    public static final int INT128 = 6;
    public static final int INT256 = 7;
    public static final int BOOL = 8;
    public static final int TRUE = 9;    // flag-only "true"
    public static final int OBJECT = 10; // boxed object (or !X / Object)
    public static final int VECTOR = 11;
    public static final int BARE = 12;   // lowercase type, written without constructor id

    public final int kind;
    public final String name;
    public final TLType elem;
    public final boolean bareVector;

    private TLType(int kind, String name, TLType elem, boolean bareVector) {
        this.kind = kind;
        this.name = name;
        this.elem = elem;
        this.bareVector = bareVector;
    }

    public static TLType parse(String s) {
        if (s.equals("#")) {
            return new TLType(FLAGS, s, null, false);
        }
        if (s.equals("int")) {
            return new TLType(INT, s, null, false);
        }
        if (s.equals("long")) {
            return new TLType(LONG, s, null, false);
        }
        if (s.equals("double")) {
            return new TLType(DOUBLE, s, null, false);
        }
        if (s.equals("string")) {
            return new TLType(STRING, s, null, false);
        }
        if (s.equals("bytes")) {
            return new TLType(BYTES, s, null, false);
        }
        if (s.equals("int128")) {
            return new TLType(INT128, s, null, false);
        }
        if (s.equals("int256")) {
            return new TLType(INT256, s, null, false);
        }
        if (s.equals("Bool")) {
            return new TLType(BOOL, s, null, false);
        }
        if (s.equals("true")) {
            return new TLType(TRUE, s, null, false);
        }
        if ((s.startsWith("Vector<") || s.startsWith("vector<")) && s.endsWith(">")) {
            TLType inner = parse(s.substring(7, s.length() - 1));
            return new TLType(VECTOR, s, inner, s.charAt(0) == 'v');
        }
        int dot = s.lastIndexOf('.');
        char first = s.charAt(dot + 1);
        if (first >= 'a' && first <= 'z') {
            return new TLType(BARE, s, null, false);
        }
        return new TLType(OBJECT, s, null, false);
    }

    @Override
    public String toString() {
        return name;
    }
}
