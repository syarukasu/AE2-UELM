package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import appeng.rebuild.planner.ExactCraftPlanResult;
import appeng.rebuild.planner.ExactCraftPlanner;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.rebuild.storage.ExactStorageVisitor;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Mechanical contract tests for the side-effect-free exact recovery activation boundary. */
class ExactRecoveryActivationTest {
    private static final long KEY_GENERATION = 701L;
    private static final long SERVER_GENERATION = 703L;
    private static final KeyId INPUT = new KeyId(0);
    private static final KeyId OUTPUT = new KeyId(1);
    private static final CpuPlanHandle HANDLE = new CpuPlanHandle(
            UUID.fromString("00000000-0000-0000-0000-000000000701"), 1L);
    private static final ReservationId RESERVATION = new ReservationId(
            UUID.fromString("00000000-0000-0000-0000-000000000703"));
    private static final UUID LEASE = UUID.fromString("00000000-0000-0000-0000-000000000707");
    private static final WorkOrderId WORK_ORDER = new WorkOrderId(
            UUID.fromString("00000000-0000-0000-0000-000000000709"));
    private static final IActionSource SOURCE = IActionSource.empty();

    @Test
    void safePreHandoffStatesRestoreWithoutStorageOrPatternCallbacks() {
        Fixture preparedFixture = fixture(AEAmount.of(2L));
        Fixture reservedFixture = fixture(AEAmount.of(2L));
        Fixture partialReleaseFixture = fixture(AEAmount.of(2L));
        Fixture emptyReleaseFixture = fixture(AEAmount.of(2L));
        List<FixtureCheckpoint> checkpoints = List.of(
                new FixtureCheckpoint(preparedFixture, prepared(preparedFixture)),
                new FixtureCheckpoint(reservedFixture, reserved(reservedFixture)),
                new FixtureCheckpoint(partialReleaseFixture,
                        releasePending(partialReleaseFixture, Map.of(INPUT, AEAmount.ONE))),
                new FixtureCheckpoint(emptyReleaseFixture, releasePending(emptyReleaseFixture, Map.of())));

        for (FixtureCheckpoint entry : checkpoints) {
            Fixture fixture = entry.fixture();
            ExactRecoveryCheckpoint checkpoint = entry.checkpoint();
            CountingStorage storage = new CountingStorage(fixture.storage());
            CountingPatterns patterns = new CountingPatterns(fixture.patterns());
            CountingGate gate = new CountingGate(true);

            ExactRecoveryActivation.Result result = new ExactRecoveryActivation().activate(checkpoint, storage,
                    patterns, gate, SOURCE);
            ExactRecoveryActivation.Activated activated = assertInstanceOf(ExactRecoveryActivation.Activated.class,
                    result);
            assertEquals(checkpoint.ledger(), activated.ledger().snapshot());
            assertEquals(checkpoint.broker(), activated.broker().snapshot());
            assertTrue(activated.workOrder().isEmpty());
            assertEquals(0, storage.transferCalls);
            assertEquals(0, storage.snapshotCalls);
            assertEquals(0, patterns.calls);
            assertEquals(1, gate.calls);
        }
    }

    @Test
    void handedOffReadyRestoresAllIdentitiesCustodyAndNextGenerationWithoutReissue() {
        Fixture fixture = fixture(AEAmount.ONE);
        Handoff handoff = handoff(fixture);
        ExactRecoveryCheckpoint checkpoint = new ExactRecoveryCheckpoint(fixture.plan(), handoff.ledger.snapshot(),
                handoff.broker.snapshot(), Optional.of(handoff.order.snapshot()), false);
        int transferCalls = handoff.storage.transferCalls;
        int snapshotCalls = handoff.storage.snapshotCalls;
        int patternCalls = handoff.patterns.calls;

        ExactRecoveryActivation.Activated activated = assertInstanceOf(ExactRecoveryActivation.Activated.class,
                new ExactRecoveryActivation().activate(checkpoint, handoff.storage, handoff.patterns, handoff.gate,
                        SOURCE));
        ExactWorkOrder restored = activated.workOrder().orElseThrow();
        assertEquals(checkpoint.ledger(), activated.ledger().snapshot());
        assertEquals(checkpoint.broker(), activated.broker().snapshot());
        assertEquals(checkpoint.workOrder().orElseThrow(), restored.snapshot());
        assertEquals(ExactWorkOrderState.READY, restored.snapshot().state());
        assertTrue(restored.snapshot().outstandingCommand().isEmpty(), "activation must not reissue a command");
        assertTrue(restored.snapshot().inFlightCommand().isEmpty());
        assertEquals(transferCalls, handoff.storage.transferCalls);
        assertEquals(snapshotCalls, handoff.storage.snapshotCalls);
        assertEquals(patternCalls, handoff.patterns.calls);
    }

