package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/**
 * Inert, validated crash-recovery authority. It cannot issue, release, acknowledge, or rebind any live service.
 * Activation into operational seams is deliberately a later recovery gate.
 */
public record ExactRecoveryCheckpoint(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
        ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder,
        Optional<ReservedPlanLease> failedHandoffLease, boolean recoveryRequired) {

    /** Source-compatible construction for checkpoints without an irreversible materialization failure. */
    public ExactRecoveryCheckpoint(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder, boolean recoveryRequired) {
        this(plan, ledger, broker, workOrder, Optional.empty(), recoveryRequired);
    }

    public ExactRecoveryCheckpoint {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(broker, "broker");
        workOrder = Objects.requireNonNull(workOrder, "workOrder");
        failedHandoffLease = Objects.requireNonNull(failedHandoffLease, "failedHandoffLease");
        if (ledger.state() == ExactCpuLedgerState.IDLE || broker.state() == ExactTransferBrokerState.IDLE
                || broker.state() == ExactTransferBrokerState.PREFLIGHT
                || broker.state() == ExactTransferBrokerState.EXTRACTING)
            throw new IllegalArgumentException("Resource-free or transient state is not a recoverable checkpoint");
        CheckpointClass checkpointClass = classify(ledger, broker, workOrder);
        if (checkpointClass != CheckpointClass.HANDED_OFF && failedHandoffLease.isPresent())
            throw new IllegalArgumentException("Only handoff recovery may retain a failed materialization lease");
        switch (checkpointClass) {
            case PREPARED -> validatePrepared(plan, ledger, broker, workOrder);
            case RESERVED -> validateReserved(plan, ledger, broker, workOrder);
            case RELEASE_PENDING -> validateReleasePending(plan, ledger, broker, workOrder);
            case HANDED_OFF -> validateHandedOff(plan, ledger, broker, workOrder, failedHandoffLease);
        }
        if (requiresRecovery(ledger, broker, workOrder) && !recoveryRequired)
            throw new IllegalArgumentException("Durable command evidence or a fail-closed authority requires recovery");
    }

    /**
     * Captures immutable observations only. Cross-object locking would deadlock against normal transitions, therefore
     * callers must invoke this from the server-thread, non-reentrant checkpoint boundary. The available gate and every
     * sampled identity are checked before and after one observation pass; no retry or operational mutation occurs.
     */
    static ExactRecoveryCheckpoint capture(ExactCpuLedger ledger, ExactTransferBroker broker,
            Optional<ExactWorkOrder> workOrder) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(broker, "broker");
        workOrder = Objects.requireNonNull(workOrder, "workOrder");
        if (!broker.recoveryCaptureAllowed() || workOrder.isPresent() && !workOrder.get().recoveryCaptureAllowed())
            throw new IllegalStateException("Recovery capture requires the idle server-thread checkpoint boundary");
        ExactCpuLedgerSnapshot ledgerBefore = ledger.snapshot();
        ExactTransferBrokerSnapshot brokerBefore = broker.snapshot();
        ExactWorkOrderSnapshot workBefore = workOrder.map(ExactWorkOrder::snapshot).orElse(null);
        Optional<ReservedPlanLease> failedLeaseBefore = broker.handoffFailureLease();
        ExactCraftingPlan plan = ledger.recoveryPlan();
        if (plan == null)
            throw new IllegalArgumentException("Recovery ledger has no sealed plan authority");
        ExactCpuLedgerSnapshot ledgerAfter = ledger.snapshot();
        ExactTransferBrokerSnapshot brokerAfter = broker.snapshot();
        ExactWorkOrderSnapshot workAfter = workOrder.map(ExactWorkOrder::snapshot).orElse(null);
        Optional<ReservedPlanLease> failedLeaseAfter = broker.handoffFailureLease();
        if (!broker.recoveryCaptureAllowed() || workOrder.isPresent() && !workOrder.get().recoveryCaptureAllowed()
                || !ledgerBefore.equals(ledgerAfter) || !brokerBefore.equals(brokerAfter)
                || !Objects.equals(workBefore, workAfter) || !failedLeaseBefore.equals(failedLeaseAfter)
                || plan != ledger.recoveryPlan())
            throw new IllegalStateException("Recovery capture lost its server-thread/non-reentrant stability gate");
        Optional<ExactWorkOrderSnapshot> capturedWork = Optional.ofNullable(workAfter);
        return new ExactRecoveryCheckpoint(plan, ledgerAfter, brokerAfter, capturedWork, failedLeaseAfter,
                requiresRecovery(ledgerAfter, brokerAfter, capturedWork));
    }

    /** Compatibility overload for the handoff-only callers from the previous execution phase. */
    static ExactRecoveryCheckpoint capture(ExactCpuLedger ledger, ExactTransferBroker broker,
            ExactWorkOrder workOrder) {
        return capture(ledger, broker, Optional.of(Objects.requireNonNull(workOrder, "workOrder")));
    }

    private static CheckpointClass classify(ExactCpuLedgerSnapshot ledger, ExactTransferBrokerSnapshot broker,
            Optional<ExactWorkOrderSnapshot> workOrder) {
        return switch (ledger.state()) {
            case PREPARED -> CheckpointClass.PREPARED;
            case RESERVED -> CheckpointClass.RESERVED;
            case RELEASE_PENDING -> CheckpointClass.RELEASE_PENDING;
            case HANDED_OFF -> CheckpointClass.HANDED_OFF;
            case IDLE -> throw new IllegalArgumentException("Idle ledger has no recovery authority");
            case FAIL_CLOSED -> classifyClosedLedger(ledger, broker, workOrder);
        };
    }

    private static CheckpointClass classifyClosedLedger(ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder) {
        if (ledger.handle().isEmpty())
            throw new IllegalArgumentException("Resource-free fail-closed ledger is not recoverable");
        if (workOrder.isPresent() || ledger.leaseIdentity().isPresent() || broker.workOrderId().isPresent()
                || broker.leaseIdentity().isPresent())
            return CheckpointClass.HANDED_OFF;
        if (ledger.releaseObligation().isPresent())
            return CheckpointClass.RELEASE_PENDING;
        if (ledger.reservationId().isPresent() || !ledger.reservedDebits().isEmpty())
            return CheckpointClass.RESERVED;
        return CheckpointClass.PREPARED;
    }

    private static void validatePrepared(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder) {
        require(ledger.handle().isPresent() && ledger.reservationId().isEmpty() && ledger.reservedDebits().isEmpty()
                && ledger.releaseObligation().isEmpty() && ledger.leaseIdentity().isEmpty(),
                "Prepared recovery ledger retains unowned resource authority");
        require(workOrder.isEmpty() && broker.workOrderId().isEmpty() && broker.leaseIdentity().isEmpty(),
                "Pre-handoff recovery cannot retain a work order");
        require(broker.state() == ExactTransferBrokerState.ROLLBACK_PENDING
                || broker.state() == ExactTransferBrokerState.FAIL_CLOSED,
                "Prepared recovery requires rollback-pending or fail-closed broker custody");
        requireBrokerPlanHandle(plan, ledger, broker);
        require(!broker.escrowed().isEmpty() && componentwiseAtMost(broker.escrowed(), plan.initialStorageDebits()),
                "Prepared recovery escrow must be a nonempty componentwise plan-debit subset");
    }

    private static void validateReserved(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder) {
        require(ledger.handle().isPresent() && ledger.reservationId().isPresent()
                && ledger.reservedDebits().equals(plan.initialStorageDebits()) && ledger.releaseObligation().isEmpty()
                && ledger.leaseIdentity().isEmpty(), "Reserved recovery ledger must own the exact sealed debit");
        require(workOrder.isEmpty() && broker.workOrderId().isEmpty() && broker.leaseIdentity().isEmpty(),
                "Pre-handoff recovery cannot retain a work order");
        require(broker.state() == ExactTransferBrokerState.RESERVED
                || broker.state() == ExactTransferBrokerState.FAIL_CLOSED,
                "Reserved recovery requires reserved or fail-closed broker custody");
        requireBrokerReservation(plan, ledger, broker);
        if (broker.state() == ExactTransferBrokerState.RESERVED)
            require(broker.escrowed().equals(plan.initialStorageDebits()),
                    "Reserved broker escrow must equal the sealed plan debit");
        else
            require(componentwiseAtMost(broker.escrowed(), plan.initialStorageDebits()),
                    "Fail-closed reserved escrow must be a componentwise plan-debit subset");
    }

    private static void validateReleasePending(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder) {
        require(ledger.handle().isPresent() && ledger.reservationId().isPresent()
                && ledger.releaseObligation().isPresent()
                && ledger.leaseIdentity().isEmpty(), "Release-pending recovery ledger lacks its release authority");
        ReleaseObligation obligation = ledger.releaseObligation().orElseThrow();
        require(obligation.handle().equals(ledger.handle().orElseThrow())
                && obligation.reservationId().equals(ledger.reservationId().orElseThrow())
                && obligation.reservedDebits().equals(plan.initialStorageDebits())
                && ledger.reservedDebits().equals(plan.initialStorageDebits()),
                "Release obligation must exactly retain the sealed reservation debit");
        require(workOrder.isEmpty() && broker.workOrderId().isEmpty() && broker.leaseIdentity().isEmpty(),
                "Pre-handoff release cannot retain a work order");
        require(broker.state() == ExactTransferBrokerState.RELEASE_PENDING
                || broker.state() == ExactTransferBrokerState.FAIL_CLOSED,
                "Release-pending recovery requires release-pending or fail-closed broker custody");
        requireBrokerReservation(plan, ledger, broker);
        require(componentwiseAtMost(broker.escrowed(), plan.initialStorageDebits()),
                "Release escrow must be a componentwise plan-debit subset");
    }

    private static void validateHandedOff(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker, Optional<ExactWorkOrderSnapshot> workOrder,
            Optional<ReservedPlanLease> failedHandoffLease) {
        require(ledger.handle().isPresent() && ledger.reservationId().isPresent() && ledger.leaseIdentity().isPresent()
                && ledger.reservedDebits().equals(plan.initialStorageDebits()) && ledger.releaseObligation().isEmpty(),
                "Handed-off recovery ledger must retain the exact lease debit");
        if (workOrder.isEmpty()) {
            require(broker.state() == ExactTransferBrokerState.FAIL_CLOSED && failedHandoffLease.isPresent(),
                    "Missing work order requires the explicit irreversible fail-closed handoff lease");
            ReservedPlanLease failed = failedHandoffLease.orElseThrow();
            require(failed.plan() == plan && failed.planId().equals(plan.planId())
                    && failed.handle().equals(ledger.handle().orElseThrow())
                    && failed.reservationId().equals(ledger.reservationId().orElseThrow())
                    && failed.leaseIdentity().equals(ledger.leaseIdentity().orElseThrow())
                    && failed.reservedDebits().equals(plan.initialStorageDebits())
                    && failed.plan().initialStorageDebits().equals(plan.initialStorageDebits())
                    && broker.escrowed().equals(plan.initialStorageDebits()),
                    "Fail-closed handoff lease or escrow differs from its sealed authority");
            requireBrokerReservation(plan, ledger, broker);
            require(broker.workOrderId().isPresent() && broker.leaseIdentity().isPresent()
                    && broker.leaseIdentity().orElseThrow().equals(failed.leaseIdentity()),
                    "Fail-closed handoff broker lacks its staged work-order lease identity");
            return;
        }
        require(failedHandoffLease.isEmpty(), "Materialized work order cannot retain a second failed-handoff lease");
        require(broker.state() == ExactTransferBrokerState.LEASED
                || broker.state() == ExactTransferBrokerState.FAIL_CLOSED,
                "Handed-off recovery requires leased or fail-closed-handoff broker state");
        require(broker.escrowed().isEmpty(), "Handed-off broker cannot retain duplicate escrow custody");
        ExactWorkOrderSnapshot order = workOrder.orElseThrow();
        require(order.planId().equals(plan.planId()) && order.handle().equals(ledger.handle().orElseThrow())
                && order.reservationId().equals(ledger.reservationId().orElseThrow())
                && order.leaseIdentity().equals(ledger.leaseIdentity().orElseThrow()),
                "Recovery work-order identity differs from its ledger lease");
        requireBrokerReservation(plan, ledger, broker);
        require(broker.workOrderId().isPresent() && broker.leaseIdentity().isPresent()
                && broker.workOrderId().orElseThrow().equals(order.workOrderId())
                && broker.leaseIdentity().orElseThrow().equals(order.leaseIdentity()),
                "Recovery broker lease identity differs from its work order");
    }

    private static void requireBrokerPlanHandle(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker) {
        require(broker.planId().isPresent() && broker.handle().isPresent() && broker.reservationId().isPresent()
                && broker.planId().orElseThrow().equals(plan.planId())
                && broker.handle().orElseThrow().equals(ledger.handle().orElseThrow()),
                "Recovery broker plan/handle/reservation identity differs from its ledger");
    }

    private static void requireBrokerReservation(ExactCraftingPlan plan, ExactCpuLedgerSnapshot ledger,
            ExactTransferBrokerSnapshot broker) {
        requireBrokerPlanHandle(plan, ledger, broker);
        require(broker.reservationId().isPresent()
                && broker.reservationId().orElseThrow().equals(ledger.reservationId().orElseThrow()),
                "Recovery broker reservation identity differs from its ledger");
    }

    private static boolean componentwiseAtMost(Map<KeyId, AEAmount> observed, Map<KeyId, AEAmount> maximum) {
        for (var entry : observed.entrySet()) {
            AEAmount limit = maximum.get(entry.getKey());
            if (limit == null || entry.getValue().compareTo(limit) > 0)
                return false;
        }
        return true;
    }

    private static boolean requiresRecovery(ExactCpuLedgerSnapshot ledger, ExactTransferBrokerSnapshot broker,
            Optional<ExactWorkOrderSnapshot> workOrder) {
        if (ledger.state() == ExactCpuLedgerState.FAIL_CLOSED || broker.state() == ExactTransferBrokerState.FAIL_CLOSED
                || broker.transferDiscrepancy().isPresent())
            return true;
        if (workOrder.isEmpty())
            return false;
        ExactWorkOrderSnapshot order = workOrder.orElseThrow();
        return order.state() == ExactWorkOrderState.FAIL_CLOSED || order.inFlightCommand().isPresent()
                || order.discrepancy().isPresent()
                || order.completedEvidence().isPresent() && !order.completedEvidenceProgressApplied();
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new IllegalArgumentException(message);
    }

    private enum CheckpointClass {
        PREPARED,
        RESERVED,
        RELEASE_PENDING,
        HANDED_OFF
    }
}
