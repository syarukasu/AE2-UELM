package appeng.rebuild.pattern;

import java.util.Objects;

/** Typed all-or-nothing result of normalizing one built-in legacy pattern. */
public sealed interface PatternNormalizationResult
        permits PatternNormalizationResult.Success, PatternNormalizationResult.Failure {
    static Success success(PatternDefinition definition, CompiledPattern compiledPattern) {
        return new Success(definition, compiledPattern);
    }

    static Failure failure(FailureReason reason, String context) {
        return new Failure(reason, context);
    }

    boolean isSuccess();

    record Success(PatternDefinition definition,
            CompiledPattern compiledPattern) implements PatternNormalizationResult {
        public Success {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(compiledPattern, "compiledPattern");
        }

        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    record Failure(FailureReason reason, String context) implements PatternNormalizationResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(context, "context");
            if (context.isBlank() || context.length() > PatternLimits.MAX_PATTERN_ID_LENGTH) {
                throw new IllegalArgumentException(
                        "Pattern normalization failure context must be bounded and non-blank");
            }
        }

        @Override
        public boolean isSuccess() {
            return false;
        }
    }

    enum FailureReason {
        UNSUPPORTED_PATTERN,
        INVALID_DEFINITION,
        INVALID_INPUT,
        INVALID_OUTPUT,
        SHAPE_LIMIT,
        IDENTITY_FAILURE,
        LEGACY_EXCEPTION
    }
}
