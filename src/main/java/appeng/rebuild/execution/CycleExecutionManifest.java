package appeng.rebuild.execution;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.planner.PlannedBatchCause;
import appeng.rebuild.planner.PlannedBatchId;
import appeng.rebuild.planner.PlannedCycleLink;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** Immutable execution manifest for one compact productive-cycle causal batch. */
public record CycleExecutionManifest(PlannedBatchId batchId, PlannedBatchCause cause, AEAmount repetitions,
        KeyId seedKey, AEAmount seedAmount, List<SealedPatternExecution> memberExecutions,
        List<PlannedCycleLink> links, Map<KeyId, AEAmount> finalCredits) implements ExecutionManifest {
    public CycleExecutionManifest {
        batchId = Objects.requireNonNull(batchId, "batchId");
        cause = Objects.requireNonNull(cause, "cause");
        repetitions = SealedPatternExecution.requirePositive(repetitions, "repetitions");
        seedKey = Objects.requireNonNull(seedKey, "seedKey");
        seedAmount = SealedPatternExecution.requirePositive(seedAmount, "seedAmount");
        Objects.requireNonNull(memberExecutions, "memberExecutions");
        if (memberExecutions.isEmpty() || memberExecutions.size() > PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS) {
            throw new IllegalArgumentException("Invalid sealed cycle member count");
        }
        memberExecutions = List.copyOf(memberExecutions);
        for (SealedPatternExecution member : memberExecutions) {
            Objects.requireNonNull(member, "memberExecutions cannot contain null");
            if (member.plannedOutputs().isEmpty()) {
                throw new IllegalArgumentException("Sealed cycle members require planned output projections");
            }
        }
        Objects.requireNonNull(links, "links");
        if (links.size() != memberExecutions.size()) {
            throw new IllegalArgumentException("Sealed cycle links must match its member count");
        }
        links = List.copyOf(links);
        finalCredits = copyCredits(finalCredits);
    }

    @Override
    public List<SealedPatternExecution> patternExecutions() {
        return memberExecutions;
    }

    private static Map<KeyId, AEAmount> copyCredits(Map<KeyId, AEAmount> source) {
        Objects.requireNonNull(source, "finalCredits");
        if (source.size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            throw new IllegalArgumentException("Too many sealed final credits");
        }
        TreeMap<KeyId, AEAmount> copy = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, AEAmount> entry : source.entrySet()) {
            KeyId key = Objects.requireNonNull(entry.getKey(), "finalCredits key");
            copy.put(key, SealedPatternExecution.requirePositive(entry.getValue(), "finalCredits value"));
        }
        return Collections.unmodifiableMap(copy);
    }
}
