package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
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
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.rebuild.storage.ExactStorageVisitor;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Exact reservation, custody, rollback, and release tests for the Phase 7 transfer broker. */
class ExactTransferBrokerTest {
    private static final long KEY_GENERATION = 41L;
    private static final long SERVER_GENERATION = 43L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(47L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(53L);
    private static final IActionSource SOURCE = IActionSource.empty();
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();
    private static final ExactCraftingPlanValidator VALIDATOR = new ExactCraftingPlanValidator();

    @Test
    void reservesEveryExactDebitIncludingAmountBeyondLong() {
        AEAmount huge = AEAmount.of(BigInteger.ONE.shiftLeft(128));
        PlanFixture fixture = plan("broker-huge", Map.of(0, AEAmount.ONE, 1, huge));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        ExactTransferBrokerResult.Reserved result = assertInstanceOf(ExactTransferBrokerResult.Reserved.class,
                broker.reserve(ledger, handle));

        assertEquals(ExactTransferBrokerState.RESERVED, result.snapshot().state());
        assertEquals(fixture.plan().initialStorageDebits(), result.snapshot().escrowed());
        assertEquals(fixture.plan().planId(), result.snapshot().planId().orElseThrow());
        assertEquals(ExactCpuLedgerState.RESERVED, ledger.snapshot().state());
        assertEquals(AEAmount.ZERO, storage.physical(new KeyId(0)));
        assertEquals(AEAmount.ZERO, storage.physical(new KeyId(1)));
        assertEquals(2, storage.captureCalls);
        assertEquals(2, storage.simulateExtractCalls);
        assertEquals(2, storage.modulateExtractCalls);
    }

    @Test
    void emptyReservationUsesNoTransferCallbackAndCanBeCancelled() {
        PlanFixture fixture = plan("broker-empty", Map.of());
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), Map.of());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        assertTrue(broker.snapshot().escrowed().isEmpty());
        assertEquals(0, storage.simulateExtractCalls);
        assertEquals(0, storage.modulateExtractCalls);

