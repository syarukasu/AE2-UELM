package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/**
 * Immutable observation of a CPU ledger; it carries no plan or mutable storage authority.
 *
 * <p>
 * Its identities describe observed state only. They cannot authorize release, handoff, completion, or abort outside the
 * execution package's broker/work-order boundary.
 */
public record ExactCpuLedgerSnapshot(ExactCpuLedgerState state, long lifecycleRevision,
        Optional<CpuPlanHandle> handle, Optional<ReservationId> reservationId, Map<KeyId, AEAmount> reservedDebits,
        Optional<ReleaseObligation> releaseObligation, Optional<UUID> leaseIdentity) {
    public ExactCpuLedgerSnapshot {
        Objects.requireNonNull(state, "state");
        if (lifecycleRevision < 0) {
            throw new IllegalArgumentException("lifecycleRevision must be non-negative");
        }
        handle = Objects.requireNonNull(handle, "handle");
        reservationId = Objects.requireNonNull(reservationId, "reservationId");
        reservedDebits = ExactReservationReceipt.copyDebitsOrEmpty(reservedDebits, "reservedDebits");
        releaseObligation = Objects.requireNonNull(releaseObligation, "releaseObligation");
        leaseIdentity = Objects.requireNonNull(leaseIdentity, "leaseIdentity");
    }
}
