package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
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
import appeng.rebuild.planner.PlannedCycleBatch;
import appeng.rebuild.planner.PlannedCycleMember;
import appeng.rebuild.planner.PlannedPatternBatch;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Contract tests for the immutable execution manifest sealed into an exact plan. */
class ExecutionManifestSealTest {
    private static final long KEY_GENERATION = 7L;
    private static final long SERVER_GENERATION = 9L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(3L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(5L);
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();
    private static final ExactCraftingPlanValidator VALIDATOR = new ExactCraftingPlanValidator();

    @Test
    void oneManifestIsSealedForEveryCausalStepInTheSameOrderWithTheSameIdentity() {
        NormalChain chain = normalChain();
        ExactCraftingPlan plan = sealed(chain.draft(), chain.storage(), chain.patterns());

        assertEquals(chain.draft().causalSteps().size(), plan.executionManifests().size());
        for (int index = 0; index < chain.draft().causalSteps().size(); index++) {
            PlannedCausalStep step = chain.draft().causalSteps().get(index);
            ExecutionManifest manifest = plan.executionManifests().get(index);
            assertEquals(step.id(), manifest.batchId(), "manifest batch id at causal index " + index);
            assertEquals(step.cause(), manifest.cause(), "manifest cause at causal index " + index);
            assertInstanceOf(NormalExecutionManifest.class, manifest);
        }
    }

    @Test
    void normalManifestRetainsTheExactCompiledPatternRevisionKindIntentInputsOutputsAndSelections() {
        List<CompiledInputSpec> sourceInputs = new ArrayList<>();
        sourceInputs.add(input(0, 2L, List.of(candidate(0, 1L)), SubstitutionPolicy.EXACT));
        List<CompiledOutputSpec> sourceOutputs = new ArrayList<>();
        sourceOutputs.add(output(1, 1L, true));
        CompiledPattern source = pattern("manifest-normal", sourceInputs, sourceOutputs);

        // Mutating the source collections after compilation must not change the sealed snapshot.
        sourceInputs.clear();
        sourceOutputs.clear();
        StorageSnapshot storage = snapshot(2, Map.of(0, AEAmount.of(2L)));
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(source), Map.of(source.id(), 0));
        ExactCraftPlanDraft draft = success(storage, List.of(source), Map.of(source.id(), 0), request(1, 1L));
        ExactCraftingPlan plan = sealed(draft, storage, patterns);

        PlannedPatternBatch batch = assertInstanceOf(PlannedPatternBatch.class, draft.causalSteps().get(0));
        NormalExecutionManifest manifest = assertInstanceOf(NormalExecutionManifest.class,
                plan.executionManifests().get(0));
        SealedPatternExecution execution = manifest.execution();
        CompiledPattern sealedPattern = execution.pattern();

        assertEquals(source.id(), sealedPattern.id());
        assertEquals(source.revision(), sealedPattern.revision());
        assertEquals(source.kind(), sealedPattern.kind());
        assertEquals(source.machineIntent(), sealedPattern.machineIntent());
        assertEquals(source.inputs(), sealedPattern.inputs());
        assertEquals(source.outputs(), sealedPattern.outputs());
        assertEquals(batch.executions(), execution.executions());
        assertEquals(batch.inputs(), execution.plannedSelections());
        assertEquals(List.of(), execution.plannedOutputs());
        assertEquals(source.inputs(), sealedPattern.inputs(), "source list mutation leaked into the manifest");
        assertEquals(source.outputs(), sealedPattern.outputs(), "source list mutation leaked into the manifest");

        assertThrows(UnsupportedOperationException.class, () -> plan.executionManifests().clear());
        assertThrows(UnsupportedOperationException.class, () -> execution.plannedSelections().clear());
        assertThrows(UnsupportedOperationException.class, () -> sealedPattern.inputs().clear());
        assertThrows(UnsupportedOperationException.class, () -> sealedPattern.outputs().clear());
    }

