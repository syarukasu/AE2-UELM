package appeng.rebuild.pattern;

import java.util.Objects;
import java.util.Optional;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;

/** Compiled ordered input candidate retaining exact source quantities and remainder. */
public record CompiledCandidateSpec(KeyId key, AEAmount amountPerExecution, Optional<CompiledRemainderSpec> remainder) {
    public CompiledCandidateSpec {
        Objects.requireNonNull(key, "key");
        RemainderSpec.requirePositive(Objects.requireNonNull(amountPerExecution, "amountPerExecution"),
                "amountPerExecution");
        remainder = Objects.requireNonNull(remainder, "remainder");
    }
}
