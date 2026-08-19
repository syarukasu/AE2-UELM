package appeng.rebuild.planner;

import java.util.Objects;

/** Typed result of pure bounded productive-ring algebra. */
public sealed interface ProductiveCycleSolveResult
        permits ProductiveCycleSolveResult.Success, ProductiveCycleSolveResult.Failure {
    record Success(ProductiveCycleTemplate template) implements ProductiveCycleSolveResult {
        public Success {
            Objects.requireNonNull(template, "template");
        }
    }

    record Failure(FailureReason reason) implements ProductiveCycleSolveResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum FailureReason {
        MALFORMED_RING,
        NON_PRODUCTIVE,
        QUANTITY_LIMIT,
        WORK_LIMIT
    }
}
