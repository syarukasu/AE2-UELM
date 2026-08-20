package appeng.rebuild.execution;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.planner.GridRevision;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/**
 * Immutable evidence that a future same-package transfer broker reserved exactly one prepared handle's initial storage
 * debit.
 *
 * <p>
 * Construction is intentionally package-private: callers cannot fabricate a successful physical reservation through the
 * public CPU API. This class owns no mutable storage reference and does not itself move a resource.
 */
public final class ExactReservationReceipt {
    private final CpuPlanHandle handle;
    private final ReservationId reservationId;
    private final ExactPlanId planId;
    private final GridRevision planningRevision;
    private final GridRevision validationRevision;
    private final ExactCraftRequest request;
    private final Map<KeyId, AEAmount> reservedDebits;

    ExactReservationReceipt(CpuPlanHandle handle, ReservationId reservationId, ExactPlanId planId,
            GridRevision planningRevision,
            GridRevision validationRevision, ExactCraftRequest request, Map<KeyId, AEAmount> reservedDebits) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.planningRevision = Objects.requireNonNull(planningRevision, "planningRevision");
        this.validationRevision = Objects.requireNonNull(validationRevision, "validationRevision");
        this.request = Objects.requireNonNull(request, "request");
        this.reservedDebits = copyDebits(reservedDebits, "reservedDebits");
    }

    /** Creates a receipt after a same-package broker has atomically reserved the plan's exact debit. */
    static ExactReservationReceipt forReservedPlan(CpuPlanHandle handle, ReservationId reservationId,
            ExactCraftingPlan plan) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(plan, "plan");
        return new ExactReservationReceipt(handle, reservationId, plan.planId(), plan.planningRevision(),
                plan.validationRevision(), plan.request(), plan.initialStorageDebits());
    }

    public CpuPlanHandle handle() {
        return handle;
    }

    public ReservationId reservationId() {
        return reservationId;
    }

    public ExactPlanId planId() {
        return planId;
    }

    public GridRevision planningRevision() {
        return planningRevision;
    }

    public GridRevision validationRevision() {
        return validationRevision;
    }

    public ExactCraftRequest request() {
        return request;
    }

    public Map<KeyId, AEAmount> reservedDebits() {
        return reservedDebits;
    }

    boolean matches(CpuPlanHandle expectedHandle, ExactCraftingPlan plan) {
        return handle.equals(expectedHandle) && planId.equals(plan.planId())
                && planningRevision.equals(plan.planningRevision())
                && validationRevision.equals(plan.validationRevision()) && request.equals(plan.request())
                && reservedDebits.equals(plan.initialStorageDebits());
    }

    static Map<KeyId, AEAmount> copyDebits(Map<KeyId, AEAmount> source, String name) {
        Objects.requireNonNull(source, name);
        if (source.size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            throw new IllegalArgumentException(name + " exceeds the bounded exact debit map size");
        }
        TreeMap<KeyId, AEAmount> copy = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, AEAmount> entry : source.entrySet()) {
            KeyId key = Objects.requireNonNull(entry.getKey(), name + " key");
            AEAmount amount = Objects.requireNonNull(entry.getValue(), name + " amount");
            if (amount.equals(AEAmount.ZERO)) {
                throw new IllegalArgumentException(name + " cannot contain zero quantities");
            }
            if (amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
                throw new IllegalArgumentException(name + " exceeds the exact quantity bit limit");
            }
            copy.put(key, amount);
        }
        return Collections.unmodifiableMap(copy);
    }

    static Map<KeyId, AEAmount> copyDebitsOrEmpty(Map<KeyId, AEAmount> source, String name) {
        return copyDebits(source, name);
    }
}
