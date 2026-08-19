package appeng.rebuild.pattern;

import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Compiled exact remainder using a registry-scoped key id. */
public record CompiledRemainderSpec(KeyId key, AEAmount amount) {
    public CompiledRemainderSpec {
        Objects.requireNonNull(key, "key");
        RemainderSpec.requirePositive(Objects.requireNonNull(amount, "amount"), "amount");
    }
}
