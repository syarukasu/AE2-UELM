package appeng.rebuild.planner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.rebuild.pattern.SubstitutionPolicy;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Deterministic contract tests for the bounded T005B1 exact planner foundation. */
class ExactCraftPlannerContractTest {
    private static final long KEY_GENERATION = 7L;
    private static final long SERVER_GENERATION = 9L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(3L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(5L);
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();

    @Test
    void simpleNoInputRecipeUsesExactCeilDivAndReportsOverproduction() {
        CompiledPattern pattern = noInput("simple", 1, 3L);

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of()), List.of(pattern), Map.of(pattern.id(), 0),
                request(1, 5L));

        assertEquals(Map.of(pattern.id(), AEAmount.of(2L)), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), draft.surplus());
        assertEquals(Map.of(), draft.storageConsumed());
        assertEquals(AEAmount.of(2L), draft.batches().get(0).executions());
        assertTrue(draft.batches().get(0).inputs().isEmpty());
    }

    @Test
    void zeroRequestIsRejectedBeforePlanning() {
        assertThrows(IllegalArgumentException.class, () -> new ExactCraftRequest(new KeyId(1), AEAmount.ZERO));
    }

    @Test
    void rootPreexistingTargetIsIgnoredAndNotConsumedOrRecorded() {
        CompiledPattern pattern = noInput("root", 1, 1L);
        StorageSnapshot storage = snapshot(2, Map.of(1, AEAmount.of(99L)));

        ExactCraftPlanDraft draft = success(storage, List.of(pattern), Map.of(pattern.id(), 0), request(1, 1L));

        assertEquals(Map.of(), draft.storageConsumed());
        assertEquals(Map.of(), draft.dependencies().storageKeyRevisions());
        assertEquals(Map.of(pattern.id(), AEAmount.ONE), draft.patternExecutions());
    }

    @ParameterizedTest(name = "exact ingredient amount {0}")
    @MethodSource("exactIngredientAmounts")
    void ingredientStorageSupportsExactBoundariesWithoutQuantityLoops(BigInteger quantity) {
        CompiledPattern pattern = oneInput("ingredient", 0, 1, AEAmount.ONE, 1, Optional.empty());
        AEAmount amount = AEAmount.of(quantity);

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of(1, amount)), List.of(pattern),
                Map.of(pattern.id(), 0), request(0, amount));

        assertEquals(Map.of(pattern.id(), amount), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(1), amount), draft.storageConsumed());
        assertEquals(Map.of(), draft.surplus());
        assertEquals(amount, draft.batches().get(0).inputs().get(0).grossConsumedAmount());
    }

    @Test
    void zeroIngredientIsInsufficientWithinTheBoundedStrategy() {
        CompiledPattern pattern = oneInput("zero-ingredient", 0, 1, AEAmount.ONE, 1, Optional.empty());

        ExactCraftPlanResult.Failure failure = failure(snapshot(2, Map.of()), List.of(pattern),
                Map.of(pattern.id(), 0), request(0, 1L));

        assertEquals(ExactCraftPlanResult.FailureReason.UNSATISFIABLE_WITHIN_STRATEGY, failure.reason());
        // Failure results intentionally do not expose a draft; the successful alternative contract below verifies
        // that zero and insufficient reads are retained when the search eventually finds another branch.
    }

    @Test
    void allOutputsAndCandidateRemainderAreCreditedOnlyAfterInputs() {
        CompiledPattern pattern = pattern("outputs", List.of(input(1, AEAmount.of(2L),
                List.of(candidate(0, AEAmount.ONE, Optional.of(remainder(3, 1L)))), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true), output(2, 3L, false)));

        ExactCraftPlanDraft draft = success(snapshot(4, Map.of(0, AEAmount.of(2L))), List.of(pattern),
                Map.of(pattern.id(), 0), request(1, 1L));

        assertEquals(Map.of(new KeyId(0), AEAmount.of(2L)), draft.storageConsumed());
        assertEquals(Map.of(new KeyId(2), AEAmount.of(3L), new KeyId(3), AEAmount.of(2L)), draft.surplus());
    }

    @Test
    void duplicateOutputKeysAreAggregatedExactly() {
        CompiledPattern pattern = pattern("duplicate-outputs", List.of(),
                List.of(output(1, 2L, true), output(1, 3L, false)));

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of()), List.of(pattern), Map.of(pattern.id(), 0),
                request(1, 6L));

        assertEquals(Map.of(pattern.id(), AEAmount.of(2L)), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(1), AEAmount.of(4L)), draft.surplus());
    }

    @Test
    void producerPriorityDescThenPatternIdDeterminesTheSelectedProducer() {
        CompiledPattern low = noInput("a-low", 1, 1L);
        CompiledPattern high = noInput("z-high", 1, 1L);

        ExactCraftPlanDraft priorityDraft = success(snapshot(2, Map.of()), List.of(low, high),
                Map.of(low.id(), 1, high.id(), 9), request(1, 1L));
        assertEquals(high.id(), priorityDraft.batches().get(0).patternId());

        ExactCraftPlanDraft tieDraft = success(snapshot(2, Map.of()), List.of(low, high),
                Map.of(low.id(), 4, high.id(), 4), request(1, 1L));
        assertEquals(low.id(), tieDraft.batches().get(0).patternId());
    }

    @Test
    void candidateListOrderDeterminesTheFirstSufficientCandidate() {
        CompiledPattern pattern = pattern("candidate-order", List.of(input(1, AEAmount.ONE,
                List.of(candidate(0, 1L, Optional.empty()), candidate(1, 1L, Optional.empty())),
                SubstitutionPolicy.ALLOW_ALTERNATIVES)), List.of(output(2, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(3, Map.of(0, AEAmount.ONE, 1, AEAmount.ONE)), List.of(pattern),
                Map.of(pattern.id(), 0), request(2, 1L));

        assertEquals(Map.of(new KeyId(0), AEAmount.ONE), draft.storageConsumed());
        assertEquals(0, draft.batches().get(0).inputs().get(0).candidateIndex());
    }

    @Test
    void failedFirstAlternativeRollsBackStorageOutputAndExecutionState() {
        CompiledPattern pattern = pattern("candidate-rollback", List.of(input(1, AEAmount.ONE,
                List.of(candidate(0, 2L, Optional.empty()), candidate(1, 1L, Optional.empty())),
                SubstitutionPolicy.ALLOW_ALTERNATIVES)), List.of(output(2, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(3, Map.of(0, AEAmount.ONE, 1, AEAmount.ONE)), List.of(pattern),
                Map.of(pattern.id(), 0), request(2, 1L));

        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), draft.storageConsumed());
        assertEquals(Map.of(), draft.surplus());
        assertEquals(Map.of(pattern.id(), AEAmount.ONE), draft.patternExecutions());
        assertEquals(1, draft.batches().size());
        assertEquals(1, draft.batches().get(0).inputs().get(0).candidateIndex());
        assertEquals(List.of(new KeyId(0), new KeyId(1)),
                List.copyOf(draft.dependencies().storageKeyRevisions().keySet()));
    }

    @Test
    void mixedProducerAllocationUsesPriorityAndConservesBothBranches() {
        CompiledPattern twoOutput = pattern("two-output", List.of(input(0, AEAmount.ONE,
                List.of(candidate(0, 2L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 2L, true)));
        CompiledPattern oneOutput = oneInput("one-output", 1, 2, AEAmount.ONE, 1, Optional.empty());

        ExactCraftPlanDraft draft = success(
                snapshot(3, Map.of(0, AEAmount.of(2L), 2, AEAmount.ONE)),
                List.of(twoOutput, oneOutput), Map.of(twoOutput.id(), 9, oneOutput.id(), 0), request(1, 3L));

        assertEquals(Map.of(twoOutput.id(), AEAmount.ONE, oneOutput.id(), AEAmount.ONE), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(0), AEAmount.of(2L), new KeyId(2), AEAmount.ONE), draft.storageConsumed());
        assertEquals(Map.of(), draft.surplus());
        assertEquals(List.of(twoOutput.id(), oneOutput.id()), draft.batches().stream()
                .map(PlannedPatternBatch::patternId).toList());
        assertEquals(List.of(AEAmount.ONE, AEAmount.ONE), draft.batches().stream()
                .map(PlannedPatternBatch::executions).toList());
    }

    @Test
    void activeSelfCycleReturnsTypedCycleFailure() {
        CompiledPattern self = oneInput("self-cycle", 1, 1, AEAmount.ONE, 1, Optional.empty());

        ExactCraftPlanResult.Failure failure = failure(snapshot(2, Map.of()), List.of(self), Map.of(self.id(), 0),
                request(1, 1L));

        assertEquals(ExactCraftPlanResult.FailureReason.CYCLE, failure.reason());
    }

    @Test
    void activeMultiCycleReturnsTypedCycleFailure() {
        CompiledPattern first = oneInput("cycle-a", 1, 2, AEAmount.ONE, 1, Optional.empty());
        CompiledPattern second = oneInput("cycle-b", 2, 1, AEAmount.ONE, 2, Optional.empty());

        ExactCraftPlanResult.Failure failure = failure(snapshot(3, Map.of()), List.of(first, second),
                Map.of(first.id(), 0, second.id(), 0), request(1, 1L));

        assertEquals(ExactCraftPlanResult.FailureReason.CYCLE, failure.reason());
    }

    @Test
    void alternativeAcyclicProducerCanSucceedDespiteCycleBranch() {
        CompiledPattern cycle = oneInput("cycle", 1, 1, AEAmount.ONE, 1, Optional.empty());
        CompiledPattern direct = noInput("direct", 1, 1L);

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of()), List.of(cycle, direct),
                Map.of(cycle.id(), 10, direct.id(), 0), request(1, 1L));

        assertEquals(direct.id(), draft.batches().get(0).patternId());
        assertEquals(Map.of(cycle.id(), new PatternRevision(0L), direct.id(), new PatternRevision(0L)),
                draft.dependencies().patternRevisions());
    }

    @Test
    void quantityLimitIsTypedAndHugeAmountDoesNotDriveALoop() {
        CompiledPattern pattern = noInput("quantity-limit", 1, 1L);
        AEAmount overLimit = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS));

        ExactCraftPlanResult.Failure failure = failure(snapshot(2, Map.of()), List.of(pattern),
                Map.of(pattern.id(), 0), request(1, overLimit));

        assertEquals(ExactCraftPlanResult.FailureReason.QUANTITY_LIMIT, failure.reason());
    }

    @Test
    void snapshotGenerationMismatchIsTypedInvalidInput() {
        CompiledPattern pattern = noInput("wrong-generation", 1, 1L, 8L);

        ExactCraftPlanResult result = PLANNER.plan(request(1, 1L), snapshot(2, Map.of()),
                patternSnapshot(List.of(pattern), Map.of(pattern.id(), 0), 8L));
        ExactCraftPlanResult.Failure failure = assertInstanceOf(ExactCraftPlanResult.Failure.class, result);

        assertEquals(ExactCraftPlanResult.FailureReason.INVALID_INPUT, failure.reason());
    }

    @Test
    void plannerWorkLimitIsStructuralAcrossFiniteBranchDecisions() {
        List<CompiledPattern> patterns = new ArrayList<>();
        Map<PatternId, Integer> priorities = new HashMap<>();
        List<CompiledCandidateSpec> candidates = new ArrayList<>();
        for (int candidateIndex = 0; candidateIndex < PatternLimits.MAX_CANDIDATES_PER_INPUT; candidateIndex++) {
            candidates.add(candidate(0, 1L, Optional.empty()));
        }
        for (int patternIndex = 0; patternIndex < 17; patternIndex++) {
            CompiledPattern pattern = pattern("work-" + patternIndex, List.of(new CompiledInputSpec(candidates,
                    AEAmount.ONE, SubstitutionPolicy.ALLOW_ALTERNATIVES)), List.of(output(1, 1L, true)));
            patterns.add(pattern);
            priorities.put(pattern.id(), 0);
        }

        ExactCraftPlanResult.Failure failure = failure(snapshot(2, Map.of()), patterns, priorities, request(1, 1L));

        assertEquals(ExactCraftPlanResult.FailureReason.WORK_LIMIT, failure.reason());
    }

    @Test
    void plannerInputValueBoundsRemainTypedAndDraftContainersAreImmutable() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedInputSelection(-1, 0, AEAmount.ONE, new KeyId(0), AEAmount.ONE, AEAmount.ONE,
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedInputSelection(0, 0, AEAmount.ZERO, new KeyId(0), AEAmount.ONE, AEAmount.ONE,
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedPatternBatch(new PlannedBatchId(0L),
                        new PlannedBatchCause.Root(new KeyId(0), AEAmount.ONE),
                        new PatternId("zero-batch"), AEAmount.ZERO, List.of()));

        CompiledPattern pattern = noInput("immutable", 1, 1L);
        ExactCraftPlanDraft draft = success(snapshot(2, Map.of()), List.of(pattern), Map.of(pattern.id(), 0),
                request(1, 1L));

        assertThrows(UnsupportedOperationException.class, () -> draft.patternExecutions().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.storageConsumed().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.surplus().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.batches().clear());
        assertEquals(draft.patternExecutions().get(pattern.id()),
                draft.batches().stream().map(PlannedPatternBatch::executions).reduce(AEAmount.ZERO, AEAmount::add));
    }

    @Test
    void plannedPatternBatchRejectsQuantityAboveTheExactBound() {
        AEAmount boundary = craftBoundaryAmount();
        AEAmount aboveLimit = craftAboveLimitAmount();
        PatternId id = new PatternId("batch-bound");

        PlannedBatchCause cause = new PlannedBatchCause.Root(new KeyId(0), AEAmount.ONE);

        assertDoesNotThrow(() -> new PlannedPatternBatch(new PlannedBatchId(0L), cause, id, boundary, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedPatternBatch(new PlannedBatchId(0L), cause, id, aboveLimit, List.of()));
    }

    @Test
    void plannedInputSelectionRejectsEitherQuantityOperandAboveTheExactBound() {
        AEAmount boundary = craftBoundaryAmount();
        AEAmount aboveLimit = craftAboveLimitAmount();
        KeyId key = new KeyId(0);

        assertDoesNotThrow(() -> new PlannedInputSelection(0, 0, boundary, key, boundary, boundary,
                Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedInputSelection(0, 0, aboveLimit, key, AEAmount.ONE, AEAmount.ONE,
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedInputSelection(0, 0, AEAmount.ONE, key, aboveLimit, AEAmount.ONE,
                        Optional.empty()));
    }

    @Test
    void exactDraftMapsRejectQuantityAboveTheExactBound() {
        AEAmount aboveLimit = craftAboveLimitAmount();
        PatternId patternId = new PatternId("draft-bound");
        KeyId key = new KeyId(0);
        GridRevision revision = emptyGridRevision();
        DependencySet dependencies = new DependencySet.Builder(revision).build();
        ExactCraftRequest request = request(1, 1L);

        assertThrows(IllegalArgumentException.class, () -> new ExactCraftPlanDraft(revision, request,
                Map.of(patternId, aboveLimit), Map.of(), Map.of(), List.of(), dependencies));
        assertThrows(IllegalArgumentException.class, () -> new ExactCraftPlanDraft(revision, request,
                Map.of(), Map.of(key, aboveLimit), Map.of(), List.of(), dependencies));
        assertThrows(IllegalArgumentException.class, () -> new ExactCraftPlanDraft(revision, request,
                Map.of(), Map.of(), Map.of(key, aboveLimit), List.of(), dependencies));
        assertThrows(IllegalArgumentException.class, () -> new ExactCraftPlanDraft(revision,
                new ExactCraftRequest(new KeyId(1), aboveLimit), Map.of(), Map.of(), Map.of(), List.of(),
                dependencies));
    }

    @Test
    void twoIndividuallyBoundedBatchesCannotAggregateBeyondTheExactBound() {
        AEAmount boundary = craftBoundaryAmount();
        PatternId patternId = new PatternId("aggregate-bound");
        ExactCraftRequest aggregateRequest = request(1, 1L);
        PlannedBatchCause rootCause = new PlannedBatchCause.Root(aggregateRequest.output(), aggregateRequest.amount());
        PlannedPatternBatch first = new PlannedPatternBatch(new PlannedBatchId(0L), rootCause, patternId, boundary,
                List.of());
        PlannedPatternBatch second = new PlannedPatternBatch(new PlannedBatchId(1L), rootCause, patternId, boundary,
                List.of());
        GridRevision revision = emptyGridRevision();
        DependencySet dependencies = new DependencySet.Builder(revision).build();

        assertThrows(IllegalArgumentException.class, () -> new ExactCraftPlanDraft(revision, aggregateRequest,
                Map.of(patternId, craftAboveLimitAmount()), Map.of(), Map.of(), List.of(first, second), dependencies));
    }

    @Test
    void plannerRejectsCompiledCandidateRemainderAndOutputOperandsAboveTheExactBound() {
        AEAmount aboveLimit = craftAboveLimitAmount();
        CompiledPattern candidate = pattern("candidate-operand-limit", List.of(input(0, AEAmount.ONE,
                List.of(candidate(0, aboveLimit, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        CompiledPattern remainder = pattern("remainder-operand-limit", List.of(input(0, AEAmount.ONE,
                List.of(candidate(0, AEAmount.ONE, Optional.of(new CompiledRemainderSpec(new KeyId(2), aboveLimit)))),
                SubstitutionPolicy.EXACT)), List.of(output(1, 1L, true)));
        CompiledPattern output = pattern("output-operand-limit", List.of(),
                List.of(new CompiledOutputSpec(new KeyId(1), aboveLimit, true)));

        assertEquals(ExactCraftPlanResult.FailureReason.QUANTITY_LIMIT,
                failure(snapshot(3, Map.of()), List.of(candidate), Map.of(candidate.id(), 0), request(1, 1L)).reason());
        assertEquals(ExactCraftPlanResult.FailureReason.QUANTITY_LIMIT,
                failure(snapshot(3, Map.of(0, AEAmount.ONE)), List.of(remainder), Map.of(remainder.id(), 0),
                        request(1, 1L)).reason());
        assertEquals(ExactCraftPlanResult.FailureReason.QUANTITY_LIMIT,
                failure(snapshot(2, Map.of()), List.of(output), Map.of(output.id(), 0), request(1, 1L)).reason());
    }

    @Test
    void exactQuantityBoundaryIsAcceptedWhenTheCraftOperationStaysWithinTheBound() {
        CompiledPattern pattern = noInput("quantity-boundary", 1, 1L);

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of()), List.of(pattern), Map.of(pattern.id(), 0),
                request(1, craftBoundaryAmount()));

        assertEquals(Map.of(pattern.id(), craftBoundaryAmount()), draft.patternExecutions());
        assertEquals(Map.of(), draft.surplus());
    }

    @Test
    void dependenciesIncludeZeroInsufficientStorageAndEveryAttemptedProducer() {
        CompiledPattern failedProducer = oneInput("attempted-failed", 0, 1, AEAmount.of(2L), 1, Optional.empty());
        CompiledPattern selectedProducer = noInput("attempted-selected", 0, 1L);
        Map<PatternId, Integer> priorities = Map.of(failedProducer.id(), 10, selectedProducer.id(), 0);

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of(1, AEAmount.ONE)),
                List.of(failedProducer, selectedProducer), priorities, request(0, 1L));

        assertEquals(Map.of(failedProducer.id(), new PatternRevision(0L),
                selectedProducer.id(), new PatternRevision(0L)), draft.dependencies().patternRevisions());
        assertEquals(Map.of(new KeyId(1), 0L), draft.dependencies().storageKeyRevisions());
        assertEquals(Map.of(), draft.storageConsumed());
    }

    @Test
    void dependenciesRecordZeroThenInsufficientThenSuccessfulCandidateReads() {
        CompiledPattern pattern = pattern("dependency-candidates", List.of(input(1, AEAmount.ONE,
                List.of(candidate(0, 1L, Optional.empty()), candidate(1, 2L, Optional.empty()),
                        candidate(2, 1L, Optional.empty())),
                SubstitutionPolicy.ALLOW_ALTERNATIVES)),
                List.of(output(3, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(4, Map.of(0, AEAmount.ZERO, 1, AEAmount.ONE, 2, AEAmount.ONE)),
                List.of(pattern), Map.of(pattern.id(), 0), request(3, 1L));

        assertEquals(Map.of(new KeyId(2), AEAmount.ONE), draft.storageConsumed());
        assertEquals(List.of(new KeyId(0), new KeyId(1), new KeyId(2)),
                List.copyOf(draft.dependencies().storageKeyRevisions().keySet()));
    }

    private static Stream<Arguments> exactIngredientAmounts() {
        return Stream.of(
                Arguments.of(BigInteger.ONE),
                Arguments.of(BigInteger.valueOf(Long.MAX_VALUE)),
                Arguments.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                Arguments.of(BigInteger.ONE.shiftLeft(128)),
                Arguments.of(BigInteger.TEN.pow(1000)));
    }

    private static ExactCraftRequest request(int output, long amount) {
        return request(output, AEAmount.of(amount));
    }

    private static ExactCraftRequest request(int output, AEAmount amount) {
        return new ExactCraftRequest(new KeyId(output), amount);
    }

    private static AEAmount craftBoundaryAmount() {
        return AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS - 1));
    }

    private static AEAmount craftAboveLimitAmount() {
        return AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS));
    }

    private static GridRevision emptyGridRevision() {
        StorageSnapshot storage = snapshot(2, Map.of());
        CompiledPattern pattern = noInput("empty-grid", 1, 1L);
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(pattern), Map.of(pattern.id(), 0),
                KEY_GENERATION);
        return GridRevision.capture(storage, patterns);
    }

    private static ExactCraftPlanDraft success(StorageSnapshot storage, List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities, ExactCraftRequest request) {
        ExactCraftPlanResult result = PLANNER.plan(request, storage,
                patternSnapshot(patterns, priorities, KEY_GENERATION));
        return assertInstanceOf(ExactCraftPlanResult.Success.class, result).draft();
    }

    private static ExactCraftPlanResult.Failure failure(StorageSnapshot storage, List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities, ExactCraftRequest request) {
        ExactCraftPlanResult result = PLANNER.plan(request, storage,
                patternSnapshot(patterns, priorities, KEY_GENERATION));
        return assertInstanceOf(ExactCraftPlanResult.Failure.class, result);
    }

    private static NormalizedPatternSnapshot patternSnapshot(List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities, long keyGeneration) {
        CompiledPatternGraph graph = ((GraphBuildResult.Success) new CompiledPatternGraphBuilder(GRAPH_GENERATION,
                keyGeneration).build(patterns)).graph();
        return new NormalizedPatternSnapshot(SERVER_GENERATION, RECIPE_REVISION, keyGeneration, graph.patternsById(),
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

    private static CompiledPattern noInput(String id, long outputKey, long outputAmount) {
        return pattern(id, List.of(), List.of(output((int) outputKey, outputAmount, true)));
    }

    private static CompiledPattern noInput(String id, long outputKey, long outputAmount, long keyGeneration) {
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, List.of(),
                List.of(output((int) outputKey, outputAmount, true)), Optional.empty(), new PatternRevision(0L),
                keyGeneration);
    }

    private static CompiledPattern oneInput(String id, long outputKey, int inputKey, AEAmount inputAmount,
            int multiplier, Optional<CompiledRemainderSpec> remainder) {
        return pattern(id, List.of(input(inputKey, AEAmount.of(multiplier),
                List.of(candidate(inputKey, inputAmount, remainder)), SubstitutionPolicy.EXACT)),
                List.of(output((int) outputKey, 1L, true)));
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

    private static CompiledCandidateSpec candidate(int key, long amount, Optional<CompiledRemainderSpec> remainder) {
        return candidate(key, AEAmount.of(amount), remainder);
    }

    private static CompiledCandidateSpec candidate(int key, AEAmount amount,
            Optional<CompiledRemainderSpec> remainder) {
        return new CompiledCandidateSpec(new KeyId(key), amount, remainder);
    }

    private static CompiledRemainderSpec remainder(int key, long amount) {
        return new CompiledRemainderSpec(new KeyId(key), AEAmount.of(amount));
    }

    private static CompiledOutputSpec output(int key, long amount, boolean primary) {
        return new CompiledOutputSpec(new KeyId(key), AEAmount.of(amount), primary);
    }
}