        assertInstanceOf(ExactTransferBrokerResult.Cancelled.class, broker.cancelReservation(ledger, handle));
        assertEquals(0, storage.modulateInsertCalls);
        assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());
        assertEquals(ExactTransferBrokerState.IDLE, broker.snapshot().state());
    }

    @Test
    void dependencyMismatchIsRejectedBeforeAnyStorageTransfer() {
        PlanFixture fixture = plan("broker-dependency", Map.of(0, AEAmount.of(9L)));
        StorageSnapshot mismatched = snapshot(fixture.storage().keyCount(), Map.of(0, AEAmount.of(9L)),
                new long[] { 1L, 0L });
        ScriptedStorage storage = new ScriptedStorage(mismatched, fixture.plan().initialStorageDebits());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        assertFailure(broker.reserve(ledger, handle), ExactTransferBrokerResult.FailureReason.DEPENDENCY_MISMATCH);

        assertEquals(0, storage.simulateExtractCalls);
        assertEquals(0, storage.modulateExtractCalls);
        assertEquals(AEAmount.of(9L), storage.physical(new KeyId(0)));
        assertEquals(ExactCpuLedgerState.PREPARED, ledger.snapshot().state());
        assertEquals(ExactTransferBrokerState.IDLE, broker.snapshot().state());
    }

    @Test
    void snapshotFailureIsRejectedBeforeDependencyOrMutation() {
        PlanFixture fixture = plan("broker-snapshot", Map.of(0, AEAmount.of(6L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        storage.captureFailure = new IllegalStateException("unavailable");
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        assertFailure(broker.reserve(ledger, handle), ExactTransferBrokerResult.FailureReason.SNAPSHOT_UNAVAILABLE);

        assertEquals(1, storage.captureCalls);
        assertEquals(0, storage.simulateExtractCalls);
        assertEquals(0, storage.modulateExtractCalls);
        assertEquals(AEAmount.of(6L), storage.physical(new KeyId(0)));
        assertEquals(ExactCpuLedgerState.PREPARED, ledger.snapshot().state());
    }

    @Test
    void dependencyChangeAfterSimulationIsRejectedBeforeModulation() {
        PlanFixture fixture = plan("broker-second-gate", Map.of(0, AEAmount.of(6L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        storage.captureSnapshots.add(fixture.storage());
        storage.captureSnapshots.add(snapshot(fixture.storage().keyCount(), Map.of(0, AEAmount.of(6L)),
                new long[] { 1L, 0L }));
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        assertFailure(broker.reserve(ledger, handle), ExactTransferBrokerResult.FailureReason.DEPENDENCY_MISMATCH);

        assertEquals(2, storage.captureCalls);
        assertEquals(1, storage.simulateExtractCalls);
        assertEquals(0, storage.modulateExtractCalls);
        assertEquals(AEAmount.of(6L), storage.physical(new KeyId(0)));
        assertEquals(ExactCpuLedgerState.PREPARED, ledger.snapshot().state());
        assertEquals(ExactTransferBrokerState.IDLE, broker.snapshot().state());
    }

    @Test
    void simulatedShortfallLeavesPreparedPlanAndPhysicalStorageUntouched() {
        PlanFixture fixture = plan("broker-sim-short", Map.of(0, AEAmount.of(7L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        storage.simulatedExtract = AEAmount.of(6L);
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        assertFailure(broker.reserve(ledger, handle),
                ExactTransferBrokerResult.FailureReason.STORAGE_SIMULATION_SHORT);

        assertEquals(1, storage.simulateExtractCalls);
        assertEquals(0, storage.modulateExtractCalls);
        assertEquals(AEAmount.of(7L), storage.physical(new KeyId(0)));
        assertEquals(ExactCpuLedgerState.PREPARED, ledger.snapshot().state());
        assertEquals(ExactTransferBrokerState.IDLE, broker.snapshot().state());
    }

    @Test
    void partialModulationRollsBackEveryExactDebitAndCancelsPreparedPlan() {
        PlanFixture fixture = plan("broker-partial", Map.of(0, AEAmount.of(5L), 1, AEAmount.of(7L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        storage.extractSteps.add(TransferStep.exact());
        storage.extractSteps.add(TransferStep.returning(AEAmount.of(2L)));
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        assertFailure(broker.reserve(ledger, handle),
                ExactTransferBrokerResult.FailureReason.STORAGE_EXTRACTION_SHORT);

        assertEquals(AEAmount.of(5L), storage.physical(new KeyId(0)));
        assertEquals(AEAmount.of(7L), storage.physical(new KeyId(1)));
        assertEquals(2, storage.modulateInsertCalls);
        assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());
        assertEquals(ExactTransferBrokerState.IDLE, broker.snapshot().state());
    }

    @Test
    void modulationExceptionRollsBackPriorCustodyAndCancelsPreparedPlan() {
        PlanFixture fixture = plan("broker-exception", Map.of(0, AEAmount.of(4L), 1, AEAmount.of(8L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        storage.extractSteps.add(TransferStep.exact());
        storage.extractSteps.add(TransferStep.throwing());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        assertFailure(broker.reserve(ledger, handle), ExactTransferBrokerResult.FailureReason.STORAGE_FAILURE);

        assertEquals(AEAmount.of(4L), storage.physical(new KeyId(0)));
        assertEquals(AEAmount.of(8L), storage.physical(new KeyId(1)));
        assertEquals(1, storage.modulateInsertCalls);
        assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());
        assertEquals(ExactTransferBrokerState.IDLE, broker.snapshot().state());
    }

    @Test
    void rollbackPartialAndThrowRemainPendingUntilBoundedExplicitProgress() {
        PlanFixture fixture = plan("broker-rollback-progress", Map.of(0, AEAmount.of(5L), 1, AEAmount.of(7L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        storage.extractSteps.add(TransferStep.exact());
        storage.extractSteps.add(TransferStep.returning(AEAmount.ONE));
        storage.insertSteps.add(TransferStep.returning(AEAmount.ONE));
        storage.insertSteps.add(TransferStep.throwing());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        assertInstanceOf(ExactTransferBrokerResult.ReleasePending.class, broker.reserve(ledger, handle));
        assertEquals(ExactTransferBrokerState.ROLLBACK_PENDING, broker.snapshot().state());
        assertEquals(Map.of(new KeyId(0), AEAmount.of(4L), new KeyId(1), AEAmount.ONE),
                broker.snapshot().escrowed());
        assertEquals(ExactCpuLedgerState.PREPARED, ledger.snapshot().state());

        int insertCalls = storage.modulateInsertCalls;
        assertFailure(broker.progressRelease(0),
                ExactTransferBrokerResult.FailureReason.INVALID_OPERATION_BUDGET);
        assertFailure(broker.progressRelease(-1),
                ExactTransferBrokerResult.FailureReason.INVALID_OPERATION_BUDGET);
        assertFailure(broker.progressRelease(PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS + 1),
                ExactTransferBrokerResult.FailureReason.INVALID_OPERATION_BUDGET);
        assertEquals(insertCalls, storage.modulateInsertCalls);

        assertInstanceOf(ExactTransferBrokerResult.ReleasePending.class, broker.progressRelease(1));
        assertEquals(insertCalls + 1, storage.modulateInsertCalls);
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), broker.snapshot().escrowed());
        assertInstanceOf(ExactTransferBrokerResult.RolledBack.class, broker.progressRelease(1));
        assertEquals(insertCalls + 2, storage.modulateInsertCalls);
        assertEquals(AEAmount.of(5L), storage.physical(new KeyId(0)));
        assertEquals(AEAmount.of(7L), storage.physical(new KeyId(1)));
        assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());
        assertEquals(ExactTransferBrokerState.IDLE, broker.snapshot().state());
    }

    @Test
    void cpuConfirmationRaceRollsBackAndAcceptsAlreadyCancelledPreparedState() {
        PlanFixture fixture = plan("broker-confirm-race", Map.of(0, AEAmount.of(3L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        storage.afterModulatedExtract = () -> ledger.cancelPrepared(handle);
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        assertFailure(broker.reserve(ledger, handle), ExactTransferBrokerResult.FailureReason.CPU_REJECTED);

        assertEquals(AEAmount.of(3L), storage.physical(new KeyId(0)));
        assertEquals(1, storage.modulateInsertCalls);
        assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());
        assertEquals(ExactTransferBrokerState.IDLE, broker.snapshot().state());
    }

    @Test
    void fullReservedCancellationReleasesBeforeCpuAcknowledgement() {
        PlanFixture fixture = plan("broker-cancel", Map.of(0, AEAmount.of(12L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));

        assertInstanceOf(ExactTransferBrokerResult.Cancelled.class, broker.cancelReservation(ledger, handle));

        assertEquals(AEAmount.of(12L), storage.physical(new KeyId(0)));
        assertEquals(1, storage.modulateInsertCalls);
        assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());
        assertEquals(ExactTransferBrokerState.IDLE, broker.snapshot().state());
    }

    @Test
    void partialReservedReleaseKeepsCpuAuthorityUntilEscrowIsEmpty() {
        AEAmount huge = AEAmount.of(BigInteger.ONE.shiftLeft(128));
        PlanFixture fixture = plan("broker-cancel-partial", Map.of(0, huge));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        storage.insertSteps.add(TransferStep.returning(AEAmount.ONE));

        ExactTransferBrokerSnapshot captured = broker.snapshot();
        assertInstanceOf(ExactTransferBrokerResult.ReleasePending.class, broker.cancelReservation(ledger, handle));
        assertEquals(ExactTransferBrokerState.RELEASE_PENDING, broker.snapshot().state());
        assertEquals(ExactCpuLedgerState.RELEASE_PENDING, ledger.snapshot().state());
        assertEquals(huge.subtractExact(AEAmount.ONE), broker.snapshot().escrowed().get(new KeyId(0)));
        assertEquals(huge, captured.escrowed().get(new KeyId(0)));
        assertThrows(UnsupportedOperationException.class, () -> captured.escrowed().clear());

        assertInstanceOf(ExactTransferBrokerResult.Cancelled.class, broker.progressRelease(1));
        assertEquals(huge, storage.physical(new KeyId(0)));
        assertEquals(ExactCpuLedgerState.IDLE, ledger.snapshot().state());
        assertEquals(ExactTransferBrokerState.IDLE, broker.snapshot().state());
    }

    @Test
    void staleHandlesForeignLedgersAndWrongCancellationIdentityDoNotMutate() {
        PlanFixture fixture = plan("broker-identities", Map.of(0, AEAmount.of(2L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactCpuLedger ledger = new ExactCpuLedger();
        ExactCpuLedger foreign = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        CpuPlanHandle foreignHandle = prepare(foreign, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        assertFailure(broker.reserve(foreign, handle), ExactTransferBrokerResult.FailureReason.STALE_HANDLE);
        assertFailure(broker.reserve(ledger, foreignHandle), ExactTransferBrokerResult.FailureReason.STALE_HANDLE);
        assertEquals(0, storage.captureCalls);
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactTransferBrokerSnapshot reserved = broker.snapshot();

        assertFailure(broker.cancelReservation(foreign, handle),
                ExactTransferBrokerResult.FailureReason.IDENTITY_MISMATCH);
        assertFailure(broker.cancelReservation(ledger, foreignHandle),
                ExactTransferBrokerResult.FailureReason.IDENTITY_MISMATCH);
        assertSnapshotEquals(reserved, broker.snapshot());
        assertEquals(ExactCpuLedgerState.RESERVED, ledger.snapshot().state());
    }

    @Test
    void wrongThreadAndGateFailureAreRejectedBeforeStorageAccess() {
        PlanFixture fixture = plan("broker-thread", Map.of(0, AEAmount.ONE));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());

        ExactTransferBroker wrongThread = new ExactTransferBroker(storage, () -> fixture.patterns(), () -> false,
                SOURCE);
        assertFailure(wrongThread.reserve(ledger, handle), ExactTransferBrokerResult.FailureReason.WRONG_THREAD);
        ExactTransferBroker failingGate = new ExactTransferBroker(storage, () -> fixture.patterns(),
                () -> {
                    throw new IllegalStateException("gate unavailable");
                }, SOURCE);
        assertFailure(failingGate.reserve(ledger, handle), ExactTransferBrokerResult.FailureReason.WRONG_THREAD);

        assertEquals(0, storage.captureCalls);
        assertEquals(AEAmount.ONE, storage.physical(new KeyId(0)));
        assertEquals(ExactCpuLedgerState.PREPARED, ledger.snapshot().state());
    }

    @Test
    void reentrantOperationIsRejectedWithoutDisruptingOuterReservation() {
        PlanFixture fixture = plan("broker-reentry", Map.of(0, AEAmount.of(5L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());
        AtomicReference<ExactTransferBrokerResult> nested = new AtomicReference<>();
        storage.captureHook = () -> nested.set(broker.reserve(ledger, handle));

        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));

        assertFailure(nested.get(), ExactTransferBrokerResult.FailureReason.REENTRANT_OPERATION);
        assertEquals(2, storage.captureCalls);
        assertEquals(1, storage.modulateExtractCalls);
        assertEquals(ExactTransferBrokerState.RESERVED, broker.snapshot().state());
        assertEquals(ExactCpuLedgerState.RESERVED, ledger.snapshot().state());
    }

    @Test
    void duplicateAndWrongStateCommandsAreStableNoOps() {
        PlanFixture fixture = plan("broker-duplicates", Map.of(0, AEAmount.of(5L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());
        assertFailure(broker.progressRelease(1), ExactTransferBrokerResult.FailureReason.WRONG_STATE);
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactTransferBrokerSnapshot reserved = broker.snapshot();
        int transfers = storage.totalTransferCalls();

        assertFailure(broker.reserve(ledger, handle), ExactTransferBrokerResult.FailureReason.WRONG_STATE);
        assertFailure(broker.progressRelease(1), ExactTransferBrokerResult.FailureReason.WRONG_STATE);
        assertSnapshotEquals(reserved, broker.snapshot());
        assertEquals(transfers, storage.totalTransferCalls());

        assertInstanceOf(ExactTransferBrokerResult.Cancelled.class, broker.cancelReservation(ledger, handle));
        assertFailure(broker.cancelReservation(ledger, handle), ExactTransferBrokerResult.FailureReason.WRONG_STATE);
        assertFailure(broker.progressRelease(1), ExactTransferBrokerResult.FailureReason.WRONG_STATE);
        assertEquals(ExactTransferBrokerState.IDLE, broker.snapshot().state());
    }

    @Test
    void hugeQuantityUsesOneCallbackPerKeyRatherThanPerUnit() {
        AEAmount huge = AEAmount.of(BigInteger.ONE.shiftLeft(128));
        PlanFixture fixture = plan("broker-compact", Map.of(0, huge));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());

        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        assertEquals(1, storage.simulateExtractCalls);
        assertEquals(1, storage.modulateExtractCalls);
        assertInstanceOf(ExactTransferBrokerResult.Cancelled.class, broker.cancelReservation(ledger, handle));
        assertEquals(1, storage.modulateInsertCalls);
        assertEquals(3, storage.totalTransferCalls());
    }

    @Test
    void releaseOverReturnFailsClosedAndRetainsKnownCustody() {
        PlanFixture fixture = plan("broker-over-return", Map.of(0, AEAmount.of(10L)));
        ScriptedStorage storage = new ScriptedStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBroker broker = broker(storage, fixture.patterns());
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        storage.insertSteps.add(TransferStep.overReturning());

        assertFailure(broker.cancelReservation(ledger, handle),
                ExactTransferBrokerResult.FailureReason.INVARIANT_VIOLATION);

        ExactTransferBrokerSnapshot failed = broker.snapshot();
        assertEquals(ExactTransferBrokerState.FAIL_CLOSED, failed.state());
        assertEquals(Map.of(new KeyId(0), AEAmount.of(10L)), failed.escrowed());
        assertEquals(ExactCpuLedgerState.RELEASE_PENDING, ledger.snapshot().state());
        assertFailure(broker.progressRelease(1), ExactTransferBrokerResult.FailureReason.FAIL_CLOSED);
        assertSnapshotEquals(failed, broker.snapshot());
    }

    private static ExactTransferBroker broker(ScriptedStorage storage, NormalizedPatternSnapshot patterns) {
        return new ExactTransferBroker(storage, () -> patterns, () -> true, SOURCE);
    }

    private static CpuPlanHandle prepare(ExactCpuLedger ledger, ExactCraftingPlan plan) {
        return assertInstanceOf(ExactCpuLedgerResult.Prepared.class, ledger.prepare(plan)).handle();
    }

    private static void assertFailure(ExactTransferBrokerResult result,
            ExactTransferBrokerResult.FailureReason reason) {
        assertEquals(reason, assertInstanceOf(ExactTransferBrokerResult.Failure.class, result).reason());
    }

    private static void assertSnapshotEquals(ExactTransferBrokerSnapshot expected,
            ExactTransferBrokerSnapshot actual) {
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.handle(), actual.handle());
        assertEquals(expected.planId(), actual.planId());
        assertEquals(expected.reservationId(), actual.reservationId());
        assertEquals(expected.escrowed(), actual.escrowed());
    }

    private static PlanFixture plan(String id, Map<Integer, AEAmount> requestedDebits) {
        TreeMap<Integer, AEAmount> debits = new TreeMap<>(requestedDebits);
        int outputKey = debits.isEmpty() ? 0 : debits.lastKey() + 1;
        int keyCount = outputKey + 1;
        List<CompiledInputSpec> inputs = new ArrayList<>(debits.size());
        for (Map.Entry<Integer, AEAmount> debit : debits.entrySet()) {
            inputs.add(input(debit.getKey(), debit.getValue()));
        }
        CompiledPattern pattern = new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs,
                List.of(new CompiledOutputSpec(new KeyId(outputKey), AEAmount.ONE, true)), Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
        StorageSnapshot storage = snapshot(keyCount, debits, new long[keyCount]);
        NormalizedPatternSnapshot patterns = patternSnapshot(pattern);
        ExactCraftRequest request = new ExactCraftRequest(new KeyId(outputKey), AEAmount.ONE);
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(request, storage, patterns)).draft();
        ExactCraftingPlan plan = assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, storage, patterns)).plan();
        TreeMap<KeyId, AEAmount> expectedDebits = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        debits.forEach((key, amount) -> expectedDebits.put(new KeyId(key), amount));
        assertEquals(expectedDebits, plan.initialStorageDebits());
        return new PlanFixture(plan, storage, patterns);
    }

    private static CompiledInputSpec input(int key, AEAmount amount) {
        return new CompiledInputSpec(
                List.of(new CompiledCandidateSpec(new KeyId(key), amount, Optional.empty())),
                AEAmount.ONE, SubstitutionPolicy.EXACT);
    }

    private static NormalizedPatternSnapshot patternSnapshot(CompiledPattern pattern) {
        CompiledPatternGraph graph = ((GraphBuildResult.Success) new CompiledPatternGraphBuilder(GRAPH_GENERATION,
                KEY_GENERATION).build(List.of(pattern))).graph();
        return new NormalizedPatternSnapshot(SERVER_GENERATION, RECIPE_REVISION, KEY_GENERATION, graph.patternsById(),
                graph, Map.of(pattern.id(), 0), 1, false, List.of());
    }

    private static StorageSnapshot snapshot(int keyCount, Map<Integer, AEAmount> values, long[] keyRevisions) {
        AmountVector amounts = new AmountVector(keyCount);
        values.forEach(amounts::set);
        return new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, keyCount, amounts, keyRevisions);
    }

    private record PlanFixture(ExactCraftingPlan plan, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns) {
    }

    private enum TransferKind {
        EXACT,
        RETURN,
        THROW,
        OVER_RETURN
    }

    private record TransferStep(TransferKind kind, AEAmount amount) {
        static TransferStep exact() {
            return new TransferStep(TransferKind.EXACT, AEAmount.ZERO);
        }

        static TransferStep returning(AEAmount amount) {
            return new TransferStep(TransferKind.RETURN, amount);
        }

        static TransferStep throwing() {
            return new TransferStep(TransferKind.THROW, AEAmount.ZERO);
        }

        static TransferStep overReturning() {
            return new TransferStep(TransferKind.OVER_RETURN, AEAmount.ZERO);
        }
    }

    private static final class ScriptedStorage implements BrokerExactStorage {
        private final StorageSnapshot dependencySnapshot;
        private final TreeMap<KeyId, AEAmount> physical = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        private final Deque<StorageSnapshot> captureSnapshots = new ArrayDeque<>();
        private final Deque<TransferStep> extractSteps = new ArrayDeque<>();
        private final Deque<TransferStep> insertSteps = new ArrayDeque<>();

        private RuntimeException captureFailure;
        private Runnable captureHook;
        private Runnable afterModulatedExtract;
        private AEAmount simulatedExtract;
        private int captureCalls;
        private int simulateExtractCalls;
        private int modulateExtractCalls;
        private int modulateInsertCalls;

        private ScriptedStorage(StorageSnapshot dependencySnapshot, Map<KeyId, AEAmount> initial) {
            this.dependencySnapshot = dependencySnapshot;
            this.physical.putAll(initial);
        }

        @Override
        public AEAmount insert(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            if (mode == Actionable.SIMULATE) {
                return amount;
            }
            modulateInsertCalls++;
            TransferStep step = insertSteps.isEmpty() ? TransferStep.exact() : insertSteps.removeFirst();
            if (step.kind() == TransferKind.THROW) {
                throw new IllegalStateException("scripted insert failure");
            }
            if (step.kind() == TransferKind.OVER_RETURN) {
                return amount.add(AEAmount.ONE);
            }
            AEAmount inserted = step.kind() == TransferKind.RETURN ? step.amount() : amount;
            if (!inserted.equals(AEAmount.ZERO)) {
                physical.put(key, physical(key).add(inserted));
            }
            return inserted;
        }

        @Override
        public AEAmount extract(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            if (mode == Actionable.SIMULATE) {
                simulateExtractCalls++;
                return simulatedExtract != null ? simulatedExtract : physical(key).min(amount);
            }
            modulateExtractCalls++;
            TransferStep step = extractSteps.isEmpty() ? TransferStep.exact() : extractSteps.removeFirst();
            if (step.kind() == TransferKind.THROW) {
                throw new IllegalStateException("scripted extract failure");
            }
            AEAmount extracted = step.kind() == TransferKind.RETURN ? step.amount() : amount;
            if (step.kind() == TransferKind.OVER_RETURN) {
                extracted = amount.add(AEAmount.ONE);
            }
            AEAmount available = physical(key);
            AEAmount physicalExtraction = extracted.min(available);
            if (!physicalExtraction.equals(AEAmount.ZERO)) {
                setPhysical(key, available.subtractExact(physicalExtraction));
            }
            if (afterModulatedExtract != null) {
                afterModulatedExtract.run();
            }
            return extracted;
        }

        @Override
        public AEAmount amount(KeyId key) {
            return physical(key);
        }

        @Override
        public void enumerate(ExactStorageVisitor visitor) {
            physical.forEach(visitor::accept);
        }

        @Override
        public StorageSnapshot captureSnapshot() {
            captureCalls++;
            if (captureHook != null) {
                captureHook.run();
            }
            if (captureFailure != null) {
                throw captureFailure;
            }
            return captureSnapshots.isEmpty() ? dependencySnapshot : captureSnapshots.removeFirst();
        }

        private AEAmount physical(KeyId key) {
            return physical.getOrDefault(key, AEAmount.ZERO);
        }

        private void setPhysical(KeyId key, AEAmount amount) {
            if (amount.equals(AEAmount.ZERO)) {
                physical.remove(key);
            } else {
                physical.put(key, amount);
            }
        }

        private int totalTransferCalls() {
            return simulateExtractCalls + modulateExtractCalls + modulateInsertCalls;
        }
    }
}
