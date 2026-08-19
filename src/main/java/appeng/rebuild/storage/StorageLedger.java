package appeng.rebuild.storage;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;

/**
 * Server-thread-owned exact aggregate for a set of physical storage locations.
 *
 * <p>
 * Callers must confine all reads and writes to the server thread. The ledger performs no world access and does not
 * retain unbounded derived caches: its location snapshots, exact aggregate, reverse index, and revisions are staged
 * together for each atomic replacement batch.
 */
public final class StorageLedger {
    private final KeyRegistry registry;
    private Map<StorageLocationId, StorageLocationSnapshot> snapshots = new HashMap<>();
    private AmountVector totals = new AmountVector(0);
    private StorageLocationIndex locationIndex = new StorageLocationIndex();
    private StorageRevision revision = StorageRevision.ZERO;
    private long[] keyRevisions = new long[0];

    public StorageLedger(KeyRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Returns the current exact aggregate amount for a registry-scoped key. */
    public AEAmount amount(KeyId key) {
        int index = validateKey(key);
        return index < totals.size() ? totals.get(index) : AEAmount.ZERO;
    }

    /** Visits non-zero aggregate amounts in ascending key-id order. */
    public void enumerate(ExactStorageVisitor visitor) {
        Objects.requireNonNull(visitor, "visitor");
        int limit = Math.min(registry.size(), totals.size());
        for (int index = 0; index < limit; index++) {
            AEAmount amount = totals.get(index);
            if (!amount.equals(AEAmount.ZERO)) {
                visitor.accept(new KeyId(index), amount);
            }
        }
    }

    public StorageRevision revision() {
        return revision;
    }

    /**
     * Copies one internally consistent exact state without mutating this ledger or its registry.
     *
     * <p>
     * Server thread only. The caller must enforce availability before exposing the returned snapshot.
     */
    public StorageSnapshot captureSnapshot() {
        int keyCount = registry.size();
        if (keyCount > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            throw new IllegalStateException("Storage snapshot key count exceeds the planning bound");
        }
        AmountVector copiedAmounts = new AmountVector(keyCount);
        for (int index = 0; index < keyCount; index++) {
            AEAmount amount = currentAmount(totals, index);
            if (!amount.equals(AEAmount.ZERO)) {
                copiedAmounts.set(index, amount);
            }
        }
        return new StorageSnapshot(registry.generation(), revision, keyCount, copiedAmounts,
                Arrays.copyOf(keyRevisions, keyCount));
    }

    /** Returns the revision that last changed this key's aggregate total. */
    public long keyRevision(KeyId key) {
        int index = validateKey(key);
        return index < keyRevisions.length ? keyRevisions[index] : 0L;
    }

    /** Returns an independent reverse-index copy. */
    public StorageLocationIndex locationIndex() {
        return locationIndex.copy();
    }

    /** Returns the immutable snapshot for {@code location}, or {@code null} if it is absent. */
    @Nullable
    public StorageLocationSnapshot snapshot(StorageLocationId location) {
        return snapshots.get(Objects.requireNonNull(location, "location"));
    }

    /**
     * Atomically applies location replacements and removals.
     *
     * <p>
     * Every input is validated and every resulting state component is staged before this ledger is mutated. A batch
     * with no effective location-content change preserves all revisions.
     */
    public void replaceAll(Map<StorageLocationId, StorageLocationSnapshot> replacements,
            Set<StorageLocationId> removals) {
        Objects.requireNonNull(replacements, "replacements");
        Objects.requireNonNull(removals, "removals");

        validateBatch(replacements, removals);

        Map<StorageLocationId, StorageLocationSnapshot> stagedSnapshots = new HashMap<>(snapshots);
        Set<StorageLocationId> changedLocations = new HashSet<>();
        for (StorageLocationId location : removals) {
            if (stagedSnapshots.remove(location) != null) {
                changedLocations.add(location);
            }
        }
        for (Map.Entry<StorageLocationId, StorageLocationSnapshot> entry : replacements.entrySet()) {
            StorageLocationSnapshot previous = stagedSnapshots.put(entry.getKey(), entry.getValue());
            if (!entry.getValue().equals(previous)) {
                changedLocations.add(entry.getKey());
            }
        }

        if (changedLocations.isEmpty()) {
            return;
        }

        AmountVector stagedTotals = totals.copy();
        stagedTotals.ensureCapacity(registry.size());
        for (StorageLocationId location : changedLocations) {
            StorageLocationSnapshot previous = snapshots.get(location);
            StorageLocationSnapshot replacement = stagedSnapshots.get(location);
            if (previous != null) {
                previous.enumerate((key, amount) -> stagedTotals.subtractExact(key.value(), amount));
            }
            if (replacement != null) {
                replacement.enumerate((key, amount) -> stagedTotals.add(key.value(), amount));
            }
        }

        boolean[] changedTotals = new boolean[registry.size()];
        for (int index = 0; index < registry.size(); index++) {
            if (!currentAmount(totals, index).equals(stagedTotals.get(index))) {
                changedTotals[index] = true;
            }
        }

        StorageRevision stagedRevision = revision.next();
        long[] stagedKeyRevisions = Arrays.copyOf(keyRevisions, registry.size());
        for (int index = 0; index < changedTotals.length; index++) {
            if (changedTotals[index]) {
                stagedKeyRevisions[index] = Math.incrementExact(stagedKeyRevisions[index]);
            }
        }

        StorageLocationIndex stagedIndex = new StorageLocationIndex();
        for (StorageLocationSnapshot snapshot : stagedSnapshots.values()) {
            stagedIndex.replace(snapshot.location(), null, snapshot);
        }

        snapshots = stagedSnapshots;
        totals = stagedTotals;
        locationIndex = stagedIndex;
        revision = stagedRevision;
        keyRevisions = stagedKeyRevisions;
    }

    private void validateBatch(Map<StorageLocationId, StorageLocationSnapshot> replacements,
            Set<StorageLocationId> removals) {
        for (StorageLocationId location : removals) {
            Objects.requireNonNull(location, "removals cannot contain null");
            if (replacements.containsKey(location)) {
                throw new IllegalArgumentException(
                        "A location cannot be replaced and removed in the same batch: " + location);
            }
        }

        for (Map.Entry<StorageLocationId, StorageLocationSnapshot> entry : replacements.entrySet()) {
            StorageLocationId location = Objects.requireNonNull(entry.getKey(),
                    "replacements cannot contain a null location");
            StorageLocationSnapshot snapshot = Objects.requireNonNull(entry.getValue(),
                    "replacements cannot contain a null snapshot");
            if (!location.equals(snapshot.location())) {
                throw new IllegalArgumentException("Replacement map key does not match snapshot location");
            }
            snapshot.enumerate((key, amount) -> validateKey(key));
        }
    }

    private int validateKey(KeyId key) {
        Objects.requireNonNull(key, "key");
        int index = key.value();
        if (index >= registry.size()) {
            throw new IndexOutOfBoundsException("Key id " + index + " outside registry size " + registry.size());
        }
        return index;
    }

    private static AEAmount currentAmount(AmountVector vector, int index) {
        return index < vector.size() ? vector.get(index) : AEAmount.ZERO;
    }
}
