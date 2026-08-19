package appeng.rebuild.pattern;

import java.util.Objects;

/** Bounded, non-throwing staging failure caused by legacy pattern data or a legacy callback. */
public record PatternProviderRecipeReloadFailure(Reason reason,
        String context) implements PatternProviderRecipeReloadResult {

    private static final int MAX_CONTEXT_LENGTH = 128;

    public enum Reason {
        LEGACY_EXCEPTION,
        MALFORMED_PATTERN,
        SHAPE_LIMIT
    }

    public PatternProviderRecipeReloadFailure {
        Objects.requireNonNull(reason, "reason");
        context = Objects.requireNonNull(context, "context");
        if (context.isBlank()) {
            throw new IllegalArgumentException("Reload failure context must not be blank");
        }
        if (context.length() > MAX_CONTEXT_LENGTH) {
            throw new IllegalArgumentException("Reload failure context exceeds " + MAX_CONTEXT_LENGTH + " characters");
        }
    }
}
