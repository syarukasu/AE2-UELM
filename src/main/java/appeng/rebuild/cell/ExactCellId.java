package appeng.rebuild.cell;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of one physical storage cell. */
public record ExactCellId(UUID value) implements Comparable<ExactCellId> {
    public ExactCellId {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public int compareTo(ExactCellId other) {
        Objects.requireNonNull(other, "other");
        int high = Long.compareUnsigned(value.getMostSignificantBits(), other.value.getMostSignificantBits());
        return high != 0 ? high
                : Long.compareUnsigned(value.getLeastSignificantBits(), other.value.getLeastSignificantBits());
    }
}
