package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/**
 * Immutable hand-off of a validated plan and its exact reservation to a future work order.
 *
 * <p>
 * The lease is the only execution authority produced by this phase. Its owner must acknowledge completion or abort;
 * this ledger neither executes patterns nor releases the resources while the lease exists.
 */
public final class ReservedPlanLease {
    private final UUID leaseIdentity;
    private final CpuPlanHandle handle;
    private final ReservationId reservationId;
    private final ExactCraftingPlan plan;
    private final Map<KeyId, AEAmount> reservedDebits;

    ReservedPlanLease(UUID leaseIdentity, CpuPlanHandle handle, ReservationId reservationId, ExactCraftingPlan plan,
            Map<KeyId, AEAmount> reservedDebits) {
        this.leaseIdentity = Objects.requireNonNull(leaseIdentity, "leaseIdentity");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.plan = Objects.requireNonNull(plan, "plan");
        this.reservedDebits = ExactReservationReceipt.copyDebits(reservedDebits, "reservedDebits");
        if (!this.reservedDebits.equals(plan.initialStorageDebits())) {
            throw new IllegalArgumentException("lease debits must equal plan initial storage debits");
        }
    }

    public UUID leaseIdentity() {
        return leaseIdentity;
    }

    public CpuPlanHandle handle() {
        return handle;
    }

    public ReservationId reservationId() {
        return reservationId;
    }

    public ExactCraftingPlan plan() {
        return plan;
    }

    /** Durable identity of the immutable plan transferred to this work-order lease. */
    public ExactPlanId planId() {
        return plan.planId();
    }

    public Map<KeyId, AEAmount> reservedDebits() {
        return reservedDebits;
    }
}
