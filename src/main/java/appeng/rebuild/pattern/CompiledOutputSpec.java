package appeng.rebuild.pattern;

import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Compiled exact output using a registry-scoped key id. */
public record CompiledOutputSpec(KeyId key, AEAmount amountPerExecution, boolean primary) {
    public CompiledOutputSpec {
        Objects.requireNonNull(key, "key");
        RemainderSpec.requirePositive(Objects.requireNonNull(amountPerExecution, "amountPerExecution"),
                "amountPerExecution");
    }
}
