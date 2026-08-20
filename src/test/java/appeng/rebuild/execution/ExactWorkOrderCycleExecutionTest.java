package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

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
import appeng.rebuild.pattern.CompiledRemainderSpec;
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
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.rebuild.storage.ExactStorageVisitor;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/**
 * T007B2 exact execution tests. These tests intentionally exercise only the package-bound work-order protocol; no
 * Minecraft bootstrap or live world is involved.
 */
class ExactWorkOrderCycleExecutionTest {
    private static final long KEY_GENERATION = 61L;
    private static final long SERVER_GENERATION = 67L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(71L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(73L);
    private static final IActionSource SOURCE = IActionSource.empty();
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();
    private static final ExactCraftingPlanValidator VALIDATOR = new ExactCraftingPlanValidator();
    private static final Comparator<KeyId> KEY_ORDER = Comparator.comparingInt(KeyId::value);
    private static final KeyId SEED = new KeyId(1);
    private static final KeyId LINK = new KeyId(2);
    private static final KeyId EXTERNAL = new KeyId(3);

    @Test
    void oneMemberProductiveCycleRunsInExactOrderAndSettlesExactTerminalCustody() {
        Harness harness = cycle(List.of(selfCycle("cycle-self", 1, 2L)), Map.of("cycle-self", 10),
                snapshot(2, Map.of(1, AEAmount.ONE)), request(1, 5L));
        ExactWorkOrder order = harness.workOrder();

        assertEquals(AEAmount.of(5L), order.snapshot().cycleRemainingRepetitions());
        assertEquals(0, order.snapshot().cycleMemberIndex());
        assertEquals(Map.of(SEED, AEAmount.ONE), order.snapshot().custody());

        for (int turn = 0; turn < 5; turn++) {
            ExactWorkCommand command = issued(order, Long.MAX_VALUE);
            assertTrue(command.location().isCycle());
            assertEquals(0, command.location().cycleMemberIndex());
            assertEquals(AEAmount.of(5L - turn), command.location().remainingRepetitions());
            assertEquals(AEAmount.ONE, command.location().remainingMemberExecutions());
            complete(order, command);
            if (turn < 4) {
                assertEquals(AEAmount.of(4L - turn), order.snapshot().cycleRemainingRepetitions());
                assertEquals(0, order.snapshot().cycleMemberIndex());
            }
        }

        ExactWorkOrderSnapshot terminal = order.snapshot();
        assertEquals(ExactWorkOrderState.SETTLEMENT_PENDING, terminal.state());
        assertEquals(1, terminal.causalStepIndex());
        assertEquals(-1, terminal.cycleMemberIndex());
        assertEquals(AEAmount.ZERO, terminal.cycleRemainingRepetitions());
        assertEquals(Map.of(SEED, AEAmount.of(6L)), terminal.custody());
        assertTrue(terminal.inFlightCommand().isEmpty());
        assertTrue(terminal.completedEvidence().isEmpty());
    }

    @Test
    void twoMemberRingDoesNotStartNextSeedMemberUntilClosingMemberRestoresSeed() {
        CompiledPattern first = pattern("ring-a", List.of(input(1, AEAmount.ONE,
                List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(2, 3L, true)));
        CompiledPattern second = pattern("ring-b", List.of(input(2, AEAmount.ONE,
                List.of(candidate(2, 2L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        Harness harness = cycle(List.of(first, second), Map.of("ring-a", 10, "ring-b", 10),
                snapshot(3, Map.of(1, AEAmount.of(2L))), request(1, 4L));
        ExactWorkOrder order = harness.workOrder();
        assertEquals(Map.of(SEED, AEAmount.of(2L)), order.snapshot().custody());
        assertEquals(0L, order.snapshot().nextGeneration());

        for (int turn = 0; turn < 4; turn++) {
            ExactWorkCommand producer = issued(order, Long.MAX_VALUE);
            assertEquals(0, producer.location().cycleMemberIndex());
            assertEquals(2L, producer.executionWindow(), "producer must cover its complete turn: " + producer);
            assertEquals(Map.of(SEED, AEAmount.of(2L)), producer.custodyInputs(),
                    "producer must consume the sealed seed: " + producer);
            assertEquals(Map.of(LINK, AEAmount.of(6L)), producer.expectedOutputs(),
                    "producer must restore the full link: " + producer);
            assertEquals(Map.of(), producer.expectedRemainders());
            assertEquals(AEAmount.of(4L - turn), producer.location().remainingRepetitions());
            assertEquals(AEAmount.of(2L), producer.location().remainingMemberExecutions());
            ExactWorkOrderTransitionResult.Accepted producerAccepted = assertInstanceOf(
                    ExactWorkOrderTransitionResult.Accepted.class,
                    order.accept(new WorkCommandAcceptance(producer)));
            Map<KeyId, AEAmount> accumulatedSeed = turn == 0 ? Map.of() : Map.of(SEED, AEAmount.of(turn));
            assertEquals(accumulatedSeed, producerAccepted.snapshot().custody(),
                    "accept must retain only the accumulated seed gain: " + producerAccepted.snapshot());
            ExactWorkOrderTransitionResult.Completed producerCompleted = assertInstanceOf(
                    ExactWorkOrderTransitionResult.Completed.class,
                    order.complete(new WorkCommandCompletion(producer, producer.expectedOutputs(),
                            producer.expectedRemainders())));
            Map<KeyId, AEAmount> producerCustody = new TreeMap<>(KEY_ORDER);
            producerCustody.putAll(accumulatedSeed);
            producerCustody.put(LINK, AEAmount.of(6L));
            assertEquals(producerCustody, producerCompleted.snapshot().custody(),
                    "producer completion must retain gain and outgoing link: " + producerCompleted.snapshot());
            ExactWorkOrderSnapshot betweenMembers = order.snapshot();
            assertEquals(1, betweenMembers.cycleMemberIndex());
            assertEquals(AEAmount.of(4L - turn), betweenMembers.cycleRemainingRepetitions());
            assertEquals(AEAmount.of(turn), betweenMembers.custody().getOrDefault(SEED, AEAmount.ZERO),
                    "only prior-turn seed gain may remain before the closer: " + betweenMembers);

            ExactWorkCommand closer = issued(order, Long.MAX_VALUE);
            assertEquals(1, closer.location().cycleMemberIndex());
            assertEquals(AEAmount.of(3L), closer.location().remainingMemberExecutions());
            complete(order, closer);
            if (turn < 3) {
                assertEquals(0, order.snapshot().cycleMemberIndex());
                assertEquals(AEAmount.of(3L - turn), order.snapshot().cycleRemainingRepetitions());
                assertTrue(order.snapshot().custody().getOrDefault(SEED, AEAmount.ZERO).compareTo(AEAmount.ZERO) > 0);
            }
        }

        assertEquals(ExactWorkOrderState.SETTLEMENT_PENDING, order.snapshot().state());
        assertEquals(Map.of(SEED, AEAmount.of(6L)), order.snapshot().custody());
    }

    @Test
    void hugeExactRepetitionCursorIsBoundedAndNeverExpandedIntoAQuantityLoop() {
        AEAmount demand = AEAmount.of(BigInteger.TEN.pow(1000));
        Harness harness = cycle(List.of(selfCycle("cycle-huge", 1, 2L)), Map.of("cycle-huge", 10),
                snapshot(2, Map.of(1, AEAmount.ONE)), request(1, demand));
        ExactWorkOrder order = harness.workOrder();
        ExactWorkOrderSnapshot before = order.snapshot();

        ExactWorkOrderCommandResult.Issued issued = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertInstanceOf(ExactWorkOrderCommandResult.Issued.class,
                        order.issueNext(Long.MAX_VALUE)));
        assertEquals(demand, before.cycleRemainingRepetitions());
        assertEquals(demand, order.snapshot().cycleRemainingRepetitions(), "issue cannot advance exact progress");
        assertEquals(0L, issued.command().id().generation());
        order.reject(new WorkCommandRejection(issued.command()));
        assertEquals(demand, order.snapshot().cycleRemainingRepetitions());
    }

    @Test
    void cycleExternalCandidatesRemainMixedPerTurnAndAreAllocatedExactly() {
        CompiledPattern sourceA = pattern("source-a", List.of(input(4, AEAmount.ONE,
                List.of(candidate(4, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(2, 2L, true)));
        CompiledPattern sourceB = pattern("source-b", List.of(input(5, AEAmount.ONE,
                List.of(candidate(5, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(3, 2L, true)));
        CompiledPattern cyclePattern = pattern("cycle-mixed", List.of(
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT),
                input(2, AEAmount.of(2L), List.of(candidate(2, 1L, Optional.empty()),
                        candidate(3, 1L, Optional.empty())), SubstitutionPolicy.ALLOW_ALTERNATIVES)),
                List.of(output(1, 2L, true)));
        CompiledPattern outer = pattern("outer", List.of(input(1, AEAmount.ONE,
                List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(9, 1L, true)));
        Harness harness = cycle(List.of(outer, cyclePattern, sourceA, sourceB),
                Map.of("outer", 10, "cycle-mixed", 10, "source-a", 0, "source-b", 0),
                snapshot(10, Map.of(1, AEAmount.ONE, 4, AEAmount.ONE, 5, AEAmount.ONE)), request(9, 3L));
        ExactWorkOrder order = harness.workOrder();

        while (!(harness.plan().executionManifests()
                .get(order.snapshot().causalStepIndex()) instanceof CycleExecutionManifest)) {
            ExactWorkCommand normal = issued(order, Long.MAX_VALUE);
            assertFalse(normal.location().isCycle());
            complete(order, normal);
        }

        while (order.snapshot().cycleMemberIndex() >= 0) {
            ExactWorkCommand cycleCommand = issued(order, Long.MAX_VALUE);
            assertEquals(0, cycleCommand.location().cycleMemberIndex());
            assertEquals(List.of(0, 1), cycleCommand.plannedSelections().stream()
                    .filter(selection -> selection.inputIndex() == 1)
                    .map(PlannedInputSelection::candidateIndex).toList());
            assertEquals(Map.of(new KeyId(1), AEAmount.ONE, new KeyId(2), AEAmount.ONE, new KeyId(3), AEAmount.ONE),
                    cycleCommand.custodyInputs());
            complete(order, cycleCommand);
        }
        assertEquals(3, order.snapshot().causalStepIndex(), "cycle is followed by the outer causal consumer");
        assertTrue(order.snapshot().cycleMemberIndex() < 0);
    }

    @Test
    void sameKeyReusableExternalRemainderForcesOnePhysicalExecutionPerWindowAndConservesIt() {
        CompiledPattern first = pattern("reusable-ring-a", List.of(
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT),
                input(3, AEAmount.ONE, List.of(candidate(3, 2L, Optional.of(remainder(3, 1L)))),
                        SubstitutionPolicy.EXACT)),
                List.of(output(2, 3L, true)));
        CompiledPattern second = pattern("reusable-ring-b", List.of(input(2, AEAmount.ONE,
                List.of(candidate(2, 2L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        Harness harness = cycle(List.of(first, second), Map.of("reusable-ring-a", 10, "reusable-ring-b", 10),
                snapshot(4, Map.of(1, AEAmount.of(2L), 3, AEAmount.of(12L))), request(1, 4L));
        ExactWorkOrder order = harness.workOrder();

        for (int turn = 0; turn < 4; turn++) {
            while (order.snapshot().cycleMemberIndex() == 0) {
                ExactWorkCommand producer = issued(order, Long.MAX_VALUE);
                assertEquals(1L, producer.executionWindow(), "reusable same-key input must use one physical window");
                assertEquals(AEAmount.of(2L), producer.custodyInputs().get(EXTERNAL),
                        "command=" + producer + " plan=" + harness.plan().executionManifests());
                assertEquals(Map.of(EXTERNAL, AEAmount.ONE), producer.expectedRemainders());
                complete(order, producer);
            }
            ExactWorkCommand closer = issued(order, Long.MAX_VALUE);
            assertEquals(1, closer.location().cycleMemberIndex());
            complete(order, closer);
        }
        assertEquals(ExactWorkOrderState.SETTLEMENT_PENDING, order.snapshot().state());
        assertEquals(Map.of(SEED, AEAmount.of(6L), EXTERNAL, AEAmount.of(4L)), order.snapshot().custody());
    }

    @Test
    void staleDuplicateAndWrongMemberEvidenceCannotMutateCycleProgress() {
        CompiledPattern first = pattern("identity-ring-a", List.of(input(1, AEAmount.ONE,
                List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(2, 3L, true)));
        CompiledPattern second = pattern("identity-ring-b", List.of(input(2, AEAmount.ONE,
                List.of(candidate(2, 2L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        Harness harness = cycle(List.of(first, second), Map.of("identity-ring-a", 10, "identity-ring-b", 10),
                snapshot(3, Map.of(1, AEAmount.of(2L))), request(1, 4L));
        ExactWorkOrder order = harness.workOrder();
        ExactWorkCommand command = issued(order, Long.MAX_VALUE);
        ExactWorkOrderSnapshot before = order.snapshot();
        ExactWorkCommand wrongMember = new ExactWorkCommand(command.id(), command.handle(), command.reservationId(),
                command.batchId(), ExactWorkCommandLocation.cycle(command.location().causalStepIndex(), 1,
                        command.location().remainingRepetitions(), command.location().remainingMemberExecutions()),
                command.pattern(), command.executionWindow(), command.plannedSelections(), command.custodyInputs(),
                command.expectedOutputSlots(), command.expectedOutputs(), command.expectedRemainders());

        ExactWorkOrderTransitionResult.Failure wrong = assertInstanceOf(ExactWorkOrderTransitionResult.Failure.class,
                order.accept(new WorkCommandAcceptance(wrongMember)));
        assertEquals(ExactWorkOrderTransitionResult.Reason.IDENTITY_MISMATCH, wrong.reason());
        assertEquals(before, order.snapshot());
        order.accept(new WorkCommandAcceptance(command));
        ExactWorkOrderSnapshot accepted = order.snapshot();
        ExactWorkOrderTransitionResult.Failure stale = assertInstanceOf(ExactWorkOrderTransitionResult.Failure.class,
                order.complete(new WorkCommandCompletion(wrongMember, wrongMember.expectedOutputs(),
                        wrongMember.expectedRemainders())));
        assertEquals(ExactWorkOrderTransitionResult.Reason.IDENTITY_MISMATCH, stale.reason());
        assertEquals(accepted, order.snapshot());
        ExactWorkOrderTransitionResult.Completed completed = assertInstanceOf(
                ExactWorkOrderTransitionResult.Completed.class,
                order.complete(new WorkCommandCompletion(command, command.expectedOutputs(),
                        command.expectedRemainders())));
        assertFalse(completed.snapshot().inFlightCommand().isPresent());
        ExactWorkOrderSnapshot after = order.snapshot();
        ExactWorkOrderTransitionResult.Failure duplicate = assertInstanceOf(
                ExactWorkOrderTransitionResult.Failure.class,
                order.complete(
                        new WorkCommandCompletion(command, command.expectedOutputs(), command.expectedRemainders())));
        assertEquals(ExactWorkOrderTransitionResult.Reason.WRONG_STATE, duplicate.reason());
        assertEquals(after, order.snapshot());
    }

    @Test
    void linkOrMemberOutputShortageIsDiscrepancyAndNeverFakeProgress() {
        Harness harness = cycle(List.of(selfCycle("cycle-shortage", 1, 2L)), Map.of("cycle-shortage", 10),
                snapshot(2, Map.of(1, AEAmount.ONE)), request(1, 1L));
        ExactWorkOrder order = harness.workOrder();
        ExactWorkCommand command = issued(order, Long.MAX_VALUE);
        order.accept(new WorkCommandAcceptance(command));
        ExactWorkOrderSnapshot accepted = order.snapshot();

        ExactWorkOrderTransitionResult.Failure result = assertInstanceOf(ExactWorkOrderTransitionResult.Failure.class,
                order.complete(new WorkCommandCompletion(command, Map.of(), Map.of())));
        assertEquals(ExactWorkOrderTransitionResult.Reason.RESULT_MISMATCH, result.reason());
        ExactWorkOrderSnapshot closed = order.snapshot();
        assertEquals(ExactWorkOrderState.FAIL_CLOSED, closed.state());
        assertEquals(accepted.cycleRemainingRepetitions(), closed.cycleRemainingRepetitions());
        assertEquals(accepted.cycleMemberIndex(), closed.cycleMemberIndex());
        assertTrue(closed.inFlightCommand().isEmpty());
        assertTrue(closed.discrepancy().isPresent());
        assertEquals(Map.of(), closed.custody());
    }

    @Test
    void LongMaximumCycleOutputIsAcceptedWithoutNarrowing() {
        AEAmount max = AEAmount.of(Long.MAX_VALUE);
        AEAmount demand = AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        Harness harness = cycle(List.of(selfCycle("cycle-long-max", 1, max)), Map.of("cycle-long-max", 10),
                snapshot(2, Map.of(1, AEAmount.ONE)), request(1, demand));
        ExactWorkOrder order = harness.workOrder();
        int commands = 0;
        while (order.snapshot().state() == ExactWorkOrderState.READY) {
            ExactWorkCommand command = issued(order, Long.MAX_VALUE);
            assertEquals(1L, command.executionWindow());
            assertEquals(Map.of(SEED, max), command.expectedOutputs());
            complete(order, command);
            assertTrue(++commands <= 2, "Long.MAX cycle must finish in bounded turns");
        }
        assertEquals(2, commands);
        assertEquals(ExactWorkOrderState.SETTLEMENT_PENDING, order.snapshot().state());
        assertEquals(AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1).subtract(BigInteger.ONE)),
                order.snapshot().custody().get(SEED));
    }

    @Test
    void cycleSnapshotRetainsGenerationAndExactProgressAcrossIssueAcceptAndCompletion() {
        Harness harness = cycle(List.of(selfCycle("cycle-snapshot", 1, 2L)), Map.of("cycle-snapshot", 10),
                snapshot(2, Map.of(1, AEAmount.ONE)), request(1, 5L));
        ExactWorkOrder order = harness.workOrder();
        ExactWorkOrderSnapshot initial = order.snapshot();
        assertEquals(0L, initial.nextGeneration());
        ExactWorkCommand command = issued(order, Long.MAX_VALUE);
        ExactWorkOrderSnapshot outstanding = order.snapshot();
        assertEquals(1L, outstanding.nextGeneration());
        assertEquals(Optional.of(command), outstanding.outstandingCommand());
        assertEquals(command.location().remainingRepetitions(), outstanding.cycleRemainingRepetitions());
        order.accept(new WorkCommandAcceptance(command));
        ExactWorkOrderSnapshot inFlight = order.snapshot();
        assertEquals(1L, inFlight.nextGeneration());
        assertEquals(Optional.of(command), inFlight.inFlightCommand());
        assertEquals(outstanding.cycleRemainingRepetitions(), inFlight.cycleRemainingRepetitions());
        ExactWorkOrderTransitionResult.Completed completed = assertInstanceOf(
                ExactWorkOrderTransitionResult.Completed.class,
                order.complete(new WorkCommandCompletion(command, command.expectedOutputs(),
                        command.expectedRemainders())));
        assertFalse(completed.snapshot().inFlightCommand().isPresent());
        ExactWorkOrderSnapshot after = order.snapshot();
        assertEquals(1L, after.nextGeneration());
        assertEquals(AEAmount.of(4L), after.cycleRemainingRepetitions());
        assertEquals(0, after.cycleMemberIndex(), "one-member cycle retains its member until all turns finish");
    }

    @Test
    void wrongThreadAndReentrantCycleIssueAreTypedAndMutationSafe() {
        Harness wrongThread = cycle(List.of(selfCycle("cycle-thread", 1, 2L)), Map.of("cycle-thread", 10),
                snapshot(2, Map.of(1, AEAmount.ONE)), request(1, 1L));
        ExactWorkOrderSnapshot before = wrongThread.workOrder().snapshot();
        wrongThread.gate().allowed = false;
        ExactWorkOrderCommandResult.Unavailable wrong = assertInstanceOf(ExactWorkOrderCommandResult.Unavailable.class,
                wrongThread.workOrder().issueNext(1L));
        assertEquals(ExactWorkOrderCommandResult.Reason.WRONG_THREAD, wrong.reason());
        assertEquals(before, wrongThread.workOrder().snapshot());

        Harness reentrant = cycle(List.of(selfCycle("cycle-reentrant", 1, 2L)), Map.of("cycle-reentrant", 10),
                snapshot(2, Map.of(1, AEAmount.ONE)), request(1, 1L));
        AtomicGate gate = reentrant.gate();
        ExactWorkOrderCommandResult[] nested = new ExactWorkOrderCommandResult[1];
        gate.hook = () -> nested[0] = reentrant.workOrder().issueNext(1L);
        ExactWorkOrderCommandResult.Issued outer = assertInstanceOf(ExactWorkOrderCommandResult.Issued.class,
                reentrant.workOrder().issueNext(1L));
        ExactWorkOrderCommandResult.Unavailable nestedResult = assertInstanceOf(
                ExactWorkOrderCommandResult.Unavailable.class, nested[0]);
        assertEquals(ExactWorkOrderCommandResult.Reason.REENTRANT, nestedResult.reason());
        assertEquals(outer.command(), reentrant.workOrder().snapshot().outstandingCommand().orElseThrow());
    }

    private static void complete(ExactWorkOrder order, ExactWorkCommand command) {
        ExactWorkOrderTransitionResult.Accepted accepted = assertInstanceOf(
                ExactWorkOrderTransitionResult.Accepted.class,
                order.accept(new WorkCommandAcceptance(command)));
        assertEquals(ExactWorkOrderState.IN_FLIGHT, accepted.snapshot().state());
        ExactWorkOrderTransitionResult.Completed completed = assertInstanceOf(
                ExactWorkOrderTransitionResult.Completed.class,
                order.complete(new WorkCommandCompletion(command, command.expectedOutputs(),
                        command.expectedRemainders())));
        assertFalse(completed.snapshot().inFlightCommand().isPresent());
    }

    private static ExactWorkCommand issued(ExactWorkOrder order, long requestedWindow) {
        return assertInstanceOf(ExactWorkOrderCommandResult.Issued.class, order.issueNext(requestedWindow)).command();
    }

    private static Harness cycle(List<CompiledPattern> patterns, Map<String, Integer> priorities,
            StorageSnapshot storage, ExactCraftRequest request) {
        NormalizedPatternSnapshot snapshot = patternSnapshot(patterns,
                priorities.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(entry -> new PatternId(entry.getKey()),
                                Map.Entry::getValue)));
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(request, storage, snapshot)).draft();
        ExactPlanValidationResult validation = VALIDATOR.validate(draft, storage, snapshot);
        ExactCraftingPlan plan = assertInstanceOf(ExactPlanValidationResult.Success.class, validation,
                "validation=" + validation + " steps=" + draft.causalSteps() + " executions="
                        + draft.patternExecutions() + " storage=" + draft.storageConsumed() + " surplus="
                        + draft.surplus())
                .plan();
        CountingStorage physical = new CountingStorage(storage, plan.initialStorageDebits());
        AtomicGate gate = new AtomicGate();
        ExactTransferBroker broker = new ExactTransferBroker(physical, () -> snapshot, gate, SOURCE);
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = assertInstanceOf(ExactCpuLedgerResult.Prepared.class, ledger.prepare(plan)).handle();
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactWorkOrder order = assertInstanceOf(ExactTransferBrokerResult.Started.class,
                broker.startWorkOrder(ledger, handle)).workOrder();
        physical.markHandoff();
        return new Harness(plan, physical, gate, order);
    }

    private static NormalizedPatternSnapshot patternSnapshot(List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities) {
        CompiledPatternGraph graph = assertInstanceOf(GraphBuildResult.Success.class,
                new CompiledPatternGraphBuilder(GRAPH_GENERATION, KEY_GENERATION).build(patterns)).graph();
        return new NormalizedPatternSnapshot(SERVER_GENERATION, RECIPE_REVISION, KEY_GENERATION, graph.patternsById(),
                graph, priorities, patterns.size(), false, List.of());
    }

    private static StorageSnapshot snapshot(int keyCount, Map<Integer, AEAmount> values) {
        AmountVector amounts = new AmountVector(keyCount);
        long[] revisions = new long[keyCount];
        values.forEach(amounts::set);
        return new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, keyCount, amounts, revisions);
    }

    private static ExactCraftRequest request(int key, long amount) {
        return request(key, AEAmount.of(amount));
    }

    private static ExactCraftRequest request(int key, AEAmount amount) {
        return new ExactCraftRequest(new KeyId(key), amount);
    }

    private static CompiledPattern selfCycle(String id, int key, long amount) {
        return selfCycle(id, key, AEAmount.of(amount));
    }

    private static CompiledPattern selfCycle(String id, int key, AEAmount amount) {
        return pattern(id, List.of(input(key, AEAmount.ONE,
                List.of(candidate(key, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(key, amount, true)));
    }

    private static CompiledPattern pattern(String id, List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs) {
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs, outputs, Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
    }

    private static CompiledInputSpec input(int key, AEAmount multiplier, List<CompiledCandidateSpec> candidates,
            SubstitutionPolicy policy) {
        return new CompiledInputSpec(candidates, multiplier, policy);
    }

    private static CompiledCandidateSpec candidate(int key, long amount,
            Optional<CompiledRemainderSpec> remainder) {
        return new CompiledCandidateSpec(new KeyId(key), AEAmount.of(amount), remainder);
    }

    private static CompiledRemainderSpec remainder(int key, long amount) {
        return new CompiledRemainderSpec(new KeyId(key), AEAmount.of(amount));
    }

    private static CompiledOutputSpec output(int key, long amount, boolean primary) {
        return output(key, AEAmount.of(amount), primary);
    }

    private static CompiledOutputSpec output(int key, AEAmount amount, boolean primary) {
        return new CompiledOutputSpec(new KeyId(key), amount, primary);
    }

    private record Harness(ExactCraftingPlan plan, CountingStorage storage, AtomicGate gate, ExactWorkOrder workOrder) {
    }

    private static final class AtomicGate implements ServerThreadGate {
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
        private final StorageSnapshot dependencies;
        private final TreeMap<KeyId, AEAmount> physical = new TreeMap<>(KEY_ORDER);
        private int callsAtHandoff;

        private CountingStorage(StorageSnapshot dependencies, Map<KeyId, AEAmount> initial) {
            this.dependencies = dependencies;
            physical.putAll(initial);
        }

        @Override
        public AEAmount insert(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            if (mode == Actionable.SIMULATE) {
                return amount;
            }
            physical.put(key, amount(key).add(amount));
            return amount;
        }

        @Override
        public AEAmount extract(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            AEAmount extracted = amount(key).min(amount);
            if (mode == Actionable.MODULATE) {
                AEAmount remainder = amount(key).subtractExact(extracted);
                if (remainder.equals(AEAmount.ZERO)) {
                    physical.remove(key);
                } else {
                    physical.put(key, remainder);
                }
            }
            return extracted;
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
            return dependencies;
        }

        private void markHandoff() {
            callsAtHandoff = 0;
        }
    }
}
