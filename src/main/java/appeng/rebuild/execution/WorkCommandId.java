package appeng.rebuild.execution;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque, lease-bound identity for one physical execution command.
 *
 * <p>
 * The generation is lifecycle metadata only. Exact resource quantities are never represented by it.
 */
public record WorkCommandId(ExactPlanId planId, UUID leaseIdentity, WorkOrderId workOrderId, long generation) {
    public WorkCommandId {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(leaseIdentity, "leaseIdentity");
        Objects.requireNonNull(workOrderId, "workOrderId");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
    }
}
