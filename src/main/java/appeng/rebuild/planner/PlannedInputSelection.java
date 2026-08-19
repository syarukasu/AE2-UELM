package appeng.rebuild.planner;

import java.util.Objects;
import java.util.Optional;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.quantity.AEAmount;

/** Immutable selected candidate and exact input requirement for one planned pattern input group. */
public record PlannedInputSelection(int inputIndex, int candidateIndex, AEAmount templateUnits, KeyId consumedKey,
        AEAmount grossConsumedAmount, AEAmount initialRequiredAmount,
        Optional<PlannedRemainderReturn> remainderReturn) {
    public PlannedInputSelection {
        if (inputIndex < 0 || inputIndex >= PatternLimits.MAX_INPUT_GROUPS || candidateIndex < 0
                || candidateIndex >= PatternLimits.MAX_CANDIDATES_PER_INPUT) {
            throw new IllegalArgumentException("Pattern input or candidate index is out of bounds");
        }
        PlannerAmounts.requireWithinLimit(templateUnits, "templateUnits");
        Objects.requireNonNull(consumedKey, "consumedKey");
        PlannerAmounts.requireWithinLimit(grossConsumedAmount, "grossConsumedAmount");
        PlannerAmounts.requireWithinLimit(initialRequiredAmount, "initialRequiredAmount");
        remainderReturn = Objects.requireNonNull(remainderReturn, "remainderReturn");
        if (templateUnits.equals(AEAmount.ZERO) || grossConsumedAmount.equals(AEAmount.ZERO)
                || initialRequiredAmount.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException("Planned input quantities must be positive");
        }
        if (initialRequiredAmount.compareTo(grossConsumedAmount) > 0) {
            throw new IllegalArgumentException("Initial requirement cannot exceed gross consumption");
        }
    }
}
