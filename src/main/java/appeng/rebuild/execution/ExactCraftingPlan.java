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
    private final Map<PatternId, PatternRevision> usedPatternRevisions;
    private final Map<PatternId, AEAmount> patternExecutions;
    private final Map<KeyId, AEAmount> initialStorageDebits;
    private final Map<KeyId, AEAmount> finalSurplus;
    private final DependencySet dependencies;

    ExactCraftingPlan(ExactPlanId planId, GridRevision planningRevision, GridRevision validationRevision,
            ExactCraftRequest request,
            List<PlannedCausalStep> causalSteps, Map<PatternId, PatternRevision> usedPatternRevisions,
            Map<PatternId, AEAmount> patternExecutions, Map<KeyId, AEAmount> initialStorageDebits,
            Map<KeyId, AEAmount> finalSurplus, DependencySet dependencies) {
        this.planId = Objects.requireNonNull(planId, "planId");
        this.planningRevision = Objects.requireNonNull(planningRevision, "planningRevision");
        this.validationRevision = Objects.requireNonNull(validationRevision, "validationRevision");
        this.request = Objects.requireNonNull(request, "request");
        this.causalSteps = List.copyOf(Objects.requireNonNull(causalSteps, "causalSteps"));
        this.usedPatternRevisions = copyPatternRevisions(usedPatternRevisions);
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
