package appeng.rebuild.planner;

import java.util.Collections;
import java.util.Comparator;
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
 * its causal batches against a later CPU ledger.
 *
 * <p>
 * The maps are deterministic aggregate views. {@link #batches()} preserves causal planning order rather than sorting.
 */
public final class ExactCraftPlanDraft {
    private final GridRevision gridRevision;
    private final ExactCraftRequest request;
    private final Map<PatternId, AEAmount> patternExecutions;
    private final Map<KeyId, AEAmount> storageConsumed;
    private final Map<KeyId, AEAmount> surplus;
    private final List<PlannedPatternBatch> batches;
    private final DependencySet dependencies;

    public ExactCraftPlanDraft(GridRevision gridRevision, ExactCraftRequest request,
            Map<PatternId, AEAmount> patternExecutions, Map<KeyId, AEAmount> storageConsumed,
            Map<KeyId, AEAmount> surplus, List<PlannedPatternBatch> batches, DependencySet dependencies) {
        this.gridRevision = Objects.requireNonNull(gridRevision, "gridRevision");
        this.request = Objects.requireNonNull(request, "request");
        PlannerAmounts.requireWithinLimit(request.amount(), "request amount");
        validateBounds(patternExecutions, storageConsumed, surplus, batches);
        this.patternExecutions = copyAmounts(patternExecutions, Comparator.naturalOrder(), "patternExecutions");
        this.storageConsumed = copyAmounts(storageConsumed, Comparator.comparingInt(KeyId::value), "storageConsumed");
        this.surplus = copyAmounts(surplus, Comparator.comparingInt(KeyId::value), "surplus");
        this.batches = List.copyOf(Objects.requireNonNull(batches, "batches"));
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        if (!gridRevision.equals(dependencies.gridRevision())) {
            throw new IllegalArgumentException("Draft dependencies must use the draft grid revision");
        }
        validateBatchExecutions();
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

    public List<PlannedPatternBatch> batches() {
        return batches;
    }

    public DependencySet dependencies() {
        return dependencies;
    }

    private void validateBatchExecutions() {
        TreeMap<PatternId, AEAmount> totals = new TreeMap<>();
        for (PlannedPatternBatch batch : batches) {
            Objects.requireNonNull(batch, "batches cannot contain null");
            totals.merge(batch.patternId(), batch.executions(),
                    (left, right) -> PlannerAmounts.checkedAdd(left, right, "batch executions"));
        }
        if (!totals.equals(patternExecutions)) {
            throw new IllegalArgumentException("Draft pattern executions must equal causal batch totals");
        }
    }

    private static void validateBounds(Map<PatternId, AEAmount> patternExecutions,
            Map<KeyId, AEAmount> storageConsumed, Map<KeyId, AEAmount> surplus,
            List<PlannedPatternBatch> batches) {
        if (Objects.requireNonNull(patternExecutions, "patternExecutions").size() > PatternLimits.MAX_GRAPH_NODES
                || Objects.requireNonNull(storageConsumed, "storageConsumed")
                        .size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS
                || Objects.requireNonNull(surplus, "surplus").size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS
                || Objects.requireNonNull(batches, "batches").size() > PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS) {
            throw new IllegalArgumentException("Draft exceeds planning bounds");
        }
        for (PlannedPatternBatch batch : batches) {
            Objects.requireNonNull(batch, "batches cannot contain null");
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
