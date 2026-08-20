package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.CompiledPatternGraph;
import appeng.rebuild.pattern.CompiledPatternGraphBuilder;
import appeng.rebuild.pattern.GraphBuildResult;
import appeng.rebuild.pattern.GraphGeneration;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternKind;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.rebuild.pattern.SubstitutionPolicy;
import appeng.rebuild.planner.ExactCraftPlanDraft;
import appeng.rebuild.planner.ExactCraftPlanResult;
import appeng.rebuild.planner.ExactCraftPlanner;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.planner.GridRevision;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Contract tests for the exact CPU ownership and receipt state machine. */
class ExactCpuLedgerTest {
    private static final long KEY_GENERATION = 7L;
    private static final long SERVER_GENERATION = 9L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(3L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(5L);
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();
    private static final ExactCraftingPlanValidator VALIDATOR = new ExactCraftingPlanValidator();

    @Test
    void idleSnapshotIsEmptyAndPrepareIsNonAuthoritative() {
        ExactCpuLedger ledger = new ExactCpuLedger();
        ExactCraftingPlan plan = validPlan();

        ExactCpuLedgerSnapshot initial = ledger.snapshot();
        assertEquals(ExactCpuLedgerState.IDLE, initial.state());
        assertEquals(0L, initial.lifecycleRevision());
        assertTrue(initial.handle().isEmpty());
        assertTrue(initial.reservationId().isEmpty());
        assertTrue(initial.reservedDebits().isEmpty());
        assertTrue(initial.releaseObligation().isEmpty());
        assertTrue(initial.leaseIdentity().isEmpty());

        ExactCpuLedgerResult.Prepared prepared = assertInstanceOf(ExactCpuLedgerResult.Prepared.class,
                ledger.prepare(plan));
        ExactCpuLedgerSnapshot afterPrepare = ledger.snapshot();
        assertEquals(ExactCpuLedgerState.PREPARED, afterPrepare.state());
        assertEquals(1L, afterPrepare.lifecycleRevision());
        assertEquals(prepared.handle(), afterPrepare.handle().orElseThrow());
        assertTrue(afterPrepare.reservationId().isEmpty());
        assertTrue(afterPrepare.reservedDebits().isEmpty());
        assertTrue(afterPrepare.releaseObligation().isEmpty());
        assertTrue(afterPrepare.leaseIdentity().isEmpty());
    }

    @Test
    void prepareNullAndSecondPrepareAreTypedWithoutMutation() {
        ExactCpuLedger ledger = new ExactCpuLedger();
        assertFailure(ledger.prepare(null), ExactCpuLedgerResult.FailureReason.MALFORMED_ARGUMENT);

        ExactCraftingPlan plan = validPlan();
        ExactCpuLedgerResult.Prepared prepared = assertInstanceOf(ExactCpuLedgerResult.Prepared.class,
                ledger.prepare(plan));
        ExactCpuLedgerSnapshot before = ledger.snapshot();

        assertFailure(ledger.prepare(plan), ExactCpuLedgerResult.FailureReason.WRONG_STATE);
        assertEquals(before, ledger.snapshot());
        assertEquals(prepared.handle(), before.handle().orElseThrow());
    }

    @Test
    void cancelPreparedIsExactAndOldHandleCannotCrossAnAbAReuse() {
        ExactCpuLedger ledger = new ExactCpuLedger();
        ExactCraftingPlan plan = validPlan();
        ExactCpuLedgerResult.Prepared first = assertInstanceOf(ExactCpuLedgerResult.Prepared.class,
                ledger.prepare(plan));

        ExactCpuLedgerResult.PreparedCancelled cancelled = assertInstanceOf(
                ExactCpuLedgerResult.PreparedCancelled.class,
                ledger.cancelPrepared(first.handle()));
        assertEquals(first.handle(), cancelled.handle());
        assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());

        ExactCpuLedgerResult.Prepared second = assertInstanceOf(ExactCpuLedgerResult.Prepared.class,
                ledger.prepare(plan));
        assertTrue(second.handle().revision() > first.handle().revision());
        assertNotEquals(first.handle(), second.handle());
        ExactCpuLedgerSnapshot before = ledger.snapshot();
        assertFailure(ledger.cancelPrepared(first.handle()), ExactCpuLedgerResult.FailureReason.STALE_HANDLE);
        assertEquals(before, ledger.snapshot());
    }

