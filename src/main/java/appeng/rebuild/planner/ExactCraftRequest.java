package appeng.rebuild.planner;

import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Immutable request for an exact amount of one registry-scoped output key. */
public record ExactCraftRequest(KeyId output, AEAmount amount) {
    public ExactCraftRequest {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(amount, "amount");
        if (amount.equals(AEAmount.ZERO)) {
            throw new IllegalArgumentException("Exact craft requests require a positive amount");
        }
    }
}
