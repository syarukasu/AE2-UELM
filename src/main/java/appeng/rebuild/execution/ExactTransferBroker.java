package appeng.rebuild.execution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.planner.DependencyValidationResult;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.rebuild.storage.StorageSnapshot;

/**
 * Server-thread-owned exact reservation and release broker for one crafting CPU.
 *
 * <p>
 * One operation is admitted at a time. Reservation captures current immutable dependencies, simulates every sorted
 * debit, and only then extracts. Every exact partial extraction is retained as escrow. Failure performs one bounded
 * rollback pass; further progress is explicit and bounded rather than an automatic retry loop.
 */
public final class ExactTransferBroker {
    private final BrokerExactStorage storage;
    private final CurrentPatternSnapshotSource patternSnapshots;
    private final ServerThreadGate serverThread;
    private final IActionSource actionSource;

    private ExactTransferBrokerState state = ExactTransferBrokerState.IDLE;
    private boolean entered;
    private ExactCpuLedger ledger;
    private CpuPlanHandle handle;
    private ExactPlanId planId;
    private ReservationId reservationId;
    private final TreeMap<KeyId, AEAmount> escrowed = new TreeMap<>(Comparator.comparingInt(KeyId::value));

    public ExactTransferBroker(BrokerExactStorage storage, CurrentPatternSnapshotSource patternSnapshots,
            ServerThreadGate serverThread, IActionSource actionSource) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.patternSnapshots = Objects.requireNonNull(patternSnapshots, "patternSnapshots");
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
        this.actionSource = Objects.requireNonNull(actionSource, "actionSource");
    }

    /** Reserves the exact initial debit of the handle-bound prepared plan. */
    synchronized ExactTransferBrokerResult reserve(ExactCpuLedger requestedLedger, CpuPlanHandle requestedHandle) {
        ExactTransferBrokerResult.Failure admission = beginOperation();
        if (admission != null) {
            return admission;
        }
        try {
            if (state == ExactTransferBrokerState.FAIL_CLOSED) {
                return failure(ExactTransferBrokerResult.FailureReason.FAIL_CLOSED);
            }
            if (state != ExactTransferBrokerState.IDLE) {
                return failure(ExactTransferBrokerResult.FailureReason.WRONG_STATE);
            }
            if (requestedLedger == null || requestedHandle == null) {
                return failure(ExactTransferBrokerResult.FailureReason.STALE_HANDLE);
            }
            ExactCraftingPlan plan = requestedLedger.preparedPlanForBroker(requestedHandle);
            if (plan == null) {
                return failure(ExactTransferBrokerResult.FailureReason.STALE_HANDLE);
            }

            bind(requestedLedger, requestedHandle, plan);
            state = ExactTransferBrokerState.PREFLIGHT;
            Map<KeyId, AEAmount> debits;
            try {
                debits = sorted(plan.initialStorageDebits());
            } catch (RuntimeException failure) {
                clearToIdle();
                return failure(ExactTransferBrokerResult.FailureReason.SNAPSHOT_UNAVAILABLE);
            }
            ExactTransferBrokerResult.FailureReason preflightFailure = validateCurrent(plan, debits);
            if (preflightFailure != null) {
                clearToIdle();
                return failure(preflightFailure);
            }
            for (Map.Entry<KeyId, AEAmount> debit : debits.entrySet()) {
                AEAmount simulated;
                try {
                    simulated = storage.extract(debit.getKey(), debit.getValue(), Actionable.SIMULATE, actionSource);
                } catch (RuntimeException failure) {
                    clearToIdle();
                    return failure(ExactTransferBrokerResult.FailureReason.STORAGE_FAILURE);
                }
                if (!validMovedAmount(simulated, debit.getValue())) {
                    return failClosed();
                }
                if (!debit.getValue().equals(simulated)) {
                    clearToIdle();
                    return failure(ExactTransferBrokerResult.FailureReason.STORAGE_SIMULATION_SHORT);
                }
            }

            preflightFailure = validateCurrent(plan, debits);
            if (preflightFailure != null) {
                clearToIdle();
                return failure(preflightFailure);
            }
            state = ExactTransferBrokerState.EXTRACTING;
            for (Map.Entry<KeyId, AEAmount> debit : debits.entrySet()) {
                AEAmount extracted;
                try {
                    extracted = storage.extract(debit.getKey(), debit.getValue(), Actionable.MODULATE, actionSource);
                } catch (RuntimeException failure) {
                    return startRollback(ExactTransferBrokerResult.FailureReason.STORAGE_FAILURE);
                }
                if (!validMovedAmount(extracted, debit.getValue())) {
                    return failClosed();
                }
                if (!extracted.equals(AEAmount.ZERO)) {
                    escrowed.put(debit.getKey(), extracted);
                }
                if (!extracted.equals(debit.getValue())) {
                    return startRollback(ExactTransferBrokerResult.FailureReason.STORAGE_EXTRACTION_SHORT);
                }
            }
            if (!escrowed.equals(debits)) {
                return startRollback(ExactTransferBrokerResult.FailureReason.INVARIANT_VIOLATION);
            }

            ExactReservationReceipt receipt = ExactReservationReceipt.forReservedPlan(handle, reservationId, plan);
            ExactCpuLedgerResult confirmation = ledger.confirmReservation(handle, receipt);
            if (!(confirmation instanceof ExactCpuLedgerResult.ReservationConfirmed confirmed)) {
                return startRollback(ExactTransferBrokerResult.FailureReason.CPU_REJECTED);
            }
            if (!confirmed.reservationId().equals(reservationId)) {
                return failClosed();
            }
            state = ExactTransferBrokerState.RESERVED;
            return new ExactTransferBrokerResult.Reserved(snapshot());
        } finally {
            endOperation();
        }
    }

    /**
     * Obtains the CPU ledger's sole release obligation internally, then performs one bounded exact release pass.
     */
    synchronized ExactTransferBrokerResult cancelReservation(ExactCpuLedger requestedLedger,
            CpuPlanHandle requestedHandle) {
        ExactTransferBrokerResult.Failure admission = beginOperation();
        if (admission != null) {
            return admission;
        }
        try {
            if (state == ExactTransferBrokerState.FAIL_CLOSED) {
                return failure(ExactTransferBrokerResult.FailureReason.FAIL_CLOSED);
            }
            if (state != ExactTransferBrokerState.RESERVED) {
                return failure(ExactTransferBrokerResult.FailureReason.WRONG_STATE);
            }
            if (ledger != requestedLedger || !Objects.equals(handle, requestedHandle)) {
                return failure(ExactTransferBrokerResult.FailureReason.IDENTITY_MISMATCH);
            }
            ExactCpuLedgerResult cancellation = ledger.cancelReserved(handle);
            if (!(cancellation instanceof ExactCpuLedgerResult.ReleaseRequired required)) {
                return failure(ExactTransferBrokerResult.FailureReason.CPU_REJECTED);
            }
            if (!matches(required.obligation())) {
                return failClosed();
            }
            state = ExactTransferBrokerState.RELEASE_PENDING;
            return releasePass(escrowed.size());
        } finally {
            endOperation();
        }
    }

    /**
     * Performs at most {@code operationBudget} distinct pending insert attempts. Package-private recovery authority.
     */
    synchronized ExactTransferBrokerResult progressRelease(int operationBudget) {
        ExactTransferBrokerResult.Failure admission = beginOperation();
        if (admission != null) {
            return admission;
        }
        try {
            if (state == ExactTransferBrokerState.FAIL_CLOSED) {
                return failure(ExactTransferBrokerResult.FailureReason.FAIL_CLOSED);
            }
            if (state != ExactTransferBrokerState.ROLLBACK_PENDING
                    && state != ExactTransferBrokerState.RELEASE_PENDING) {
                return failure(ExactTransferBrokerResult.FailureReason.WRONG_STATE);
            }
            if (operationBudget <= 0 || operationBudget > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
                return failure(ExactTransferBrokerResult.FailureReason.INVALID_OPERATION_BUDGET);
            }
            return releasePass(operationBudget);
        } finally {
            endOperation();
        }
    }

    /** Returns a detached exact observation and never acts as release authority. */
    public synchronized ExactTransferBrokerSnapshot snapshot() {
        return new ExactTransferBrokerSnapshot(state, Optional.ofNullable(handle), Optional.ofNullable(planId),
                Optional.ofNullable(reservationId), escrowed);
    }

    private ExactTransferBrokerResult startRollback(ExactTransferBrokerResult.FailureReason reason) {
        state = ExactTransferBrokerState.ROLLBACK_PENDING;
        ExactTransferBrokerResult release = releasePass(escrowed.size());
        if (release instanceof ExactTransferBrokerResult.RolledBack) {
            return failure(reason);
        }
        return release;
    }

    private ExactTransferBrokerResult releasePass(int operationBudget) {
        ExactTransferBrokerState releaseState = state;
        int attempted = 0;
        for (Map.Entry<KeyId, AEAmount> entry : new ArrayList<>(escrowed.entrySet())) {
            if (attempted++ >= operationBudget) {
                break;
            }
            AEAmount requested = entry.getValue();
            AEAmount inserted;
            try {
                inserted = storage.insert(entry.getKey(), requested, Actionable.MODULATE, actionSource);
            } catch (RuntimeException failure) {
                continue;
            }
            if (!validMovedAmount(inserted, requested)) {
                return failClosed();
            }
            if (inserted.equals(requested)) {
                escrowed.remove(entry.getKey());
            } else if (!inserted.equals(AEAmount.ZERO)) {
                escrowed.put(entry.getKey(), requested.subtractExact(inserted));
            }
        }
        if (!escrowed.isEmpty()) {
            return new ExactTransferBrokerResult.ReleasePending(snapshot());
        }
        if (releaseState == ExactTransferBrokerState.RELEASE_PENDING) {
            ExactCpuLedgerResult acknowledgement = ledger.acknowledgeRelease(handle, reservationId);
            if (!(acknowledgement instanceof ExactCpuLedgerResult.ReleaseAcknowledged)) {
                return failClosed();
            }
            clearToIdle();
            return new ExactTransferBrokerResult.Cancelled(snapshot());
        }
        if (!cancelPreparedAfterRollback()) {
            return failClosed();
        }
        clearToIdle();
        return new ExactTransferBrokerResult.RolledBack(snapshot());
    }

    private void bind(ExactCpuLedger requestedLedger, CpuPlanHandle requestedHandle, ExactCraftingPlan plan) {
        ledger = requestedLedger;
        handle = requestedHandle;
        planId = plan.planId();
        reservationId = ReservationId.fresh();
        escrowed.clear();
    }

    private boolean matches(ReleaseObligation obligation) {
        return obligation != null && obligation.handle().equals(handle)
                && obligation.reservationId().equals(reservationId) && obligation.reservedDebits().equals(escrowed);
    }

    /** Captures both authorities together and validates every dependency and debit against that fresh view. */
    private ExactTransferBrokerResult.FailureReason validateCurrent(ExactCraftingPlan plan,
            Map<KeyId, AEAmount> debits) {
        try {
            StorageSnapshot storageSnapshot = Objects.requireNonNull(storage.captureSnapshot(), "storage snapshot");
            NormalizedPatternSnapshot patternSnapshot = Objects.requireNonNull(patternSnapshots.captureCurrent(),
                    "pattern snapshot");
            if (plan.dependencies().validate(storageSnapshot,
                    patternSnapshot) instanceof DependencyValidationResult.Invalid) {
                return ExactTransferBrokerResult.FailureReason.DEPENDENCY_MISMATCH;
            }
            for (Map.Entry<KeyId, AEAmount> debit : debits.entrySet()) {
                if (debit.getKey().value() >= storageSnapshot.keyCount()
                        || storageSnapshot.amount(debit.getKey()).compareTo(debit.getValue()) < 0) {
                    return ExactTransferBrokerResult.FailureReason.STORAGE_SIMULATION_SHORT;
                }
            }
            return null;
        } catch (RuntimeException failure) {
            return ExactTransferBrokerResult.FailureReason.SNAPSHOT_UNAVAILABLE;
        }
    }

    private boolean cancelPreparedAfterRollback() {
        ExactCpuLedgerResult cancellation = ledger.cancelPrepared(handle);
        if (cancellation instanceof ExactCpuLedgerResult.PreparedCancelled) {
            return true;
        }
        ExactCpuLedgerSnapshot ledgerSnapshot = ledger.snapshot();
        return ledgerSnapshot.state() == ExactCpuLedgerState.IDLE && ledgerSnapshot.handle().isEmpty();
    }

    private ExactTransferBrokerResult.Failure beginOperation() {
        if (entered) {
            return failure(ExactTransferBrokerResult.FailureReason.REENTRANT_OPERATION);
        }
        entered = true;
        boolean correctThread;
        try {
            correctThread = serverThread.isServerThread();
        } catch (RuntimeException failure) {
            correctThread = false;
        }
        if (!correctThread) {
            ExactTransferBrokerResult.Failure failure = failure(ExactTransferBrokerResult.FailureReason.WRONG_THREAD);
            entered = false;
            return failure;
        }
        return null;
    }

    private void endOperation() {
        entered = false;
    }

    private ExactTransferBrokerResult failClosed() {
        state = ExactTransferBrokerState.FAIL_CLOSED;
        return failure(ExactTransferBrokerResult.FailureReason.INVARIANT_VIOLATION);
    }

    private ExactTransferBrokerResult.Failure failure(ExactTransferBrokerResult.FailureReason reason) {
        return new ExactTransferBrokerResult.Failure(reason, snapshot());
    }

    private void clearToIdle() {
        ledger = null;
        handle = null;
        planId = null;
        reservationId = null;
        escrowed.clear();
        state = ExactTransferBrokerState.IDLE;
    }

    private static Map<KeyId, AEAmount> sorted(Map<KeyId, AEAmount> amounts) {
        TreeMap<KeyId, AEAmount> copy = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        copy.putAll(amounts);
        return copy;
    }

    /** A conforming BrokerExactStorage can never fail this check; failure is a protocol-level custody violation. */
    private static boolean validMovedAmount(AEAmount moved, AEAmount requested) {
        return moved != null && moved.toBigInteger().bitLength() <= PlannerLimits.MAX_CRAFT_QUANTITY_BITS
                && moved.compareTo(requested) <= 0;
    }
}
