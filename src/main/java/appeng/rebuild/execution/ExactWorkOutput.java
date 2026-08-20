package appeng.rebuild.execution;

import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Exact expected output for one preserved compiled-pattern output slot. */
public record ExactWorkOutput(int outputIndex, KeyId key, AEAmount amount) {
    public ExactWorkOutput {
        if (outputIndex < 0) {
            throw new IllegalArgumentException("outputIndex must be non-negative");
        }
        Objects.requireNonNull(key, "key");
        amount = ExactWorkCommand.requirePositiveBounded(amount, "amount");
    }
}
