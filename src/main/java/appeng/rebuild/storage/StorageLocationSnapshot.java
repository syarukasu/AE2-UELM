package appeng.rebuild.storage;

import java.util.Objects;
import java.util.Optional;

import appeng.rebuild.cell.ExactCellId;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;

/**
 * Immutable exact contents of one storage location.
 *
 * <p>
 * Snapshot equality describes logical contents, not {@link AmountVector} allocation capacity. This permits callers to
 * retain efficient, over-allocated vectors without making otherwise identical snapshots unequal.
 */
public final class StorageLocationSnapshot {
    private final StorageLocationId location;
    private final AmountVector amounts;
    private final ExactCellId exactCellId;
    private final long exactCellRevision;

    public StorageLocationSnapshot(StorageLocationId location, AmountVector amounts) {
        this(location, amounts, null, 0L);
    }

    public StorageLocationSnapshot(StorageLocationId location, AmountVector amounts, ExactCellId exactCellId,
            long exactCellRevision) {
        this.location = Objects.requireNonNull(location, "location");
        this.amounts = Objects.requireNonNull(amounts, "amounts").copy();
        if (exactCellId == null && exactCellRevision != 0L) {
            throw new IllegalArgumentException("A legacy location cannot carry an exact-cell revision");
        }
        if (exactCellRevision < 0L) {
            throw new IllegalArgumentException("Exact-cell revision must be non-negative");
        }
        this.exactCellId = exactCellId;
        this.exactCellRevision = exactCellRevision;
    }

    public StorageLocationId location() {
        return location;
    }

    public Optional<ExactCellId> exactCellId() {
        return Optional.ofNullable(exactCellId);
    }

    public long exactCellRevision() {
        return exactCellRevision;
    }

    public int size() {
        return amounts.size();
    }

    /** Returns the exact amount for {@code key}, or zero when it is beyond this snapshot's capacity. */
    public AEAmount amount(KeyId key) {
        Objects.requireNonNull(key, "key");
        int index = key.value();
        return index < amounts.size() ? amounts.get(index) : AEAmount.ZERO;
    }

    /** Visits each non-zero amount in ascending key-id order. */
    public void enumerate(ExactStorageVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        for (int index = 0; index < amounts.size(); index++) {
            AEAmount amount = amounts.get(index);
            if (!amount.equals(AEAmount.ZERO)) {
                visitor.accept(new KeyId(index), amount);
            }
        }
    }

    /** Returns a defensive copy of this snapshot's amount vector. */
    public AmountVector copyAmounts() {
        return amounts.copy();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof StorageLocationSnapshot other) || !location.equals(other.location)
                || !Objects.equals(exactCellId, other.exactCellId)
                || exactCellRevision != other.exactCellRevision
                || amounts.nonZeroSize() != other.amounts.nonZeroSize()) {
            return false;
        }

        int size = Math.max(amounts.size(), other.amounts.size());
        for (int index = 0; index < size; index++) {
            if (!amountAt(index).equals(other.amountAt(index))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = location.hashCode();
        result = 31 * result + Objects.hashCode(exactCellId);
        result = 31 * result + Long.hashCode(exactCellRevision);
        for (int index = 0; index < amounts.size(); index++) {
            AEAmount amount = amounts.get(index);
            if (!amount.equals(AEAmount.ZERO)) {
                result = 31 * result + Integer.hashCode(index);
                result = 31 * result + amount.hashCode();
            }
        }
        return result;
    }

    private AEAmount amountAt(int index) {
        return index < amounts.size() ? amounts.get(index) : AEAmount.ZERO;
    }
}
