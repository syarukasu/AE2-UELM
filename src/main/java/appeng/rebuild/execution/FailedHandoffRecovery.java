package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Inert evidence retained when work-order materialization fails after the irreversible ledger handoff. */
public record FailedHandoffRecovery(UUID leaseIdentity, CpuPlanHandle handle, ReservationId reservationId,
        ExactPlanId planId, Map<KeyId, AEAmount> reservedDebits) {
    public FailedHandoffRecovery {
        Objects.requireNonNull(leaseIdentity, "leaseIdentity");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(planId, "planId");
        reservedDebits = ExactReservationReceipt.copyDebits(reservedDebits, "reservedDebits");
    }

    static FailedHandoffRecovery capture(ReservedPlanLease lease) {
        Objects.requireNonNull(lease, "lease");
        return new FailedHandoffRecovery(lease.leaseIdentity(), lease.handle(), lease.reservationId(), lease.planId(),
                lease.reservedDebits());
    }
}
