package appeng.rebuild.pattern;

import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Compiled exact remainder returned for each selected input template unit. */
public record CompiledRemainderSpec(KeyId key, AEAmount amountPerTemplate) {
    public CompiledRemainderSpec {
        Objects.requireNonNull(key, "key");
        RemainderSpec.requirePositive(Objects.requireNonNull(amountPerTemplate, "amountPerTemplate"),
                "amountPerTemplate");
    }
}
