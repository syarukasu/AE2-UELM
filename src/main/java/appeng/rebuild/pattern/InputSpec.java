package appeng.rebuild.pattern;

import java.util.List;
import java.util.Objects;

import appeng.rebuild.quantity.AEAmount;

/**
 * Ordered candidate group for one pattern input. The first candidate is primary; {@code multiplier} is the exact
 * selected-template count and remains separate from every candidate's per-template amount.
 */
public record InputSpec(List<CandidateSpec> candidates, AEAmount multiplier, SubstitutionPolicy policy) {
    public InputSpec {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        RemainderSpec.requirePositive(Objects.requireNonNull(multiplier, "multiplier"), "multiplier");
        Objects.requireNonNull(policy, "policy");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("An input must have at least one candidate");
        }
        if (candidates.size() > PatternLimits.MAX_CANDIDATES_PER_INPUT) {
            throw new IllegalArgumentException("An input has too many candidates: " + candidates.size());
        }
        if (policy == SubstitutionPolicy.EXACT && candidates.size() != 1) {
            throw new IllegalArgumentException("EXACT inputs must have exactly one candidate");
        }
    }
}
