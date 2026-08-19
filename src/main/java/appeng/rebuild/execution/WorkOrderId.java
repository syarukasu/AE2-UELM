package appeng.rebuild.execution;

import java.util.Objects;
import java.util.UUID;

/** Durable identity for one exact work order. */
public record WorkOrderId(UUID value) {
    public WorkOrderId {
        Objects.requireNonNull(value, "value");
    }

    /** Creates a new identity only inside the execution ownership boundary. */
    static WorkOrderId fresh() {
        return new WorkOrderId(UUID.randomUUID());
    }
}
