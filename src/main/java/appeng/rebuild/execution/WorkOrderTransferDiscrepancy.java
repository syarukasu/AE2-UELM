package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** Immutable recovery evidence for an untrustworthy work-order storage-release response. */
public record WorkOrderTransferDiscrepancy(Reason reason, ExactPlanId planId, CpuPlanHandle handle,
        ReservationId reservationId, java.util.UUID leaseIdentity, WorkOrderId workOrderId, KeyId key,
        AEAmount requested, Optional<AEAmount> reported, Map<KeyId, AEAmount> knownCustody) {

    public WorkOrderTransferDiscrepancy {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(leaseIdentity, "leaseIdentity");
        Objects.requireNonNull(workOrderId, "workOrderId");
        Objects.requireNonNull(key, "key");
        requested = boundedPositive(requested, "requested");
        reported = Objects.requireNonNull(reported, "reported");
        reported.ifPresent(value -> boundedPositive(value, "reported"));
        knownCustody = ExactReservationReceipt.copyDebitsOrEmpty(knownCustody, "knownCustody");
        if ((reason == Reason.NULL_RETURN || reason == Reason.UNBOUNDED_RETURN) && reported.isPresent())
            throw new IllegalArgumentException("Untrusted work-order transfer has no trusted reported quantity");
        if (reason == Reason.OUT_OF_RANGE_RETURN
                && (reported.isEmpty() || reported.orElseThrow().compareTo(requested) <= 0))
            throw new IllegalArgumentException("Bounded work-order over-return must be strictly above requested");
        if (!requested.equals(knownCustody.get(key)))
            throw new IllegalArgumentException("Work-order transfer discrepancy must bind its pending custody entry");
    }

    private static AEAmount boundedPositive(AEAmount amount, String name) {
        amount = Objects.requireNonNull(amount, name);
        if (amount.equals(AEAmount.ZERO) || amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS)
            throw new IllegalArgumentException(name + " must be positive and bounded");
        return amount;
    }

    public enum Reason {
        NULL_RETURN, UNBOUNDED_RETURN, OUT_OF_RANGE_RETURN
    }
}
