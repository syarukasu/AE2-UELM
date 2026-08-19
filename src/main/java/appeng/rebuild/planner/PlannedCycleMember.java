package appeng.rebuild.planner;

import java.util.List;
import java.util.Objects;

import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.quantity.AEAmount;

/** One pattern repeated per turn of a productive cycle. */
public record PlannedCycleMember(PatternId patternId, AEAmount executionsPerTurn,
        List<PlannedInputSelection> inputsPerTurn, List<PlannedCycleOutput> outputsPerTurn) {
    public PlannedCycleMember {
        Objects.requireNonNull(patternId, "patternId");
        requirePositive(executionsPerTurn, "executionsPerTurn");
        inputsPerTurn = copyInputs(inputsPerTurn);
        outputsPerTurn = copyOutputs(outputsPerTurn);
    }

    static List<PlannedInputSelection> copyInputs(List<PlannedInputSelection> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.size() > PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN) {
            throw new IllegalArgumentException("Planned cycle member has too many inputs");
        }
        inputs = List.copyOf(inputs);
        int previousInput = -1;
        int previousCandidate = -1;
        for (PlannedInputSelection input : inputs) {
            Objects.requireNonNull(input, "inputs cannot contain null");
            if (input.inputIndex() < previousInput || input.inputIndex() == previousInput
                    && input.candidateIndex() <= previousCandidate) {
                throw new IllegalArgumentException(
                        "Planned inputs must be strictly ordered by input and candidate index");
            }
            previousInput = input.inputIndex();
            previousCandidate = input.candidateIndex();
        }
        return inputs;
    }

    static List<PlannedCycleOutput> copyOutputs(List<PlannedCycleOutput> outputs) {
        Objects.requireNonNull(outputs, "outputs");
        if (outputs.size() > PatternLimits.MAX_OUTPUTS) {
            throw new IllegalArgumentException("Planned cycle member has too many outputs");
        }
        int previousOutput = -1;
        for (PlannedCycleOutput output : outputs) {
            Objects.requireNonNull(output, "outputs cannot contain null");
            if (output.outputIndex() <= previousOutput) {
                throw new IllegalArgumentException("Planned outputs must be strictly ordered by output index");
            }
            previousOutput = output.outputIndex();
        }
        return List.copyOf(outputs);
    }

    static void requirePositive(AEAmount amount, String name) {
        PlannerAmounts.requireWithinLimit(amount, name);
        if (amount.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
