package appeng.rebuild.planner;

import java.util.Objects;

/** Typed all-or-nothing result of the bounded exact craft-planning strategy. */
public sealed interface ExactCraftPlanResult permits ExactCraftPlanResult.Success, ExactCraftPlanResult.Failure {
    record Success(ExactCraftPlanDraft draft) implements ExactCraftPlanResult {
        public Success {
            Objects.requireNonNull(draft, "draft");
        }
    }

    record Failure(FailureReason reason) implements ExactCraftPlanResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum FailureReason {
        NO_PRODUCER,
        CYCLE,
        UNSATISFIABLE_WITHIN_STRATEGY,
        WORK_LIMIT,
        QUANTITY_LIMIT,
        INVALID_INPUT
    }
}
