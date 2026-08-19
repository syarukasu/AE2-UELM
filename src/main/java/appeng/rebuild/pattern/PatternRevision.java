package appeng.rebuild.pattern;

/** Non-negative source revision for a pattern definition. */
public record PatternRevision(long value) {
    public PatternRevision {
        if (value < 0) {
            throw new IllegalArgumentException("Pattern revision must be non-negative: " + value);
        }
    }
}
