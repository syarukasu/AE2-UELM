package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.rebuild.storage.ExactStorageVisitor;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Ownership-boundary tests for reserved escrow becoming one immutable work-order lease. */
class ExactWorkOrderHandoffTest {
    private static final long KEY_GENERATION = 61L;
    private static final long SERVER_GENERATION = 67L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(71L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(73L);
    private static final IActionSource SOURCE = IActionSource.empty();
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();
    private static final ExactCraftingPlanValidator VALIDATOR = new ExactCraftingPlanValidator();

    @Test
    void successfulStartTransfersExactHugeCustodyAndEveryIdentityOnce() {
        AEAmount huge = AEAmount.of(BigInteger.ONE.shiftLeft(128));
        PlanFixture fixture = plan("handoff-huge", Map.of(0, AEAmount.ONE, 1, huge));
        CountingStorage storage = new CountingStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        TestGate gate = new TestGate();
        ExactTransferBroker broker = broker(storage, fixture.patterns(), gate);
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        ExactTransferBrokerResult.Reserved reserved = assertInstanceOf(ExactTransferBrokerResult.Reserved.class,
                broker.reserve(ledger, handle));
        ReservationId reservationId = reserved.snapshot().reservationId().orElseThrow();
        int transfersBeforeStart = storage.totalTransferCalls();

        ExactWorkOrder workOrder = assertInstanceOf(ExactTransferBrokerResult.Started.class,
                broker.startWorkOrder(ledger, handle)).workOrder();

        ExactTransferBrokerSnapshot leased = broker.snapshot();
        ExactWorkOrderSnapshot work = workOrder.snapshot();
        ExactCpuLedgerSnapshot cpu = ledger.snapshot();
        assertEquals(ExactTransferBrokerState.LEASED, leased.state());
        assertTrue(leased.escrowed().isEmpty());
        assertEquals(Optional.of(fixture.plan().planId()), leased.planId());
        assertEquals(Optional.of(handle), leased.handle());
        assertEquals(Optional.of(reservationId), leased.reservationId());
        assertTrue(leased.workOrderId().isPresent());
        assertTrue(leased.leaseIdentity().isPresent());

        assertEquals(ExactWorkOrderState.READY, work.state());
        assertEquals(fixture.plan().planId(), work.planId());
        assertEquals(handle, work.handle());
        assertEquals(reservationId, work.reservationId());
        assertEquals(leased.workOrderId().orElseThrow(), work.workOrderId());
        assertEquals(leased.leaseIdentity().orElseThrow(), work.leaseIdentity());
        assertEquals(fixture.plan().initialStorageDebits(), work.custody());

        assertEquals(ExactCpuLedgerState.HANDED_OFF, cpu.state());
        assertEquals(Optional.of(handle), cpu.handle());
        assertEquals(Optional.of(reservationId), cpu.reservationId());
        assertEquals(Optional.of(work.leaseIdentity()), cpu.leaseIdentity());
        assertEquals(fixture.plan().initialStorageDebits(), cpu.reservedDebits());
        assertTrue(cpu.releaseObligation().isEmpty());
        assertEquals(transfersBeforeStart, storage.totalTransferCalls());
        assertEquals(AEAmount.ZERO, storage.physical(new KeyId(0)));
        assertEquals(AEAmount.ZERO, storage.physical(new KeyId(1)));
    }

    @Test
    void emptyCustodyCanBeLeasedWithoutFabricatingStorageWork() {
        PlanFixture fixture = plan("handoff-empty", Map.of());
        CountingStorage storage = new CountingStorage(fixture.storage(), Map.of());
        TestGate gate = new TestGate();
        ExactTransferBroker broker = broker(storage, fixture.patterns(), gate);
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());

        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        assertEquals(0, storage.totalTransferCalls());
        ExactWorkOrder workOrder = assertInstanceOf(ExactTransferBrokerResult.Started.class,
                broker.startWorkOrder(ledger, handle)).workOrder();

        assertTrue(workOrder.snapshot().custody().isEmpty());
        assertTrue(broker.snapshot().escrowed().isEmpty());
        assertEquals(ExactTransferBrokerState.LEASED, broker.snapshot().state());
        assertEquals(ExactWorkOrderState.READY, workOrder.snapshot().state());
        assertEquals(ExactCpuLedgerState.HANDED_OFF, ledger.snapshot().state());
        assertEquals(0, storage.totalTransferCalls());
    }

