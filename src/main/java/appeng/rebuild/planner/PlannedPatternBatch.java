package appeng.rebuild.planner;

import java.util.List;
import java.util.Objects;

import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.quantity.AEAmount;

/** Immutable causal batch: inputs are satisfied before its outputs and remainders may be credited. */
public record PlannedPatternBatch(PatternId patternId, AEAmount executions, List<PlannedInputSelection> inputs) {
    public PlannedPatternBatch {
        Objects.requireNonNull(patternId, "patternId");
        PlannerAmounts.requireWithinLimit(executions, "executions");
        if (executions.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException("Planned pattern executions must be positive");
        }
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        if (inputs.size() > PatternLimits.MAX_INPUT_GROUPS) {
            throw new IllegalArgumentException("Planned batch has too many inputs");
        }
        for (int index = 0; index < inputs.size(); index++) {
            PlannedInputSelection input = Objects.requireNonNull(inputs.get(index), "inputs cannot contain null");
            if (input.inputIndex() != index) {
                throw new IllegalArgumentException("Planned inputs must be ordered by source input index");
            }
        }
    }
}
