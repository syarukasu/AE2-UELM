package appeng.rebuild.execution;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/**
 * Bounded, concurrency-safe ownership ledger for one exact crafting CPU.
 *
 * <p>
 * All methods are synchronized so a transition and the observation used to authorize it are one linearizable action.
 * This class deliberately has no world, grid, thread, inventory, pattern-execution, or retry API. A future transfer
 * broker performs physical reservation, release, and work-order execution around the immutable values produced here.
 */
public final class ExactCpuLedger {
    private final UUID ledgerIdentity = UUID.randomUUID();

    private ExactCpuLedgerState state = ExactCpuLedgerState.IDLE;
    private long lifecycleRevision;
    private CpuPlanHandle handle;
    private ExactCraftingPlan preparedPlan;
    private ExactReservationReceipt receipt;
    private ReleaseObligation releaseObligation;
    private ReservedPlanLease lease;

    /** Prepares a validated plan, producing an ABA-safe handle without reserving any resource. */
    public synchronized ExactCpuLedgerResult prepare(ExactCraftingPlan plan) {
        if (plan == null) {
            return failure(ExactCpuLedgerResult.FailureReason.MALFORMED_ARGUMENT);
        }
        if (state == ExactCpuLedgerState.FAIL_CLOSED) {
            return failure(ExactCpuLedgerResult.FailureReason.FAIL_CLOSED);
        }
        if (state != ExactCpuLedgerState.IDLE) {
            return failure(ExactCpuLedgerResult.FailureReason.WRONG_STATE);
        }
        if (lifecycleRevision == Long.MAX_VALUE) {
            failClosed();
            return failure(ExactCpuLedgerResult.FailureReason.REVISION_EXHAUSTED);
        }

        lifecycleRevision = Math.incrementExact(lifecycleRevision);
        handle = new CpuPlanHandle(ledgerIdentity, lifecycleRevision);
        preparedPlan = plan;
        state = ExactCpuLedgerState.PREPARED;
        return new ExactCpuLedgerResult.Prepared(handle);
    }

    /** Returns the handle-bound prepared plan only to the same-package transfer broker. */
    synchronized ExactCraftingPlan preparedPlanForBroker(CpuPlanHandle expectedHandle) {
        if (state != ExactCpuLedgerState.PREPARED || expectedHandle == null || !expectedHandle.equals(handle)) {
            return null;
        }
        return preparedPlan;
    }

    /**
     * Same-package broker transition that records an exact reservation receipt. The receipt must describe exactly the
     * prepared plan's initial debit; partial, extra, stale, or mismatched receipts do not change ledger state.
     */
    synchronized ExactCpuLedgerResult confirmReservation(CpuPlanHandle expectedHandle,
            ExactReservationReceipt reservationReceipt) {
        ExactCpuLedgerResult.Failure failure = checkStateAndHandle(ExactCpuLedgerState.PREPARED, expectedHandle);
        if (failure != null) {
            return failure;
        }
        if (reservationReceipt == null) {
            return failure(ExactCpuLedgerResult.FailureReason.MALFORMED_ARGUMENT);
        }
        if (!reservationReceipt.matches(handle, preparedPlan)) {
            return failure(ExactCpuLedgerResult.FailureReason.RECEIPT_MISMATCH);
        }

        receipt = reservationReceipt;
        state = ExactCpuLedgerState.RESERVED;
        return new ExactCpuLedgerResult.ReservationConfirmed(handle, receipt.reservationId());
    }

    /** Cancels an unreserved plan. No resource obligation exists in {@link ExactCpuLedgerState#PREPARED}. */
    public synchronized ExactCpuLedgerResult cancelPrepared(CpuPlanHandle expectedHandle) {
        ExactCpuLedgerResult.Failure failure = checkStateAndHandle(ExactCpuLedgerState.PREPARED, expectedHandle);
        if (failure != null) {
            return failure;
        }
        CpuPlanHandle completedHandle = handle;
        clearToIdle();
        return new ExactCpuLedgerResult.PreparedCancelled(completedHandle);
    }

    /**
     * Transfers release responsibility exactly once to a broker. The returned obligation is now the sole resource owner
     * until the same-package transfer broker acknowledges the release.
     */
    synchronized ExactCpuLedgerResult cancelReserved(CpuPlanHandle expectedHandle) {
        ExactCpuLedgerResult.Failure failure = checkStateAndHandle(ExactCpuLedgerState.RESERVED, expectedHandle);
        if (failure != null) {
            return failure;
        }
        releaseObligation = new ReleaseObligation(handle, receipt.reservationId(), receipt.reservedDebits());
        preparedPlan = null;
        receipt = null;
        state = ExactCpuLedgerState.RELEASE_PENDING;
        return new ExactCpuLedgerResult.ReleaseRequired(releaseObligation);
    }