    @Test
    void leasedBrokerRejectsDuplicateCancelReserveAndReleaseCommandsWithoutMutation() {
        PlanFixture fixture = plan("handoff-terminal-broker", Map.of(0, AEAmount.of(9L)));
        CountingStorage storage = new CountingStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactTransferBroker broker = broker(storage, fixture.patterns(), new TestGate());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactWorkOrder workOrder = assertInstanceOf(ExactTransferBrokerResult.Started.class,
                broker.startWorkOrder(ledger, handle)).workOrder();
        ExactTransferBrokerSnapshot beforeBroker = broker.snapshot();
        ExactWorkOrderSnapshot beforeWork = workOrder.snapshot();
        ExactCpuLedgerSnapshot beforeCpu = ledger.snapshot();
        int transfers = storage.totalTransferCalls();

        assertFailure(broker.startWorkOrder(ledger, handle), ExactTransferBrokerResult.FailureReason.WRONG_STATE);
        assertFailure(broker.cancelReservation(ledger, handle), ExactTransferBrokerResult.FailureReason.WRONG_STATE);
        assertFailure(broker.reserve(ledger, handle), ExactTransferBrokerResult.FailureReason.WRONG_STATE);
        assertFailure(broker.progressRelease(1), ExactTransferBrokerResult.FailureReason.WRONG_STATE);
        assertCpuFailure(ledger.cancelReserved(handle), ExactCpuLedgerResult.FailureReason.WRONG_STATE);
        assertCpuFailure(ledger.handoff(handle), ExactCpuLedgerResult.FailureReason.WRONG_STATE);

        assertBrokerSnapshotEquals(beforeBroker, broker.snapshot());
        assertEquals(beforeWork, workOrder.snapshot());
        assertEquals(beforeCpu, ledger.snapshot());
        assertEquals(transfers, storage.totalTransferCalls());
    }

    @Test
    void wrongLedgerAndHandleCannotStartOrChangeReservedCustody() {
        PlanFixture fixture = plan("handoff-wrong-identity", Map.of(0, AEAmount.of(4L)));
        CountingStorage storage = new CountingStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactTransferBroker broker = broker(storage, fixture.patterns(), new TestGate());
        ExactCpuLedger ledger = new ExactCpuLedger();
        ExactCpuLedger foreign = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        CpuPlanHandle foreignHandle = prepare(foreign, fixture.plan());
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactTransferBrokerSnapshot beforeBroker = broker.snapshot();
        ExactCpuLedgerSnapshot beforeCpu = ledger.snapshot();
        int transfers = storage.totalTransferCalls();

        assertFailure(broker.startWorkOrder(foreign, handle),
                ExactTransferBrokerResult.FailureReason.IDENTITY_MISMATCH);
        assertFailure(broker.startWorkOrder(ledger, foreignHandle),
                ExactTransferBrokerResult.FailureReason.IDENTITY_MISMATCH);

        assertBrokerSnapshotEquals(beforeBroker, broker.snapshot());
        assertEquals(beforeCpu, ledger.snapshot());
        assertEquals(transfers, storage.totalTransferCalls());
    }

    @Test
    void cpuIdentityMismatchBeforeHandoffDoesNotCreateWorkOrderOrMoveCustody() {
        PlanFixture fixture = plan("handoff-cpu-mismatch", Map.of(0, AEAmount.of(4L)));
        CountingStorage storage = new CountingStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactTransferBroker broker = broker(storage, fixture.patterns(), new TestGate());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactTransferBrokerSnapshot beforeBroker = broker.snapshot();
        int transfers = storage.totalTransferCalls();
        assertInstanceOf(ExactCpuLedgerResult.ReleaseRequired.class, ledger.cancelReserved(handle));

        assertFailure(broker.startWorkOrder(ledger, handle),
                ExactTransferBrokerResult.FailureReason.IDENTITY_MISMATCH);

        assertBrokerSnapshotEquals(beforeBroker, broker.snapshot());
        assertEquals(ExactCpuLedgerState.RELEASE_PENDING, ledger.snapshot().state());
        assertTrue(broker.snapshot().workOrderId().isEmpty());
        assertTrue(broker.snapshot().leaseIdentity().isEmpty());
        assertEquals(transfers, storage.totalTransferCalls());
    }

