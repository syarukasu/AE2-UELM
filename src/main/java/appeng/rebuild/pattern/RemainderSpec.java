package appeng.rebuild.pattern;

import java.util.Objects;

import appeng.api.stacks.AEKey;
import appeng.rebuild.quantity.AEAmount;

/** Exact non-zero remainder returned by one selected input candidate. */
public record RemainderSpec(AEKey key, AEAmount amount) {
    public RemainderSpec {
        Objects.requireNonNull(key, "key");
        requirePositive(Objects.requireNonNull(amount, "amount"), "amount");
    }

    static void requirePositive(AEAmount amount, String name) {
        if (amount.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
