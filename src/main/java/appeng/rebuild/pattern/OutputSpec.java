package appeng.rebuild.pattern;

import java.util.Objects;

import appeng.api.stacks.AEKey;
import appeng.rebuild.quantity.AEAmount;

/** One exact output from a pattern execution. */
public record OutputSpec(AEKey key, AEAmount amountPerExecution, boolean primary) {
    public OutputSpec {
        Objects.requireNonNull(key, "key");
        RemainderSpec.requirePositive(Objects.requireNonNull(amountPerExecution, "amountPerExecution"),
                "amountPerExecution");
    }
}
