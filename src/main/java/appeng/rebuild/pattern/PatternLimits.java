package appeng.rebuild.pattern;

/** Finite shape limits for normalized pattern data. Violations are rejected; no data is truncated. */
public final class PatternLimits {
    public static final int MAX_INPUT_GROUPS = 64;
    public static final int MAX_CANDIDATES_PER_INPUT = 4096;
    public static final int MAX_TOTAL_CANDIDATES_PER_PATTERN = 4096;
    public static final int MAX_OUTPUTS = 16;
    public static final int MAX_PATTERN_ID_LENGTH = 256;
    public static final int MAX_MACHINE_ATTRIBUTES = 16;
    public static final int MAX_MACHINE_FIELD_LENGTH = 256;
    public static final int MAX_MACHINE_ATTRIBUTE_KEY_LENGTH = 128;
    public static final int MAX_MACHINE_ATTRIBUTE_VALUE_LENGTH = 256;

    private PatternLimits() {
    }
}
