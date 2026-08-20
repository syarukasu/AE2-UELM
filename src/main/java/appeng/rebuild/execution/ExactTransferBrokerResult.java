package appeng.rebuild.execution;

import java.util.Objects;

/** Opaque typed result for one exact transfer-broker operation. */
public sealed interface ExactTransferBrokerResult permits ExactTransferBrokerResult.Reserved,
        ExactTransferBrokerResult.Started,
        ExactTransferBrokerResult.Cancelled, ExactTransferBrokerResult.RolledBack,
        ExactTransferBrokerResult.ReleasePending, ExactTransferBrokerResult.Failure {
    record Reserved(ExactTransferBrokerSnapshot snapshot) implements ExactTransferBrokerResult {
        public Reserved {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** The broker has transferred exact custody to one work order. */
    record Started(ExactWorkOrder workOrder) implements ExactTransferBrokerResult {
        public Started {
            Objects.requireNonNull(workOrder, "workOrder");
        }
    }

    record Cancelled(ExactTransferBrokerSnapshot snapshot) implements ExactTransferBrokerResult {
        public Cancelled {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record RolledBack(ExactTransferBrokerSnapshot snapshot) implements ExactTransferBrokerResult {
        public RolledBack {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record ReleasePending(ExactTransferBrokerSnapshot snapshot) implements ExactTransferBrokerResult {
        public ReleasePending {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    record Failure(FailureReason reason, ExactTransferBrokerSnapshot snapshot) implements ExactTransferBrokerResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    enum FailureReason {
        WRONG_THREAD,
        REENTRANT_OPERATION,
        WRONG_STATE,
        STALE_HANDLE,
        DEPENDENCY_MISMATCH,
        SNAPSHOT_UNAVAILABLE,
        STORAGE_FAILURE,
        STORAGE_SIMULATION_SHORT,
        STORAGE_EXTRACTION_SHORT,
        CPU_REJECTED,
        IDENTITY_MISMATCH,
        INVALID_OPERATION_BUDGET,
        INVARIANT_VIOLATION,
        FAIL_CLOSED
    }
}
