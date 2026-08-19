package appeng.rebuild.planner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
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

/** Contract tests for the bounded T005B2 planner allocation and remainder strategy. */
class ExactCraftPlannerB2ContractTest {
    private static final long KEY_GENERATION = 7L;
    private static final long SERVER_GENERATION = 9L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(3L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(5L);
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();

    @Test
    void mixedCandidatesUseExactAllocationAndCanonicalInputCandidateOrder() {
        CompiledPattern pattern = pattern("mixed-candidates", List.of(input(1, AEAmount.of(2L),
                List.of(candidate(0, 1L, Optional.empty()), candidate(1, 1L, Optional.empty())),
                SubstitutionPolicy.ALLOW_ALTERNATIVES)), List.of(output(2, 2L, true)));

        ExactCraftPlanDraft draft = success(snapshot(3, Map.of(0, AEAmount.ONE, 1, AEAmount.ONE)), List.of(pattern),
                Map.of(pattern.id(), 0), request(2, 2L));

        PlannedPatternBatch batch = draft.batches().get(0);
        assertEquals(List.of(0, 1), batch.inputs().stream().map(PlannedInputSelection::candidateIndex).toList());
        assertEquals(List.of(0, 0), batch.inputs().stream().map(PlannedInputSelection::inputIndex).toList());
        assertEquals(AEAmount.of(2L), batch.inputs().stream().map(PlannedInputSelection::templateUnits)
                .reduce(AEAmount.ZERO, AEAmount::add));
        assertEquals(Map.of(new KeyId(0), AEAmount.ONE, new KeyId(1), AEAmount.ONE), draft.storageConsumed());
        assertEquals(Map.of(new KeyId(0), 0L, new KeyId(1), 0L), draft.dependencies().storageKeyRevisions());
    }

    @Test
    void failedGreedyCandidateRollsBackBeforeRotatedCandidateSucceeds() {
        CompiledPattern pattern = pattern("rotated-candidate", List.of(
                input(1, AEAmount.ONE,
                        List.of(candidate(0, 1L, Optional.empty()), candidate(1, 1L, Optional.empty())),
                        SubstitutionPolicy.ALLOW_ALTERNATIVES),
                input(0, AEAmount.ONE, List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(3, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(4, Map.of(0, AEAmount.ONE, 1, AEAmount.ONE)), List.of(pattern),
                Map.of(pattern.id(), 0), request(3, 1L));

        PlannedPatternBatch batch = draft.batches().get(0);
        assertEquals(List.of(1, 0), batch.inputs().stream().map(PlannedInputSelection::candidateIndex).toList());
        assertEquals(List.of(0, 1), batch.inputs().stream().map(PlannedInputSelection::inputIndex).toList());
        assertEquals(Map.of(new KeyId(0), AEAmount.ONE, new KeyId(1), AEAmount.ONE), draft.storageConsumed());
        assertEquals(Map.of(pattern.id(), AEAmount.ONE), draft.patternExecutions());
        assertEquals(Map.of(), draft.surplus());
        assertEquals(List.of(new KeyId(0), new KeyId(1)),
                List.copyOf(draft.dependencies().storageKeyRevisions().keySet()));
    }

    @Test
    void threeCandidateRotationWrapsAndCombinesLaterCandidateWithCandidateZero() {
        CompiledPattern pattern = pattern("cyclic-candidate-rotation", List.of(
                input(0, AEAmount.of(2L), List.of(candidate(0, 1L, Optional.empty()),
                        candidate(1, 1L, Optional.empty()), candidate(2, 1L, Optional.empty())),
                        SubstitutionPolicy.ALLOW_ALTERNATIVES),
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(3, 1L, true)));

        ExactCraftPlanDraft draft = success(
                snapshot(4, Map.of(0, AEAmount.ONE, 1, AEAmount.ONE, 2, AEAmount.ONE)), List.of(pattern),
                Map.of(pattern.id(), 0), request(3, 1L));

        PlannedPatternBatch batch = draft.batches().get(0);
        assertEquals(List.of(0, 2, 0), batch.inputs().stream().map(PlannedInputSelection::candidateIndex).toList());
        assertEquals(List.of(0, 0, 1), batch.inputs().stream().map(PlannedInputSelection::inputIndex).toList());
        assertEquals(Map.of(new KeyId(0), AEAmount.ONE, new KeyId(1), AEAmount.ONE, new KeyId(2), AEAmount.ONE),
                draft.storageConsumed());
        assertEquals(Map.of(pattern.id(), AEAmount.ONE), draft.patternExecutions());
    }

    @Test
    void mixedProducersFulfillByPriorityAndPreserveCausalBatchOrder() {
        CompiledPattern producerA = pattern("producer-a", List.of(input(0, AEAmount.ONE,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(3, 2L, true)));
        CompiledPattern producerB = pattern("producer-b", List.of(input(2, AEAmount.ONE,
                List.of(candidate(2, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(3, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(4, Map.of(0, AEAmount.ONE, 2, AEAmount.ONE)),
                List.of(producerA, producerB), Map.of(producerA.id(), 10, producerB.id(), 0), request(3, 3L));

        assertEquals(List.of(producerA.id(), producerB.id()), draft.batches().stream()
                .map(PlannedPatternBatch::patternId).toList());
        assertEquals(Map.of(producerA.id(), AEAmount.ONE, producerB.id(), AEAmount.ONE), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(0), AEAmount.ONE, new KeyId(2), AEAmount.ONE), draft.storageConsumed());
        assertEquals(Map.of(), draft.surplus());
    }

    @Test
    void hugeCandidateRequestUsesOneFullBatchWhenWholeTrialIsFeasible() {
        AEAmount requested = AEAmount.of(BigInteger.TEN.pow(1000));
        CompiledPattern pattern = pattern("huge-candidate-chunks", List.of(input(0, AEAmount.ONE,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of(0, requested)), List.of(pattern),
                Map.of(pattern.id(), 0), request(1, requested));

        assertEquals(1, draft.batches().size(), "a feasible full trial must not be split gratuitously");
        assertEquals(requested, draft.batches().get(0).executions());
        assertEquals(requested, draft.batches().get(0).inputs().get(0).templateUnits());
        assertEquals(Map.of(pattern.id(), requested), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(0), requested), draft.storageConsumed());
    }

    @Test
    void staleDescendingProducerScheduleSkipsPowersAboveCurrentRemaining() {
        // Two candidates intentionally disable same-key seed reuse. The full five-execution attempt needs five X,
        // while stock has four. Fallback executes four, then skips stale power two and executes the final one.
        CompiledPattern pattern = pattern("stale-producer-schedule", List.of(input(1, AEAmount.ONE,
                List.of(candidate(1, 1L, Optional.of(remainder(1, 1L))), candidate(2, 1L, Optional.empty())),
                SubstitutionPolicy.ALLOW_ALTERNATIVES)), List.of(output(0, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(3, Map.of(1, AEAmount.of(4L))), List.of(pattern),
                Map.of(pattern.id(), 0), request(0, 5L));

        assertEquals(List.of(AEAmount.of(4L), AEAmount.ONE),
                draft.batches().stream().map(PlannedPatternBatch::executions).toList());
        assertEquals(Map.of(pattern.id(), AEAmount.of(5L)), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(1), AEAmount.of(4L)), draft.storageConsumed());
        assertEquals(Map.of(new KeyId(1), AEAmount.of(4L)), draft.surplus());
        assertTrue(!draft.surplus().containsKey(new KeyId(0)), "target output must have no surplus");
    }

    @Test
    void fallbackChunkDecompositionAboveChunkBitLimitIsTypedWorkLimit() {
        AEAmount requestAmount = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_CHUNK_BITS));
        CompiledPattern pattern = pattern("chunk-work-limit", List.of(input(0, AEAmount.ONE,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));

        ExactCraftPlanResult.Failure failure = failure(snapshot(2, Map.of(0, AEAmount.ONE)), List.of(pattern),
                Map.of(pattern.id(), 0), request(1, requestAmount));

        assertEquals(ExactCraftPlanResult.FailureReason.WORK_LIMIT, failure.reason());
    }

    @Test
    void explicitAbaRevisitLimitationIsUnsatisfiableWithoutPartialDraft() {
        // A must be used before B to consume the seeded link, B replenishes the link, and A must be used again.
        CompiledPattern a = oneInputOutput("aba-a", 0, 1, 1L, 1L);
        CompiledPattern b = pattern("aba-b", List.of(input(2, AEAmount.ONE,
                List.of(candidate(2, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(0, 1L, true), output(1, 1L, false)));

        ExactCraftPlanResult.Failure failure = failure(snapshot(3, Map.of(1, AEAmount.ONE, 2, AEAmount.ONE)),
                List.of(a, b), Map.of(a.id(), 10, b.id(), 0), request(0, 3L));

        assertEquals(ExactCraftPlanResult.FailureReason.UNSATISFIABLE_WITHIN_STRATEGY, failure.reason());
    }

    @Test
    void plannedBatchSelectionsMustBeStrictlyUniqueSortedAndWithinTheTotalSelectionCap() {
        PlannedInputSelection first = selection(0, 0);
        PlannedInputSelection second = selection(0, 1);
        PatternId id = new PatternId("batch-schema");

        assertDoesNotThrow(() -> new PlannedPatternBatch(id, AEAmount.ONE, List.of(first, second)));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedPatternBatch(id, AEAmount.ONE, List.of(first, first)));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedPatternBatch(id, AEAmount.ONE, List.of(second, first)));

        List<PlannedInputSelection> overCap = new ArrayList<>(PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN + 1);
        for (int candidateIndex = 0; candidateIndex < PatternLimits.MAX_CANDIDATES_PER_INPUT; candidateIndex++) {
            overCap.add(selection(0, candidateIndex));
        }
        overCap.add(selection(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlannedPatternBatch(id, AEAmount.ONE, overCap));
    }

    @Test
    void plannerValidatesPerGroupTemplateSumForMixedCandidates() {
        CompiledPattern pattern = pattern("template-sum", List.of(input(0, AEAmount.of(3L),
                List.of(candidate(0, 1L, Optional.empty()), candidate(1, 1L, Optional.empty())),
                SubstitutionPolicy.ALLOW_ALTERNATIVES)), List.of(output(2, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(3, Map.of(0, AEAmount.ONE, 1, AEAmount.of(2L))), List.of(pattern),
                Map.of(pattern.id(), 0), request(2, 1L));

        assertEquals(AEAmount.of(3L), draft.batches().get(0).inputs().stream()
                .map(PlannedInputSelection::templateUnits).reduce(AEAmount.ZERO, AEAmount::add));
        assertEquals(Map.of(new KeyId(0), AEAmount.ONE, new KeyId(1), AEAmount.of(2L)), draft.storageConsumed());
    }

    @Test
    void initialRequiredMayBeLessThanGrossButNeverExceedsIt() {
        KeyId key = new KeyId(0);
        PlannedInputSelection selection = new PlannedInputSelection(0, 0, AEAmount.ONE, key, AEAmount.of(3L),
                AEAmount.ONE, Optional.empty());

        assertEquals(AEAmount.of(3L), selection.grossConsumedAmount());
        assertEquals(AEAmount.ONE, selection.initialRequiredAmount());
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedInputSelection(0, 0, AEAmount.ONE, key, AEAmount.ONE, AEAmount.of(2L),
                        Optional.empty()));
    }

    @ParameterizedTest(name = "reusable remainder A={0}, R={1}, n={2}")
    @MethodSource("reusableRemainderCases")
    void reusableRemainderUsesClosedFormInitialSeedAndExactSurplus(BigInteger inputAmount,
            BigInteger remainderAmount, BigInteger executions) {
        AEAmount input = AEAmount.of(inputAmount);
        AEAmount remainder = AEAmount.of(remainderAmount);
        AEAmount requested = AEAmount.of(executions);
        AEAmount seed = initialSeed(input, remainder, requested);
        AEAmount gross = times(input, requested);
        AEAmount finalSurplus = remainder.compareTo(input) < 0
                ? remainder
                : AEAmount.of(inputAmount.add(requested.toBigInteger()
                        .multiply(remainderAmount.subtract(inputAmount))));

        CompiledPattern source = pattern("remainder-source", List.of(input(0, AEAmount.ONE,
                List.of(candidate(0, input, Optional.of(new CompiledRemainderSpec(new KeyId(0), remainder)))),
                SubstitutionPolicy.EXACT)), List.of(output(1, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of(0, seed)), List.of(source),
                Map.of(source.id(), 0), request(1, requested));

        assertEquals(Map.of(source.id(), requested), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(0), seed), draft.storageConsumed());
        assertEquals(Map.of(new KeyId(0), finalSurplus), draft.surplus());
        assertEquals(gross, draft.batches().stream().filter(batch -> batch.patternId().equals(source.id()))
                .flatMap(batch -> batch.inputs().stream()).map(PlannedInputSelection::grossConsumedAmount)
                .reduce(AEAmount.ZERO, AEAmount::add));
        assertEquals(seed, draft.batches().stream().filter(batch -> batch.patternId().equals(source.id()))
                .flatMap(batch -> batch.inputs().stream()).map(PlannedInputSelection::initialRequiredAmount)
                .reduce(AEAmount.ZERO, AEAmount::add));
        assertEquals(finalSurplus, draft.batches().stream().filter(batch -> batch.patternId().equals(source.id()))
                .flatMap(batch -> batch.inputs().stream()).flatMap(selection -> selection.remainderReturn().stream())
                .map(PlannedRemainderReturn::amount).reduce(AEAmount.ZERO, AEAmount::add));
        assertTrue(draft.batches().stream().anyMatch(batch -> batch.patternId().equals(source.id())));
    }

    @ParameterizedTest(name = "multiplier=2 remainder per template {0}")
    @MethodSource("multiplierRemainderCases")
    void plannerEmitsMultiplierRemainderClosedForm(BigInteger remainderPerTemplate) {
        AEAmount multiplier = AEAmount.of(2L);
        AEAmount inputPerTemplate = AEAmount.of(2L);
        AEAmount remainder = AEAmount.of(remainderPerTemplate);
        AEAmount executions = AEAmount.of(3L);
        AEAmount inputPerExecution = times(inputPerTemplate, multiplier);
        AEAmount returnPerExecution = times(remainder, multiplier);
        AEAmount seed = returnPerExecution.compareTo(inputPerExecution) < 0
                ? AEAmount.of(inputPerExecution.toBigInteger().add(executions.toBigInteger().subtract(BigInteger.ONE)
                        .multiply(inputPerExecution.toBigInteger().subtract(returnPerExecution.toBigInteger()))))
                : inputPerExecution;
        AEAmount finalReturn = returnPerExecution.compareTo(inputPerExecution) < 0
                ? returnPerExecution
                : AEAmount.of(inputPerExecution.toBigInteger().add(executions.toBigInteger()
                        .multiply(returnPerExecution.toBigInteger().subtract(inputPerExecution.toBigInteger()))));

        CompiledPattern source = pattern("multiplier-remainder", List.of(input(0, multiplier,
                List.of(candidate(0, inputPerTemplate,
                        Optional.of(new CompiledRemainderSpec(new KeyId(0), remainder)))),
                SubstitutionPolicy.EXACT)), List.of(output(1, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(2, Map.of(0, seed)), List.of(source), Map.of(source.id(), 0),
                request(1, executions));
        PlannedInputSelection selection = draft.batches().get(0).inputs().get(0);

        assertEquals(seed, selection.initialRequiredAmount());
        assertEquals(times(inputPerExecution, executions), selection.grossConsumedAmount());
        assertEquals(finalReturn, selection.remainderReturn().orElseThrow().amount());
        assertEquals(Map.of(new KeyId(0), seed), draft.storageConsumed());
        assertEquals(Map.of(new KeyId(0), finalReturn), draft.surplus());
    }

    @Test
    void returnedContainerCanFeedALaterCausalBatch() {
        AEAmount container = AEAmount.of(2L);
        CompiledPattern source = pattern("later-container-source", List.of(input(0, AEAmount.ONE,
                List.of(candidate(0, container, Optional.of(new CompiledRemainderSpec(new KeyId(0), container)))),
                SubstitutionPolicy.EXACT)), List.of(output(1, 1L, true)));
        CompiledPattern consumer = pattern("later-container-consumer", List.of(
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT),
                input(0, AEAmount.ONE, List.of(candidate(0, container, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(2, 1L, true)));

        ExactCraftPlanDraft draft = success(snapshot(3, Map.of(0, container)), List.of(source, consumer),
                Map.of(source.id(), 10, consumer.id(), 0), request(2, 1L));

        assertEquals(Map.of(source.id(), AEAmount.ONE, consumer.id(), AEAmount.ONE), draft.patternExecutions());
        assertEquals(Map.of(new KeyId(0), container), draft.storageConsumed());
        assertEquals(Map.of(), draft.surplus());
        assertEquals(container, draft.batches().stream().filter(batch -> batch.patternId().equals(source.id()))
                .flatMap(batch -> batch.inputs().stream()).flatMap(selection -> selection.remainderReturn().stream())
                .map(PlannedRemainderReturn::amount).reduce(AEAmount.ZERO, AEAmount::add));
        List<PatternId> order = draft.batches().stream().map(PlannedPatternBatch::patternId).toList();
        assertTrue(order.indexOf(source.id()) < order.indexOf(consumer.id()));
    }

    @Test
    void sameConsumedAndReturnedKeyCannotUseAContainerShortcut() {
        CompiledPattern pattern = pattern("container-shortcut", List.of(input(1, AEAmount.ONE,
                List.of(candidate(1, 1L, Optional.of(remainder(1, 1L)))), SubstitutionPolicy.EXACT)),
                List.of(output(0, 1L, true), output(1, 1L, false)));

        ExactCraftPlanResult.Failure failure = failure(snapshot(2, Map.of()), List.of(pattern),
                Map.of(pattern.id(), 0), request(0, 1L));

        assertTrue(failure.reason() == ExactCraftPlanResult.FailureReason.CYCLE
                || failure.reason() == ExactCraftPlanResult.FailureReason.UNSATISFIABLE_WITHIN_STRATEGY);
    }

    @Test
    void sameKeyOutputDisablesSeedShortcutForNGreaterThanOne() {
        CompiledPattern pattern = pattern("same-key-output-exclusion", List.of(input(1, AEAmount.ONE,
                List.of(candidate(1, 2L, Optional.of(remainder(1, 1L)))), SubstitutionPolicy.EXACT)),
                List.of(output(0, 1L, true), output(1, 1L, false)));
        AEAmount seed = AEAmount.of(3L);
        AEAmount gross = AEAmount.of(4L);

        ExactCraftPlanResult.Failure seedStockFailure = failure(snapshot(2, Map.of(1, seed)), List.of(pattern),
                Map.of(pattern.id(), 0), request(0, 2L));
        ExactCraftPlanDraft grossStockDraft = success(snapshot(2, Map.of(1, gross)), List.of(pattern),
                Map.of(pattern.id(), 0), request(0, 2L));

        assertTrue(seedStockFailure.reason() == ExactCraftPlanResult.FailureReason.CYCLE
                || seedStockFailure.reason() == ExactCraftPlanResult.FailureReason.UNSATISFIABLE_WITHIN_STRATEGY);
        assertEquals(gross, grossStockDraft.batches().stream().flatMap(batch -> batch.inputs().stream())
                .map(PlannedInputSelection::initialRequiredAmount).reduce(AEAmount.ZERO, AEAmount::add));
        assertEquals(Map.of(new KeyId(1), gross), grossStockDraft.storageConsumed());
        assertEquals(Map.of(new KeyId(1), gross), grossStockDraft.surplus());
    }

    @Test
    void remainderAndInitialGrossFieldsRejectQuantityAboveTheExactBound() {
        AEAmount boundary = craftBoundaryAmount();
        AEAmount aboveLimit = craftAboveLimitAmount();
        KeyId key = new KeyId(0);

        assertDoesNotThrow(() -> new PlannedRemainderReturn(key, boundary));
        assertThrows(IllegalArgumentException.class, () -> new PlannedRemainderReturn(key, aboveLimit));
        assertDoesNotThrow(() -> new PlannedInputSelection(0, 0, boundary, key, boundary, boundary,
                Optional.of(new PlannedRemainderReturn(key, boundary))));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedInputSelection(0, 0, AEAmount.ONE, key, AEAmount.ONE, aboveLimit, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new PlannedInputSelection(0, 0, AEAmount.ONE, key, AEAmount.ONE, AEAmount.ONE,
                        Optional.of(new PlannedRemainderReturn(key, aboveLimit))));
    }

    private static Stream<Arguments> reusableRemainderCases() {
        BigInteger huge = BigInteger.TEN.pow(1000);
        return Stream.of(
                Arguments.of(BigInteger.valueOf(2L), BigInteger.ONE, BigInteger.ONE),
                Arguments.of(BigInteger.valueOf(2L), BigInteger.valueOf(2L), BigInteger.ONE),
                Arguments.of(BigInteger.valueOf(2L), BigInteger.valueOf(3L), BigInteger.ONE),
                Arguments.of(BigInteger.valueOf(2L), BigInteger.ONE, huge),
                Arguments.of(BigInteger.valueOf(2L), BigInteger.valueOf(2L), huge),
                Arguments.of(BigInteger.valueOf(2L), BigInteger.valueOf(3L), huge));
    }

    private static Stream<Arguments> multiplierRemainderCases() {
        return Stream.of(Arguments.of(BigInteger.ONE), Arguments.of(BigInteger.valueOf(3L)));
    }

    private static ExactCraftRequest request(int output, long amount) {
        return request(output, AEAmount.of(amount));
    }

    private static ExactCraftRequest request(int output, AEAmount amount) {
        return new ExactCraftRequest(new KeyId(output), amount);
    }

    private static ExactCraftPlanDraft success(StorageSnapshot storage, List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities, ExactCraftRequest request) {
        ExactCraftPlanResult result = PLANNER.plan(request, storage, patternSnapshot(patterns, priorities));
        return assertInstanceOf(ExactCraftPlanResult.Success.class, result).draft();
    }

    private static ExactCraftPlanResult.Failure failure(StorageSnapshot storage, List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities, ExactCraftRequest request) {
        ExactCraftPlanResult result = PLANNER.plan(request, storage, patternSnapshot(patterns, priorities));
        return assertInstanceOf(ExactCraftPlanResult.Failure.class, result);
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

    private static CompiledPattern noInput(String id, long outputKey, long outputAmount) {
        return pattern(id, List.of(), List.of(output((int) outputKey, outputAmount, true)));
    }

    private static CompiledPattern oneInputOutput(String id, int outputKey, int inputKey, long inputAmount,
            long outputAmount) {
        return pattern(id, List.of(input(inputKey, AEAmount.ONE,
                List.of(candidate(inputKey, inputAmount, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(outputKey, outputAmount, true)));
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

    private static PlannedInputSelection selection(int inputIndex, int candidateIndex) {
        return new PlannedInputSelection(inputIndex, candidateIndex, AEAmount.ONE, new KeyId(candidateIndex),
                AEAmount.ONE, AEAmount.ONE, Optional.empty());
    }

    private static AEAmount times(AEAmount left, AEAmount right) {
        return AEAmount.of(left.toBigInteger().multiply(right.toBigInteger()));
    }

    private static AEAmount initialSeed(AEAmount input, AEAmount remainder, AEAmount executions) {
        if (remainder.compareTo(input) >= 0) {
            return input;
        }
        return AEAmount.of(input.toBigInteger().add(executions.toBigInteger().subtract(BigInteger.ONE)
                .multiply(input.toBigInteger().subtract(remainder.toBigInteger()))));
    }

    private static AEAmount craftBoundaryAmount() {
        return AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS - 1));
    }

    private static AEAmount craftAboveLimitAmount() {
        return AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS));
    }
}
