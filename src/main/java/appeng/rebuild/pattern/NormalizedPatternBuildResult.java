package appeng.rebuild.pattern;

import java.util.Objects;

/** Typed all-or-nothing result of building one grid's normalized pattern shadow. */
public sealed interface NormalizedPatternBuildResult
        permits NormalizedPatternBuildResult.Success, NormalizedPatternBuildResult.Failure {
    record Success(NormalizedPatternSnapshot snapshot) implements NormalizedPatternBuildResult {
        public Success {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Failure(FailureReason reason, String context) implements NormalizedPatternBuildResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            context = Objects.requireNonNull(context, "context");
            if (context.isBlank() || context.length() > PatternLimits.MAX_PATTERN_ID_LENGTH) {
                throw new IllegalArgumentException("Normalized build failure context must be bounded and non-blank");
            }
        }
    }

    enum FailureReason {
        PREPARATION_FAILURE,
        NORMALIZATION_FAILURE,
        PATTERN_COLLISION,
        GRID_LIMIT,
        GRAPH_FAILURE,
        GRAPH_GENERATION_EXHAUSTED,
        DIAGNOSTIC_LIMIT,
        SERVER_CONTEXT,
        STALE_REVISION,
        COORDINATOR_STATE,
        DELIVERY_FAILURE,
        LEGACY_EXCEPTION
    }
}
