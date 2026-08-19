package appeng.rebuild.planner;

import java.util.Objects;

import appeng.rebuild.pattern.CompiledPattern;

/**
 * One ordered member of a proposed simple productive ring.
 *
 * <p>
 * The selected output supplies the selected input of the following member. Structural validation is deliberately
 * performed by {@link ProductiveCycleSolver}, so data reconstructed from a snapshot is reported as a typed failure
 * rather than escaping as an exception.
 */
public record ProductiveCycleRingMember(CompiledPattern pattern, int internalInputIndex, int internalCandidateIndex,
        int supplyingOutputIndex) {
    public ProductiveCycleRingMember {
        Objects.requireNonNull(pattern, "pattern");
    }
}
