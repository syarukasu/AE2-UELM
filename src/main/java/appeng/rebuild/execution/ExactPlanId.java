package appeng.rebuild.execution;

import java.util.Objects;
import java.util.UUID;

/** Durable immutable identity assigned to one successfully sealed exact plan. */
public record ExactPlanId(UUID value) {
    public ExactPlanId {
        Objects.requireNonNull(value, "value");
    }

    /** Creates an identity only while sealing a plan inside this execution package. */
    static ExactPlanId fresh() {
        return new ExactPlanId(UUID.randomUUID());
    }
}
