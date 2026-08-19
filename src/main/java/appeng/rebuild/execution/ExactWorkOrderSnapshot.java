package appeng.rebuild.execution;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** Immutable bounded observation of one work order and the exact resources in its custody. */
public record ExactWorkOrderSnapshot(ExactWorkOrderState state, ExactPlanId planId, CpuPlanHandle handle,
        ReservationId reservationId, UUID leaseIdentity, WorkOrderId workOrderId, Map<KeyId, AEAmount> custody,
        int causalStepIndex, AEAmount remainingExecutions, List<AEAmount> selectionRemaining,
        Optional<ExactWorkCommand> outstandingCommand, Optional<ExactWorkCommand> inFlightCommand,
        Optional<ExactWorkDiscrepancy> discrepancy) {
    public ExactWorkOrderSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(leaseIdentity, "leaseIdentity");
        Objects.requireNonNull(workOrderId, "workOrderId");
        custody = ExactReservationReceipt.copyDebitsOrEmpty(custody, "custody");
        if (causalStepIndex < 0) {
            throw new IllegalArgumentException("causalStepIndex must be non-negative");
        }
        remainingExecutions = Objects.requireNonNull(remainingExecutions, "remainingExecutions");
        if (remainingExecutions.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException("remainingExecutions exceeds the bounded exact quantity limit");
        }
        selectionRemaining = List.copyOf(Objects.requireNonNull(selectionRemaining, "selectionRemaining"));
        if (selectionRemaining.size() > PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
            throw new IllegalArgumentException("selectionRemaining exceeds the bounded selection limit");
        }
        for (AEAmount amount : selectionRemaining) {
            if (Objects.requireNonNull(amount, "selectionRemaining entry").toBigInteger()
                    .bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
                throw new IllegalArgumentException("selectionRemaining entry exceeds the bounded exact quantity limit");
            }
        }
        outstandingCommand = Objects.requireNonNull(outstandingCommand, "outstandingCommand");
        inFlightCommand = Objects.requireNonNull(inFlightCommand, "inFlightCommand");
        discrepancy = Objects.requireNonNull(discrepancy, "discrepancy");
    }
}