    @Test
    void cycleManifestRetainsEveryMemberLinkSeedRepetitionOutputAndCreditExactly() {
        CycleFixture fixture = cycleFixture();
        ExactCraftingPlan plan = sealed(fixture.draft(), fixture.storage(), fixture.patterns());
        PlannedCycleBatch cycle = fixture.draft().causalSteps().stream()
                .filter(PlannedCycleBatch.class::isInstance).map(PlannedCycleBatch.class::cast).findFirst()
                .orElseThrow();
        CycleExecutionManifest manifest = plan.executionManifests().stream()
                .filter(CycleExecutionManifest.class::isInstance)
                .map(CycleExecutionManifest.class::cast).findFirst().orElseThrow();

        assertEquals(cycle.id(), manifest.batchId());
        assertEquals(cycle.cause(), manifest.cause());
        assertEquals(cycle.repetitions(), manifest.repetitions());
        assertEquals(cycle.seedKey(), manifest.seedKey());
        assertEquals(cycle.seedAmount(), manifest.seedAmount());
        assertEquals(cycle.links(), manifest.links());
        assertEquals(cycle.finalCredits(), manifest.finalCredits());
        assertEquals(cycle.members().size(), manifest.memberExecutions().size());
        for (int index = 0; index < cycle.members().size(); index++) {
            PlannedCycleMember planned = cycle.members().get(index);
            SealedPatternExecution sealed = manifest.memberExecutions().get(index);
            assertEquals(planned.patternId(), sealed.pattern().id());
            assertEquals(planned.executionsPerTurn(), sealed.executions());
            assertEquals(planned.inputsPerTurn(), sealed.plannedSelections());
            assertEquals(planned.outputsPerTurn(), sealed.plannedOutputs());
            assertEquals(fixture.patterns().patternsById().get(planned.patternId()).inputs(),
                    sealed.pattern().inputs());
            assertEquals(fixture.patterns().patternsById().get(planned.patternId()).outputs(),
                    sealed.pattern().outputs());
        }

        assertThrows(UnsupportedOperationException.class, () -> manifest.memberExecutions().clear());
        assertThrows(UnsupportedOperationException.class, () -> manifest.links().clear());
        assertThrows(UnsupportedOperationException.class, () -> manifest.finalCredits().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.memberExecutions().get(0).plannedOutputs().clear());
    }

    @Test
    void planConstructorRejectsMissingExtraAndReorderedManifests() {
        NormalChain chain = normalChain();
        ExactCraftingPlan plan = sealed(chain.draft(), chain.storage(), chain.patterns());
        List<ExecutionManifest> valid = plan.executionManifests();

        assertThrows(IllegalArgumentException.class, () -> reconstruct(plan, valid.subList(0, 1),
                plan.usedPatternRevisions()));

        List<ExecutionManifest> extra = new ArrayList<>(valid);
        extra.add(valid.get(0));
        assertThrows(IllegalArgumentException.class, () -> reconstruct(plan, extra, plan.usedPatternRevisions()));

        assertThrows(IllegalArgumentException.class,
                () -> reconstruct(plan, List.of(valid.get(1), valid.get(0)), plan.usedPatternRevisions()));
    }

    @Test
    void planConstructorRejectsWrongManifestTypeRevisionAndCause() {
        NormalChain chain = normalChain();
        ExactCraftingPlan plan = sealed(chain.draft(), chain.storage(), chain.patterns());
        List<ExecutionManifest> valid = plan.executionManifests();
        PlannedPatternBatch firstStep = assertInstanceOf(PlannedPatternBatch.class, chain.draft().causalSteps().get(0));
        NormalExecutionManifest first = assertInstanceOf(NormalExecutionManifest.class, valid.get(0));

        CycleExecutionManifest foreignCycle = assertInstanceOf(CycleExecutionManifest.class,
                cycleFixture().sealedPlan().executionManifests().stream()
                        .filter(CycleExecutionManifest.class::isInstance)
                        .findFirst().orElseThrow());
        CycleExecutionManifest wrongType = new CycleExecutionManifest(first.batchId(), first.cause(),
                foreignCycle.repetitions(), foreignCycle.seedKey(), foreignCycle.seedAmount(),
                foreignCycle.memberExecutions(), foreignCycle.links(), foreignCycle.finalCredits());
        assertThrows(IllegalArgumentException.class,
                () -> reconstruct(plan, List.of(wrongType, valid.get(1)), plan.usedPatternRevisions()));

        CompiledPattern original = first.execution().pattern();
        CompiledPattern revised = new CompiledPattern(original.id(), original.kind(), original.inputs(),
                original.outputs(), original.machineIntent(), new PatternRevision(original.revision().value() + 1L),
                original.keyRegistryGeneration());
        NormalExecutionManifest wrongRevision = new NormalExecutionManifest(first.batchId(), first.cause(),
                new SealedPatternExecution(revised, first.execution().executions(),
                        first.execution().plannedSelections(),
                        first.execution().plannedOutputs()));
        assertThrows(IllegalArgumentException.class,
                () -> reconstruct(plan, List.of(wrongRevision, valid.get(1)), plan.usedPatternRevisions()));

        PlannedBatchCause wrongCause = new PlannedBatchCause.Root(new KeyId(99), AEAmount.ONE);
        NormalExecutionManifest wrongCauseManifest = new NormalExecutionManifest(first.batchId(), wrongCause,
                first.execution());
        assertEquals(firstStep.id(), wrongCauseManifest.batchId());
        assertThrows(IllegalArgumentException.class,
                () -> reconstruct(plan, List.of(wrongCauseManifest, valid.get(1)), plan.usedPatternRevisions()));
    }