    /** Same-package broker acknowledgement for one completed release and its exact reservation identity. */
    synchronized ExactCpuLedgerResult acknowledgeRelease(CpuPlanHandle expectedHandle,
            ReservationId expectedReservationId) {
        ExactCpuLedgerResult.Failure failure = checkStateAndHandle(ExactCpuLedgerState.RELEASE_PENDING, expectedHandle);
        if (failure != null) {
            return failure;
        }
        if (expectedReservationId == null) {
            return failure(ExactCpuLedgerResult.FailureReason.MALFORMED_ARGUMENT);
        }
        if (!releaseObligation.reservationId().equals(expectedReservationId)) {
            return failure(ExactCpuLedgerResult.FailureReason.IDENTITY_MISMATCH);
        }
        CpuPlanHandle completedHandle = handle;
        clearToIdle();
        return new ExactCpuLedgerResult.ReleaseAcknowledged(completedHandle);
    }

    /**
     * Transfers the plan and the exact reservation to a unique future work-order lease. The ledger retains no cancel or
     * release authority after this succeeds.
     */
    synchronized ExactCpuLedgerResult handoff(CpuPlanHandle expectedHandle) {
        ExactCpuLedgerResult.Failure failure = checkStateAndHandle(ExactCpuLedgerState.RESERVED, expectedHandle);
        if (failure != null) {
            return failure;
        }
        lease = new ReservedPlanLease(UUID.randomUUID(), handle, receipt.reservationId(), preparedPlan,
                receipt.reservedDebits());
        preparedPlan = null;
        receipt = null;
        state = ExactCpuLedgerState.HANDED_OFF;
        return new ExactCpuLedgerResult.HandedOff(lease);
    }

    /** Same-package work-order acknowledgement for completion or abort; detailed accounting belongs to Phase 7. */
    synchronized ExactCpuLedgerResult acknowledgeHandoff(CpuPlanHandle expectedHandle,
            UUID expectedLeaseIdentity) {
        ExactCpuLedgerResult.Failure failure = checkStateAndHandle(ExactCpuLedgerState.HANDED_OFF, expectedHandle);
        if (failure != null) {
            return failure;
        }
        if (expectedLeaseIdentity == null) {
            return failure(ExactCpuLedgerResult.FailureReason.MALFORMED_ARGUMENT);
        }
        if (!lease.leaseIdentity().equals(expectedLeaseIdentity)) {
            return failure(ExactCpuLedgerResult.FailureReason.IDENTITY_MISMATCH);
        }
        CpuPlanHandle completedHandle = handle;
        clearToIdle();
        return new ExactCpuLedgerResult.HandoffAcknowledged(completedHandle);
    }

    /** Returns an immutable state observation without exposing a prepared plan. */
    public synchronized ExactCpuLedgerSnapshot snapshot() {
        ReservationId reservationId = receipt != null ? receipt.reservationId()
                : releaseObligation != null ? releaseObligation.reservationId()
                        : lease != null ? lease.reservationId() : null;
        Map<KeyId, AEAmount> debits = receipt != null ? receipt.reservedDebits()
                : releaseObligation != null ? releaseObligation.reservedDebits()
                        : lease != null ? lease.reservedDebits() : Map.of();
        return new ExactCpuLedgerSnapshot(state, lifecycleRevision, Optional.ofNullable(handle),
                Optional.ofNullable(reservationId), debits, Optional.ofNullable(releaseObligation),
                Optional.ofNullable(lease).map(ReservedPlanLease::leaseIdentity));
    }

    private ExactCpuLedgerResult.Failure checkStateAndHandle(ExactCpuLedgerState requiredState,
            CpuPlanHandle expectedHandle) {
        if (state == ExactCpuLedgerState.FAIL_CLOSED) {
            return failure(ExactCpuLedgerResult.FailureReason.FAIL_CLOSED);
        }
        if (expectedHandle == null || handle == null || !handle.equals(expectedHandle)) {
            return failure(ExactCpuLedgerResult.FailureReason.STALE_HANDLE);
        }
        if (state != requiredState) {
            return failure(ExactCpuLedgerResult.FailureReason.WRONG_STATE);
        }
        return null;
    }

    private void clearToIdle() {
        handle = null;
        preparedPlan = null;
        receipt = null;
        releaseObligation = null;
        lease = null;
        state = ExactCpuLedgerState.IDLE;
    }

    private void failClosed() {
        handle = null;
        preparedPlan = null;
        receipt = null;
        releaseObligation = null;
        lease = null;
        state = ExactCpuLedgerState.FAIL_CLOSED;
    }

    private static ExactCpuLedgerResult.Failure failure(ExactCpuLedgerResult.FailureReason reason) {
        return new ExactCpuLedgerResult.Failure(Objects.requireNonNull(reason, "reason"));
    }
}
