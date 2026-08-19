package appeng.rebuild.pattern;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable normalized source pattern definition. */
public record PatternDefinition(PatternId id, PatternKind kind, List<InputSpec> inputs, List<OutputSpec> outputs,
        Optional<MachineIntent> machineIntent, PatternRevision revision) {
    public PatternDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        machineIntent = Objects.requireNonNull(machineIntent, "machineIntent");
        Objects.requireNonNull(revision, "revision");
        if (inputs.size() > PatternLimits.MAX_INPUT_GROUPS) {
            throw new IllegalArgumentException("Pattern has too many input groups: " + inputs.size());
        }
        validateTotalCandidates(inputs);
        if (outputs.isEmpty() || outputs.size() > PatternLimits.MAX_OUTPUTS) {
            throw new IllegalArgumentException(
                    "Pattern must have between one and " + PatternLimits.MAX_OUTPUTS + " outputs");
        }
        long primaryOutputs = outputs.stream().filter(OutputSpec::primary).count();
        if (primaryOutputs != 1) {
            throw new IllegalArgumentException("Pattern must have exactly one primary output");
        }
        if (kind == PatternKind.CRAFTING && machineIntent.isPresent()) {
            throw new IllegalArgumentException("Crafting patterns cannot have machine intent");
        }
        if (kind == PatternKind.PROCESSING && machineIntent.isEmpty()) {
            throw new IllegalArgumentException("Processing patterns require machine intent");
        }
    }

    private static void validateTotalCandidates(List<InputSpec> inputs) {
        int totalCandidates = 0;
        for (InputSpec input : inputs) {
            int candidateCount = input.candidates().size();
            if (candidateCount > PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN - totalCandidates) {
                throw new IllegalArgumentException(
                        "Pattern has too many candidates: more than " + PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN);
            }
            totalCandidates += candidateCount;
        }
    }
}
