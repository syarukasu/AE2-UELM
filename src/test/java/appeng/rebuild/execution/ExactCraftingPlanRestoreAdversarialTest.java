package appeng.rebuild.execution;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
import appeng.rebuild.planner.DependencySet;
import appeng.rebuild.planner.ExactCraftPlanResult;
import appeng.rebuild.planner.ExactCraftPlanner;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.planner.GridRevision;
import appeng.rebuild.planner.PlannedBatchCause;
import appeng.rebuild.planner.PlannedBatchId;
import appeng.rebuild.planner.PlannedCausalStep;
import appeng.rebuild.planner.PlannedCycleBatch;
import appeng.rebuild.planner.PlannedCycleLink;
import appeng.rebuild.planner.PlannedCycleMember;
import appeng.rebuild.planner.PlannedCycleOutput;
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.planner.PlannedPatternBatch;
import appeng.rebuild.planner.PlannedRemainderReturn;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Tests the public restore boundary against forged sealed arithmetic and bounded hostile collections. */
class ExactCraftingPlanRestoreAdversarialTest {
    private static final long KEY_GENERATION = 7L;
    private static final long SERVER_GENERATION = 9L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(3L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(5L);
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();
    private static final ExactCraftingPlanValidator VALIDATOR = new ExactCraftingPlanValidator();

    @Test
    void matchingStepAndManifestSelectionsRejectWrongTemplateGrossInitialAndRemainder() {
        ExactCraftingPlan plan = normalPlan(2L);
        PlannedInputSelection original = ((PlannedPatternBatch) plan.causalSteps().get(0)).inputs().get(0);
        KeyId inputKey = original.consumedKey();
        KeyId outputKey = plan.request().output();

        List<PlannedInputSelection> forged = List.of(
                new PlannedInputSelection(original.inputIndex(), original.candidateIndex(), AEAmount.of(2L), inputKey,
                        original.grossConsumedAmount(), original.initialRequiredAmount(), Optional.empty()),
                new PlannedInputSelection(original.inputIndex(), original.candidateIndex(), original.templateUnits(),
                        inputKey, AEAmount.ONE, AEAmount.ONE, Optional.empty()),
                new PlannedInputSelection(original.inputIndex(), original.candidateIndex(), original.templateUnits(),
                        inputKey, original.grossConsumedAmount(), AEAmount.ONE, Optional.empty()),
                new PlannedInputSelection(original.inputIndex(), original.candidateIndex(), original.templateUnits(),
                        inputKey, original.grossConsumedAmount(), original.initialRequiredAmount(),
                        Optional.of(new PlannedRemainderReturn(outputKey, AEAmount.ONE))));

        for (PlannedInputSelection selection : forged) {
            assertThrows(IllegalArgumentException.class, () -> restoreWithSelection(plan, selection));
        }
    }

    @Test
    void forgedCyclePlannedOutputCreditsAndRepetitionsCannotCrossRestoreBoundary() {
        ExactCraftingPlan plan = cyclePlan();
        PlannedCycleBatch cycle = (PlannedCycleBatch) plan.causalSteps().get(0);
        CycleExecutionManifest manifest = (CycleExecutionManifest) plan.executionManifests().get(0);

        PlannedCycleMember originalMember = cycle.members().get(0);
        PlannedCycleOutput originalOutput = originalMember.outputsPerTurn().get(0);
        PlannedCycleOutput forgedOutput = new PlannedCycleOutput(originalOutput.outputIndex(), originalOutput.key(),
                originalOutput.amountPerTurn().add(AEAmount.ONE));
        PlannedCycleMember forgedMember = new PlannedCycleMember(originalMember.patternId(),
                originalMember.executionsPerTurn(), originalMember.inputsPerTurn(), List.of(forgedOutput));
        PlannedCycleLink closing = cycle.links().get(cycle.links().size() - 1);
        AEAmount forgedPerTurnCredit = forgedOutput.amountPerTurn().subtractExact(closing.amountPerTurn());
        AEAmount forgedCredit = forgedPerTurnCredit.multiply(cycle.repetitions()).add(cycle.seedAmount());
        PlannedCycleBatch forgedCycle = new PlannedCycleBatch(cycle.id(), cycle.cause(), cycle.repetitions(),
                cycle.seedKey(), cycle.seedAmount(), List.of(forgedMember), cycle.links(), Map.of(cycle.seedKey(),
                        forgedCredit));
        SealedPatternExecution forgedExecution = new SealedPatternExecution(
                manifest.memberExecutions().get(0).pattern(),
                manifest.memberExecutions().get(0).executions(), forgedMember.inputsPerTurn(),
                forgedMember.outputsPerTurn());
        CycleExecutionManifest forgedManifest = new CycleExecutionManifest(manifest.batchId(), manifest.cause(),
                manifest.repetitions(), manifest.seedKey(), manifest.seedAmount(), List.of(forgedExecution),
                manifest.links(), forgedCycle.finalCredits());
        assertThrows(IllegalArgumentException.class,
                () -> restoreWith(plan, List.of(forgedCycle), List.of(forgedManifest), plan.validationRevision(),
                        plan.dependencies()));

        Map<KeyId, AEAmount> forgedCredits = Map.of(cycle.seedKey(), cycle.finalCredits().get(cycle.seedKey()).add(
                AEAmount.ONE));
        CycleExecutionManifest creditManifest = new CycleExecutionManifest(manifest.batchId(), manifest.cause(),
                manifest.repetitions(), manifest.seedKey(), manifest.seedAmount(), manifest.memberExecutions(),
                manifest.links(), forgedCredits);
        assertThrows(IllegalArgumentException.class,
                () -> restoreWith(plan, plan.causalSteps(), List.of(creditManifest), plan.validationRevision(),
                        plan.dependencies()));

        CycleExecutionManifest repetitionManifest = new CycleExecutionManifest(manifest.batchId(), manifest.cause(),
                manifest.repetitions().add(AEAmount.ONE), manifest.seedKey(), manifest.seedAmount(),
                manifest.memberExecutions(), manifest.links(), manifest.finalCredits());
        assertThrows(IllegalArgumentException.class,
                () -> restoreWith(plan, plan.causalSteps(), List.of(repetitionManifest), plan.validationRevision(),
                        plan.dependencies()));
    }

    @Test
    void provenanceMissingPatternDependencyAndForgedSummariesAreRejected() {
        ExactCraftingPlan plan = normalPlan(2L);
        GridRevision provenance = new GridRevision(SERVER_GENERATION + 1, KEY_GENERATION, GRAPH_GENERATION,
                RECIPE_REVISION, StorageRevision.ZERO);
        assertThrows(IllegalArgumentException.class,
                () -> restoreWith(plan, plan.causalSteps(), plan.executionManifests(), provenance,
                        plan.dependencies()));

        DependencySet.Builder missingBuilder = new DependencySet.Builder(plan.dependencies().gridRevision());
        plan.dependencies().storageKeyRevisions().forEach(missingBuilder::recordStorageRead);
        DependencySet missingPattern = missingBuilder.build();
        assertThrows(IllegalArgumentException.class,
                () -> restoreWith(plan, plan.causalSteps(), plan.executionManifests(), plan.validationRevision(),
                        missingPattern));

        assertThrows(IllegalArgumentException.class,
                () -> restoreWith(plan, plan.causalSteps(), plan.executionManifests(), plan.validationRevision(),
                        plan.dependencies(), plan.initialStorageDebits(), Map.of(new KeyId(0), AEAmount.ONE)));
    }

    @Test
    void rootOutputStorageShortcutIsRejectedEvenWhenItsSealedArithmeticMatches() {
        CompiledPattern pattern = pattern("root-storage-shortcut", List.of(input(1, 2L)),
                List.of(output(1, 1L)));
        GridRevision revision = revision();
        PlannedBatchId batchId = new PlannedBatchId(1L);
        ExactCraftRequest request = new ExactCraftRequest(new KeyId(1), AEAmount.ONE);
        PlannedBatchCause.Root cause = new PlannedBatchCause.Root(new KeyId(1), AEAmount.ONE);
        PlannedInputSelection selection = new PlannedInputSelection(0, 0, AEAmount.ONE, new KeyId(1),
                AEAmount.of(2L), AEAmount.of(2L), Optional.empty());
        PlannedPatternBatch batch = new PlannedPatternBatch(batchId, cause, pattern.id(), AEAmount.ONE,
                List.of(selection));
        SealedPatternExecution execution = new SealedPatternExecution(pattern, AEAmount.ONE, List.of(selection),
                List.of());
        NormalExecutionManifest manifest = new NormalExecutionManifest(batchId, cause, execution);
        DependencySet.Builder dependencies = new DependencySet.Builder(revision);
        dependencies.recordPatternRead(pattern.id(), pattern.revision());

        assertThrows(IllegalArgumentException.class, () -> ExactCraftingPlan.restoreValidated(
                new ExactPlanId(UUID.randomUUID()), revision, revision, request, List.of(batch), List.of(manifest),
                Map.of(pattern.id(), pattern.revision()), Map.of(pattern.id(), AEAmount.ONE),
                Map.of(new KeyId(1), AEAmount.of(2L)), Map.of(), dependencies.build()));
    }

    @Test
    void oversizedCustomCausalCollectionIsRejectedBeforeIteration() {
        ExactCraftingPlan plan = normalPlan(2L);
        List<PlannedCausalStep> oversized = new AbstractList<>() {
            @Override
            public PlannedCausalStep get(int index) {
                throw new AssertionError("preflight must reject by size before iteration");
            }

            @Override
            public int size() {
                return appeng.rebuild.planner.PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS + 1;
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> restoreWith(plan, (List<PlannedCausalStep>) (List<?>) oversized, plan.executionManifests(),
                        plan.validationRevision(),
                        plan.dependencies()));
    }

    private static ExactCraftingPlan restoreWithSelection(ExactCraftingPlan plan, PlannedInputSelection selection) {
        PlannedPatternBatch original = (PlannedPatternBatch) plan.causalSteps().get(0);
        PlannedPatternBatch step = new PlannedPatternBatch(original.id(), original.cause(), original.patternId(),
                original.executions(), List.of(selection));
        NormalExecutionManifest originalManifest = (NormalExecutionManifest) plan.executionManifests().get(0);
        SealedPatternExecution sealed = new SealedPatternExecution(originalManifest.execution().pattern(),
                originalManifest.execution().executions(), List.of(selection), List.of());
        NormalExecutionManifest manifest = new NormalExecutionManifest(step.id(), step.cause(), sealed);
        return restoreWith(plan, List.of(step), List.of(manifest), plan.validationRevision(), plan.dependencies());
    }

    private static ExactCraftingPlan restoreWith(ExactCraftingPlan plan,
            List<? extends PlannedCausalStep> steps, List<ExecutionManifest> manifests, GridRevision validation,
            DependencySet dependencies) {
        return restoreWith(plan, steps, manifests, validation, dependencies, plan.initialStorageDebits(),
                plan.finalSurplus());
    }

    @SuppressWarnings("unchecked")
    private static ExactCraftingPlan restoreWith(ExactCraftingPlan plan,
            List<? extends PlannedCausalStep> steps, List<ExecutionManifest> manifests, GridRevision validation,
            DependencySet dependencies, Map<KeyId, AEAmount> debits, Map<KeyId, AEAmount> surplus) {
        List<PlannedCausalStep> typedSteps = (List<PlannedCausalStep>) (List<?>) steps;
        return ExactCraftingPlan.restoreValidated(plan.planId(), plan.planningRevision(), validation, plan.request(),
                typedSteps, manifests, plan.usedPatternRevisions(), plan.patternExecutions(), debits, surplus,
                dependencies);
    }

    private static ExactCraftingPlan normalPlan(long amount) {
        CompiledPattern normal = pattern("restore-normal-" + amount, List.of(input(0, amount)),
                List.of(output(1, 1L)));
        StorageSnapshot storage = snapshot(2, Map.of(0, AEAmount.of(amount)));
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(normal), Map.of(normal.id(), 0));
        ExactCraftPlanResult.Success planned = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(new ExactCraftRequest(new KeyId(1), AEAmount.ONE), storage, patterns));
        return assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(planned.draft(), storage, patterns)).plan();
    }

    private static ExactCraftingPlan cyclePlan() {
        CompiledPattern self = pattern("restore-cycle", List.of(input(0, 1L)), List.of(output(0, 2L)));
        StorageSnapshot storage = snapshot(1, Map.of(0, AEAmount.ONE));
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(self), Map.of(self.id(), 10));
        ExactCraftPlanResult.Success planned = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(new ExactCraftRequest(new KeyId(0), AEAmount.of(5L)), storage, patterns));
        ExactCraftingPlan result = assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(planned.draft(), storage, patterns)).plan();
        assertInstanceOf(PlannedCycleBatch.class, result.causalSteps().get(0));
        return result;
    }

    private static GridRevision revision() {
        return new GridRevision(SERVER_GENERATION, KEY_GENERATION, GRAPH_GENERATION, RECIPE_REVISION,
                StorageRevision.ZERO);
    }

    private static CompiledPattern pattern(String id, List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs) {
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING, inputs, outputs, Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
    }

    private static CompiledInputSpec input(int key, long amount) {
        return new CompiledInputSpec(List.of(new CompiledCandidateSpec(new KeyId(key), AEAmount.of(amount),
                Optional.empty())), AEAmount.ONE, SubstitutionPolicy.EXACT);
    }

    private static CompiledOutputSpec output(int key, long amount) {
        return new CompiledOutputSpec(new KeyId(key), AEAmount.of(amount), true);
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
}
