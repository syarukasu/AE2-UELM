package appeng.rebuild.planner;

import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Immutable selected candidate and exact input requirement for one planned pattern input group. */
public record PlannedInputSelection(int inputIndex, int candidateIndex, AEAmount templateUnits, KeyId consumedKey,
        AEAmount consumedAmount) {
    public PlannedInputSelection {
        if (inputIndex < 0 || candidateIndex < 0) {
            throw new IllegalArgumentException("Pattern input and candidate indexes must be non-negative");
        }
        PlannerAmounts.requireWithinLimit(templateUnits, "templateUnits");
        Objects.requireNonNull(consumedKey, "consumedKey");
        PlannerAmounts.requireWithinLimit(consumedAmount, "consumedAmount");
        if (templateUnits.equals(AEAmount.ZERO) || consumedAmount.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException("Planned input quantities must be positive");
        }
    }
}
