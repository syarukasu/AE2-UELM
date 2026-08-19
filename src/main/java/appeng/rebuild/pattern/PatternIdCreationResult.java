package appeng.rebuild.pattern;

import java.util.Objects;

/** Typed all-or-nothing result of canonical PatternId creation. */
public sealed interface PatternIdCreationResult
        permits PatternIdCreationResult.Success, PatternIdCreationResult.Failure {
    static Success success(PatternId patternId) {
        return new Success(patternId);
    }

    static Failure failure(FailureReason reason, String context) {
        return new Failure(reason, context);
    }

    boolean isSuccess();

    record Success(PatternId patternId) implements PatternIdCreationResult {
        public Success {
            Objects.requireNonNull(patternId, "patternId");
        }

        @Override
        public boolean isSuccess() {
            return true;
        }
    }

    record Failure(FailureReason reason, String context) implements PatternIdCreationResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(context, "context");
            if (context.isBlank() || context.length() > PatternLimits.MAX_PATTERN_ID_LENGTH) {
                throw new IllegalArgumentException("PatternId failure context must be bounded and non-blank");
            }
        }

        @Override
        public boolean isSuccess() {
            return false;
        }
    }

    enum FailureReason {
        IDENTITY_LIMIT,
        MALFORMED_TAG,
        UNSUPPORTED_TAG
    }
}