    @Test
    void exactHugeQuantitiesSurviveActivationWithoutAQuantityProportionalWindow() {
        for (BigInteger value : List.of(BigInteger.ONE.shiftLeft(64), BigInteger.ONE.shiftLeft(128),
                BigInteger.TEN.pow(1000))) {
            AEAmount demand = AEAmount.of(value);
            Fixture fixture = fixture(demand);
            ExactRecoveryCheckpoint checkpoint = reserved(fixture);
            CountingStorage storage = new CountingStorage(fixture.storage());
            CountingPatterns patterns = new CountingPatterns(fixture.patterns());

            ExactRecoveryActivation.Activated activated = assertInstanceOf(ExactRecoveryActivation.Activated.class,
                    new ExactRecoveryActivation().activate(checkpoint, storage, patterns, new CountingGate(true),
                            SOURCE));
            assertEquals(demand, activated.ledger().snapshot().reservedDebits().get(INPUT));
            assertEquals(demand, activated.broker().snapshot().escrowed().get(INPUT));
            assertEquals(0, storage.transferCalls);
            assertEquals(0, storage.snapshotCalls);
            assertEquals(0, patterns.calls);
        }
    }

    @Test
    void outstandingCommandIsRetainedButInFlightAndAllAmbiguousEvidenceRemainRecoveryRequired() {
        Fixture fixture = fixture(AEAmount.ONE);
        Handoff handoff = handoff(fixture);
        ExactWorkCommand command = assertInstanceOf(ExactWorkOrderCommandResult.Issued.class,
                handoff.order.issueNext(Long.MAX_VALUE)).command();
        ExactRecoveryCheckpoint outstanding = new ExactRecoveryCheckpoint(fixture.plan(), handoff.ledger.snapshot(),
                handoff.broker.snapshot(), Optional.of(handoff.order.snapshot()), false);
        int transferCalls = handoff.storage.transferCalls;
        ExactRecoveryActivation activation = new ExactRecoveryActivation();
        ExactRecoveryActivation.Activated resumed = assertInstanceOf(ExactRecoveryActivation.Activated.class,
                activation.activate(outstanding, handoff.storage, handoff.patterns, handoff.gate, SOURCE));
        assertEquals(Optional.of(command), resumed.workOrder().orElseThrow().snapshot().outstandingCommand());
        assertEquals(transferCalls, handoff.storage.transferCalls);

        assertInstanceOf(ExactWorkOrderTransitionResult.Accepted.class, handoff.order.accept(
                new WorkCommandAcceptance(command)));
        ExactRecoveryCheckpoint inFlight = new ExactRecoveryCheckpoint(fixture.plan(), handoff.ledger.snapshot(),
                handoff.broker.snapshot(), Optional.of(handoff.order.snapshot()), true);
        assertRecoveryRequired(activation.activate(inFlight, handoff.storage, handoff.patterns, handoff.gate, SOURCE),
                inFlight);
        assertEquals(transferCalls, handoff.storage.transferCalls);

        ExactWorkOrderTransitionResult.Failure mismatch = assertInstanceOf(ExactWorkOrderTransitionResult.Failure.class,
                handoff.order.complete(new WorkCommandCompletion(command, Map.of(), Map.of())));
        assertEquals(ExactWorkOrderTransitionResult.Reason.RESULT_MISMATCH, mismatch.reason());
        ExactRecoveryCheckpoint discrepancy = new ExactRecoveryCheckpoint(fixture.plan(), handoff.ledger.snapshot(),
                handoff.broker.snapshot(), Optional.of(handoff.order.snapshot()), true);
        assertRecoveryRequired(activation.activate(discrepancy, handoff.storage, handoff.patterns, handoff.gate,
                SOURCE), discrepancy);
        assertEquals(transferCalls, handoff.storage.transferCalls);
    }

