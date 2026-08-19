package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** Immutable sole recovery evidence for an untrustworthy physical storage transfer response. */
public record BrokerTransferDiscrepancy(Operation operation, Reason reason, ExactPlanId planId, CpuPlanHandle handle,
        ReservationId reservationId, KeyId key, AEAmount requested, Optional<AEAmount> reported,
        Map<KeyId, AEAmount> knownEscrow) {

    public BrokerTransferDiscrepancy {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(key, "key");
        requested = requireBoundedPositive(requested, "requested");
        reported = Objects.requireNonNull(reported, "reported");
        reported.ifPresent(value -> requireBoundedPositive(value, "reported"));
        knownEscrow = ExactReservationReceipt.copyDebitsOrEmpty(knownEscrow, "knownEscrow");
        if ((reason == Reason.NULL_RETURN || reason == Reason.UNBOUNDED_RETURN) && reported.isPresent())
            throw new IllegalArgumentException("Untrusted transfer marker cannot retain a reported quantity");
        if (reason == Reason.OUT_OF_RANGE_RETURN
                && (reported.isEmpty() || reported.get().compareTo(requested) <= 0))
            throw new IllegalArgumentException("Bounded out-of-range return must be strictly above requested");
        if (operation == Operation.INSERT && !requested.equals(knownEscrow.get(key)))
            throw new IllegalArgumentException("Insert discrepancy must bind its exact pending escrow entry");
        if (operation == Operation.EXTRACT && knownEscrow.containsKey(key))
            throw new IllegalArgumentException("Extract discrepancy key cannot already have trusted escrow custody");
    }

    private static AEAmount requireBoundedPositive(AEAmount amount, String name) {
        amount = Objects.requireNonNull(amount, name);
        if (amount.equals(AEAmount.ZERO) || amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS)
            throw new IllegalArgumentException(name + " must be positive and bounded");
        return amount;
    }

    public enum Operation {
        EXTRACT, INSERT
    }

    public enum Reason {
        NULL_RETURN, UNBOUNDED_RETURN, OUT_OF_RANGE_RETURN
    }
}