    @Test
    void planConstructorRejectsManifestCoverageThatOmitsOrAddsAUsedPatternRevision() {
        NormalChain chain = normalChain();
        ExactCraftingPlan plan = sealed(chain.draft(), chain.storage(), chain.patterns());

        Map<PatternId, PatternRevision> missing = new java.util.TreeMap<>(plan.usedPatternRevisions());
        missing.remove(chain.producer().id());
        assertThrows(IllegalArgumentException.class,
                () -> reconstruct(plan, plan.executionManifests(), missing));

        Map<PatternId, PatternRevision> extra = new java.util.TreeMap<>(plan.usedPatternRevisions());
        extra.put(new PatternId("manifest-unsealed-pattern"), new PatternRevision(0L));
        assertThrows(IllegalArgumentException.class,
                () -> reconstruct(plan, plan.executionManifests(), extra));
    }

    private static ExactCraftingPlan reconstruct(ExactCraftingPlan source, List<ExecutionManifest> manifests,
            Map<PatternId, PatternRevision> usedPatternRevisions) {
        return new ExactCraftingPlan(source.planId(), source.planningRevision(), source.validationRevision(),
                source.request(), source.causalSteps(), manifests, usedPatternRevisions, source.patternExecutions(),
                source.initialStorageDebits(), source.finalSurplus(), source.dependencies());
    }

    private static ExactCraftingPlan sealed(ExactCraftPlanDraft draft, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns) {
        return assertInstanceOf(ExactPlanValidationResult.Success.class, VALIDATOR.validate(draft, storage, patterns))
                .plan();
    }

    private static ExactCraftPlanDraft success(StorageSnapshot storage, List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities, ExactCraftRequest request) {
        return assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(request, storage, patternSnapshot(patterns, priorities))).draft();
    }

    private static NormalChain normalChain() {
        CompiledPattern producer = noInput("manifest-producer", 2, 1L);
        CompiledPattern consumer = pattern("manifest-consumer", List.of(input(2, 1L,
                List.of(candidate(2, 1L)), SubstitutionPolicy.EXACT)), List.of(output(1, 1L, true)));
        StorageSnapshot storage = snapshot(3, Map.of());
        List<CompiledPattern> compiled = List.of(consumer, producer);
        Map<PatternId, Integer> priorities = Map.of(consumer.id(), 10, producer.id(), 0);
        NormalizedPatternSnapshot patterns = patternSnapshot(compiled, priorities);
        ExactCraftPlanDraft draft = success(storage, compiled, priorities, request(1, 1L));
        return new NormalChain(draft, storage, patterns, producer, consumer);
    }

    private static CycleFixture cycleFixture() {
        CompiledPattern self = pattern("manifest-cycle", List.of(input(1, 1L,
                List.of(candidate(1, 1L)), SubstitutionPolicy.EXACT)), List.of(output(1, 2L, true)));
        StorageSnapshot storage = snapshot(2, Map.of(1, AEAmount.ONE));
        List<CompiledPattern> compiled = List.of(self);
        Map<PatternId, Integer> priorities = Map.of(self.id(), 10);
        NormalizedPatternSnapshot patterns = patternSnapshot(compiled, priorities);
        ExactCraftPlanDraft draft = success(storage, compiled, priorities, request(1, 5L));
        ExactCraftingPlan plan = sealed(draft, storage, patterns);
        return new CycleFixture(draft, storage, patterns, plan);
    }

    private record NormalChain(ExactCraftPlanDraft draft, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns, CompiledPattern producer, CompiledPattern consumer) {
    }

    private record CycleFixture(ExactCraftPlanDraft draft, StorageSnapshot storage,
            NormalizedPatternSnapshot patterns, ExactCraftingPlan sealedPlan) {
    }

    private static ExactCraftRequest request(int output, long amount) {
        return new ExactCraftRequest(new KeyId(output), AEAmount.of(amount));
    }

    private static CompiledPattern noInput(String id, int outputKey, long outputAmount) {
        return pattern(id, List.of(), List.of(output(outputKey, outputAmount, true)));
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

    private static CompiledCandidateSpec candidate(int key, long amount) {
        return new CompiledCandidateSpec(new KeyId(key), AEAmount.of(amount), Optional.empty());
    }

    private static CompiledOutputSpec output(int key, long amount, boolean primary) {
        return new CompiledOutputSpec(new KeyId(key), AEAmount.of(amount), primary);
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
        for (Map.Entry<Integer, AEAmount> value : values.entrySet()) {
            amounts.set(value.getKey(), value.getValue());
        }
        return new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, keyCount, amounts, revisions);
    }
}
