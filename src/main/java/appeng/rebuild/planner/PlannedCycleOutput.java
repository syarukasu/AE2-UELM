package appeng.rebuild.planner;

import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.quantity.AEAmount;

/**
 * One claimed aggregated compiled-pattern output produced by a cycle member in one turn.
 *
 * <p>
 * Phase 6 rechecks this bounded projection against the referenced compiled-pattern revision before execution.
 */
public record PlannedCycleOutput(int outputIndex, KeyId key, AEAmount amountPerTurn) {
    public PlannedCycleOutput {
        if (outputIndex < 0 || outputIndex >= PatternLimits.MAX_OUTPUTS) {
            throw new IllegalArgumentException("Cycle output index is out of bounds");
        }
        Objects.requireNonNull(key, "key");
        PlannedCycleMember.requirePositive(amountPerTurn, "amountPerTurn");
    }
}