    @Test
    void exactReceiptConfirmsAndPartialExtraWrongDebitRevisionRequestAndHandleAreRejected() {
        ExactCraftingPlan plan = validPlan();
        ExactCraftingPlan sameProjectionDifferentPlan = validPlan();
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, plan);

        Map<KeyId, AEAmount> expected = plan.initialStorageDebits();
        assertEquals(Map.of(new KeyId(0), AEAmount.ONE, new KeyId(1), AEAmount.ONE), expected);

        List<ExactReservationReceipt> invalid = List.of(
                receipt(handle, sameProjectionDifferentPlan, expected),
                receipt(handle, plan, Map.of(new KeyId(0), AEAmount.ONE)),
                receipt(handle, plan, Map.of(new KeyId(0), AEAmount.ONE, new KeyId(1), AEAmount.ONE,
                        new KeyId(3), AEAmount.ONE)),
                receipt(handle, plan, Map.of(new KeyId(0), AEAmount.of(2L), new KeyId(1), AEAmount.ONE)),
                receipt(handle, plan.planId(), new GridRevision(99L, plan.planningRevision().keyRegistryGeneration(),
                        plan.planningRevision().graphGeneration(), plan.planningRevision().recipeRevision(),
                        plan.planningRevision().storageRevision()), plan.validationRevision(), plan.request(),
                        expected),
                receipt(handle, plan.planId(), plan.planningRevision(), new GridRevision(
                        plan.validationRevision().serverGeneration(), 88L, plan.validationRevision().graphGeneration(),
                        plan.validationRevision().recipeRevision(), plan.validationRevision().storageRevision()),
                        plan.request(), expected),
                receipt(handle, plan.planId(), plan.planningRevision(), plan.validationRevision(),
                        new ExactCraftRequest(new KeyId(3), AEAmount.ONE), expected),
                receipt(new CpuPlanHandle(UUID.randomUUID(), handle.revision()), plan, expected));

        for (ExactReservationReceipt receipt : invalid) {
            ExactCpuLedgerSnapshot before = ledger.snapshot();
            assertFailure(ledger.confirmReservation(handle, receipt),
                    ExactCpuLedgerResult.FailureReason.RECEIPT_MISMATCH);
            assertEquals(before, ledger.snapshot());
        }

