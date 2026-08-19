package appeng.rebuild.execution;

import java.util.List;
import java.util.Objects;

import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.planner.PlannedCycleOutput;
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/**
 * Exact execution data for one immutable compiled-pattern revision.
 *
 * <p>
 * {@link #pattern()} retains the complete compiled inputs, outputs, kind, machine intent, and revision. The explicit
 * selections and cycle outputs retain the plan-specific projection used by the causal step.
 */
public record SealedPatternExecution(CompiledPattern pattern, AEAmount executions,
        List<PlannedInputSelection> plannedSelections, List<PlannedCycleOutput> plannedOutputs) {
    public SealedPatternExecution {
        pattern = Objects.requireNonNull(pattern, "pattern");
        executions = requirePositive(executions, "executions");
        plannedSelections = copySelections(plannedSelections);
        plannedOutputs = copyOutputs(plannedOutputs);
    }

    static AEAmount requirePositive(AEAmount amount, String name) {
        amount = Objects.requireNonNull(amount, name);
        if (amount.equals(AEAmount.ZERO) || amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException(name + " must be a positive bounded exact amount");
        }
        return amount;
    }

    static List<PlannedInputSelection> copySelections(List<PlannedInputSelection> selections) {
        selections = List.copyOf(Objects.requireNonNull(selections, "plannedSelections"));
        if (selections.size() > PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN) {
            throw new IllegalArgumentException("Too many sealed planned selections");
        }
        return selections;
    }

    static List<PlannedCycleOutput> copyOutputs(List<PlannedCycleOutput> outputs) {
        outputs = List.copyOf(Objects.requireNonNull(outputs, "plannedOutputs"));
        if (outputs.size() > PatternLimits.MAX_OUTPUTS) {
            throw new IllegalArgumentException("Too many sealed planned outputs");
        }
        return outputs;
    }
}
