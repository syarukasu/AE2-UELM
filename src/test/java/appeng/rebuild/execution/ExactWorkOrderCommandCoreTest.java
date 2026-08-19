package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
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
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.rebuild.storage.ExactStorageVisitor;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/**
 * Focused core contract for bounded command issue, executor evidence, and exact custody accounting.
 *
 * <p>
 * All mutations are exercised through the package-bound execution boundary. The storage implementation is a detached
 * exact fixture; command issue/acknowledgement itself must not call it after broker handoff.
 */
class ExactWorkOrderCommandCoreTest {
    private static final long KEY_GENERATION = 61L;
    private static final long SERVER_GENERATION = 67L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(71L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(73L);
    private static final IActionSource SOURCE = IActionSource.empty();
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();
    private static final ExactCraftingPlanValidator VALIDATOR = new ExactCraftingPlanValidator();
    private static final Comparator<KeyId> KEY_ORDER = Comparator.comparingInt(KeyId::value);
    private static final KeyId INPUT_A = new KeyId(0);
    private static final KeyId INPUT_B = new KeyId(1);
    private static final KeyId OUTPUT = new KeyId(2);
    private static final KeyId REMAINDER = new KeyId(3);

    @Test
    void normalCommandIssueRejectAcceptCompleteIsExactAndStorageIsUntouchedAfterHandoff() {
        Harness harness = normal("single", pattern("single", List.of(input(0, 1L,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true))), snapshot(2, Map.of(0, AEAmount.ONE)), request(1, 1L));
        ExactWorkOrder workOrder = harness.workOrder();
        Map<KeyId, AEAmount> custody = workOrder.snapshot().custody();
        int transferCalls = harness.storage().totalTransferCalls();

        ExactWorkCommand first = issued(workOrder, 1L);
        assertEquals(ExactWorkOrderState.COMMAND_OUTSTANDING, workOrder.snapshot().state());
        assertEquals(custody, workOrder.snapshot().custody());
        assertEquals(transferCalls, harness.storage().totalTransferCalls());

        assertInstanceOf(ExactWorkOrderTransitionResult.Rejected.class,
                workOrder.reject(new WorkCommandRejection(first)));
        assertEquals(ExactWorkOrderState.READY, workOrder.snapshot().state());
        assertEquals(custody, workOrder.snapshot().custody());
        assertTrue(workOrder.snapshot().outstandingCommand().isEmpty());

        ExactWorkCommand second = issued(workOrder, 1L);
        assertNotEquals(first.id(), second.id(), "rejected command generations must not be reused");
        assertInstanceOf(ExactWorkOrderTransitionResult.Accepted.class,
                workOrder.accept(new WorkCommandAcceptance(second)));
        ExactWorkOrderSnapshot inFlight = workOrder.snapshot();
        assertEquals(ExactWorkOrderState.IN_FLIGHT, inFlight.state());
        assertEquals(Map.of(), inFlight.custody());
        assertEquals(Optional.of(second), inFlight.inFlightCommand());
        assertEquals(custody, second.custodyInputs());

        ExactWorkOrderTransitionResult.Completed completed = assertInstanceOf(
                ExactWorkOrderTransitionResult.Completed.class,
                workOrder.complete(new WorkCommandCompletion(second, second.expectedOutputs(),
                        second.expectedRemainders())));
        assertEquals(ExactWorkOrderState.SETTLEMENT_PENDING, completed.snapshot().state());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), completed.snapshot().custody());
        assertTrue(completed.snapshot().inFlightCommand().isEmpty());
        assertTrue(completed.snapshot().completedEvidence().isEmpty());
        assertEquals(transferCalls, harness.storage().totalTransferCalls());
    }

    @Test
    void progressMovesOnlyAfterMatchingCompletionAndWholeCommandEvidenceIsAtomic() {
        Harness harness = normal("progress", pattern("progress", List.of(input(0, 1L,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true))), snapshot(2, Map.of(0, AEAmount.of(3L))), request(1, 3L));
        ExactWorkOrder workOrder = harness.workOrder();
        ExactWorkCommand command = issued(workOrder, 2L);
        ExactWorkOrderSnapshot before = workOrder.snapshot();

        assertInstanceOf(ExactWorkOrderTransitionResult.Failure.class,
                workOrder.accept(new WorkCommandAcceptance(foreign(command))));
        assertEquals(before, workOrder.snapshot(), "foreign whole-command evidence must not move custody");
        assertInstanceOf(ExactWorkOrderTransitionResult.Accepted.class,
                workOrder.accept(new WorkCommandAcceptance(command)));
        ExactWorkOrderSnapshot accepted = workOrder.snapshot();
        assertEquals(before.remainingExecutions(), accepted.remainingExecutions());
        assertEquals(before.selectionRemaining(), accepted.selectionRemaining());
        assertTrue(command.custodyInputs().entrySet().stream().allMatch(entry -> before.custody()
                .getOrDefault(entry.getKey(), AEAmount.ZERO).compareTo(entry.getValue()) >= 0));
        assertEquals(Map.of(INPUT_A, AEAmount.ONE), accepted.custody());

        ExactWorkOrderTransitionResult.Failure staleCompletion = assertInstanceOf(
                ExactWorkOrderTransitionResult.Failure.class,
                workOrder.complete(new WorkCommandCompletion(foreign(command), command.expectedOutputs(),
                        command.expectedRemainders())));
        assertEquals(ExactWorkOrderTransitionResult.Reason.IDENTITY_MISMATCH, staleCompletion.reason());
        assertEquals(accepted, workOrder.snapshot());

        assertInstanceOf(ExactWorkOrderTransitionResult.Completed.class,
                workOrder.complete(new WorkCommandCompletion(command, command.expectedOutputs(),
                        command.expectedRemainders())));
        ExactWorkOrderSnapshot after = workOrder.snapshot();
        assertEquals(AEAmount.ONE, after.remainingExecutions());
        assertEquals(List.of(AEAmount.ONE), after.selectionRemaining());
        assertEquals(ExactWorkOrderState.READY, after.state());
        assertTrue(after.completedEvidence().isEmpty());
    }

    @Test
    void duplicateAndStaleEvidenceAreRejectedWithoutMutation() {
        Harness harness = normal("stale", pattern("stale", List.of(input(0, 1L,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true))), snapshot(2, Map.of(0, AEAmount.of(2L))), request(1, 2L));
        ExactWorkOrder workOrder = harness.workOrder();
        ExactWorkCommand command = issued(workOrder, 1L);
        assertInstanceOf(ExactWorkOrderTransitionResult.Rejected.class,
                workOrder.reject(new WorkCommandRejection(command)));
        ExactWorkOrderSnapshot rejected = workOrder.snapshot();

        assertEquals(ExactWorkOrderTransitionResult.Reason.WRONG_STATE,
                assertInstanceOf(ExactWorkOrderTransitionResult.Failure.class,
                        workOrder.reject(new WorkCommandRejection(command))).reason());
        assertEquals(rejected, workOrder.snapshot());

        ExactWorkCommand next = issued(workOrder, 1L);
        assertEquals(ExactWorkOrderTransitionResult.Reason.IDENTITY_MISMATCH,
                assertInstanceOf(ExactWorkOrderTransitionResult.Failure.class,
                        workOrder.accept(new WorkCommandAcceptance(command))).reason());
        assertEquals(next, workOrder.snapshot().outstandingCommand().orElseThrow());
        assertEquals(rejected.custody(), workOrder.snapshot().custody());
    }

    @Test
    void custodyMovesToInFlightThenCreditsOutputSlotsAndRemaindersExactly() {
        CompiledPattern pattern = pattern("slots", List.of(input(0, 1L,
                List.of(candidate(0, 1L, Optional.of(remainder(3, 1L)))), SubstitutionPolicy.EXACT)),
                List.of(output(1, 2L, true), output(1, 3L, false)));
        Harness harness = normal("slots", pattern, snapshot(4, Map.of(0, AEAmount.ONE)), request(1, 5L));
        ExactWorkOrder workOrder = harness.workOrder();
        ExactWorkCommand command = issued(workOrder, 1L);
        assertEquals(Map.of(INPUT_A, AEAmount.ONE), workOrder.snapshot().custody());
        assertEquals(List.of(new ExactWorkOutput(0, new KeyId(1), AEAmount.of(2L)),
                new ExactWorkOutput(1, new KeyId(1), AEAmount.of(3L))), command.expectedOutputSlots());
        assertEquals(Map.of(new KeyId(1), AEAmount.of(5L)), command.expectedOutputs());
        assertEquals(Map.of(REMAINDER, AEAmount.ONE), command.expectedRemainders());

        workOrder.accept(new WorkCommandAcceptance(command));
        assertEquals(Map.of(), workOrder.snapshot().custody());
        assertEquals(Optional.of(command), workOrder.snapshot().inFlightCommand());
        workOrder.complete(new WorkCommandCompletion(command, command.expectedOutputs(), command.expectedRemainders()));
        ExactWorkOrderSnapshot terminal = workOrder.snapshot();
        assertEquals(ExactWorkOrderState.SETTLEMENT_PENDING, terminal.state());
        assertEquals(Map.of(new KeyId(1), AEAmount.of(5L), REMAINDER, AEAmount.ONE), terminal.custody());
    }

    @Test
    void candidateAThenBAllocationAcrossPhysicalWindowsConservesExactInputs() {
        CompiledPattern pattern = pattern("candidate-windows", List.of(input(0, 1L,
                List.of(candidate(0, 1L, Optional.empty()), candidate(1, 1L, Optional.empty())),
                SubstitutionPolicy.ALLOW_ALTERNATIVES)), List.of(output(2, 1L, true)));
        Harness harness = normal("candidate-windows", pattern, snapshot(3,
                Map.of(0, AEAmount.ONE, 1, AEAmount.ONE)), request(2, 2L));
        ExactWorkOrder workOrder = harness.workOrder();

        ExactWorkCommand first = issued(workOrder, 1L);
        assertEquals(List.of(0),
                first.plannedSelections().stream().map(PlannedInputSelection::candidateIndex).toList());
        assertEquals(Map.of(INPUT_A, AEAmount.ONE), first.custodyInputs());
        complete(workOrder, first);
        assertEquals(List.of(AEAmount.ZERO, AEAmount.ONE), workOrder.snapshot().selectionRemaining());

        ExactWorkCommand second = issued(workOrder, 1L);
        assertEquals(List.of(1),
                second.plannedSelections().stream().map(PlannedInputSelection::candidateIndex).toList());
        assertEquals(Map.of(INPUT_B, AEAmount.ONE), second.custodyInputs());
        complete(workOrder, second);
        assertEquals(ExactWorkOrderState.SETTLEMENT_PENDING, workOrder.snapshot().state());
        assertEquals(Map.of(OUTPUT, AEAmount.of(2L)), workOrder.snapshot().custody());
    }

    @Test
    void reusableSameKeySeedForcesOneExecutionWindowsAndCumulativeRemainderIsExact() {
        CompiledPattern pattern = pattern("reusable", List.of(input(0, 1L,
                List.of(candidate(0, 2L, Optional.of(remainder(0, 1L)))), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        Harness harness = normal("reusable", pattern, snapshot(2, Map.of(0, AEAmount.of(4L))), request(1, 3L));
        ExactWorkOrder workOrder = harness.workOrder();
        NormalExecutionManifest manifest = assertInstanceOf(NormalExecutionManifest.class,
                harness.plan().executionManifests().get(0));
        PlannedInputSelection sealed = manifest.execution().plannedSelections().get(0);
        assertEquals(AEAmount.of(4L), sealed.initialRequiredAmount());
        assertEquals(AEAmount.of(6L), sealed.grossConsumedAmount());

        for (int index = 0; index < 3; index++) {
            ExactWorkCommand command = issued(workOrder, Long.MAX_VALUE);
            assertEquals(1L, command.executionWindow());
            assertEquals(AEAmount.of(2L), command.custodyInputs().get(INPUT_A));
            assertEquals(Map.of(INPUT_A, AEAmount.ONE), command.expectedRemainders());
            complete(workOrder, command);
            if (index < 2) {
                assertEquals(AEAmount.of(2 - index), workOrder.snapshot().remainingExecutions());
            }
        }
        assertEquals(ExactWorkOrderState.SETTLEMENT_PENDING, workOrder.snapshot().state());
        assertEquals(Map.of(INPUT_A, AEAmount.ONE, new KeyId(1), AEAmount.of(3L)), workOrder.snapshot().custody());
    }

    @Test
    void physicalLongMaximumIsAcceptedAndOverflowPerExecutionFallsBackOrReturnsTypedUnavailable() {
        AEAmount longMaximum = AEAmount.of(Long.MAX_VALUE);
        CompiledPattern bounded = pattern("long-windows", List.of(input(0, 1L,
                List.of(candidate(0, Long.MAX_VALUE, Optional.of(remainder(3, Long.MAX_VALUE)))),
                SubstitutionPolicy.EXACT)), List.of(output(2, Long.MAX_VALUE, true)));
        AEAmount twoLongs = longMaximum.add(longMaximum);
        Harness harness = normal("long-windows", bounded, snapshot(4, Map.of(0, twoLongs)), request(2, twoLongs));
        ExactWorkOrder workOrder = harness.workOrder();
        ExactWorkCommand first = issued(workOrder, Long.MAX_VALUE);
        assertEquals(1L, first.executionWindow());
        assertEquals(Map.of(INPUT_A, longMaximum), first.custodyInputs());
        assertEquals(Map.of(OUTPUT, longMaximum), first.expectedOutputs());
        assertEquals(Map.of(REMAINDER, longMaximum), first.expectedRemainders());
        complete(workOrder, first);
        ExactWorkCommand second = issued(workOrder, Long.MAX_VALUE);
        assertEquals(1L, second.executionWindow());
        complete(workOrder, second);
        assertEquals(ExactWorkOrderState.SETTLEMENT_PENDING, workOrder.snapshot().state());
        assertEquals(Map.of(OUTPUT, twoLongs, REMAINDER, twoLongs), workOrder.snapshot().custody());

        AEAmount overLong = longMaximum.add(AEAmount.ONE);
        CompiledPattern overflowing = pattern("one-exec-overflow", List.of(), List.of(output(2, overLong, true)));
        Harness unavailable = normal("one-exec-overflow", overflowing, snapshot(3, Map.of()), request(2, overLong));
        ExactWorkOrderSnapshot before = unavailable.workOrder().snapshot();
        ExactWorkOrderCommandResult.Unavailable result = assertInstanceOf(ExactWorkOrderCommandResult.Unavailable.class,
                unavailable.workOrder().issueNext(Long.MAX_VALUE));
        assertEquals(ExactWorkOrderCommandResult.Reason.PHYSICAL_WINDOW_UNAVAILABLE, result.reason());
        assertEquals(before.custody(), result.snapshot().custody());
        assertEquals(ExactWorkOrderState.READY, result.snapshot().state());
    }

    @Test
    void hugeRemainingExactQuantityUsesOneBoundedPhysicalWindowWithoutQuantityLoop() {
        AEAmount huge = AEAmount.of(BigInteger.ONE.shiftLeft(130));
        CompiledPattern pattern = pattern("huge-window", List.of(input(0, 1L,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(2, 1L, true)));
        Harness harness = normal("huge-window", pattern, snapshot(3, Map.of(0, huge)), request(2, huge));
        ExactWorkOrder workOrder = harness.workOrder();

        ExactWorkOrderCommandResult.Issued issued = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertInstanceOf(ExactWorkOrderCommandResult.Issued.class,
                        workOrder.issueNext(Long.MAX_VALUE)));
        assertEquals(Long.MAX_VALUE, issued.command().executionWindow());
        assertEquals(huge, workOrder.snapshot().remainingExecutions(), "issue must not advance progress");
        assertEquals(ExactWorkOrderState.COMMAND_OUTSTANDING, workOrder.snapshot().state());
    }

    @Test
    void candidateBoundarySearchFindsWindowTwoWhenWindowsOneAndThreeProjectOutOfBound()
            throws ReflectiveOperationException {
        // The first candidate consumes no B, the second consumes six B, and the third consumes a different key. A
        // second input consumes that different key every execution while the pattern returns four B. Around the
        // boundary this gives projected B values M+1, M-1, M+3 for windows 1, 2, and 3 respectively. This deliberately
        // makes feasibility nonmonotonic.
        CompiledPattern pattern = pattern("nonmonotonic-boundary", List.of(
                input(0, 1L, List.of(candidate(0, 1L, Optional.empty()), candidate(1, 6L, Optional.empty()),
                        candidate(3, 1L, Optional.empty()), candidate(4, 1L, Optional.empty())),
                        SubstitutionPolicy.ALLOW_ALTERNATIVES),
                input(3, 1L, List.of(candidate(3, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 4L, false), output(2, 1L, true)));
        StorageSnapshot storage = snapshot(5, Map.of(0, AEAmount.ONE, 1, AEAmount.of(6L), 3, AEAmount.of(11L),
                4, AEAmount.of(7L)));
        Harness harness = normal("nonmonotonic-boundary", pattern, storage, request(2, 10L));
        AEAmount maximumBound = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS)
                .subtract(BigInteger.ONE));
        TreeMap<KeyId, AEAmount> projectedCustody = new TreeMap<>(KEY_ORDER);
        projectedCustody.putAll(harness.workOrder().snapshot().custody());
        projectedCustody.put(INPUT_B, maximumBound.subtractExact(AEAmount.of(3L)));
        setField(harness.workOrder(), "custody", projectedCustody);

        ExactWorkOrderCommandResult.Issued issued = assertInstanceOf(ExactWorkOrderCommandResult.Issued.class,
                harness.workOrder().issueNext(3L));
        assertEquals(2L, issued.command().executionWindow(),
                "the bounded search must inspect the feasible structural interval rather than assume global monotonicity: "
                        + harness.plan().executionManifests());
        assertEquals(ExactWorkOrderState.COMMAND_OUTSTANDING, harness.workOrder().snapshot().state());
    }

    @Test
    void generationExhaustionFailsClosedWithoutDroppingCustodyOrReusingGeneration()
            throws ReflectiveOperationException {
        Harness harness = normal("generation", pattern("generation", List.of(input(0, 1L,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true))), snapshot(2, Map.of(0, AEAmount.ONE)), request(1, 1L));
        ExactWorkOrder workOrder = harness.workOrder();
        Map<KeyId, AEAmount> custody = workOrder.snapshot().custody();
        setField(workOrder, "nextGeneration", Long.MAX_VALUE);
        ExactWorkCommand last = issued(workOrder, 1L);
        assertEquals(Long.MAX_VALUE, last.id().generation());
        assertTrue(workOrder.snapshot().generationExhausted());
        assertInstanceOf(ExactWorkOrderTransitionResult.Rejected.class,
                workOrder.reject(new WorkCommandRejection(last)));

        ExactWorkOrderCommandResult.Unavailable failed = assertInstanceOf(ExactWorkOrderCommandResult.Unavailable.class,
                workOrder.issueNext(1L));
        assertEquals(ExactWorkOrderCommandResult.Reason.COMMAND_GENERATION_EXHAUSTED, failed.reason());
        assertEquals(ExactWorkOrderState.FAIL_CLOSED, failed.snapshot().state());
        assertEquals(custody, failed.snapshot().custody());
        assertTrue(failed.snapshot().outstandingCommand().isEmpty());
        assertTrue(failed.snapshot().inFlightCommand().isEmpty());
        assertTrue(failed.snapshot().completedEvidence().isEmpty());
    }

    @Test
    void wrongThreadAndReentrantCallbacksAreTypedAndDoNotMutateTheOrder() {
        Harness wrongThread = normal("thread", pattern("thread", List.of(input(0, 1L,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true))), snapshot(2, Map.of(0, AEAmount.ONE)), request(1, 1L));
        wrongThread.gate().allowed = false;
        ExactWorkOrderSnapshot before = wrongThread.workOrder().snapshot();
        ExactWorkOrderCommandResult.Unavailable wrong = assertInstanceOf(ExactWorkOrderCommandResult.Unavailable.class,
                wrongThread.workOrder().issueNext(1L));
        assertEquals(ExactWorkOrderCommandResult.Reason.WRONG_THREAD, wrong.reason());
        assertEquals(before, wrong.snapshot());

        Harness reentrant = normal("reentrant", pattern("reentrant", List.of(input(0, 1L,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true))), snapshot(2, Map.of(0, AEAmount.ONE)), request(1, 1L));
        AtomicGate gate = reentrant.gate();
        final ExactWorkOrderCommandResult[] nested = new ExactWorkOrderCommandResult[1];
        gate.hook = () -> nested[0] = reentrant.workOrder().issueNext(1L);
        ExactWorkOrderCommandResult.Issued outer = assertInstanceOf(ExactWorkOrderCommandResult.Issued.class,
                reentrant.workOrder().issueNext(1L));
        assertEquals(ExactWorkOrderCommandResult.Reason.REENTRANT,
                assertInstanceOf(ExactWorkOrderCommandResult.Unavailable.class, nested[0]).reason());
        assertSame(outer.command(), reentrant.workOrder().snapshot().outstandingCommand().orElseThrow());
    }

    @Test
    void exactTerminalCompletionRetainsRequestPlusSurplusAndMismatchRetainsRecoveryEvidence() {
        CompiledPattern pattern = pattern("terminal", List.of(input(0, 1L,
                List.of(candidate(0, 1L, Optional.of(remainder(3, 1L)))), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        Harness exact = normal("terminal-exact", pattern, snapshot(4, Map.of(0, AEAmount.ONE)), request(1, 1L));
        ExactWorkCommand command = issued(exact.workOrder(), 1L);
        complete(exact.workOrder(), command);
        assertEquals(ExactWorkOrderState.SETTLEMENT_PENDING, exact.workOrder().snapshot().state());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE, REMAINDER, AEAmount.ONE),
                exact.workOrder().snapshot().custody());

        Harness mismatch = normal("terminal-mismatch", pattern, snapshot(4, Map.of(0, AEAmount.ONE)), request(1, 1L));
        ExactWorkCommand mismatchCommand = issued(mismatch.workOrder(), 1L);
        mismatch.workOrder().accept(new WorkCommandAcceptance(mismatchCommand));
        ExactWorkOrderTransitionResult.Failure failure = assertInstanceOf(ExactWorkOrderTransitionResult.Failure.class,
                mismatch.workOrder().complete(new WorkCommandCompletion(mismatchCommand,
                        Map.of(OUTPUT, AEAmount.of(2L)), mismatchCommand.expectedRemainders())));
        assertEquals(ExactWorkOrderTransitionResult.Reason.RESULT_MISMATCH, failure.reason());
        ExactWorkOrderSnapshot closed = mismatch.workOrder().snapshot();
        assertEquals(ExactWorkOrderState.FAIL_CLOSED, closed.state());
        assertEquals(Map.of(OUTPUT, AEAmount.of(2L), REMAINDER, AEAmount.ONE), closed.custody());
        assertEquals(Optional.of(mismatchCommand), closed.discrepancy().map(ExactWorkDiscrepancy::command));
        assertTrue(closed.completedEvidence().isEmpty());
    }

    @Test
    void exactCompletionOverflowRetainsCompletedEvidenceAndAppliedProgressIdentity()
            throws ReflectiveOperationException {
        CompiledPattern pattern = pattern("completion-overflow", List.of(), List.of(output(2, 1L, true)));
        Harness harness = normal("completion-overflow", pattern, snapshot(3, Map.of()), request(2, 1L));
        ExactWorkOrder workOrder = harness.workOrder();
        ExactWorkCommand command = issued(workOrder, 1L);
        workOrder.accept(new WorkCommandAcceptance(command));
        AEAmount maximumBound = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS)
                .subtract(BigInteger.ONE));
        TreeMap<KeyId, AEAmount> corruptedCustody = new TreeMap<>(KEY_ORDER);
        corruptedCustody.put(OUTPUT, maximumBound);
        setField(workOrder, "custody", corruptedCustody);

        ExactWorkOrderTransitionResult.Failure failure = assertInstanceOf(ExactWorkOrderTransitionResult.Failure.class,
                workOrder.complete(new WorkCommandCompletion(command, command.expectedOutputs(),
                        command.expectedRemainders())));
        assertEquals(ExactWorkOrderTransitionResult.Reason.INVARIANT_VIOLATION, failure.reason());
        ExactWorkOrderSnapshot closed = workOrder.snapshot();
        assertEquals(ExactWorkOrderState.FAIL_CLOSED, closed.state());
        assertEquals(Map.of(OUTPUT, maximumBound), closed.custody());
        assertEquals(Optional.of(command), closed.completedEvidence().map(ExactCompletedCommandEvidence::command));
        assertTrue(closed.completedEvidenceProgressApplied());
        assertEquals(1, closed.causalStepIndex());
        assertEquals(AEAmount.ZERO, closed.remainingExecutions());
        assertTrue(closed.inFlightCommand().isEmpty());
    }

    @Test
    void cycleManifestReturnsTypedUnsupportedWithoutMovingCustody() {
        CompiledPattern cycle = pattern("cycle-unsupported", List.of(input(1, 1L,
                List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 2L, true)));
        Harness harness = normal("cycle-unsupported", cycle, snapshot(2, Map.of(1, AEAmount.ONE)), request(1, 5L));
        ExactWorkOrderSnapshot before = harness.workOrder().snapshot();
        ExactWorkOrderCommandResult.CycleUnsupported result = assertInstanceOf(
                ExactWorkOrderCommandResult.CycleUnsupported.class, harness.workOrder().issueNext(Long.MAX_VALUE));
        assertEquals(before, result.snapshot());
        assertEquals(before, harness.workOrder().snapshot());
        assertEquals(0, harness.storage().totalTransferCalls() - harness.storage().brokerTransferCallsAtHandoff());
    }

    private static void complete(ExactWorkOrder workOrder, ExactWorkCommand command) {
        ExactWorkOrderTransitionResult.Accepted accepted = assertInstanceOf(
                ExactWorkOrderTransitionResult.Accepted.class,
                workOrder.accept(new WorkCommandAcceptance(command)));
        assertEquals(ExactWorkOrderState.IN_FLIGHT, accepted.snapshot().state());
        ExactWorkOrderTransitionResult result = workOrder.complete(new WorkCommandCompletion(command,
                command.expectedOutputs(), command.expectedRemainders()));
        assertInstanceOf(ExactWorkOrderTransitionResult.Completed.class, result, () -> result.toString());
    }

    private static ExactWorkCommand issued(ExactWorkOrder workOrder, long requestedWindow) {
        return assertInstanceOf(ExactWorkOrderCommandResult.Issued.class, workOrder.issueNext(requestedWindow))
                .command();
    }

    private static ExactWorkCommand foreign(ExactWorkCommand command) {
        WorkCommandId id = new WorkCommandId(command.id().planId(), command.id().leaseIdentity(),
                command.id().workOrderId(), command.id().generation() + 100L);
        return new ExactWorkCommand(id, command.handle(), command.reservationId(), command.batchId(), command.pattern(),
                command.executionWindow(), command.plannedSelections(), command.custodyInputs(),
                command.expectedOutputSlots(), command.expectedOutputs(), command.expectedRemainders());
    }

    private static void setField(ExactWorkOrder workOrder, String name, Object value)
            throws ReflectiveOperationException {
        Field field = ExactWorkOrder.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(workOrder, value);
    }

    private static Harness normal(String id, CompiledPattern pattern, StorageSnapshot storage,
            ExactCraftRequest request) {
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(pattern), Map.of(pattern.id(), 0));
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(request, storage, patterns)).draft();
        ExactCraftingPlan plan = assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, storage, patterns)).plan();
        AtomicGate gate = new AtomicGate();
        CountingStorage physical = new CountingStorage(storage, plan.initialStorageDebits());
        ExactTransferBroker broker = new ExactTransferBroker(physical, () -> patterns, gate, SOURCE);
        ExactCpuLedger ledger = new ExactCpuLedger();
        CpuPlanHandle handle = assertInstanceOf(ExactCpuLedgerResult.Prepared.class, ledger.prepare(plan)).handle();
        assertInstanceOf(ExactTransferBrokerResult.Reserved.class, broker.reserve(ledger, handle));
        ExactWorkOrder workOrder = assertInstanceOf(ExactTransferBrokerResult.Started.class,
                broker.startWorkOrder(ledger, handle)).workOrder();
        physical.markHandoff();
        return new Harness(id, plan, patterns, physical, gate, workOrder);
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

    private static ExactCraftRequest request(int output, long amount) {
        return new ExactCraftRequest(new KeyId(output), AEAmount.of(amount));
    }

    private static ExactCraftRequest request(int output, AEAmount amount) {
        return new ExactCraftRequest(new KeyId(output), amount);
    }

    private static CompiledPattern pattern(String id, List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs) {
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs, outputs, Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
    }

    private static CompiledInputSpec input(int key, long multiplier, List<CompiledCandidateSpec> candidates,
            SubstitutionPolicy policy) {
        return new CompiledInputSpec(candidates, AEAmount.of(multiplier), policy);
    }

    private static CompiledCandidateSpec candidate(int key, long amount,
            Optional<CompiledRemainderSpec> remainder) {
        return new CompiledCandidateSpec(new KeyId(key), AEAmount.of(amount), remainder);
    }

    private static CompiledRemainderSpec remainder(int key, long amount) {
        return new CompiledRemainderSpec(new KeyId(key), AEAmount.of(amount));
    }

    private static CompiledOutputSpec output(int key, long amount, boolean primary) {
        return new CompiledOutputSpec(new KeyId(key), AEAmount.of(amount), primary);
    }

    private static CompiledOutputSpec output(int key, AEAmount amount, boolean primary) {
        return new CompiledOutputSpec(new KeyId(key), amount, primary);
    }

    private record Harness(String id, ExactCraftingPlan plan, NormalizedPatternSnapshot patterns,
            CountingStorage storage, AtomicGate gate, ExactWorkOrder workOrder) {
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
        private final StorageSnapshot dependencySnapshot;
        private final TreeMap<KeyId, AEAmount> physical = new TreeMap<>(KEY_ORDER);
        private int simulateExtractCalls;
        private int modulateExtractCalls;
        private int modulateInsertCalls;
        private int handoffCalls;

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
            physical.put(key, amount(key).add(amount));
            return amount;
        }

        @Override
        public AEAmount extract(KeyId key, AEAmount amount, Actionable mode, IActionSource source) {
            AEAmount extracted = amount(key).min(amount);
            if (mode == Actionable.SIMULATE) {
                simulateExtractCalls++;
                return extracted;
            }
            modulateExtractCalls++;
            AEAmount remaining = amount(key).subtractExact(extracted);
            if (remaining.equals(AEAmount.ZERO)) {
                physical.remove(key);
            } else {
                physical.put(key, remaining);
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
            return dependencySnapshot;
        }

        private int totalTransferCalls() {
            return simulateExtractCalls + modulateExtractCalls + modulateInsertCalls;
        }

        private void markHandoff() {
            handoffCalls = totalTransferCalls();
        }

        private int brokerTransferCallsAtHandoff() {
            return handoffCalls;
        }
    }
}
