package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/**
 * Exact resources that the transfer broker, rather than the CPU ledger, must release.
 *
 * <p>
 * Once produced, this value is the sole release authority. The ledger retains no second path that could return the same
 * resources.
 */
public final class ReleaseObligation {
    private final CpuPlanHandle handle;
    private final ReservationId reservationId;
    private final Map<KeyId, AEAmount> reservedDebits;

    ReleaseObligation(CpuPlanHandle handle, ReservationId reservationId, Map<KeyId, AEAmount> reservedDebits) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.reservedDebits = ExactReservationReceipt.copyDebits(reservedDebits, "reservedDebits");
    }

    /** Creates immutable checkpoint data only; it grants no broker, storage, or lifecycle mutation capability. */
    public static ReleaseObligation restoreForRecovery(CpuPlanHandle handle, ReservationId reservationId,
            Map<KeyId, AEAmount> reservedDebits) {
        return new ReleaseObligation(handle, reservationId, reservedDebits);
    }

    public CpuPlanHandle handle() {
        return handle;
    }

    public ReservationId reservationId() {
        return reservationId;
    }

    public Map<KeyId, AEAmount> reservedDebits() {
        return reservedDebits;
    }
}
