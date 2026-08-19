package appeng.rebuild.cell;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import appeng.api.stacks.AEKey;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** Immutable, revisioned contents of one UUID-backed storage cell. */
public record ExactCellSnapshot(ExactCellId id, ExactCellDescriptor descriptor, long revision,
        Map<AEKey, AEAmount> amounts) {
    public ExactCellSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(descriptor, "descriptor");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        amounts = copyAmounts(amounts);
    }

    static Map<AEKey, AEAmount> copyAmounts(Map<AEKey, AEAmount> source) {
        Objects.requireNonNull(source, "source");
        if (source.size() > ExactCellStorageManager.MAX_KEYS_PER_CELL) {
            throw new IllegalArgumentException("cell key limit exceeded");
        }
        Map<AEKey, AEAmount> copy = new LinkedHashMap<>(source.size());
        source.forEach((key, amount) -> {
            Objects.requireNonNull(key, "cell key");
            Objects.requireNonNull(amount, "cell amount");
            if (amount.equals(AEAmount.ZERO)) {
                throw new IllegalArgumentException("zero cell amount");
            }
            if (amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
                throw new IllegalArgumentException("cell amount exceeds exact quantity bound");
            }
            if (copy.put(key, amount) != null) {
                throw new IllegalArgumentException("duplicate cell key");
            }
        });
        return Map.copyOf(copy);
    }
}
