package appeng.rebuild.pattern;

import java.util.Objects;

/** Bounded deterministic diagnostic retained with a normalized grid shadow snapshot. */
public record NormalizedPatternDiagnostic(Reason reason, String context) {
    public enum Reason {
        LEGACY_FALLBACK
    }

    public NormalizedPatternDiagnostic {
        Objects.requireNonNull(reason, "reason");
        context = Objects.requireNonNull(context, "context");
        if (context.isBlank() || context.length() > PatternLimits.MAX_PATTERN_ID_LENGTH) {
            throw new IllegalArgumentException("Normalized diagnostic context must be bounded and non-blank");
        }
    }
}
