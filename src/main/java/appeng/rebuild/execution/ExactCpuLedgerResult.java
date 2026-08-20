package appeng.rebuild.execution;

import java.util.Objects;

/** Typed result for a linearizable exact CPU ledger transition. */
public sealed interface ExactCpuLedgerResult permits ExactCpuLedgerResult.Prepared,
        ExactCpuLedgerResult.ReservationConfirmed, ExactCpuLedgerResult.PreparedCancelled,
        ExactCpuLedgerResult.ReleaseRequired, ExactCpuLedgerResult.ReleaseAcknowledged,
        ExactCpuLedgerResult.HandedOff, ExactCpuLedgerResult.HandoffAcknowledged, ExactCpuLedgerResult.Failure {
    record Prepared(CpuPlanHandle handle) implements ExactCpuLedgerResult {
        public Prepared {
            Objects.requireNonNull(handle, "handle");
        }
    }

    record ReservationConfirmed(CpuPlanHandle handle, ReservationId reservationId) implements ExactCpuLedgerResult {
        public ReservationConfirmed {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(reservationId, "reservationId");
        }
    }

    record PreparedCancelled(CpuPlanHandle handle) implements ExactCpuLedgerResult {
        public PreparedCancelled {
            Objects.requireNonNull(handle, "handle");
        }
    }

    record ReleaseRequired(ReleaseObligation obligation) implements ExactCpuLedgerResult {
        public ReleaseRequired {
            Objects.requireNonNull(obligation, "obligation");
        }
    }

    record ReleaseAcknowledged(CpuPlanHandle handle) implements ExactCpuLedgerResult {
        public ReleaseAcknowledged {
            Objects.requireNonNull(handle, "handle");
        }
    }

    record HandedOff(ReservedPlanLease lease) implements ExactCpuLedgerResult {
        public HandedOff {
            Objects.requireNonNull(lease, "lease");
        }
    }

    record HandoffAcknowledged(CpuPlanHandle handle) implements ExactCpuLedgerResult {
        public HandoffAcknowledged {
            Objects.requireNonNull(handle, "handle");
        }
    }

    record Failure(FailureReason reason) implements ExactCpuLedgerResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum FailureReason {
        WRONG_STATE,
        STALE_HANDLE,
        RECEIPT_MISMATCH,
        IDENTITY_MISMATCH,
        MALFORMED_ARGUMENT,
        REVISION_EXHAUSTED,
        FAIL_CLOSED
    }
}
