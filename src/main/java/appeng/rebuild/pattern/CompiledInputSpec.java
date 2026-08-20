package appeng.rebuild.pattern;

import java.util.List;
import java.util.Objects;

import appeng.rebuild.quantity.AEAmount;

/**
 * Compiled ordered candidate group retaining its exact template multiplier separately from each candidate's
 * per-template amount.
 */
public record CompiledInputSpec(List<CompiledCandidateSpec> candidates, AEAmount multiplier,
        SubstitutionPolicy policy) {
    public CompiledInputSpec {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        RemainderSpec.requirePositive(Objects.requireNonNull(multiplier, "multiplier"), "multiplier");
        Objects.requireNonNull(policy, "policy");
        if (candidates.isEmpty() || candidates.size() > PatternLimits.MAX_CANDIDATES_PER_INPUT) {
            throw new IllegalArgumentException("Compiled input has an invalid candidate count: " + candidates.size());
        }
        if (policy == SubstitutionPolicy.EXACT && candidates.size() != 1) {
            throw new IllegalArgumentException("EXACT inputs must have exactly one candidate");
        }
    }
}
