package appeng.rebuild.execution;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import appeng.api.networking.security.IActionSource;
import appeng.rebuild.storage.BrokerExactStorage;

/**
 * The single durable owner of a live exact CPU execution graph.
 *
 * <p>
 * The native CPU owns one instance of this class. Its component authorities never escape: callers receive immutable
 * observations only, while every transition which can retain material immediately replaces the CPU's recovery
 * checkpoint. This is deliberately a server-thread operation boundary; it neither reads a world nor retries a failed
 * storage operation.
 */
public final class ExactCpuExecutionSession {
    private final ExactCpuLedger ledger;
    private final ExactTransferBroker broker;
    private final Consumer<ExactRecoveryCheckpoint> checkpointPublisher;
    private final Runnable checkpointClearer;
    private Optional<ExactWorkOrder> workOrder;
    private boolean publishedCheckpoint;

    private ExactCpuExecutionSession(ExactCpuLedger ledger, ExactTransferBroker broker,
            Optional<ExactWorkOrder> workOrder,
            Consumer<ExactRecoveryCheckpoint> checkpointPublisher, Runnable checkpointClearer) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.workOrder = Objects.requireNonNull(workOrder, "workOrder");
        this.checkpointPublisher = Objects.requireNonNull(checkpointPublisher, "checkpointPublisher");
        this.checkpointClearer = Objects.requireNonNull(checkpointClearer, "checkpointClearer");
    }

    /**
     * Creates a new CPU-owned session. No plan or physical authority exists until {@link #prepare(ExactCraftingPlan)}.
     */
    public static ExactCpuExecutionSession create(BrokerExactStorage storage, CurrentPatternSnapshotSource patterns,
            ServerThreadGate serverThread, IActionSource actionSource,
            Consumer<ExactRecoveryCheckpoint> checkpointPublisher, Runnable checkpointClearer) {
        return new ExactCpuExecutionSession(new ExactCpuLedger(), new ExactTransferBroker(
                Objects.requireNonNull(storage, "storage"), Objects.requireNonNull(patterns, "patterns"),
                Objects.requireNonNull(serverThread, "serverThread"),
                Objects.requireNonNull(actionSource, "actionSource")),
                Optional.empty(), checkpointPublisher, checkpointClearer);
    }

    /** Activates an inert CPU tag only into this durable session. */
    public static ActivationResult activate(ExactRecoveryCheckpoint checkpoint, BrokerExactStorage storage,
            CurrentPatternSnapshotSource patterns, ServerThreadGate serverThread, IActionSource actionSource,
            Consumer<ExactRecoveryCheckpoint> checkpointPublisher, Runnable checkpointClearer) {
        ExactRecoveryActivation.Result result = new ExactRecoveryActivation().activate(checkpoint, storage, patterns,
                serverThread, actionSource);
        if (result instanceof ExactRecoveryActivation.Activated activated) {
            ExactCpuExecutionSession session = new ExactCpuExecutionSession(activated.ledger(), activated.broker(),
                    activated.workOrder(), checkpointPublisher, checkpointClearer);
            session.publishDurableCheckpoint();
            return new Activated(session, session.snapshot());
        }
        if (result instanceof ExactRecoveryActivation.RecoveryRequired required) {
            return new RecoveryRequired(required.reason());
        }
        return new Rejected(((ExactRecoveryActivation.Rejected) result).reason());
    }

    public ExactCpuSessionResult prepare(ExactCraftingPlan plan) {
        ExactCpuLedgerResult result = ledger.prepare(plan);
        publishIfDurable();
        return new Ledger(result, snapshot());
    }

    public ExactCpuSessionResult reserve() {
        CpuPlanHandle handle = ledger.snapshot().handle().orElse(null);
        ExactTransferBrokerResult result = handle == null
                ? new ExactTransferBrokerResult.Failure(ExactTransferBrokerResult.FailureReason.STALE_HANDLE,
                        broker.snapshot())
                : broker.reserve(ledger, handle);
        publishIfDurable();
        return new Broker(observe(result), snapshot());
    }

    /** Cancels a prepared plan before any physical reservation exists. */
    public ExactCpuSessionResult discardPrepared() {
        ExactCpuLedgerSnapshot snapshot = ledger.snapshot();
        ExactCpuLedgerResult result = snapshot.state() == ExactCpuLedgerState.PREPARED && snapshot.handle().isPresent()
                ? ledger.cancelPrepared(snapshot.handle().orElseThrow())
                : new ExactCpuLedgerResult.Failure(ExactCpuLedgerResult.FailureReason.WRONG_STATE);
        publishIfDurable();
        return new Ledger(result, snapshot());
    }

    public ExactCpuSessionResult startWorkOrder() {
        CpuPlanHandle handle = ledger.snapshot().handle().orElse(null);
        ExactTransferBrokerResult result = handle == null
                ? new ExactTransferBrokerResult.Failure(ExactTransferBrokerResult.FailureReason.STALE_HANDLE,
                        broker.snapshot())
                : broker.startWorkOrder(ledger, handle);
        if (result instanceof ExactTransferBrokerResult.Started started) {
            workOrder = Optional.of(started.workOrder());
        }
        publishIfDurable();
        return new Broker(observe(result), snapshot());
    }

    public ExactCpuSessionResult cancelReservation() {
        CpuPlanHandle handle = ledger.snapshot().handle().orElse(null);
        ExactTransferBrokerResult result = handle == null
                ? new ExactTransferBrokerResult.Failure(ExactTransferBrokerResult.FailureReason.STALE_HANDLE,
                        broker.snapshot())
                : broker.cancelReservation(ledger, handle);
        publishIfDurable();
        return new Broker(observe(result), snapshot());
    }

    public ExactCpuSessionResult progressReservationRelease(int operationBudget) {
        ExactTransferBrokerResult result = broker.progressRelease(operationBudget);
        publishIfDurable();
        return new Broker(observe(result), snapshot());
    }

    public ExactCpuSessionResult issueNext(long physicalWindow) {
        if (workOrder.isEmpty()) {
            return new Unavailable(UnavailableReason.NO_WORK_ORDER, snapshot());
        }
        ExactWorkOrderCommandResult result = workOrder.orElseThrow().issueNext(physicalWindow);
        publishIfDurable();
        return new Command(result, snapshot());
    }

    public ExactCpuSessionResult accept(WorkCommandAcceptance acceptance) {
        return transition(order -> order.accept(acceptance));
    }

    /** Accepts the exact command returned by {@link #issueNext(long)} without exposing evidence constructors. */
    public ExactCpuSessionResult acceptIssued(ExactWorkCommand command) {
        return accept(new WorkCommandAcceptance(Objects.requireNonNull(command, "command")));
    }

    public ExactCpuSessionResult reject(WorkCommandRejection rejection) {
        return transition(order -> order.reject(rejection));
    }

    /** Rejects an unexecuted issued command and restores its custody to the work order. */
    public ExactCpuSessionResult rejectIssued(ExactWorkCommand command) {
        return reject(new WorkCommandRejection(Objects.requireNonNull(command, "command")));
    }

    public ExactCpuSessionResult complete(WorkCommandCompletion completion) {
        return transition(order -> order.complete(completion));
    }

    /** Completes one accepted command with exact, identity-bound physical results. */
    public ExactCpuSessionResult completeIssued(ExactWorkCommand command,
            java.util.Map<appeng.rebuild.key.KeyId, appeng.rebuild.quantity.AEAmount> actualOutputs,
            java.util.Map<appeng.rebuild.key.KeyId, appeng.rebuild.quantity.AEAmount> actualRemainders) {
        return complete(new WorkCommandCompletion(Objects.requireNonNull(command, "command"), actualOutputs,
                actualRemainders));
    }

    public ExactCpuSessionResult requestCancellation() {
        if (workOrder.isEmpty()) {
            return new Unavailable(UnavailableReason.NO_WORK_ORDER, snapshot());
        }
        ExactWorkOrderLifecycleResult result = workOrder.orElseThrow().requestCancellation();
        publishIfDurable();
        return new Lifecycle(result, snapshot());
    }

    public ExactCpuSessionResult progressWorkRelease(int operationBudget) {
        if (workOrder.isEmpty()) {
            return new Unavailable(UnavailableReason.NO_WORK_ORDER, snapshot());
        }
        ExactWorkOrderLifecycleResult result = workOrder.orElseThrow().progressRelease(operationBudget);
        publishIfDurable();
        return new Lifecycle(result, snapshot());
    }

    /** Immutable exact status for packet/GUI code; legacy consumers must explicitly project these values. */
    public ExactCpuSessionSnapshot snapshot() {
        return new ExactCpuSessionSnapshot(ledger.snapshot(), broker.snapshot(),
                workOrder.map(ExactWorkOrder::snapshot));
    }

    private ExactCpuSessionResult transition(WorkOrderTransition transition) {
        if (workOrder.isEmpty()) {
            return new Unavailable(UnavailableReason.NO_WORK_ORDER, snapshot());
        }
        ExactWorkOrderTransitionResult result = transition.apply(workOrder.orElseThrow());
        publishIfDurable();
        return new Transition(result, snapshot());
    }

    private void publishIfDurable() {
        ExactCpuLedgerState ledgerState = ledger.snapshot().state();
        ExactTransferBrokerState brokerState = broker.snapshot().state();
        // A prepared plan has no physical authority. ExactRecoveryCheckpoint intentionally rejects that idle broker
        // shape, so it must not create a stale raw save merely because plan preparation succeeded.
        if (ledgerState != ExactCpuLedgerState.IDLE && brokerState != ExactTransferBrokerState.IDLE) {
            publishDurableCheckpoint();
        } else if (publishedCheckpoint) {
            checkpointClearer.run();
            publishedCheckpoint = false;
        }
    }

    private void publishDurableCheckpoint() {
        checkpointPublisher.accept(ExactRecoveryCheckpoint.capture(ledger, broker, workOrder));
        publishedCheckpoint = true;
    }

    private static BrokerOutcome observe(ExactTransferBrokerResult result) {
        if (result instanceof ExactTransferBrokerResult.Reserved) {
            return new BrokerOutcome(BrokerOutcomeType.RESERVED, Optional.empty());
        }
        if (result instanceof ExactTransferBrokerResult.Started) {
            return new BrokerOutcome(BrokerOutcomeType.STARTED, Optional.empty());
        }
        if (result instanceof ExactTransferBrokerResult.Cancelled) {
            return new BrokerOutcome(BrokerOutcomeType.CANCELLED, Optional.empty());
        }
        if (result instanceof ExactTransferBrokerResult.RolledBack) {
            return new BrokerOutcome(BrokerOutcomeType.ROLLED_BACK, Optional.empty());
        }
        if (result instanceof ExactTransferBrokerResult.ReleasePending) {
            return new BrokerOutcome(BrokerOutcomeType.RELEASE_PENDING, Optional.empty());
        }
        return new BrokerOutcome(BrokerOutcomeType.FAILURE,
                Optional.of(((ExactTransferBrokerResult.Failure) result).reason()));
    }

    @FunctionalInterface
    private interface WorkOrderTransition {
        ExactWorkOrderTransitionResult apply(ExactWorkOrder order);
    }

    /** Session outcomes are observations; none expose the ledger, broker, or a mutable work-order authority. */
    public sealed interface ExactCpuSessionResult permits Ledger, Broker, Command, Transition, Lifecycle, Unavailable {
    }

    public record Ledger(ExactCpuLedgerResult result,
            ExactCpuSessionSnapshot snapshot) implements ExactCpuSessionResult {
    }

    public record Broker(BrokerOutcome result, ExactCpuSessionSnapshot snapshot) implements ExactCpuSessionResult {
    }

    public record Command(ExactWorkOrderCommandResult result,
            ExactCpuSessionSnapshot snapshot) implements ExactCpuSessionResult {
    }

    public record Transition(ExactWorkOrderTransitionResult result, ExactCpuSessionSnapshot snapshot)
            implements
                ExactCpuSessionResult {
    }

    public record Lifecycle(ExactWorkOrderLifecycleResult result, ExactCpuSessionSnapshot snapshot)
            implements
                ExactCpuSessionResult {
    }

    public record Unavailable(UnavailableReason reason,
            ExactCpuSessionSnapshot snapshot) implements ExactCpuSessionResult {
    }

    public enum UnavailableReason {
        NO_WORK_ORDER
    }

    public record BrokerOutcome(BrokerOutcomeType type, Optional<ExactTransferBrokerResult.FailureReason> failure) {
        public BrokerOutcome {
            Objects.requireNonNull(type, "type");
            failure = Objects.requireNonNull(failure, "failure");
        }
    }

    public enum BrokerOutcomeType {
        RESERVED,
        STARTED,
        CANCELLED,
        ROLLED_BACK,
        RELEASE_PENDING,
        FAILURE
    }

    public record ExactCpuSessionSnapshot(ExactCpuLedgerSnapshot ledger, ExactTransferBrokerSnapshot broker,
            Optional<ExactWorkOrderSnapshot> workOrder) {
        public ExactCpuSessionSnapshot {
            Objects.requireNonNull(ledger, "ledger");
            Objects.requireNonNull(broker, "broker");
            workOrder = Objects.requireNonNull(workOrder, "workOrder");
        }
    }

    public sealed interface ActivationResult permits Activated, RecoveryRequired, Rejected {
    }

    /** The native CPU retains this session; it never receives its ledger, broker, or work order. */
    public record Activated(ExactCpuExecutionSession session,
            ExactCpuSessionSnapshot snapshot) implements ActivationResult {
    }

    public record RecoveryRequired(ExactRecoveryActivation.Reason reason) implements ActivationResult {
    }

    public record Rejected(ExactRecoveryActivation.Reason reason) implements ActivationResult {
    }
}
