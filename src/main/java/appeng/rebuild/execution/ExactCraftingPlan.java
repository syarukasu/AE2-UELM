package appeng.rebuild.execution;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.planner.DependencySet;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.planner.GridRevision;
import appeng.rebuild.planner.PlannedCausalStep;
import appeng.rebuild.planner.PlannedCycleBatch;
import appeng.rebuild.planner.PlannedCycleMember;
import appeng.rebuild.planner.PlannedPatternBatch;
import appeng.rebuild.quantity.AEAmount;

/**
 * Immutable exact plan sealed against immutable storage and pattern snapshots.
 *
 * <p>
 * This value is deliberately not execution authority. A later CPU ledger must atomically reserve its
 * {@link #initialStorageDebits()} and recheck its dependencies before any physical mutation takes place.
 */
public final class ExactCraftingPlan {
    private final ExactPlanId planId;
    private final GridRevision planningRevision;
    private final GridRevision validationRevision;
    private final ExactCraftRequest request;
    private final List<PlannedCausalStep> causalSteps;
    private final List<ExecutionManifest> executionManifests;
    private final Map<PatternId, PatternRevision> usedPatternRevisions;
    private final Map<PatternId, AEAmount> patternExecutions;
    private final Map<KeyId, AEAmount> initialStorageDebits;
    private final Map<KeyId, AEAmount> finalSurplus;
    private final DependencySet dependencies;

