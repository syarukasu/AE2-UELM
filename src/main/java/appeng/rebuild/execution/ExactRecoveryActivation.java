package appeng.rebuild.execution;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import appeng.api.networking.security.IActionSource;
import appeng.rebuild.storage.BrokerExactStorage;

/**
 * Sole public activation boundary for an inert {@link ExactRecoveryCheckpoint}.
 *
 * <p>
 * Activation is deliberately assignment-only: it calls neither storage nor pattern capture, does not touch a world, and
 * never retries or reissues a command. A checkpoint with any ambiguous physical authority remains inert and is returned
 * as typed recovery-required evidence instead of being made operational.
 */
public final class ExactRecoveryActivation {
    /** A process lifetime bound: a stale durable checkpoint must never recreate a second live authority. */
    private static final int MAX_ACTIVATION_CLAIMS = 65_536;
    private static final Set<ActivationIdentity> ACTIVATED = new HashSet<>();

    private boolean entered;

    /**
     * Rebuilds a safe, resumable ownership graph using explicitly injected live dependencies.
     *
     * <p>
     * The server-thread gate is admitted before any injected dependency can be retained or inspected. The storage and
     * current-pattern source are intentionally not called here; normal later operations perform their own preflight.
     */
    public synchronized Result activate(ExactRecoveryCheckpoint checkpoint, BrokerExactStorage storage,
            CurrentPatternSnapshotSource patternSnapshots, ServerThreadGate serverThread, IActionSource actionSource) {
        return activate(checkpoint, storage, patternSnapshots, serverThread, actionSource, null);
    }

    /** Activates one in-flight command only after its native executor proved the same durable identity is retained. */
    public synchronized Result activateConfirmedInFlight(ExactRecoveryCheckpoint checkpoint, BrokerExactStorage storage,
            CurrentPatternSnapshotSource patternSnapshots, ServerThreadGate serverThread, IActionSource actionSource,
            WorkCommandId confirmedCommand) {
        return activate(checkpoint, storage, patternSnapshots, serverThread, actionSource,
                Objects.requireNonNull(confirmedCommand, "confirmedCommand"));
    }

    private Result activate(ExactRecoveryCheckpoint checkpoint, BrokerExactStorage storage,
            CurrentPatternSnapshotSource patternSnapshots, ServerThreadGate serverThread, IActionSource actionSource,
            WorkCommandId confirmedCommand) {
        if (serverThread == null) {
            return new Rejected(Reason.MALFORMED_ARGUMENT);
        }
        if (entered) {
            return new Rejected(Reason.REENTRANT);
        }
        entered = true;
        try {
            if (!onServerThread(serverThread)) {
                return new Rejected(Reason.WRONG_THREAD);
            }
            if (checkpoint == null || storage == null || patternSnapshots == null || actionSource == null) {
                return new Rejected(Reason.MALFORMED_ARGUMENT);
            }
            if (checkpoint.recoveryRequired() && !isConfirmedNativeInFlight(checkpoint, confirmedCommand)) {
                return new RecoveryRequired(checkpoint, Reason.AMBIGUOUS_PHYSICAL_STATE);
            }
            if (isUnreachableCompletedHandoff(checkpoint)) {
                return new Rejected(Reason.INVALID_CHECKPOINT);
            }

            try {
                ExactCpuLedger ledger = ExactCpuLedger.restoreFromRecovery(checkpoint.plan(), checkpoint.ledger());
                ExactTransferBroker broker = ExactTransferBroker.restoreFromRecovery(ledger, checkpoint.plan(),
                        checkpoint.broker(), storage, patternSnapshots, serverThread, actionSource);
                Optional<ExactWorkOrder> workOrder = checkpoint.workOrder().map(snapshot -> {
                    ReservedPlanLease lease = ledger.recoveryLease();
                    if (lease == null) {
                        throw new IllegalArgumentException("Work-order recovery has no handed-off lease");
                    }
                    return confirmedCommand == null
                            ? ExactWorkOrder.restoreFromRecovery(snapshot, lease, storage, serverThread, actionSource,
                                    ledger, broker)
                            : ExactWorkOrder.restoreConfirmedInFlightFromRecovery(snapshot, lease, storage,
                                    serverThread, actionSource, ledger, broker);
                });
                // A final detached observation check proves that all reconstructed identities and quantities match the
                // checkpoint before any live object is published to the caller.
                if (!ledger.snapshot().equals(checkpoint.ledger()) || !broker.snapshot().equals(checkpoint.broker())
                        || !workOrder.map(ExactWorkOrder::snapshot).equals(checkpoint.workOrder())) {
                    return new Rejected(Reason.INVARIANT_VIOLATION);
                }
                Claim claim = claim(ActivationIdentity.from(checkpoint));
                if (claim == Claim.ALREADY_ACTIVATED) {
                    return new Rejected(Reason.ALREADY_ACTIVATED);
                }
                if (claim == Claim.LIMIT_REACHED) {
                    return new Rejected(Reason.ACTIVATION_LIMIT);
                }
                // After the irreversible claim, construction and return are assignment-only. In particular, an Error
                // intentionally leaves the claim retained rather than risking a second live authority.
                return new Activated(ledger, broker, workOrder);
            } catch (RuntimeException invalid) {
                return new Rejected(Reason.INVALID_CHECKPOINT);
            }
        } finally {
            entered = false;
        }
    }

