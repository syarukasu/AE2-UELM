package appeng.rebuild.execution;

import java.util.List;
import java.util.Objects;

import appeng.rebuild.planner.PlannedBatchCause;
import appeng.rebuild.planner.PlannedBatchId;

/** Immutable execution manifest for one non-cycle causal batch. */
public record NormalExecutionManifest(PlannedBatchId batchId, PlannedBatchCause cause,
        SealedPatternExecution execution) implements ExecutionManifest {
    public NormalExecutionManifest {
        batchId = Objects.requireNonNull(batchId, "batchId");
        cause = Objects.requireNonNull(cause, "cause");
        execution = Objects.requireNonNull(execution, "execution");
        if (!execution.plannedOutputs().isEmpty()) {
            throw new IllegalArgumentException("Normal execution manifests cannot contain cycle output projections");
        }
    }

    @Override
    public List<SealedPatternExecution> patternExecutions() {
        return List.of(execution);
    }
}
