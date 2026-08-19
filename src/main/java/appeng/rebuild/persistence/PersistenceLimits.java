package appeng.rebuild.persistence;

import appeng.rebuild.pattern.PatternLimits;

/** Finite persistence decoding limits. All violations reject the complete record. */
public final class PersistenceLimits {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_KEYS = 65_536;
    public static final int MAX_NBT_DEPTH = PatternLimits.MAX_PATTERN_ID_NBT_DEPTH;
    public static final int MAX_NBT_NODES = PatternLimits.MAX_PATTERN_ID_NBT_NODES;
    public static final int MAX_COMPOUND_ENTRIES = PatternLimits.MAX_PATTERN_ID_NBT_COMPOUND_ENTRIES;
    public static final int MAX_LIST_ENTRIES = PatternLimits.MAX_PATTERN_ID_NBT_LIST_ENTRIES;
    public static final int MAX_ARRAY_ELEMENTS = PatternLimits.MAX_PATTERN_ID_NBT_ARRAY_ELEMENTS;
    public static final int MAX_UTF8_BYTES = PatternLimits.MAX_PATTERN_ID_NBT_STRING_UTF8_BYTES;
    public static final int MAX_ENCODED_BYTES = PatternLimits.MAX_PATTERN_ID_NBT_ENCODED_BYTES;

    private PersistenceLimits() {
    }
}