    @Test
    void identityConfirmedNativeCommandRestoresInFlightWithoutReissue() {
        Fixture fixture = fixture(AEAmount.ONE);
        Handoff handoff = handoff(fixture);
        ExactWorkCommand command = assertInstanceOf(ExactWorkOrderCommandResult.Issued.class,
                handoff.order.issueNext(Long.MAX_VALUE)).command();
        assertInstanceOf(ExactWorkOrderTransitionResult.Accepted.class,
                handoff.order.accept(new WorkCommandAcceptance(command)));
        ExactRecoveryCheckpoint checkpoint = new ExactRecoveryCheckpoint(fixture.plan(), handoff.ledger.snapshot(),
                handoff.broker.snapshot(), Optional.of(handoff.order.snapshot()), true);
        WorkCommandId wrong = new WorkCommandId(command.id().planId(), command.id().leaseIdentity(),
                command.id().workOrderId(), command.id().generation() + 1L);

        assertRecoveryRequired(new ExactRecoveryActivation().activateConfirmedInFlight(checkpoint, handoff.storage,
                handoff.patterns, handoff.gate, SOURCE, wrong), checkpoint);
        ExactRecoveryActivation.Activated activated = assertInstanceOf(ExactRecoveryActivation.Activated.class,
                new ExactRecoveryActivation().activateConfirmedInFlight(checkpoint, handoff.storage, handoff.patterns,
                        handoff.gate, SOURCE, command.id()));
        assertEquals(Optional.of(command), activated.workOrder().orElseThrow().snapshot().inFlightCommand());
        assertEquals(ExactWorkOrderState.IN_FLIGHT, activated.workOrder().orElseThrow().snapshot().state());
    }

    @Test
    void brokerDiscrepancyFailedHandoffAndWorkOrderTransferDiscrepancyNeverAutoResume() {
        Fixture fixture = fixture(AEAmount.ONE);
        CountingStorage storage = new CountingStorage(fixture.storage());
        CountingPatterns patterns = new CountingPatterns(fixture.patterns());
        CountingGate gate = new CountingGate(true);
        BrokerTransferDiscrepancy brokerEvidence = new BrokerTransferDiscrepancy(
                BrokerTransferDiscrepancy.Operation.EXTRACT, BrokerTransferDiscrepancy.Reason.NULL_RETURN,
                fixture.plan().planId(), HANDLE, RESERVATION, INPUT, AEAmount.ONE, Optional.empty(), Map.of());
        ExactCpuLedgerSnapshot preparedLedger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.PREPARED, 1L,
                Optional.of(HANDLE), Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
        ExactTransferBrokerSnapshot brokerFailure = new ExactTransferBrokerSnapshot(
                ExactTransferBrokerState.FAIL_CLOSED,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), Map.of(), Optional.of(brokerEvidence));
        ExactRecoveryCheckpoint brokerDiscrepancy = new ExactRecoveryCheckpoint(fixture.plan(), preparedLedger,
                brokerFailure, Optional.empty(), Optional.empty(), true);
        assertRecoveryRequired(
                new ExactRecoveryActivation().activate(brokerDiscrepancy, storage, patterns, gate, SOURCE),
                brokerDiscrepancy);
        assertEquals(0, storage.transferCalls);
        assertEquals(0, patterns.calls);