    @Test
    void brokerAndWorkOrderSnapshotsAreDetachedAndReadOnly() {
        AEAmount huge = AEAmount.of(BigInteger.ONE.shiftLeft(128));
        PlanFixture fixture = plan("handoff-immutable", Map.of(0, huge));
        CountingStorage storage = new CountingStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        ExactTransferBroker broker = broker(storage, fixture.patterns(), new TestGate());
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactTransferBrokerSnapshot reserved = broker.snapshot();
        ExactWorkOrder workOrder = assertInstanceOf(ExactTransferBrokerResult.Started.class,
                broker.startWorkOrder(ledger, handle)).workOrder();
        ExactWorkOrderSnapshot work = workOrder.snapshot();
        ExactTransferBrokerSnapshot leased = broker.snapshot();

        assertEquals(Map.of(new KeyId(0), huge), reserved.escrowed());
        assertEquals(Map.of(new KeyId(0), huge), work.custody());
        assertTrue(leased.escrowed().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> reserved.escrowed().clear());
        assertThrows(UnsupportedOperationException.class, () -> work.custody().clear());
        assertEquals(work, workOrder.snapshot());
        assertBrokerSnapshotEquals(leased, broker.snapshot());
    }

    @Test
    void wrongThreadAtStartLeavesReservationAndCpuUntouched() {
        PlanFixture fixture = plan("handoff-thread", Map.of(0, AEAmount.of(3L)));
        CountingStorage storage = new CountingStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        TestGate gate = new TestGate();
        ExactTransferBroker broker = broker(storage, fixture.patterns(), gate);
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactTransferBrokerSnapshot beforeBroker = broker.snapshot();
        ExactCpuLedgerSnapshot beforeCpu = ledger.snapshot();
        int transfers = storage.totalTransferCalls();
        gate.allowed = false;

        assertFailure(broker.startWorkOrder(ledger, handle), ExactTransferBrokerResult.FailureReason.WRONG_THREAD);

        assertBrokerSnapshotEquals(beforeBroker, broker.snapshot());
        assertEquals(beforeCpu, ledger.snapshot());
        assertEquals(transfers, storage.totalTransferCalls());
    }

    @Test
    void reentrantStartIsRejectedWhileOuterHandoffSucceedsExactlyOnce() {
        PlanFixture fixture = plan("handoff-reentry", Map.of(0, AEAmount.of(3L)));
        CountingStorage storage = new CountingStorage(fixture.storage(), fixture.plan().initialStorageDebits());
        TestGate gate = new TestGate();
        ExactTransferBroker broker = broker(storage, fixture.patterns(), gate);
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = prepare(ledger, fixture.plan());
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        AtomicReference<ExactTransferBrokerResult> nested = new AtomicReference<>();
        gate.hook = () -> nested.set(broker.startWorkOrder(ledger, handle));

        ExactWorkOrder outer = assertInstanceOf(ExactTransferBrokerResult.Started.class,
                broker.startWorkOrder(ledger, handle)).workOrder();

        assertFailure(nested.get(), ExactTransferBrokerResult.FailureReason.REENTRANT_OPERATION);
        assertEquals(ExactWorkOrderState.READY, outer.snapshot().state());
        assertEquals(ExactTransferBrokerState.LEASED, broker.snapshot().state());
        assertEquals(ExactCpuLedgerState.HANDED_OFF, ledger.snapshot().state());
    }

    @Test
    void publicWorkOrderSurfaceHasNoConstructorOrMutationCommand() {
        assertEquals(0, ExactWorkOrder.class.getConstructors().length);
        assertEquals(0, ExactWorkOrder.class.getFields().length);
        Set<String> declaredPublicMethods = java.util.Arrays.stream(ExactWorkOrder.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("snapshot"), declaredPublicMethods);

        Method stagedFactory;
        try {
            stagedFactory = ExactWorkOrder.class.getDeclaredMethod("fromStaged", WorkOrderId.class,
                    ReservedPlanLease.class, Map.class, BrokerExactStorage.class, ServerThreadGate.class,
                    IActionSource.class, ExactCpuLedger.class);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("expected package-private staged work-order factory", exception);
        }
        assertFalse(Modifier.isPublic(stagedFactory.getModifiers()));
    }

    private static ExactTransferBroker broker(CountingStorage storage, NormalizedPatternSnapshot patterns,
            TestGate gate) {
        return new ExactTransferBroker(storage, () -> patterns, gate, SOURCE);
    }

    private static CpuPlanHandle prepare(ExactCpuLedger ledger, ExactCraftingPlan plan) {
        return assertInstanceOf(ExactCpuLedgerResult.Prepared.class, ledger.prepare(plan)).handle();
    }

    private static void assertFailure(ExactTransferBrokerResult result,
            ExactTransferBrokerResult.FailureReason reason) {
        assertEquals(reason, assertInstanceOf(ExactTransferBrokerResult.Failure.class, result).reason());
    }