        ReservationId reservationId = new ReservationId(UUID.randomUUID());
        ExactReservationReceipt exact = ExactReservationReceipt.forReservedPlan(handle, reservationId, plan);
        ExactCpuLedgerResult.ReservationConfirmed confirmed = assertInstanceOf(
                ExactCpuLedgerResult.ReservationConfirmed.class, ledger.confirmReservation(handle, exact));
        assertEquals(reservationId, confirmed.reservationId());
        ExactCpuLedgerSnapshot reserved = ledger.snapshot();
        assertEquals(ExactCpuLedgerState.RESERVED, reserved.state());
        assertEquals(Optional.of(reservationId), reserved.reservationId());
        assertEquals(expected, reserved.reservedDebits());
    }

    @Test
    void staleHandleFromOtherLedgerIsRejectedWithoutStateChange() {
        ExactCraftingPlan plan = validPlan();
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, plan);
        ExactCpuLedger other = new ExactCpuLedger();
        CpuPlanHandle otherHandle = prepare(other, plan);

        ExactCpuLedgerSnapshot before = ledger.snapshot();
        assertFailure(ledger.cancelPrepared(otherHandle), ExactCpuLedgerResult.FailureReason.STALE_HANDLE);
        assertEquals(before, ledger.snapshot());
    }

    @Test
    void reservedCancelCreatesOneImmutableReleaseObligationAndOnlyCorrectAckReturnsIdle() {
        ExactCraftingPlan plan = validPlan();
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, plan);
        ReservationId reservationId = new ReservationId(UUID.randomUUID());
        confirm(ledger, handle, plan, reservationId);

        ExactCpuLedgerResult.ReleaseRequired required = assertInstanceOf(ExactCpuLedgerResult.ReleaseRequired.class,
                ledger.cancelReserved(handle));
        ReleaseObligation obligation = required.obligation();
        assertEquals(handle, obligation.handle());
        assertEquals(reservationId, obligation.reservationId());
        assertEquals(plan.initialStorageDebits(), obligation.reservedDebits());
        assertThrows(UnsupportedOperationException.class, () -> obligation.reservedDebits().clear());
        assertEquals(ExactCpuLedgerState.RELEASE_PENDING, ledger.snapshot().state());

        ExactCpuLedgerSnapshot beforeDuplicate = ledger.snapshot();
        assertFailure(ledger.cancelReserved(handle), ExactCpuLedgerResult.FailureReason.WRONG_STATE);
        assertFailure(ledger.handoff(handle), ExactCpuLedgerResult.FailureReason.WRONG_STATE);
        assertEquals(beforeDuplicate, ledger.snapshot());

        assertFailure(ledger.acknowledgeRelease(new CpuPlanHandle(UUID.randomUUID(), handle.revision()), reservationId),
                ExactCpuLedgerResult.FailureReason.STALE_HANDLE);
        assertEquals(beforeDuplicate, ledger.snapshot());
        assertFailure(ledger.acknowledgeRelease(handle, new ReservationId(UUID.randomUUID())),
                ExactCpuLedgerResult.FailureReason.IDENTITY_MISMATCH);
        assertEquals(beforeDuplicate, ledger.snapshot());

        assertInstanceOf(ExactCpuLedgerResult.ReleaseAcknowledged.class,
                ledger.acknowledgeRelease(handle, reservationId));
        assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());
        assertFailure(ledger.acknowledgeRelease(handle, reservationId),
                ExactCpuLedgerResult.FailureReason.STALE_HANDLE);
    }

    @Test
    void handoffTransfersExactPlanAndDebitsWithSoleOwnershipAndSupportsCompletionAndAbortAck() {
        ExactCraftingPlan plan = validPlan();
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, plan);
        ReservationId reservationId = new ReservationId(UUID.randomUUID());
        confirm(ledger, handle, plan, reservationId);

        ExactCpuLedgerResult.HandedOff handedOff = assertInstanceOf(ExactCpuLedgerResult.HandedOff.class,
                ledger.handoff(handle));
        ReservedPlanLease lease = handedOff.lease();
        assertEquals(handle, lease.handle());
        assertEquals(reservationId, lease.reservationId());
        assertEquals(plan, lease.plan());
        assertEquals(plan.initialStorageDebits(), lease.reservedDebits());
        assertThrows(UnsupportedOperationException.class, () -> lease.reservedDebits().clear());
        ExactCpuLedgerSnapshot handedSnapshot = ledger.snapshot();
        assertEquals(ExactCpuLedgerState.HANDED_OFF, handedSnapshot.state());
        assertEquals(Optional.of(lease.leaseIdentity()), handedSnapshot.leaseIdentity());
        assertEquals(Optional.of(reservationId), handedSnapshot.reservationId());
        assertEquals(plan.initialStorageDebits(), handedSnapshot.reservedDebits());
        assertTrue(handedSnapshot.releaseObligation().isEmpty());

        assertFailure(ledger.cancelReserved(handle), ExactCpuLedgerResult.FailureReason.WRONG_STATE);
        assertFailure(ledger.acknowledgeRelease(handle, reservationId), ExactCpuLedgerResult.FailureReason.WRONG_STATE);
        assertFailure(ledger.handoff(handle), ExactCpuLedgerResult.FailureReason.WRONG_STATE);
        ExactCpuLedgerSnapshot beforeWrongAck = ledger.snapshot();
        assertFailure(ledger.acknowledgeHandoff(new CpuPlanHandle(UUID.randomUUID(), handle.revision()),
                lease.leaseIdentity()), ExactCpuLedgerResult.FailureReason.STALE_HANDLE);
        assertFailure(ledger.acknowledgeHandoff(handle, UUID.randomUUID()),
                ExactCpuLedgerResult.FailureReason.IDENTITY_MISMATCH);
        assertEquals(beforeWrongAck, ledger.snapshot());

        assertInstanceOf(ExactCpuLedgerResult.HandoffAcknowledged.class,
                ledger.acknowledgeHandoff(handle, lease.leaseIdentity()));
        assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());
        assertFailure(ledger.acknowledgeHandoff(handle, lease.leaseIdentity()),
                ExactCpuLedgerResult.FailureReason.STALE_HANDLE);

        ExactCpuLedger abortLedger = new ExactCpuLedger();
        CpuPlanHandle abortHandle = prepare(abortLedger, plan);
        ReservationId abortReservation = new ReservationId(UUID.randomUUID());
        confirm(abortLedger, abortHandle, plan, abortReservation);
        ReservedPlanLease abortLease = assertInstanceOf(ExactCpuLedgerResult.HandedOff.class,
                abortLedger.handoff(abortHandle)).lease();
        assertInstanceOf(ExactCpuLedgerResult.HandoffAcknowledged.class,
                abortLedger.acknowledgeHandoff(abortHandle, abortLease.leaseIdentity()));
        assertEquals(ExactCpuLedgerState.IDLE, abortLedger.snapshot().state());
    }

    @Test
    void emptyDebitPlanCanBeReservedAndHandedOffWithoutFabricatedQuantity() {
        ExactCraftingPlan plan = emptyDebitPlan();
        assertTrue(plan.initialStorageDebits().isEmpty());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, plan);
        ReservationId reservationId = new ReservationId(UUID.randomUUID());
        ExactReservationReceipt receipt = ExactReservationReceipt.forReservedPlan(handle, reservationId, plan);
        assertTrue(receipt.reservedDebits().isEmpty());
        assertInstanceOf(ExactCpuLedgerResult.ReservationConfirmed.class,
                ledger.confirmReservation(handle, receipt));
        assertTrue(ledger.snapshot().reservedDebits().isEmpty());
        ReservedPlanLease lease = assertInstanceOf(ExactCpuLedgerResult.HandedOff.class, ledger.handoff(handle))
                .lease();
        assertTrue(lease.reservedDebits().isEmpty());
        assertInstanceOf(ExactCpuLedgerResult.HandoffAcknowledged.class,
                ledger.acknowledgeHandoff(handle, lease.leaseIdentity()));
        assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());
    }

    @Test
    void receiptBoundaryRejectsZeroOverBitAndOversizedDebitMapsButAcceptsMaximumBitLength() {
        ExactCraftingPlan plan = validPlan();
        CpuPlanHandle handle = new CpuPlanHandle(UUID.randomUUID(), 1L);

        assertThrows(IllegalArgumentException.class, () -> new ExactReservationReceipt(handle,
                ReservationId.fresh(), plan.planId(), plan.planningRevision(), plan.validationRevision(),
                plan.request(),
                Map.of(new KeyId(0), AEAmount.ZERO)));

        AEAmount maximumBitLength = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS - 1));
        ExactReservationReceipt maximum = new ExactReservationReceipt(handle, ReservationId.fresh(),
                plan.planId(), plan.planningRevision(), plan.validationRevision(), plan.request(),
                Map.of(new KeyId(0), maximumBitLength));
        assertEquals(maximumBitLength, maximum.reservedDebits().get(new KeyId(0)));

        AEAmount overBitLength = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS));
        assertThrows(IllegalArgumentException.class, () -> new ExactReservationReceipt(handle,
                ReservationId.fresh(), plan.planId(), plan.planningRevision(), plan.validationRevision(),
                plan.request(),
                Map.of(new KeyId(0), overBitLength)));

        Map<KeyId, AEAmount> oversized = new AbstractMap<>() {
            @Override
            public Set<Entry<KeyId, AEAmount>> entrySet() {
                return Set.of();
            }

            @Override
            public int size() {
                return PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS + 1;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new ExactReservationReceipt(handle,
                ReservationId.fresh(), plan.planId(), plan.planningRevision(), plan.validationRevision(),
                plan.request(),
                oversized));
    }

    @Test
    void receiptAndSnapshotInputsAreCopiedAndAllExposedMapsAreImmutable() {
        ExactCraftingPlan plan = validPlan();
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, plan);
        Map<KeyId, AEAmount> mutable = new HashMap<>(plan.initialStorageDebits());
        ExactReservationReceipt receipt = new ExactReservationReceipt(handle, ReservationId.fresh(),
                plan.planId(), plan.planningRevision(), plan.validationRevision(), plan.request(), mutable);
        mutable.clear();
        assertEquals(plan.initialStorageDebits(), receipt.reservedDebits());
        assertThrows(UnsupportedOperationException.class, () -> receipt.reservedDebits().clear());

        assertInstanceOf(ExactCpuLedgerResult.ReservationConfirmed.class, ledger.confirmReservation(handle, receipt));
        ExactCpuLedgerSnapshot snapshot = ledger.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.reservedDebits().clear());
        assertInstanceOf(ExactCpuLedgerResult.ReleaseRequired.class, ledger.cancelReserved(handle));
        ReleaseObligation obligation = ledger.snapshot().releaseObligation().orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> obligation.reservedDebits().clear());
    }

    @Test
    void lifecycleRevisionIsMonotonicAndLongMaxFailsClosedWithoutAba() {
        ExactCraftingPlan plan = validPlan();
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle first = prepare(ledger, plan);
        assertInstanceOf(ExactCpuLedgerResult.PreparedCancelled.class, ledger.cancelPrepared(first));
        CpuPlanHandle second = prepare(ledger, plan);
        assertTrue(second.revision() > first.revision());
        assertInstanceOf(ExactCpuLedgerResult.PreparedCancelled.class, ledger.cancelPrepared(second));
        assertEquals(2L, ledger.snapshot().lifecycleRevision());

        try {
            var field = ExactCpuLedger.class.getDeclaredField("lifecycleRevision");
            field.setAccessible(true);
            field.setLong(ledger, Long.MAX_VALUE);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("The bounded lifecycle test requires the existing private revision field",
                    exception);
        }
        assertFailure(ledger.prepare(plan), ExactCpuLedgerResult.FailureReason.REVISION_EXHAUSTED);
        assertEquals(ExactCpuLedgerState.FAIL_CLOSED, ledger.snapshot().state());
        assertFailure(ledger.prepare(plan), ExactCpuLedgerResult.FailureReason.FAIL_CLOSED);
    }

    @Test
    void concurrentCancelAndHandoffHaveExactlyOneLinearizableWinnerRepeatedly() throws Exception {
        ExactCraftingPlan plan = validPlan();
        for (int iteration = 0; iteration < 32; iteration++) {
            ExactCpuLedger ledger = new ExactCpuLedger();
            CpuPlanHandle handle = prepare(ledger, plan);
            ReservationId reservationId = new ReservationId(UUID.randomUUID());
            confirm(ledger, handle, plan, reservationId);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<ExactCpuLedgerResult> cancel = pool.submit(race(start, () -> ledger.cancelReserved(handle)));
                Future<ExactCpuLedgerResult> handoff = pool.submit(race(start, () -> ledger.handoff(handle)));
                start.countDown();
                ExactCpuLedgerResult cancelResult = cancel.get(10, TimeUnit.SECONDS);
                ExactCpuLedgerResult handoffResult = handoff.get(10, TimeUnit.SECONDS);
                boolean cancelWon = cancelResult instanceof ExactCpuLedgerResult.ReleaseRequired;
                boolean handoffWon = handoffResult instanceof ExactCpuLedgerResult.HandedOff;
                assertTrue(cancelWon ^ handoffWon, "exactly one transition must win");
                if (cancelWon) {
                    assertFailure(handoffResult, ExactCpuLedgerResult.FailureReason.WRONG_STATE);
                    ReleaseObligation obligation = ((ExactCpuLedgerResult.ReleaseRequired) cancelResult).obligation();
                    assertInstanceOf(ExactCpuLedgerResult.ReleaseAcknowledged.class,
                            ledger.acknowledgeRelease(handle, obligation.reservationId()));
                } else {
                    assertFailure(cancelResult, ExactCpuLedgerResult.FailureReason.WRONG_STATE);
                    ReservedPlanLease lease = ((ExactCpuLedgerResult.HandedOff) handoffResult).lease();
                    assertInstanceOf(ExactCpuLedgerResult.HandoffAcknowledged.class,
                            ledger.acknowledgeHandoff(handle, lease.leaseIdentity()));
                }
                assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private static Callable<ExactCpuLedgerResult> race(CountDownLatch start, Callable<ExactCpuLedgerResult> action) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            return action.call();
        };
    }

    private static void assertFailure(ExactCpuLedgerResult result, ExactCpuLedgerResult.FailureReason reason) {
        assertEquals(reason, assertInstanceOf(ExactCpuLedgerResult.Failure.class, result).reason());
    }

    private static CpuPlanHandle prepare(ExactCpuLedger ledger, ExactCraftingPlan plan) {
        return assertInstanceOf(ExactCpuLedgerResult.Prepared.class, ledger.prepare(plan)).handle();
    }

    private static void confirm(ExactCpuLedger ledger, CpuPlanHandle handle, ExactCraftingPlan plan,
            ReservationId reservationId) {
        assertInstanceOf(ExactCpuLedgerResult.ReservationConfirmed.class,
                ledger.confirmReservation(handle,
                        ExactReservationReceipt.forReservedPlan(handle, reservationId, plan)));
    }

    private static ExactReservationReceipt receipt(CpuPlanHandle handle, ExactCraftingPlan plan,
            Map<KeyId, AEAmount> debits) {
        return new ExactReservationReceipt(handle, ReservationId.fresh(), plan.planId(), plan.planningRevision(),
                plan.validationRevision(), plan.request(), debits);
    }

    private static ExactReservationReceipt receipt(CpuPlanHandle handle, ExactPlanId planId,
            GridRevision planningRevision, GridRevision validationRevision, ExactCraftRequest request,
            Map<KeyId, AEAmount> debits) {
        return new ExactReservationReceipt(handle, ReservationId.fresh(), planId, planningRevision, validationRevision,
                request, debits);
    }

    private static ExactCraftingPlan validPlan() {
        CompiledPattern pattern = pattern("cpu-ledger-normal", List.of(
                input(0), input(1)), List.of(output(2, 1L)));
        StorageSnapshot storage = snapshot(3, Map.of(0, AEAmount.of(3L), 1, AEAmount.of(4L)));
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(pattern), Map.of(pattern.id(), 0));
        ExactCraftRequest request = new ExactCraftRequest(new KeyId(2), AEAmount.ONE);
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(request, storage, patterns)).draft();
        return assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, storage, patterns)).plan();
    }

    private static ExactCraftingPlan emptyDebitPlan() {
        CompiledPattern pattern = pattern("cpu-ledger-empty", List.of(), List.of(output(2, 1L)));
        StorageSnapshot storage = snapshot(3, Map.of());
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(pattern), Map.of(pattern.id(), 0));
        ExactCraftRequest request = new ExactCraftRequest(new KeyId(2), AEAmount.ONE);
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(request, storage, patterns)).draft();
        return assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, storage, patterns)).plan();
    }

    private static CompiledPattern pattern(String id, List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs) {
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs, outputs, Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
    }

    private static CompiledInputSpec input(int key) {
        return new CompiledInputSpec(List.of(new CompiledCandidateSpec(new KeyId(key), AEAmount.ONE, Optional.empty())),
                AEAmount.ONE, SubstitutionPolicy.EXACT);
    }

    private static CompiledOutputSpec output(int key, long amount) {
        return new CompiledOutputSpec(new KeyId(key), AEAmount.of(amount), true);
    }

    private static NormalizedPatternSnapshot patternSnapshot(List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities) {
        CompiledPatternGraph graph = ((GraphBuildResult.Success) new CompiledPatternGraphBuilder(GRAPH_GENERATION,
                KEY_GENERATION).build(patterns)).graph();
        return new NormalizedPatternSnapshot(SERVER_GENERATION, RECIPE_REVISION, KEY_GENERATION, graph.patternsById(),
                graph, priorities, patterns.size(), false, List.of());
    }

    private static StorageSnapshot snapshot(int keyCount, Map<Integer, AEAmount> values) {
        AmountVector amounts = new AmountVector(keyCount);
        long[] revisions = new long[keyCount];
        for (Map.Entry<Integer, AEAmount> value : values.entrySet()) {
            amounts.set(value.getKey(), value.getValue());
        }
        return new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, keyCount, amounts, revisions);
    }
}
