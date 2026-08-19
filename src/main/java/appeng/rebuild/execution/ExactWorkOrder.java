package appeng.rebuild.execution;

import java.util.Map;

import appeng.api.networking.security.IActionSource;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.BrokerExactStorage;

/**
 * Sole owner of the escrow transferred from an exact CPU reservation.
 *
 * <p>
 * Phase 7 establishes ownership only. Commands, physical execution, cancellation, settlement, and CPU acknowledgement
 * are deliberately absent until their respective exact accounting transitions are implemented.
 */
public final class ExactWorkOrder {
    private final WorkOrderId workOrderId;
    private final ReservedPlanLease lease;
    // Future same-package accounting transitions replace this immutable value atomically; it is never exposed directly.
    private Map<KeyId, AEAmount> custody;
    @SuppressWarnings("unused")
    private final BrokerExactStorage storage;
    @SuppressWarnings("unused")
    private final ServerThreadGate serverThread;
    @SuppressWarnings("unused")
    private final IActionSource actionSource;
    @SuppressWarnings("unused")
    private final ExactCpuLedger ledger;

    private ExactWorkOrderState state = ExactWorkOrderState.READY;

    ExactWorkOrder(WorkOrderId workOrderId, ReservedPlanLease lease, Map<KeyId, AEAmount> custody,
            BrokerExactStorage storage, ServerThreadGate serverThread, IActionSource actionSource,
            ExactCpuLedger ledger) {
        this.workOrderId = workOrderId;
        this.lease = lease;
        this.custody = custody;
        this.storage = storage;
        this.serverThread = serverThread;
        this.actionSource = actionSource;
        this.ledger = ledger;
    }

    /** Assignment-only materialization from the broker's fully validated pre-handoff staging record. */
    static ExactWorkOrder fromStaged(WorkOrderId workOrderId, ReservedPlanLease lease, Map<KeyId, AEAmount> custody,
            BrokerExactStorage storage, ServerThreadGate serverThread, IActionSource actionSource,
            ExactCpuLedger ledger) {
        return new ExactWorkOrder(workOrderId, lease, custody, storage, serverThread, actionSource, ledger);
    }

    /** Returns a detached exact observation; it grants no mutation or release authority. */
    public synchronized ExactWorkOrderSnapshot snapshot() {
        return new ExactWorkOrderSnapshot(state, lease.planId(), lease.handle(), lease.reservationId(),
                lease.leaseIdentity(), workOrderId, custody);
    }
}
