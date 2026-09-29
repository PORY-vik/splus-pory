package splusjava.tl;

/** One parameter of a TL constructor. */
public final class TLParam {
    public final String name;
    public final TLType type;
    /** Name of the flags field controlling this parameter ("flags", "flags2") or null. */
    public final String flagName;
    /** Bit index inside the flags field, or -1. */
    public final int flagBit;

    TLParam(String name, TLType type, String flagName, int flagBit) {
        this.name = name;
        this.type = type;
        this.flagName = flagName;
        this.flagBit = flagBit;
    }

    public boolean isConditional() {
        return flagName != null;
    }
}
