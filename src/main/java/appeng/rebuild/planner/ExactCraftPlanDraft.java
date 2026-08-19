package appeng.rebuild.planner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.quantity.AEAmount;

/**
 * Immutable, non-authoritative exact crafting draft. It grants no execution authority; Phase 6 validates and executes
 * its causal steps against a later CPU ledger.
 *
 * <p>
 * The maps are deterministic aggregate views. {@link #causalSteps()} is authoritative. {@link #batches()} is an
 * immutable compatibility projection retaining causal order for normal pattern batches only.
 */
public final class ExactCraftPlanDraft {
    private final GridRevision gridRevision;
    private final ExactCraftRequest request;
    private final Map<PatternId, AEAmount> patternExecutions;
    private final Map<KeyId, AEAmount> storageConsumed;
    private final Map<KeyId, AEAmount> surplus;
    private final List<PlannedCausalStep> causalSteps;
    private final List<PlannedPatternBatch> batches;
    private final DependencySet dependencies;

    /** Creates a draft from authoritative causal steps. */
    public ExactCraftPlanDraft(GridRevision gridRevision, ExactCraftRequest request,
            Map<PatternId, AEAmount> patternExecutions, Map<KeyId, AEAmount> storageConsumed,
            Map<KeyId, AEAmount> surplus, Collection<? extends PlannedCausalStep> causalSteps,
            DependencySet dependencies) {
        this.gridRevision = Objects.requireNonNull(gridRevision, "gridRevision");
        this.request = Objects.requireNonNull(request, "request");
        PlannerAmounts.requireWithinLimit(request.amount(), "request amount");
        List<PlannedCausalStep> copiedSteps = copyCausalSteps(causalSteps);
        validateBounds(patternExecutions, storageConsumed, surplus, copiedSteps);
        this.patternExecutions = copyAmounts(patternExecutions, Comparator.naturalOrder(), "patternExecutions");
        this.storageConsumed = copyAmounts(storageConsumed, Comparator.comparingInt(KeyId::value), "storageConsumed");
        this.surplus = copyAmounts(surplus, Comparator.comparingInt(KeyId::value), "surplus");
        this.causalSteps = copiedSteps;
        this.batches = normalBatchProjection(copiedSteps);
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        if (!gridRevision.equals(dependencies.gridRevision())) {
            throw new IllegalArgumentException("Draft dependencies must use the draft grid revision");
        }
        validateCausalReferences();
        validatePatternExecutions();
    }

    public GridRevision gridRevision() {
        return gridRevision;
    }

    public ExactCraftRequest request() {
        return request;
    }

    public Map<PatternId, AEAmount> patternExecutions() {
        return patternExecutions;
    }

    public Map<KeyId, AEAmount> storageConsumed() {
        return storageConsumed;
    }

    public Map<KeyId, AEAmount> surplus() {
        return surplus;
    }

    /** Authoritative immutable causal step list, in producer-before-consumer causal order. */
    public List<PlannedCausalStep> causalSteps() {
        return causalSteps;
    }

    /** Immutable normal-step projection retained for source compatibility. */
    public List<PlannedPatternBatch> batches() {
        return batches;
    }

    public DependencySet dependencies() {
        return dependencies;
    }

    private void validateCausalReferences() {
        Map<PlannedBatchId, Integer> positions = new HashMap<>();
        for (int index = 0; index < causalSteps.size(); index++) {
            PlannedCausalStep step = causalSteps.get(index);
            if (positions.put(step.id(), index) != null) {
                throw new IllegalArgumentException("Causal step ids must be unique");
            }
        }
        for (int index = 0; index < causalSteps.size(); index++) {
            PlannedCausalStep step = causalSteps.get(index);
            if (step.cause() instanceof PlannedBatchCause.Root root) {
                if (!root.output().equals(request.output()) || !root.demandedAmount().equals(request.amount())) {
                    throw new IllegalArgumentException("Root cause must match the exact craft request");
                }
                continue;
            }
            PlannedBatchCause.Input input = (PlannedBatchCause.Input) step.cause();
            Integer consumerPosition = positions.get(input.consumerBatchId());
            if (consumerPosition == null || consumerPosition <= index) {
                throw new IllegalArgumentException("Input causes must reference a later existing consumer step");
            }
            PlannedCausalStep consumer = causalSteps.get(consumerPosition);
            if (input.cycleMemberIndex() == PlannedBatchCause.Input.NORMAL_CONSUMER_MEMBER) {
                if (!(consumer instanceof PlannedPatternBatch batch)
                        || !matchesSelection(batch.inputs(), input.inputIndex(), input.candidateIndex(), input.key())) {
                    throw new IllegalArgumentException("Normal input cause must reference its consumer selection");
                }
            } else {
                if (!(consumer instanceof PlannedCycleBatch cycle)
                        || input.cycleMemberIndex() >= cycle.members().size()
                        || !matchesSelection(cycle.members().get(input.cycleMemberIndex()).inputsPerTurn(),
                                input.inputIndex(), input.candidateIndex(), input.key())) {
                    throw new IllegalArgumentException(
                            "Cycle input cause must reference its consumer member selection");
                }
            }
        }
    }

