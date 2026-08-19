package appeng.rebuild.pattern;

import java.util.Objects;
import java.util.Optional;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Compiled ordered input template candidate retaining its exact per-template amount and remainder. */
public record CompiledCandidateSpec(KeyId key, AEAmount amountPerTemplate, Optional<CompiledRemainderSpec> remainder) {
    public CompiledCandidateSpec {
        Objects.requireNonNull(key, "key");
        RemainderSpec.requirePositive(Objects.requireNonNull(amountPerTemplate, "amountPerTemplate"),
                "amountPerTemplate");
        remainder = Objects.requireNonNull(remainder, "remainder");
    }
}
