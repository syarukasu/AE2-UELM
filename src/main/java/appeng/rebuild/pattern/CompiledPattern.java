package appeng.rebuild.pattern;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable KeyId pattern representation scoped to one key-registry generation. */
public record CompiledPattern(PatternId id, PatternKind kind, List<CompiledInputSpec> inputs,
        List<CompiledOutputSpec> outputs, Optional<MachineIntent> machineIntent, PatternRevision revision,
        long keyRegistryGeneration) {
    public CompiledPattern {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        machineIntent = Objects.requireNonNull(machineIntent, "machineIntent");
        Objects.requireNonNull(revision, "revision");
        if (keyRegistryGeneration < 0) {
            throw new IllegalArgumentException(
                    "Key registry generation must be non-negative: " + keyRegistryGeneration);
        }
        if (inputs.size() > PatternLimits.MAX_INPUT_GROUPS) {
            throw new IllegalArgumentException("Pattern has too many input groups: " + inputs.size());
        }
        validateTotalCandidates(inputs);
        if (outputs.isEmpty() || outputs.size() > PatternLimits.MAX_OUTPUTS) {
            throw new IllegalArgumentException("Pattern has an invalid output count: " + outputs.size());
        }
        long primaryOutputs = outputs.stream().filter(CompiledOutputSpec::primary).count();
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

    private static void validateTotalCandidates(List<CompiledInputSpec> inputs) {
        int totalCandidates = 0;
        for (CompiledInputSpec input : inputs) {
            int candidateCount = input.candidates().size();
            if (candidateCount > PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN - totalCandidates) {
                throw new IllegalArgumentException(
                        "Pattern has too many candidates: more than " + PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN);
            }
            totalCandidates += candidateCount;
        }
    }
}
