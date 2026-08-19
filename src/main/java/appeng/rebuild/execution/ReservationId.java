package appeng.rebuild.execution;

import java.util.Objects;
import java.util.UUID;

/** Immutable broker-generated identity for one exact physical reservation. */
public record ReservationId(UUID value) {
    public ReservationId {
        Objects.requireNonNull(value, "value");
    }

    /** Creates a new identity for a same-package transfer broker. */
    static ReservationId fresh() {
        return new ReservationId(UUID.randomUUID());
    }
}