    private void validatePatternExecutions() {
        TreeMap<PatternId, AEAmount> totals = new TreeMap<>();
        for (PlannedCausalStep step : causalSteps) {
            if (step instanceof PlannedPatternBatch batch) {
                addExecution(totals, batch.patternId(), batch.executions());
            } else {
                PlannedCycleBatch cycle = (PlannedCycleBatch) step;
                for (PlannedCycleMember member : cycle.members()) {
                    addExecution(totals, member.patternId(), PlannerAmounts.checkedMultiply(member.executionsPerTurn(),
                            cycle.repetitions(), "cycle member executions"));
                }
            }
        }
        if (!totals.equals(patternExecutions)) {
            throw new IllegalArgumentException("Draft pattern executions must equal causal step totals");
        }
    }

    private static void addExecution(Map<PatternId, AEAmount> totals, PatternId patternId, AEAmount amount) {
        totals.merge(patternId, amount, (left, right) -> PlannerAmounts.checkedAdd(left, right, "batch executions"));
    }

    private static boolean matchesSelection(List<PlannedInputSelection> selections, int inputIndex,
            int candidateIndex, KeyId key) {
        for (PlannedInputSelection selection : selections) {
            if (selection.inputIndex() == inputIndex && selection.candidateIndex() == candidateIndex
                    && selection.consumedKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static List<PlannedCausalStep> copyCausalSteps(Collection<? extends PlannedCausalStep> source) {
        Objects.requireNonNull(source, "causalSteps");
        if (source.size() > PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
            throw new IllegalArgumentException("Draft exceeds planning bounds");
        }
        List<PlannedCausalStep> result = new ArrayList<>(source.size());
        for (PlannedCausalStep step : source) {
            result.add(Objects.requireNonNull(step, "causalSteps cannot contain null"));
        }
        return List.copyOf(result);
    }

    private static List<PlannedPatternBatch> normalBatchProjection(List<PlannedCausalStep> causalSteps) {
        List<PlannedPatternBatch> result = new ArrayList<>();
        for (PlannedCausalStep step : causalSteps) {
            if (step instanceof PlannedPatternBatch batch) {
                result.add(batch);
            }
        }
        return List.copyOf(result);
    }

    private static void validateBounds(Map<PatternId, AEAmount> patternExecutions,
            Map<KeyId, AEAmount> storageConsumed, Map<KeyId, AEAmount> surplus,
            List<PlannedCausalStep> causalSteps) {
        if (Objects.requireNonNull(patternExecutions, "patternExecutions").size() > PatternLimits.MAX_GRAPH_NODES
                || Objects.requireNonNull(storageConsumed, "storageConsumed")
                        .size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS
                || Objects.requireNonNull(surplus, "surplus").size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS
                || causalSteps.size() > PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
            throw new IllegalArgumentException("Draft exceeds planning bounds");
        }
    }

    private static <K> Map<K, AEAmount> copyAmounts(Map<K, AEAmount> source, Comparator<K> comparator,
            String name) {
        Objects.requireNonNull(source, name);
        TreeMap<K, AEAmount> result = new TreeMap<>(comparator);
        for (Map.Entry<K, AEAmount> entry : source.entrySet()) {
            K key = Objects.requireNonNull(entry.getKey(), name + " cannot contain null keys");
            AEAmount amount = Objects.requireNonNull(entry.getValue(), name + " cannot contain null amounts");
            if (amount.equals(AEAmount.ZERO)) {
                throw new IllegalArgumentException(name + " cannot contain zero amounts");
            }
            PlannerAmounts.requireWithinLimit(amount, name + " amount");
            result.put(key, amount);
        }
        return Collections.unmodifiableMap(result);
    }
}
