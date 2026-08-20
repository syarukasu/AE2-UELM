package appeng.rebuild.execution;

import java.util.Objects;
import java.util.Optional;

import appeng.rebuild.planner.DependencyValidationResult;

/** Typed, non-mutating result of sealing an exact craft draft for a later CPU reservation. */
public sealed interface ExactPlanValidationResult
        permits ExactPlanValidationResult.Success, ExactPlanValidationResult.Failure {
    record Success(ExactCraftingPlan plan) implements ExactPlanValidationResult {
        public Success {
            Objects.requireNonNull(plan, "plan");
        }
    }

    record Failure(FailureReason reason, Optional<DependencyValidationResult.Reason> dependencyReason)
            implements
                ExactPlanValidationResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            dependencyReason = Objects.requireNonNull(dependencyReason, "dependencyReason");
            if ((reason == FailureReason.DEPENDENCY_MISMATCH) != dependencyReason.isPresent()) {
                throw new IllegalArgumentException("Only dependency failures may carry a dependency reason");
            }
        }

        public Failure(FailureReason reason) {
            this(reason, Optional.empty());
        }
    }

    enum FailureReason {
        DEPENDENCY_MISMATCH,
        MALFORMED_STEP,
        MALFORMED_PATTERN,
        SUMMARY_MISMATCH,
        INSUFFICIENT_STORAGE,
        QUANTITY_LIMIT,
        WORK_LIMIT
    }
}
