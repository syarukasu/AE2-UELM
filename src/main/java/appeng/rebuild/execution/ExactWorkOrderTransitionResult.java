package appeng.rebuild.execution;

import java.util.Objects;

/**
 * Immutable outcome of a same-package work-order transition.
 *
 * <p>
 * It is an observation only: possessing this result cannot acknowledge, release, or mutate a work order.
 */
public sealed interface ExactWorkOrderTransitionResult permits ExactWorkOrderTransitionResult.Accepted,
        ExactWorkOrderTransitionResult.Rejected, ExactWorkOrderTransitionResult.Completed,
        ExactWorkOrderTransitionResult.Failure {
    record Accepted(ExactWorkOrderSnapshot snapshot) implements ExactWorkOrderTransitionResult {
        public Accepted {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Rejected(ExactWorkOrderSnapshot snapshot) implements ExactWorkOrderTransitionResult {
        public Rejected {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Completed(ExactWorkOrderSnapshot snapshot) implements ExactWorkOrderTransitionResult {
        public Completed {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Failure(Reason reason, ExactWorkOrderSnapshot snapshot) implements ExactWorkOrderTransitionResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    enum Reason {
        WRONG_THREAD,
        REENTRANT,
        WRONG_STATE,
        IDENTITY_MISMATCH,
        INVARIANT_VIOLATION,
        RESULT_MISMATCH,
        FAIL_CLOSED
    }
}