    ExactCraftingPlan(ExactPlanId planId, GridRevision planningRevision, GridRevision validationRevision,
            ExactCraftRequest request,
            List<PlannedCausalStep> causalSteps, List<ExecutionManifest> executionManifests,
            Map<PatternId, PatternRevision> usedPatternRevisions,
            Map<PatternId, AEAmount> patternExecutions, Map<KeyId, AEAmount> initialStorageDebits,
            Map<KeyId, AEAmount> finalSurplus, DependencySet dependencies) {
        this.planId = Objects.requireNonNull(planId, "planId");
        this.planningRevision = Objects.requireNonNull(planningRevision, "planningRevision");
        this.validationRevision = Objects.requireNonNull(validationRevision, "validationRevision");
        this.request = Objects.requireNonNull(request, "request");
        this.causalSteps = List.copyOf(Objects.requireNonNull(causalSteps, "causalSteps"));
        this.usedPatternRevisions = copyPatternRevisions(usedPatternRevisions);
        this.executionManifests = copyAndValidateManifests(executionManifests, this.causalSteps,
                this.usedPatternRevisions);
        this.patternExecutions = copyPatternAmounts(patternExecutions, "patternExecutions");
        this.initialStorageDebits = copyKeyAmounts(initialStorageDebits, "initialStorageDebits");
        this.finalSurplus = copyKeyAmounts(finalSurplus, "finalSurplus");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    /** Durable identity assigned by {@link ExactCraftingPlanValidator} when this plan was successfully sealed. */
    public ExactPlanId planId() {
        return planId;
    }

    public GridRevision planningRevision() {
        return planningRevision;
    }

    /** Identity captured from the immutable snapshots that successfully sealed this plan. */
    public GridRevision validationRevision() {
        return validationRevision;
    }

    public ExactCraftRequest request() {
        return request;
    }

    public List<PlannedCausalStep> causalSteps() {
        return causalSteps;
    }

    /**
     * Immutable compiled execution data in the same causal order as {@link #causalSteps()}.
     *
     * <p>
     * Executors must use this sealed data rather than consulting the current pattern registry after a reload.
     */
    public List<ExecutionManifest> executionManifests() {
        return executionManifests;
    }

    public Map<PatternId, PatternRevision> usedPatternRevisions() {
        return usedPatternRevisions;
    }

    public Map<PatternId, AEAmount> patternExecutions() {
        return patternExecutions;
    }

    public Map<KeyId, AEAmount> initialStorageDebits() {
        return initialStorageDebits;
    }

    public Map<KeyId, AEAmount> finalSurplus() {
        return finalSurplus;
    }

    public DependencySet dependencies() {
        return dependencies;
    }

    private static Map<PatternId, PatternRevision> copyPatternRevisions(Map<PatternId, PatternRevision> source) {
        Objects.requireNonNull(source, "usedPatternRevisions");
        TreeMap<PatternId, PatternRevision> copy = new TreeMap<>();
        for (Map.Entry<PatternId, PatternRevision> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "usedPatternRevisions key"),
                    Objects.requireNonNull(entry.getValue(), "usedPatternRevisions value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<ExecutionManifest> copyAndValidateManifests(List<ExecutionManifest> source,
            List<PlannedCausalStep> steps, Map<PatternId, PatternRevision> usedRevisions) {
        source = List.copyOf(Objects.requireNonNull(source, "executionManifests"));
        if (source.size() != steps.size()) {
            throw new IllegalArgumentException("Each causal step requires exactly one execution manifest");
        }
        TreeMap<PatternId, PatternRevision> sealedRevisions = new TreeMap<>();
        for (int index = 0; index < steps.size(); index++) {
            PlannedCausalStep step = Objects.requireNonNull(steps.get(index), "causalSteps cannot contain null");
            ExecutionManifest manifest = Objects.requireNonNull(source.get(index),
                    "executionManifests cannot contain null");
            if (!step.id().equals(manifest.batchId()) || !step.cause().equals(manifest.cause())) {
                throw new IllegalArgumentException("Execution manifest identity must match its causal step");
            }
            validateManifestShape(step, manifest);
            for (SealedPatternExecution execution : manifest.patternExecutions()) {
                PatternId id = execution.pattern().id();
                PatternRevision prior = sealedRevisions.putIfAbsent(id, execution.pattern().revision());
                if (prior != null && !prior.equals(execution.pattern().revision())) {
                    throw new IllegalArgumentException("One sealed plan cannot use conflicting pattern revisions");
                }
            }
        }
        if (!sealedRevisions.equals(usedRevisions)) {
            throw new IllegalArgumentException("Sealed execution manifests must cover every used pattern revision");
        }
        return source;
    }

    private static void validateManifestShape(PlannedCausalStep step, ExecutionManifest manifest) {
        if (step instanceof PlannedPatternBatch batch && manifest instanceof NormalExecutionManifest normal) {
            SealedPatternExecution execution = normal.execution();
            if (!batch.patternId().equals(execution.pattern().id())
                    || !batch.executions().equals(execution.executions())
                    || !batch.inputs().equals(execution.plannedSelections())) {
                throw new IllegalArgumentException("Normal execution manifest differs from its causal batch");
            }
            return;
        }
        if (step instanceof PlannedCycleBatch batch && manifest instanceof CycleExecutionManifest cycle) {
            if (!batch.repetitions().equals(cycle.repetitions()) || !batch.seedKey().equals(cycle.seedKey())
                    || !batch.seedAmount().equals(cycle.seedAmount()) || !batch.links().equals(cycle.links())
                    || !batch.finalCredits().equals(cycle.finalCredits())
                    || batch.members().size() != cycle.memberExecutions().size()) {
                throw new IllegalArgumentException("Cycle execution manifest differs from its causal batch");
            }
            for (int index = 0; index < batch.members().size(); index++) {
                PlannedCycleMember member = batch.members().get(index);
                SealedPatternExecution execution = cycle.memberExecutions().get(index);
                if (!member.patternId().equals(execution.pattern().id())
                        || !member.executionsPerTurn().equals(execution.executions())
                        || !member.inputsPerTurn().equals(execution.plannedSelections())
                        || !member.outputsPerTurn().equals(execution.plannedOutputs())) {
                    throw new IllegalArgumentException("Cycle member execution manifest differs from its causal batch");
                }
            }
            return;
        }
        throw new IllegalArgumentException("Execution manifest type must match its causal step");
    }

    private static Map<PatternId, AEAmount> copyPatternAmounts(Map<PatternId, AEAmount> source, String name) {
        Objects.requireNonNull(source, name);
        TreeMap<PatternId, AEAmount> copy = new TreeMap<>();
        for (Map.Entry<PatternId, AEAmount> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"),
                    requirePositive(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<KeyId, AEAmount> copyKeyAmounts(Map<KeyId, AEAmount> source, String name) {
        Objects.requireNonNull(source, name);
        TreeMap<KeyId, AEAmount> copy = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, AEAmount> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"),
                    requirePositive(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static AEAmount requirePositive(AEAmount amount, String name) {
        amount = Objects.requireNonNull(amount, name);
        if (amount.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return amount;
    }
}