    private static void assertCpuFailure(ExactCpuLedgerResult result, ExactCpuLedgerResult.FailureReason reason) {
        assertEquals(reason, assertInstanceOf(ExactCpuLedgerResult.Failure.class, result).reason());
    }

    private static void assertBrokerSnapshotEquals(ExactTransferBrokerSnapshot expected,
            ExactTransferBrokerSnapshot actual) {
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.handle(), actual.handle());
        assertEquals(expected.planId(), actual.planId());
        assertEquals(expected.reservationId(), actual.reservationId());
        assertEquals(expected.workOrderId(), actual.workOrderId());
        assertEquals(expected.leaseIdentity(), actual.leaseIdentity());
        assertEquals(expected.escrowed(), actual.escrowed());
    }

    private static PlanFixture plan(String id, Map<Integer, AEAmount> requestedDebits) {
        TreeMap<Integer, AEAmount> debits = new TreeMap<>(requestedDebits);
        int outputKey = debits.isEmpty() ? 0 : debits.lastKey() + 1;
        int keyCount = outputKey + 1;
        List<CompiledInputSpec> inputs = new ArrayList<>(debits.size());
        for (Map.Entry<Integer, AEAmount> debit : debits.entrySet()) {
            inputs.add(new CompiledInputSpec(
                    List.of(new CompiledCandidateSpec(new KeyId(debit.getKey()), debit.getValue(), Optional.empty())),
                    AEAmount.ONE, SubstitutionPolicy.EXACT));
        }
        CompiledPattern pattern = new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs,
                List.of(new CompiledOutputSpec(new KeyId(outputKey), AEAmount.ONE, true)), Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
        StorageSnapshot storage = snapshot(keyCount, debits);
        NormalizedPatternSnapshot patterns = patternSnapshot(pattern);
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(new ExactCraftRequest(new KeyId(outputKey), AEAmount.ONE), storage, patterns)).draft();
        ExactCraftingPlan plan = assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, storage, patterns)).plan();
        return new PlanFixture(plan, storage, patterns);
    }

    private static NormalizedPatternSnapshot patternSnapshot(CompiledPattern pattern) {
        CompiledPatternGraph graph = ((GraphBuildResult.Success) new CompiledPatternGraphBuilder(GRAPH_GENERATION,
                KEY_GENERATION).build(List.of(pattern))).graph();
        return new NormalizedPatternSnapshot(SERVER_GENERATION, RECIPE_REVISION, KEY_GENERATION, graph.patternsById(),
                graph, Map.of(pattern.id(), 0), 1, false, List.of());
    }

    private static StorageSnapshot snapshot(int keyCount, Map<Integer, AEAmount> values) {
        AmountVector amounts = new AmountVector(keyCount);
        values.forEach(amounts::set);
        return new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, keyCount, amounts, new long[keyCount]);
    }

    private record PlanFixture(ExactCraftingPlan plan, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns) {
    }

    private static final class TestGate implements ServerThreadGate {
        private boolean allowed = true;
        private Runnable hook;

        @Override
        public boolean isServerThread() {
            if (hook != null) {
                Runnable current = hook;
                hook = null;
                current.run();
            }
            return allowed;
        }
    }

    private static final class CountingStorage implements BrokerExactStorage {
        private final StorageSnapshot dependencySnapshot;
        private final TreeMap<KeyId, AEAmount> physical = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        private int simulateExtractCalls;
        private int modulateExtractCalls;
        private int modulateInsertCalls;

        private CountingStorage(StorageSnapshot dependencySnapshot, Map<KeyId, AEAmount> initial) {
            this.dependencySnapshot = dependencySnapshot;
            physical.putAll(initial);
        }

        @Override
        public AEAmount insert(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            if (mode == Actionable.SIMULATE) {
                return amount;
            }
            modulateInsertCalls++;
            physical.put(key, physical(key).add(amount));
            return amount;
        }

        @Override
        public AEAmount extract(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            AEAmount extracted = physical(key).min(amount);
            if (mode == Actionable.SIMULATE) {
                simulateExtractCalls++;
                return extracted;
            }
            modulateExtractCalls++;
            AEAmount remaining = physical(key).subtractExact(extracted);
            if (remaining.equals(AEAmount.ZERO)) {
                physical.remove(key);
            } else {
                physical.put(key, remaining);
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
            return dependencySnapshot;
        }

        private AEAmount physical(KeyId key) {
            return physical.getOrDefault(key, AEAmount.ZERO);
        }

        private int totalTransferCalls() {
            return simulateExtractCalls + modulateExtractCalls + modulateInsertCalls;
        }
    }
}
