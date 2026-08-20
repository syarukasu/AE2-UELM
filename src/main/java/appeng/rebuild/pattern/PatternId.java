package appeng.rebuild.pattern;

import java.util.Objects;

/** Stable value identifier for a pattern. */
public record PatternId(String value) implements Comparable<PatternId> {
    public PatternId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > PatternLimits.MAX_PATTERN_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "Pattern id must be non-blank and at most " + PatternLimits.MAX_PATTERN_ID_LENGTH + " characters");
        }
    }

    @Override
    public int compareTo(PatternId other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }
}
