package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Immutable, persistable observation of one broker's identity binding and exact escrow. */
public record ExactTransferBrokerSnapshot(ExactTransferBrokerState state, Optional<CpuPlanHandle> handle,
        Optional<ExactPlanId> planId, Optional<ReservationId> reservationId, Map<KeyId, AEAmount> escrowed) {
    public ExactTransferBrokerSnapshot {
        Objects.requireNonNull(state, "state");
        handle = Objects.requireNonNull(handle, "handle");
        planId = Objects.requireNonNull(planId, "planId");
        reservationId = Objects.requireNonNull(reservationId, "reservationId");
        escrowed = ExactReservationReceipt.copyDebitsOrEmpty(escrowed, "escrowed");
        if (state == ExactTransferBrokerState.IDLE
                && (handle.isPresent() || planId.isPresent() || reservationId.isPresent() || !escrowed.isEmpty())) {
            throw new IllegalArgumentException("An idle broker observation cannot retain reservation authority");
        }
    }
}
