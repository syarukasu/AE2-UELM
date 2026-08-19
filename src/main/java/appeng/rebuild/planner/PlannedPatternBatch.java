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
        if (inputs.size() > PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN) {
            throw new IllegalArgumentException("Planned batch has too many inputs");
        }
        int previousInput = -1;
        int previousCandidate = -1;
        for (int index = 0; index < inputs.size(); index++) {
            PlannedInputSelection input = Objects.requireNonNull(inputs.get(index), "inputs cannot contain null");
            if (input.inputIndex() < previousInput || input.inputIndex() == previousInput
                    && input.candidateIndex() <= previousCandidate) {
                throw new IllegalArgumentException(
                        "Planned inputs must be strictly ordered by input and candidate index");
            }
            previousInput = input.inputIndex();
            previousCandidate = input.candidateIndex();
        }
    }
}
