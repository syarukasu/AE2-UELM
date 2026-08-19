package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

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
import appeng.rebuild.planner.PlannedBatchCause;
import appeng.rebuild.planner.PlannedCausalStep;
import appeng.rebuild.planner.PlannedPatternBatch;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Focused contract tests for exact-plan revalidation and sealing. */
class ExactCraftingPlanValidatorTest {
    private static final long KEY_GENERATION = 7L;
    private static final long SERVER_GENERATION = 9L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(3L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(5L);
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();
    private static final ExactCraftingPlanValidator VALIDATOR = new ExactCraftingPlanValidator();

    @Test
    void plannerProducedNormalPlanSeals() {
        CompiledPattern normal = pattern("validator-normal", List.of(input(0, AEAmount.ONE,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        StorageSnapshot storage = snapshot(2, Map.of(0, AEAmount.ONE));
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(normal), Map.of(normal.id(), 0));
        ExactCraftPlanDraft draft = success(storage, List.of(normal), Map.of(normal.id(), 0), request(1, 1L));

        ExactCraftingPlan plan = sealed(draft, storage, patterns);

        assertEquals(draft.gridRevision(), plan.planningRevision());
        assertEquals(draft.gridRevision(), plan.validationRevision());
        assertEquals(Map.of(normal.id(), new PatternRevision(0L)), plan.usedPatternRevisions());
        assertEquals(Map.of(normal.id(), AEAmount.ONE), plan.patternExecutions());
        assertEquals(Map.of(new KeyId(0), AEAmount.ONE), plan.initialStorageDebits());
        assertEquals(Map.of(), plan.finalSurplus());
    }

    @Test
    void plannerProducedProductiveSelfCycleWithHugeDemandSealsWithExactImmutableMaps() {
        CompiledPattern self = selfCycle("validator-huge-self-cycle", 1, 2L);
        StorageSnapshot storage = snapshot(2, Map.of(1, AEAmount.ONE));
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(self), Map.of(self.id(), 10));
        AEAmount demand = AEAmount.of(BigInteger.TEN.pow(1000));
        ExactCraftPlanDraft draft = success(storage, List.of(self), Map.of(self.id(), 10), request(1, demand));

        ExactCraftingPlan plan = sealed(draft, storage, patterns);

        assertEquals(Map.of(self.id(), new PatternRevision(0L)), plan.usedPatternRevisions());
        assertEquals(Map.of(self.id(), demand), plan.patternExecutions());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), plan.initialStorageDebits());
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), plan.finalSurplus());
        assertUnmodifiable(plan.usedPatternRevisions());
        assertUnmodifiable(plan.patternExecutions());
        assertUnmodifiable(plan.initialStorageDebits());
        assertUnmodifiable(plan.finalSurplus());
    }

    @Test
    void dependencyMismatchIsTypedBeforePlanRevalidation() {
        CompiledPattern original = pattern("validator-dependency", List.of(input(0, AEAmount.ONE,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        CompiledPattern revised = pattern("validator-dependency", new PatternRevision(1L),
                List.of(input(0, AEAmount.ONE, List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        StorageSnapshot storage = snapshot(2, Map.of(0, AEAmount.ONE));
        ExactCraftPlanDraft draft = success(storage, List.of(original), Map.of(original.id(), 0), request(1, 1L));

        ExactPlanValidationResult.Failure failure = failure(draft, storage,
                patternSnapshot(List.of(revised), Map.of(revised.id(), 0)));

        assertEquals(ExactPlanValidationResult.FailureReason.DEPENDENCY_MISMATCH, failure.reason());
        assertEquals(Optional.of(appeng.rebuild.planner.DependencyValidationResult.Reason.PATTERN_REVISION_MISMATCH),
                failure.dependencyReason());
    }

    @Test
    void summaryMismatchIsTypedForReverseDebitAndSurplus() {
        CompiledPattern self = selfCycle("validator-summary-cycle", 1, 2L);
        StorageSnapshot storage = snapshot(2, Map.of(1, AEAmount.ONE));
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(self), Map.of(self.id(), 10));
        ExactCraftPlanDraft draft = success(storage, List.of(self), Map.of(self.id(), 10), request(1, 5L));

        ExactCraftPlanDraft debitMismatch = copyDraft(draft, Map.of(new KeyId(1), AEAmount.of(2L)), draft.surplus());
        ExactCraftPlanDraft surplusMismatch = copyDraft(draft, draft.storageConsumed(),
                Map.of(new KeyId(1), AEAmount.of(2L)));

        assertEquals(ExactPlanValidationResult.FailureReason.SUMMARY_MISMATCH,
                failure(debitMismatch, storage, patterns).reason());
        assertEquals(ExactPlanValidationResult.FailureReason.SUMMARY_MISMATCH,
                failure(surplusMismatch, storage, patterns).reason());
    }

    @Test
    void insufficientStorageIsTypedAfterDependenciesAndSummaryValidate() {
        CompiledPattern normal = pattern("validator-insufficient", List.of(input(0, AEAmount.ONE,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        StorageSnapshot plannedStorage = snapshot(2, Map.of(0, AEAmount.ONE));
        StorageSnapshot currentStorage = snapshot(2, Map.of());
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(normal), Map.of(normal.id(), 0));
        ExactCraftPlanDraft draft = success(plannedStorage, List.of(normal), Map.of(normal.id(), 0), request(1, 1L));

        ExactPlanValidationResult.Failure failure = failure(draft, currentStorage, patterns);

        assertEquals(ExactPlanValidationResult.FailureReason.INSUFFICIENT_STORAGE, failure.reason());
    }

    @Test
    void rootCraftShortfallCannotBeReplacedByRequestKeyStorageWhileCycleSeedDebitEqualsSurplusSeals() {
        CompiledPattern direct = noInput("validator-root-shortfall", 1, 1L);
        StorageSnapshot directStorage = snapshot(2, Map.of(1, AEAmount.ONE));
        NormalizedPatternSnapshot directPatterns = patternSnapshot(List.of(direct), Map.of(direct.id(), 0));
        ExactCraftPlanDraft complete = success(snapshot(2, Map.of()), List.of(direct), Map.of(direct.id(), 0),
                request(1, 2L));
        PlannedPatternBatch originalBatch = complete.batches().get(0);
        PlannedPatternBatch shortBatch = new PlannedPatternBatch(originalBatch.id(), originalBatch.cause(),
                originalBatch.patternId(), AEAmount.ONE, originalBatch.inputs());
        ExactCraftPlanDraft shortfall = copyDraft(complete, Map.of(direct.id(), AEAmount.ONE),
                Map.of(new KeyId(1), AEAmount.ONE), Map.of(), List.of(shortBatch));

        assertEquals(ExactPlanValidationResult.FailureReason.MALFORMED_STEP,
                failure(shortfall, directStorage, directPatterns).reason());

        CompiledPattern self = selfCycle("validator-seed-cycle", 1, 2L);
        StorageSnapshot cycleStorage = snapshot(2, Map.of(1, AEAmount.ONE));
        NormalizedPatternSnapshot cyclePatterns = patternSnapshot(List.of(self), Map.of(self.id(), 10));
        ExactCraftPlanDraft cycleDraft = success(cycleStorage, List.of(self), Map.of(self.id(), 10), request(1, 5L));
        ExactCraftingPlan cyclePlan = sealed(cycleDraft, cycleStorage, cyclePatterns);
        assertEquals(Map.of(new KeyId(1), AEAmount.ONE), cyclePlan.initialStorageDebits());
        assertEquals(cyclePlan.initialStorageDebits(), cyclePlan.finalSurplus());
    }

    @Test
    void internalCycleCompiledInputWithAlternateCandidateOrRemainderRejects() {
        CompiledPattern original = selfCycle("validator-internal-shape", 1, 2L);
        StorageSnapshot storage = snapshot(2, Map.of(1, AEAmount.ONE));
        ExactCraftPlanDraft draft = success(storage, List.of(original), Map.of(original.id(), 10), request(1, 5L));

        CompiledPattern alternate = pattern("validator-internal-shape", List.of(input(1, AEAmount.ONE,
                List.of(candidate(1, 1L, Optional.empty()), candidate(0, 1L, Optional.empty())),
                SubstitutionPolicy.ALLOW_ALTERNATIVES)), List.of(output(1, 2L, true)));
        CompiledPattern remainder = pattern("validator-internal-shape", List.of(input(1, AEAmount.ONE,
                List.of(candidate(1, 1L, Optional.of(remainder(0, 1L)))), SubstitutionPolicy.EXACT)),
                List.of(output(1, 2L, true)));

        assertEquals(ExactPlanValidationResult.FailureReason.MALFORMED_STEP,
                failure(draft, storage, patternSnapshot(List.of(alternate), Map.of(alternate.id(), 10))).reason());
        assertEquals(ExactPlanValidationResult.FailureReason.MALFORMED_STEP,
                failure(draft, storage, patternSnapshot(List.of(remainder), Map.of(remainder.id(), 10))).reason());
    }

    @Test
    void normalInputCauseDemandedMismatchRejects() {
        NormalChain chain = normalChain();
        PlannedPatternBatch producer = chain.draft().batches().stream()
                .filter(batch -> batch.patternId().equals(chain.producer().id())).findFirst().orElseThrow();
        PlannedPatternBatch consumer = chain.draft().batches().stream()
                .filter(batch -> batch.patternId().equals(chain.consumer().id())).findFirst().orElseThrow();
        PlannedBatchCause.Input cause = (PlannedBatchCause.Input) producer.cause();
        PlannedPatternBatch tamperedProducer = new PlannedPatternBatch(producer.id(),
                new PlannedBatchCause.Input(cause.consumerBatchId(), cause.cycleMemberIndex(), cause.inputIndex(),
                        cause.candidateIndex(), cause.key(), AEAmount.of(2L)),
                producer.patternId(), producer.executions(), producer.inputs());
        ExactCraftPlanDraft tampered = copyDraft(chain.draft(), chain.draft().patternExecutions(),
                chain.draft().storageConsumed(), chain.draft().surplus(), List.of(tamperedProducer, consumer));

        assertEquals(ExactPlanValidationResult.FailureReason.MALFORMED_STEP,
                failure(tampered, chain.storage(), chain.patterns()).reason());
    }

    @Test
    void cycleExternalCauseDemandedAmountMustBeScaledByRepetitions() {
        CycleExternalFixture fixture = cycleExternalFixture();
        PlannedPatternBatch external = fixture.draft().batches().stream()
                .filter(batch -> batch.patternId().equals(fixture.external().id())).findFirst().orElseThrow();
        PlannedBatchCause.Input cause = (PlannedBatchCause.Input) external.cause();
        PlannedPatternBatch tamperedExternal = new PlannedPatternBatch(external.id(),
                new PlannedBatchCause.Input(cause.consumerBatchId(), cause.cycleMemberIndex(), cause.inputIndex(),
                        cause.candidateIndex(), cause.key(), AEAmount.ONE),
                external.patternId(), external.executions(), external.inputs());
        List<PlannedCausalStep> steps = fixture.draft().causalSteps().stream()
                .map(step -> step.id().equals(external.id()) ? tamperedExternal : step).toList();
        ExactCraftPlanDraft tampered = copyDraft(fixture.draft(), fixture.draft().patternExecutions(),
                fixture.draft().storageConsumed(), fixture.draft().surplus(), steps);

        assertEquals(ExactPlanValidationResult.FailureReason.MALFORMED_STEP,
                failure(tampered, fixture.storage(), fixture.patterns()).reason());
    }

    private static ExactCraftPlanDraft copyDraft(ExactCraftPlanDraft source, Map<PatternId, AEAmount> executions,
            Map<KeyId, AEAmount> storageConsumed, Map<KeyId, AEAmount> surplus,
            List<? extends PlannedCausalStep> steps) {
        return new ExactCraftPlanDraft(source.gridRevision(), source.request(), executions, storageConsumed, surplus,
                steps, source.dependencies());
    }

    private static ExactCraftPlanDraft copyDraft(ExactCraftPlanDraft source, Map<KeyId, AEAmount> storageConsumed,
            Map<KeyId, AEAmount> surplus) {
        return copyDraft(source, source.patternExecutions(), storageConsumed, surplus, source.causalSteps());
    }

    private static ExactCraftPlanDraft success(StorageSnapshot storage, List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities, ExactCraftRequest request) {
        return assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(request, storage, patternSnapshot(patterns, priorities))).draft();
    }

    private static ExactCraftingPlan sealed(ExactCraftPlanDraft draft, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns) {
        return assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, storage, patterns)).plan();
    }

    private static ExactPlanValidationResult.Failure failure(ExactCraftPlanDraft draft, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns) {
        return assertInstanceOf(ExactPlanValidationResult.Failure.class, VALIDATOR.validate(draft, storage, patterns));
    }

    private static void assertUnmodifiable(Map<?, ?> values) {
        assertThrows(UnsupportedOperationException.class, values::clear);
    }

    private static NormalChain normalChain() {
        CompiledPattern producer = noInput("validator-cause-producer", 2, 1L);
        CompiledPattern consumer = pattern("validator-cause-consumer", List.of(input(2, AEAmount.ONE,
                List.of(candidate(2, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)));
        StorageSnapshot storage = snapshot(3, Map.of());
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(consumer, producer),
                Map.of(consumer.id(), 10, producer.id(), 0));
        ExactCraftPlanDraft draft = success(storage, List.of(consumer, producer),
                Map.of(consumer.id(), 10, producer.id(), 0), request(1, 1L));
        return new NormalChain(draft, storage, patterns, producer, consumer);
    }

    private static CycleExternalFixture cycleExternalFixture() {
        CompiledPattern external = noInput("validator-cycle-external", 2, 1L);
        CompiledPattern cycle = pattern("validator-cycle-with-external", List.of(
                input(1, AEAmount.ONE, List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT),
                input(2, AEAmount.ONE, List.of(candidate(2, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(1, 2L, true)));
        CompiledPattern outer = pattern("validator-cycle-outer", List.of(input(1, AEAmount.ONE,
                List.of(candidate(1, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(3, 1L, true)));
        StorageSnapshot storage = snapshot(4, Map.of(1, AEAmount.ONE));
        List<CompiledPattern> compiled = List.of(outer, cycle, external);
        Map<PatternId, Integer> priorities = Map.of(outer.id(), 10, cycle.id(), 10, external.id(), 0);
        NormalizedPatternSnapshot patterns = patternSnapshot(compiled, priorities);
        ExactCraftPlanDraft draft = success(storage, compiled, priorities, request(3, 3L));
        return new CycleExternalFixture(draft, storage, patterns, external, cycle, outer);
    }

    private record NormalChain(ExactCraftPlanDraft draft, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns, CompiledPattern producer, CompiledPattern consumer) {
    }

    private record CycleExternalFixture(ExactCraftPlanDraft draft, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns, CompiledPattern external, CompiledPattern cycle,
            CompiledPattern outer) {
    }

    private static ExactCraftRequest request(int output, long amount) {
        return request(output, AEAmount.of(amount));
    }

    private static ExactCraftRequest request(int output, AEAmount amount) {
        return new ExactCraftRequest(new KeyId(output), amount);
    }

    private static CompiledPattern selfCycle(String id, int key, long outputAmount) {
        return pattern(id, List.of(input(key, AEAmount.ONE,
                List.of(candidate(key, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(key, outputAmount, true)));
    }

    private static CompiledPattern noInput(String id, int outputKey, long outputAmount) {
        return pattern(id, List.of(), List.of(output(outputKey, outputAmount, true)));
    }

    private static CompiledPattern pattern(String id, List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs) {
        return pattern(id, new PatternRevision(0L), inputs, outputs);
    }

    private static CompiledPattern pattern(String id, PatternRevision revision, List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs) {
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs, outputs, Optional.empty(),
                revision, KEY_GENERATION);
    }

    private static CompiledInputSpec input(int key, AEAmount multiplier, List<CompiledCandidateSpec> candidates,
            SubstitutionPolicy policy) {
        return new CompiledInputSpec(candidates, multiplier, policy);
    }

    private static CompiledCandidateSpec candidate(int key, long amount, Optional<CompiledRemainderSpec> remainder) {
        return new CompiledCandidateSpec(new KeyId(key), AEAmount.of(amount), remainder);
    }

    private static CompiledRemainderSpec remainder(int key, long amount) {
        return new CompiledRemainderSpec(new KeyId(key), AEAmount.of(amount));
    }

    private static CompiledOutputSpec output(int key, long amount, boolean primary) {
        return new CompiledOutputSpec(new KeyId(key), AEAmount.of(amount), primary);
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
