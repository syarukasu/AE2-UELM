package appeng.rebuild.pattern;

import java.util.Objects;

import appeng.api.stacks.AEKey;
import appeng.rebuild.quantity.AEAmount;

/** Exact non-zero remainder returned for each selected input template unit. */
public record RemainderSpec(AEKey key, AEAmount amountPerTemplate) {
    public RemainderSpec {
        Objects.requireNonNull(key, "key");
        requirePositive(Objects.requireNonNull(amountPerTemplate, "amountPerTemplate"), "amountPerTemplate");
    }

    static void requirePositive(AEAmount amount, String name) {
        if (amount.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