    private static boolean isConfirmedNativeInFlight(ExactRecoveryCheckpoint checkpoint,
            WorkCommandId confirmedCommand) {
        if (checkpoint == null || confirmedCommand == null || checkpoint.failedHandoff().isPresent()
                || checkpoint.broker().state() != ExactTransferBrokerState.LEASED
                || checkpoint.broker().transferDiscrepancy().isPresent()) {
            return false;
        }
        ExactWorkOrderSnapshot order = checkpoint.workOrder().orElse(null);
        if (order == null || (order.state() != ExactWorkOrderState.IN_FLIGHT
                && order.state() != ExactWorkOrderState.CANCEL_PENDING)
                || order.discrepancy().isPresent() || order.completedEvidence().isPresent()
                || order.transferDiscrepancy().isPresent()) {
            return false;
        }
        return order.inFlightCommand().map(ExactWorkCommand::id).filter(confirmedCommand::equals).isPresent();
    }

    private static boolean onServerThread(ServerThreadGate serverThread) {
        try {
            return serverThread.isServerThread();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean isUnreachableCompletedHandoff(ExactRecoveryCheckpoint checkpoint) {
        return checkpoint.ledger().state() == ExactCpuLedgerState.HANDED_OFF
                && checkpoint.broker().state() == ExactTransferBrokerState.LEASED
                && checkpoint.workOrder().map(ExactWorkOrderSnapshot::state)
                        .filter(state -> state == ExactWorkOrderState.COMPLETED).isPresent();
    }

    /** Atomically retains one process-lifetime claim; entries are deliberately never released. */
    private static synchronized Claim claim(ActivationIdentity identity) {
        if (ACTIVATED.contains(identity)) {
            return Claim.ALREADY_ACTIVATED;
        }
        if (ACTIVATED.size() >= MAX_ACTIVATION_CLAIMS) {
            return Claim.LIMIT_REACHED;
        }
        ACTIVATED.add(identity);
        return Claim.CLAIMED;
    }

    /** Complete durable identity of one recoverable authority graph. */
    private record ActivationIdentity(ExactPlanId planId, CpuPlanHandle handle,
            Optional<ReservationId> reservationId, Optional<java.util.UUID> leaseIdentity) {
        private ActivationIdentity {
            Objects.requireNonNull(planId, "planId");
            Objects.requireNonNull(handle, "handle");
            reservationId = Objects.requireNonNull(reservationId, "reservationId");
            leaseIdentity = Objects.requireNonNull(leaseIdentity, "leaseIdentity");
        }

        private static ActivationIdentity from(ExactRecoveryCheckpoint checkpoint) {
            return new ActivationIdentity(checkpoint.plan().planId(), checkpoint.ledger().handle().orElseThrow(),
                    checkpoint.ledger().reservationId(), checkpoint.ledger().leaseIdentity());
        }
    }

    private enum Claim {
        CLAIMED,
        ALREADY_ACTIVATED,
        LIMIT_REACHED
    }

    /** Typed outcome; it exposes no raw restoration setters. */
    public sealed interface Result permits Activated, RecoveryRequired, Rejected {
    }

    /** A fully reconstructed, still side-effect-free graph. The caller must explicitly perform any later operation. */
    public record Activated(ExactCpuLedger ledger, ExactTransferBroker broker, Optional<ExactWorkOrder> workOrder)
            implements
                Result {
        public Activated {
            Objects.requireNonNull(ledger, "ledger");
            Objects.requireNonNull(broker, "broker");
            workOrder = Objects.requireNonNull(workOrder, "workOrder");
        }
    }

    /** A durable checkpoint retained for manual, domain-specific recovery rather than automatically resumed. */
    public record RecoveryRequired(ExactRecoveryCheckpoint checkpoint, Reason reason) implements Result {
        public RecoveryRequired {
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Activation was refused before publishing any reconstructed authority. */
    public record Rejected(Reason reason) implements Result {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum Reason {
        MALFORMED_ARGUMENT,
        REENTRANT,
        WRONG_THREAD,
        AMBIGUOUS_PHYSICAL_STATE,
        INVALID_CHECKPOINT,
        INVARIANT_VIOLATION,
        ALREADY_ACTIVATED,
        ACTIVATION_LIMIT
    }
}
