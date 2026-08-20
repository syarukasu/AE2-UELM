package appeng.rebuild.planner;

import java.util.Objects;

import appeng.rebuild.quantity.AEAmount;

/** Package-local exact-quantity bound checks shared by immutable planner values. */
final class PlannerAmounts {
    private PlannerAmounts() {
    }

    static AEAmount requireWithinLimit(AEAmount amount, String name) {
        amount = Objects.requireNonNull(amount, name);
        if (amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException(name + " exceeds the planner quantity limit");
        }
        return amount;
    }

    static AEAmount checkedAdd(AEAmount left, AEAmount right, String name) {
        requireWithinLimit(left, name);
        requireWithinLimit(right, name);
        return requireWithinLimit(left.add(right), name);
    }

    static AEAmount checkedMultiply(AEAmount left, AEAmount right, String name) {
        requireWithinLimit(left, name);
        requireWithinLimit(right, name);
        return requireWithinLimit(left.multiply(right), name);
    }
}
