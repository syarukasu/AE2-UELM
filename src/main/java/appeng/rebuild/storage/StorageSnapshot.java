package appeng.rebuild.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;

/**
 * Immutable, registry-scoped exact storage state captured from one ledger revision.
 *
 * <p>
 * This value contains no legacy keys, world state, or service references. Its amount vector and key revisions are
 * defensively copied at construction, and the vector capacity is exactly its bounded key count.
 */
public final class StorageSnapshot {
    private final long keyRegistryGeneration;
    private final StorageRevision revision;
    private final int keyCount;
    private final AmountVector amounts;
    private final long[] keyRevisions;
    private final List<KeyId> nonZeroKeys;

    public StorageSnapshot(long keyRegistryGeneration, StorageRevision revision, int keyCount, AmountVector amounts,
            long[] keyRevisions) {
        if (keyRegistryGeneration < 0) {
            throw new IllegalArgumentException("Key registry generation must be non-negative");
        }
        if (keyCount < 0 || keyCount > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            throw new IllegalArgumentException("Storage snapshot key count is out of bounds: " + keyCount);
        }
        this.keyRegistryGeneration = keyRegistryGeneration;
        this.revision = Objects.requireNonNull(revision, "revision");
        this.keyCount = keyCount;
        this.amounts = copyAmounts(Objects.requireNonNull(amounts, "amounts"), keyCount);
        this.keyRevisions = copyKeyRevisions(Objects.requireNonNull(keyRevisions, "keyRevisions"), keyCount);
        this.nonZeroKeys = findNonZeroKeys(this.amounts, keyCount);
    }

    public long keyRegistryGeneration() {
        return keyRegistryGeneration;
    }

    public StorageRevision revision() {
        return revision;
    }

    public int keyCount() {
        return keyCount;
    }

    /** Returns the exact amount for a key within this snapshot's registry-scoped range. */
    public AEAmount amount(KeyId key) {
        return amounts.get(validateKey(key));
    }

    /** Returns the aggregate-change revision for a key within this snapshot's registry-scoped range. */
    public long keyRevision(KeyId key) {
        return keyRevisions[validateKey(key)];
    }

    /** Returns non-zero keys in ascending KeyId order. */
    public List<KeyId> nonZeroKeys() {
        return nonZeroKeys;
    }

    /** Returns an independent exact amount-vector copy. */
    public AmountVector copyAmounts() {
        return amounts.copy();
    }

    private int validateKey(KeyId key) {
        Objects.requireNonNull(key, "key");
        int index = key.value();
        if (index >= keyCount) {
            throw new IndexOutOfBoundsException("Key id " + index + " outside snapshot key count " + keyCount);
        }
        return index;
    }

    private static AmountVector copyAmounts(AmountVector source, int keyCount) {
        if (source.size() != keyCount) {
            throw new IllegalArgumentException("Storage snapshot amounts must exactly match its key count");
        }
        AmountVector result = new AmountVector(keyCount);
        for (int index = 0; index < keyCount; index++) {
            AEAmount amount = source.get(index);
            if (!amount.equals(AEAmount.ZERO)) {
                result.set(index, amount);
            }
        }
        return result;
    }

    private static long[] copyKeyRevisions(long[] source, int keyCount) {
        if (source.length != keyCount) {
            throw new IllegalArgumentException("Storage snapshot revisions must exactly match its key count");
        }
        long[] result = new long[keyCount];
        for (int index = 0; index < keyCount; index++) {
            long keyRevision = source[index];
            if (keyRevision < 0) {
                throw new IllegalArgumentException("Storage key revision must be non-negative");
            }
            result[index] = keyRevision;
        }
        return result;
    }

    private static List<KeyId> findNonZeroKeys(AmountVector amounts, int keyCount) {
        List<KeyId> keys = new ArrayList<>(amounts.nonZeroSize());
        for (int index = 0; index < keyCount; index++) {
            if (!amounts.get(index).equals(AEAmount.ZERO)) {
                keys.add(new KeyId(index));
            }
        }
        return List.copyOf(keys);
    }
}
