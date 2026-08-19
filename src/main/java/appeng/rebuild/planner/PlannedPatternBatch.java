package appeng.rebuild.planner;

import java.util.List;
import java.util.Objects;

import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.quantity.AEAmount;

/** Immutable normal causal batch: inputs are satisfied before its outputs and remainders may be credited. */
public record PlannedPatternBatch(PlannedBatchId id, PlannedBatchCause cause, PatternId patternId, AEAmount executions,
        List<PlannedInputSelection> inputs) implements PlannedCausalStep {
    public PlannedPatternBatch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(patternId, "patternId");
        PlannedCycleMember.requirePositive(executions, "executions");
        inputs = PlannedCycleMember.copyInputs(inputs);
    }
}
