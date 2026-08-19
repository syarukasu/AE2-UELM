package appeng.rebuild.execution;

import java.util.Objects;

/** Typed observation returned by cancellation, bounded release, or final CPU handoff acknowledgement. */
public sealed interface ExactWorkOrderLifecycleResult permits ExactWorkOrderLifecycleResult.CancellationWaiting,
        ExactWorkOrderLifecycleResult.ReleasePending, ExactWorkOrderLifecycleResult.Settled,
        ExactWorkOrderLifecycleResult.Cancelled, ExactWorkOrderLifecycleResult.Failure {
    record CancellationWaiting(ExactWorkOrderSnapshot snapshot) implements ExactWorkOrderLifecycleResult {
        public CancellationWaiting {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record ReleasePending(ExactWorkOrderSnapshot snapshot) implements ExactWorkOrderLifecycleResult {
        public ReleasePending {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Settled(ExactWorkOrderSnapshot snapshot) implements ExactWorkOrderLifecycleResult {
        public Settled {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Cancelled(ExactWorkOrderSnapshot snapshot) implements ExactWorkOrderLifecycleResult {
        public Cancelled {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Failure(Reason reason, ExactWorkOrderSnapshot snapshot) implements ExactWorkOrderLifecycleResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    enum Reason {
        WRONG_THREAD, REENTRANT, WRONG_STATE, INVALID_OPERATION_BUDGET, IDENTITY_MISMATCH,
        STORAGE_PROTOCOL_VIOLATION, INVARIANT_VIOLATION, CPU_REJECTED, FAIL_CLOSED
    }
}
