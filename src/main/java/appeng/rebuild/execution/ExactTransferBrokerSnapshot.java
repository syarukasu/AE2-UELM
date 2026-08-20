package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Immutable, persistable observation of one broker's identity binding and exact escrow. */
public record ExactTransferBrokerSnapshot(ExactTransferBrokerState state, Optional<CpuPlanHandle> handle,
        Optional<ExactPlanId> planId, Optional<ReservationId> reservationId, Optional<WorkOrderId> workOrderId,
        Optional<UUID> leaseIdentity, Map<KeyId, AEAmount> escrowed,
        Optional<BrokerTransferDiscrepancy> transferDiscrepancy) {
    /** Source-compatible snapshot construction for callers without a retained protocol-discrepancy authority. */
    public ExactTransferBrokerSnapshot(ExactTransferBrokerState state, Optional<CpuPlanHandle> handle,
            Optional<ExactPlanId> planId, Optional<ReservationId> reservationId, Optional<WorkOrderId> workOrderId,
            Optional<UUID> leaseIdentity, Map<KeyId, AEAmount> escrowed) {
        this(state, handle, planId, reservationId, workOrderId, leaseIdentity, escrowed, Optional.empty());
    }

    public ExactTransferBrokerSnapshot {
        Objects.requireNonNull(state, "state");
        handle = Objects.requireNonNull(handle, "handle");
        planId = Objects.requireNonNull(planId, "planId");
        reservationId = Objects.requireNonNull(reservationId, "reservationId");
        workOrderId = Objects.requireNonNull(workOrderId, "workOrderId");
        leaseIdentity = Objects.requireNonNull(leaseIdentity, "leaseIdentity");
        escrowed = ExactReservationReceipt.copyDebitsOrEmpty(escrowed, "escrowed");
        transferDiscrepancy = Objects.requireNonNull(transferDiscrepancy, "transferDiscrepancy");
        if (transferDiscrepancy.isPresent() && state != ExactTransferBrokerState.FAIL_CLOSED)
            throw new IllegalArgumentException("Transfer discrepancy requires fail-closed broker state");
        if (transferDiscrepancy.isPresent()) {
            if (workOrderId.isPresent() || leaseIdentity.isPresent())
                throw new IllegalArgumentException("Transfer discrepancy cannot coexist with handoff evidence");
            BrokerTransferDiscrepancy evidence = transferDiscrepancy.get();
            if (!planId.equals(Optional.of(evidence.planId())) || !handle.equals(Optional.of(evidence.handle()))
                    || !reservationId.equals(Optional.of(evidence.reservationId()))
                    || !escrowed.equals(evidence.knownEscrow()))
                throw new IllegalArgumentException("Transfer discrepancy must bind the broker's retained authority");
        }
        if (state == ExactTransferBrokerState.IDLE
                && (handle.isPresent() || planId.isPresent() || reservationId.isPresent() || workOrderId.isPresent()
                        || leaseIdentity.isPresent() || !escrowed.isEmpty())) {
            throw new IllegalArgumentException("An idle broker observation cannot retain reservation authority");
        }
        if (state == ExactTransferBrokerState.LEASED
                && (handle.isEmpty() || planId.isEmpty() || reservationId.isEmpty() || workOrderId.isEmpty()
                        || leaseIdentity.isEmpty() || !escrowed.isEmpty())) {
            throw new IllegalArgumentException(
                    "A leased broker observation must identify its work order without escrow");
        }
        if (state != ExactTransferBrokerState.LEASED && state != ExactTransferBrokerState.FAIL_CLOSED
                && (workOrderId.isPresent() || leaseIdentity.isPresent())) {
            throw new IllegalArgumentException("Only a leased broker observation may retain work-order identity");
        }
        if (state == ExactTransferBrokerState.FAIL_CLOSED && workOrderId.isPresent() != leaseIdentity.isPresent()) {
            throw new IllegalArgumentException(
                    "A fail-closed handoff observation must retain both ownership identities");
        }
    }
}
