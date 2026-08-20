package appeng.rebuild.api.exact;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import appeng.rebuild.execution.ExactCpuExecutionSession;
import appeng.rebuild.execution.ExactCpuLedgerState;
import appeng.rebuild.execution.ExactTransferBrokerState;
import appeng.rebuild.execution.ExactWorkOrderSnapshot;
import appeng.rebuild.execution.ExactWorkOrderState;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/**
 * Exact, transport-safe observation of a CPU session.
 *
 * <p>
 * This is deliberately an observation: it has no plan, storage, broker, or work-order authority. The three amounts are
 * exact totals for material retained by their corresponding snapshot section.
 */
public record ExactCpuStatus(ExactCpuLedgerState ledgerState, ExactTransferBrokerState brokerState,
        Optional<ExactWorkOrderState> workOrderState, AEAmount reserved, AEAmount escrowed, AEAmount custody) {

    public ExactCpuStatus {
        Objects.requireNonNull(ledgerState, "ledgerState");
        Objects.requireNonNull(brokerState, "brokerState");
        workOrderState = Objects.requireNonNull(workOrderState, "workOrderState");
        requireBounded(reserved, "reserved");
        requireBounded(escrowed, "escrowed");
        requireBounded(custody, "custody");
    }

    /** Derives a bounded status without reading the world or exposing mutable execution state. */
    public static BuildResult from(ExactCpuExecutionSession.ExactCpuSessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        AEAmount reserved = sum(snapshot.ledger().reservedDebits());
        AEAmount escrowed = sum(snapshot.broker().escrowed());
        if (reserved == null || escrowed == null) {
            return new Failure(FailureReason.QUANTITY_LIMIT);
        }
        Optional<ExactWorkOrderSnapshot> workOrder = snapshot.workOrder();
        AEAmount custody = AEAmount.ZERO;
        if (workOrder.isPresent()) {
            custody = sum(workOrder.get().custody());
        }
        if (custody == null) {
            return new Failure(FailureReason.QUANTITY_LIMIT);
        }
        return new Success(new ExactCpuStatus(snapshot.ledger().state(), snapshot.broker().state(),
                workOrder.map(ExactWorkOrderSnapshot::state), reserved, escrowed, custody));
    }

    private static AEAmount sum(Map<KeyId, AEAmount> amounts) {
        AEAmount total = AEAmount.ZERO;
        for (AEAmount amount : amounts.values()) {
            total = total.add(Objects.requireNonNull(amount, "amount"));
            if (total.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
                return null;
            }
        }
        return total;
    }

    private static void requireBounded(AEAmount amount, String name) {
        Objects.requireNonNull(amount, name);
        if (amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException(name + " exceeds exact status quantity limit");
        }
    }

    public sealed interface BuildResult permits Success, Failure {
    }

    public record Success(ExactCpuStatus status) implements BuildResult {
        public Success {
            Objects.requireNonNull(status, "status");
        }
    }

    public record Failure(FailureReason reason) implements BuildResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum FailureReason {
        QUANTITY_LIMIT
    }
}
