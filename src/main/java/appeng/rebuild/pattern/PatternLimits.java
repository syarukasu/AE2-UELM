package appeng.rebuild.pattern;

/** Finite shape limits for normalized pattern data. Violations are rejected; no data is truncated. */
public final class PatternLimits {
    public static final int MAX_INPUT_GROUPS = 128;
    public static final int MAX_CANDIDATES_PER_INPUT = 4096;
    public static final int MAX_TOTAL_CANDIDATES_PER_PATTERN = 4096;
    public static final int MAX_OUTPUTS = 64;
    public static final int MAX_PATTERN_ID_LENGTH = 256;
    public static final int MAX_GRAPH_NODES = 65_536;
    public static final int MAX_GRAPH_EDGES = 262_144;
    /** Maximum nesting depth accepted by canonical PatternId NBT encoding. */
    public static final int MAX_PATTERN_ID_NBT_DEPTH = 64;
    /** Maximum NBT tag nodes accepted by canonical PatternId NBT encoding. */
    public static final int MAX_PATTERN_ID_NBT_NODES = 65_536;
    /** Maximum entries accepted in one compound by canonical PatternId NBT encoding. */
    public static final int MAX_PATTERN_ID_NBT_COMPOUND_ENTRIES = 4_096;
    /** Maximum entries accepted in one list by canonical PatternId NBT encoding. */
    public static final int MAX_PATTERN_ID_NBT_LIST_ENTRIES = 65_536;
    /** Maximum elements accepted in one NBT primitive array by canonical PatternId NBT encoding. */
    public static final int MAX_PATTERN_ID_NBT_ARRAY_ELEMENTS = 1_048_576;
    /** Maximum strict UTF-8 byte length accepted for one NBT string or compound key. */
    public static final int MAX_PATTERN_ID_NBT_STRING_UTF8_BYTES = 65_535;
    /** Maximum total bytes streamed into the canonical PatternId NBT encoding. */
    public static final int MAX_PATTERN_ID_NBT_ENCODED_BYTES = 1_048_576;
    public static final int MAX_MACHINE_ATTRIBUTES = 16;
    public static final int MAX_MACHINE_FIELD_LENGTH = 256;
    public static final int MAX_MACHINE_ATTRIBUTE_KEY_LENGTH = 128;
    public static final int MAX_MACHINE_ATTRIBUTE_VALUE_LENGTH = 256;

    private PatternLimits() {
    }
}
