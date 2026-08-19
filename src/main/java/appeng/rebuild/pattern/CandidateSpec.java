package appeng.rebuild.pattern;

import java.util.Objects;
import java.util.Optional;

import appeng.api.stacks.AEKey;
import appeng.rebuild.quantity.AEAmount;

/** One ordered source-key template candidate for an input group, with its exact per-template amount. */
public record CandidateSpec(AEKey key, AEAmount amountPerTemplate, Optional<RemainderSpec> remainder) {
    public CandidateSpec {
        Objects.requireNonNull(key, "key");
        RemainderSpec.requirePositive(Objects.requireNonNull(amountPerTemplate, "amountPerTemplate"),
                "amountPerTemplate");
        remainder = Objects.requireNonNull(remainder, "remainder");
    }
}
