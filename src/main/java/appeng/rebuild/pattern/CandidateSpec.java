package appeng.rebuild.pattern;

import java.util.Objects;
import java.util.Optional;

import appeng.api.stacks.AEKey;
import appeng.rebuild.quantity.AEAmount;

/** One ordered source-key candidate for an input group. */
public record CandidateSpec(AEKey key, AEAmount amountPerExecution, Optional<RemainderSpec> remainder) {
    public CandidateSpec {
        Objects.requireNonNull(key, "key");
        RemainderSpec.requirePositive(Objects.requireNonNull(amountPerExecution, "amountPerExecution"),
                "amountPerExecution");
        remainder = Objects.requireNonNull(remainder, "remainder");
    }
}
