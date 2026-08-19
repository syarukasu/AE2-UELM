package appeng.rebuild.planner;

import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Immutable exact remainder credited only after its planned batch's inputs succeed. */
public record PlannedRemainderReturn(KeyId key, AEAmount amount) {
    public PlannedRemainderReturn {
        Objects.requireNonNull(key, "key");
        PlannerAmounts.requireWithinLimit(amount, "amount");
        if (amount.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException("Planned remainder returns must be positive");
        }
    }
}
