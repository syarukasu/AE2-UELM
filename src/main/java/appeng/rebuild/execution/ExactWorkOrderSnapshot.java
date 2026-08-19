package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Immutable bounded observation of one work order and the exact resources in its custody. */
public record ExactWorkOrderSnapshot(ExactWorkOrderState state, ExactPlanId planId, CpuPlanHandle handle,
        ReservationId reservationId, UUID leaseIdentity, WorkOrderId workOrderId, Map<KeyId, AEAmount> custody) {
    public ExactWorkOrderSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(leaseIdentity, "leaseIdentity");
        Objects.requireNonNull(workOrderId, "workOrderId");
        custody = ExactReservationReceipt.copyDebitsOrEmpty(custody, "custody");
    }
}