        ExactCpuLedgerSnapshot handedOffLedger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.HANDED_OFF, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), fixture.plan().initialStorageDebits(), Optional.empty(),
                Optional.of(LEASE));
        ExactTransferBrokerSnapshot failedBroker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.FAIL_CLOSED,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION),
                Optional.of(WORK_ORDER), Optional.of(LEASE), fixture.plan().initialStorageDebits(), Optional.empty());
        FailedHandoffRecovery failed = new FailedHandoffRecovery(LEASE, HANDLE, RESERVATION, fixture.plan().planId(),
                fixture.plan().initialStorageDebits());
        ExactRecoveryCheckpoint failedHandoff = new ExactRecoveryCheckpoint(fixture.plan(), handedOffLedger,
                failedBroker, Optional.empty(), Optional.of(failed), true);
        assertRecoveryRequired(new ExactRecoveryActivation().activate(failedHandoff, storage, patterns, gate, SOURCE),
                failedHandoff);

        Handoff badRelease = handoff(fixture, true);
        assertInstanceOf(ExactWorkOrderLifecycleResult.ReleasePending.class, badRelease.order.requestCancellation());
        assertInstanceOf(ExactWorkOrderLifecycleResult.Failure.class, badRelease.order.progressRelease(1));
        ExactRecoveryCheckpoint transferDiscrepancy = new ExactRecoveryCheckpoint(fixture.plan(),
                badRelease.ledger.snapshot(),
                badRelease.broker.snapshot(), Optional.of(badRelease.order.snapshot()), true);
        assertTrue(transferDiscrepancy.workOrder().orElseThrow().transferDiscrepancy().isPresent());
        assertRecoveryRequired(new ExactRecoveryActivation().activate(transferDiscrepancy, badRelease.storage,
                badRelease.patterns, badRelease.gate, SOURCE), transferDiscrepancy);
    }

    @Test
    void wrongThreadAndRuntimeGateFailuresAreTypedAndErrorsPropagate() {
        Fixture fixture = fixture(AEAmount.ONE);
        ExactRecoveryCheckpoint checkpoint = reserved(fixture);

        CountingStorage storage = new CountingStorage(fixture.storage());
        CountingPatterns patterns = new CountingPatterns(fixture.patterns());
        ExactRecoveryActivation.Rejected wrongThread = assertInstanceOf(ExactRecoveryActivation.Rejected.class,
                new ExactRecoveryActivation().activate(checkpoint, storage, patterns, new CountingGate(false), SOURCE));
        assertEquals(ExactRecoveryActivation.Reason.WRONG_THREAD, wrongThread.reason());
        assertEquals(0, storage.transferCalls);
        assertEquals(0, storage.snapshotCalls);
        assertEquals(0, patterns.calls);

        ExactRecoveryActivation.Rejected callbackFailure = assertInstanceOf(ExactRecoveryActivation.Rejected.class,
                new ExactRecoveryActivation().activate(checkpoint, storage, patterns, () -> {
                    throw new IllegalStateException("gate callback");
                }, SOURCE));
        assertEquals(ExactRecoveryActivation.Reason.WRONG_THREAD, callbackFailure.reason());
        assertThrows(AssertionError.class, () -> new ExactRecoveryActivation().activate(checkpoint, storage, patterns,
                () -> {
                    throw new AssertionError("fatal gate callback");
                }, SOURCE));
        assertInstanceOf(ExactRecoveryActivation.Activated.class,
                new ExactRecoveryActivation().activate(checkpoint, storage, patterns, new CountingGate(true), SOURCE));
    }

    @Test
    void reentrantActivationIsRejectedAndDoesNotPublishDuplicateAuthority() {
        Fixture fixture = fixture(AEAmount.ONE);
        ExactRecoveryCheckpoint checkpoint = reserved(fixture);
        CountingStorage storage = new CountingStorage(fixture.storage());
        CountingPatterns patterns = new CountingPatterns(fixture.patterns());
        ExactRecoveryActivation activation = new ExactRecoveryActivation();
        AtomicReference<ExactRecoveryActivation.Result> nested = new AtomicReference<>();
        AtomicReference<ReentrantGate> gateRef = new AtomicReference<>();
        ReentrantGate gate = new ReentrantGate(() -> nested.set(activation.activate(checkpoint, storage, patterns,
                gateRef.get(), SOURCE)));
        gateRef.set(gate);

        assertInstanceOf(ExactRecoveryActivation.Activated.class,
                activation.activate(checkpoint, storage, patterns, gate, SOURCE));
        ExactRecoveryActivation.Rejected nestedResult = assertInstanceOf(ExactRecoveryActivation.Rejected.class,
                nested.get());
        assertEquals(ExactRecoveryActivation.Reason.REENTRANT, nestedResult.reason());
        assertEquals(0, storage.transferCalls);
        assertEquals(0, storage.snapshotCalls);
        assertEquals(0, patterns.calls);

        ExactRecoveryActivation.Result second = activation.activate(checkpoint, storage, patterns,
                new CountingGate(true), SOURCE);
        ExactRecoveryActivation.Rejected repeated = assertInstanceOf(ExactRecoveryActivation.Rejected.class, second);
        assertEquals("ALREADY_ACTIVATED", repeated.reason().name());
    }

    @Test
    void processWideClaimAllowsExactlyOneSuccessAcrossActivatorInstancesAndThreads() throws Exception {
        Fixture fixture = fixture(AEAmount.ONE);
        ExactRecoveryCheckpoint checkpoint = reserved(fixture);
        CountingStorage firstStorage = new CountingStorage(fixture.storage());
        CountingStorage secondStorage = new CountingStorage(fixture.storage());
        CountingPatterns firstPatterns = new CountingPatterns(fixture.patterns());
        CountingPatterns secondPatterns = new CountingPatterns(fixture.patterns());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<ExactRecoveryActivation.Result> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return new ExactRecoveryActivation().activate(checkpoint, firstStorage, firstPatterns,
                        new CountingGate(true), SOURCE);
            });
            Future<ExactRecoveryActivation.Result> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return new ExactRecoveryActivation().activate(checkpoint, secondStorage, secondPatterns,
                        new CountingGate(true), SOURCE);
            });
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            ExactRecoveryActivation.Result firstResult = first.get();
            ExactRecoveryActivation.Result secondResult = second.get();
            int activated = (firstResult instanceof ExactRecoveryActivation.Activated ? 1 : 0)
                    + (secondResult instanceof ExactRecoveryActivation.Activated ? 1 : 0);
            assertEquals(1, activated);
            ExactRecoveryActivation.Rejected rejected = firstResult instanceof ExactRecoveryActivation.Rejected value
                    ? value
                    : assertInstanceOf(ExactRecoveryActivation.Rejected.class, secondResult);
            assertEquals("ALREADY_ACTIVATED", rejected.reason().name());
            assertEquals(0, firstStorage.transferCalls + secondStorage.transferCalls);
            assertEquals(0, firstStorage.snapshotCalls + secondStorage.snapshotCalls);
            assertEquals(0, firstPatterns.calls + secondPatterns.calls);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleLedgerRevisionIsTypedInvalidCheckpointWithoutDependencyMutation() {
        Fixture fixture = fixture(AEAmount.ONE);
        ExactCpuLedgerSnapshot staleLedger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.RESERVED, 2L,
                Optional.of(HANDLE), Optional.of(RESERVATION), fixture.plan().initialStorageDebits(), Optional.empty(),
                Optional.empty());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.RESERVED,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), fixture.plan().initialStorageDebits(), Optional.empty());
        ExactRecoveryCheckpoint stale = new ExactRecoveryCheckpoint(fixture.plan(), staleLedger, broker,
                Optional.empty(), false);
        CountingStorage storage = new CountingStorage(fixture.storage());
        CountingPatterns patterns = new CountingPatterns(fixture.patterns());

        ExactRecoveryActivation.Rejected result = assertInstanceOf(ExactRecoveryActivation.Rejected.class,
                new ExactRecoveryActivation().activate(stale, storage, patterns, new CountingGate(true), SOURCE));
        assertEquals(ExactRecoveryActivation.Reason.INVALID_CHECKPOINT, result.reason());
        assertEquals(0, storage.transferCalls);
        assertEquals(0, storage.snapshotCalls);
        assertEquals(0, patterns.calls);

        assertInstanceOf(ExactRecoveryActivation.Activated.class,
                new ExactRecoveryActivation().activate(reserved(fixture), storage, patterns, new CountingGate(true),
                        SOURCE));
    }

    @Test
    void completedWorkOrderCannotBeForgedAsAHandedOffRecoveryAuthority() {
        Fixture fixture = fixture(AEAmount.ONE);
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.HANDED_OFF, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), fixture.plan().initialStorageDebits(), Optional.empty(),
                Optional.of(LEASE));
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.LEASED,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION),
                Optional.of(WORK_ORDER), Optional.of(LEASE), Map.of(), Optional.empty());
        ExactWorkOrderSnapshot completed = new ExactWorkOrderSnapshot(ExactWorkOrderState.COMPLETED,
                fixture.plan().planId(), HANDLE, RESERVATION, LEASE, WORK_ORDER, Map.of(),
                fixture.plan().executionManifests().size(), AEAmount.ZERO, List.of(), AEAmount.ZERO, -1,
                AEAmount.ZERO, List.of(), Optional.of(ExactWorkOrderReleaseMode.SETTLEMENT), 0L, false,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);

        assertThrows(IllegalArgumentException.class,
                () -> new ExactRecoveryCheckpoint(fixture.plan(), ledger, broker, Optional.of(completed), false));
    }

    private static void assertRecoveryRequired(ExactRecoveryActivation.Result result,
            ExactRecoveryCheckpoint checkpoint) {
        ExactRecoveryActivation.RecoveryRequired required = assertInstanceOf(
                ExactRecoveryActivation.RecoveryRequired.class, result);
        assertSameCheckpoint(checkpoint, required.checkpoint());
        assertEquals(ExactRecoveryActivation.Reason.AMBIGUOUS_PHYSICAL_STATE, required.reason());
    }

    private static void assertSameCheckpoint(ExactRecoveryCheckpoint expected, ExactRecoveryCheckpoint actual) {
        assertEquals(expected.plan(), actual.plan());
        assertEquals(expected.ledger(), actual.ledger());
        assertEquals(expected.broker(), actual.broker());
        assertEquals(expected.workOrder(), actual.workOrder());
        assertEquals(expected.failedHandoff(), actual.failedHandoff());
        assertEquals(expected.recoveryRequired(), actual.recoveryRequired());
    }

    private static ExactRecoveryCheckpoint prepared(Fixture fixture) {
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.PREPARED, 1L,
                Optional.of(HANDLE), Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.ROLLBACK_PENDING,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), fixture.plan().initialStorageDebits(), Optional.empty());
        return new ExactRecoveryCheckpoint(fixture.plan(), ledger, broker, Optional.empty(), false);
    }

    private static ExactRecoveryCheckpoint reserved(Fixture fixture) {
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.RESERVED, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), fixture.plan().initialStorageDebits(), Optional.empty(),
                Optional.empty());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.RESERVED,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), fixture.plan().initialStorageDebits(), Optional.empty());
        return new ExactRecoveryCheckpoint(fixture.plan(), ledger, broker, Optional.empty(), false);
    }

    private static ExactRecoveryCheckpoint releasePending(Fixture fixture, Map<KeyId, AEAmount> escrow) {
        ReleaseObligation obligation = ReleaseObligation.restoreForRecovery(HANDLE, RESERVATION,
                fixture.plan().initialStorageDebits());
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.RELEASE_PENDING, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), fixture.plan().initialStorageDebits(),
                Optional.of(obligation), Optional.empty());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.RELEASE_PENDING,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), escrow, Optional.empty());
        return new ExactRecoveryCheckpoint(fixture.plan(), ledger, broker, Optional.empty(), false);
    }

    private static Handoff handoff(Fixture fixture) {
        return handoff(fixture, false);
    }

    private static Handoff handoff(Fixture fixture, boolean invalidInsert) {
        CountingStorage storage = new CountingStorage(fixture.storage(), invalidInsert);
        CountingPatterns patterns = new CountingPatterns(fixture.patterns());
        CountingGate gate = new CountingGate(true);
        ExactTransferBroker broker = new ExactTransferBroker(storage, patterns, gate, SOURCE);
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = assertInstanceOf(ExactCpuLedgerResult.Prepared.class, ledger.prepare(fixture.plan()))
                .handle();
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactWorkOrder order = assertInstanceOf(ExactTransferBrokerResult.Started.class,
                broker.startWorkOrder(ledger, handle)).workOrder();
        return new Handoff(fixture, storage, patterns, gate, broker, ledger, order);
    }

    private static Fixture fixture(AEAmount demand) {
        CompiledPattern pattern = new CompiledPattern(new PatternId("activation-pattern"),
                PatternKind.CRAFTING,
                List.of(new CompiledInputSpec(
                        List.of(new CompiledCandidateSpec(INPUT, AEAmount.ONE, Optional.empty())), AEAmount.ONE,
                        SubstitutionPolicy.EXACT)),
                List.of(new CompiledOutputSpec(OUTPUT, AEAmount.ONE, true)), Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
        CompiledPatternGraph graph = assertInstanceOf(GraphBuildResult.Success.class,
                new CompiledPatternGraphBuilder(new GraphGeneration(719L), KEY_GENERATION).build(List.of(pattern)))
                .graph();
        NormalizedPatternSnapshot patterns = new NormalizedPatternSnapshot(SERVER_GENERATION,
                new RecipeRevision(727L), KEY_GENERATION, graph.patternsById(), graph, Map.of(pattern.id(), 0), 1,
                false, List.of());
        AmountVector amounts = new AmountVector(2);
        amounts.set(INPUT.value(), demand);
        StorageSnapshot storage = new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, 2, amounts,
                new long[2]);
        ExactCraftPlanResult.Success planned = assertInstanceOf(ExactCraftPlanResult.Success.class,
                new ExactCraftPlanner().plan(new ExactCraftRequest(OUTPUT, demand), storage, patterns));
        ExactPlanValidationResult.Success validated = assertInstanceOf(ExactPlanValidationResult.Success.class,
                new ExactCraftingPlanValidator().validate(planned.draft(), storage, patterns));
        return new Fixture(validated.plan(), patterns, storage);
    }

    private record Fixture(ExactCraftingPlan plan, NormalizedPatternSnapshot patterns, StorageSnapshot storage) {
    }

    private record FixtureCheckpoint(Fixture fixture, ExactRecoveryCheckpoint checkpoint) {
    }

    private record Handoff(Fixture fixture, CountingStorage storage, CountingPatterns patterns, CountingGate gate,
            ExactTransferBroker broker, ExactCpuLedger ledger, ExactWorkOrder order) {
    }

    private static final class CountingGate implements ServerThreadGate {
        private final boolean serverThread;
        private int calls;

        private CountingGate(boolean serverThread) {
            this.serverThread = serverThread;
        }

        @Override
        public boolean isServerThread() {
            calls++;
            return serverThread;
        }
    }

    private static final class ReentrantGate implements ServerThreadGate {
        private final Runnable callback;
        private int calls;

        private ReentrantGate(Runnable callback) {
            this.callback = callback;
        }

        @Override
        public boolean isServerThread() {
            calls++;
            if (calls == 1)
                callback.run();
            return true;
        }
    }

    private static final class CountingPatterns implements CurrentPatternSnapshotSource {
        private final NormalizedPatternSnapshot snapshot;
        private int calls;

        private CountingPatterns(NormalizedPatternSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public NormalizedPatternSnapshot captureCurrent() {
            calls++;
            return snapshot;
        }
    }

    private static final class CountingStorage implements BrokerExactStorage {
        private static final java.util.Comparator<KeyId> KEY_ORDER = java.util.Comparator.comparingInt(KeyId::value);

        private final StorageSnapshot dependencies;
        private final TreeMap<KeyId, AEAmount> physical = new TreeMap<>(KEY_ORDER);
        private final boolean invalidInsert;
        private int transferCalls;
        private int snapshotCalls;

        private CountingStorage(StorageSnapshot dependencies) {
            this(dependencies, false);
        }

        private CountingStorage(StorageSnapshot dependencies, boolean invalidInsert) {
            this.dependencies = dependencies;
            this.invalidInsert = invalidInsert;
            for (KeyId key : dependencies.nonZeroKeys())
                physical.put(key, dependencies.amount(key));
        }

        @Override
        public AEAmount insert(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            transferCalls++;
            if (invalidInsert)
                return amount.add(AEAmount.ONE);
            if (mode == Actionable.MODULATE)
                physical.merge(key, amount, AEAmount::add);
            return amount;
        }

        @Override
        public AEAmount extract(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            transferCalls++;
            AEAmount available = physical.getOrDefault(key, AEAmount.ZERO).min(amount);
            if (mode == Actionable.MODULATE && !available.equals(AEAmount.ZERO))
                physical.put(key, physical.get(key).subtractExact(available));
            return available;
        }

        @Override
        public AEAmount amount(KeyId key) {
            return physical.getOrDefault(key, AEAmount.ZERO);
        }

        @Override
        public void enumerate(ExactStorageVisitor visitor) {
            physical.forEach(visitor::accept);
        }

        @Override
        public StorageSnapshot captureSnapshot() {
            snapshotCalls++;
            return dependencies;
        }
    }
}
