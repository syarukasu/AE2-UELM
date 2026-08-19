package appeng.rebuild.storage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import appeng.rebuild.key.KeyId;

/**
 * Reverse index from non-zero key amounts to their storage locations.
 *
 * <p>
 * This type is server-thread-owned. Returned sets and copies cannot mutate this index.
 */
public final class StorageLocationIndex {
    private final Map<KeyId, Set<StorageLocationId>> locationsByKey;

    public StorageLocationIndex() {
        this(new HashMap<>());
    }

    private StorageLocationIndex(Map<KeyId, Set<StorageLocationId>> locationsByKey) {
        this.locationsByKey = locationsByKey;
    }

    /** Returns an immutable snapshot of locations containing a non-zero amount for {@code key}. */
    public Set<StorageLocationId> locations(KeyId key) {
        Objects.requireNonNull(key, "key");
        Set<StorageLocationId> locations = locationsByKey.get(key);
        return locations == null ? Set.of() : Set.copyOf(locations);
    }

    public int indexedKeyCount() {
        return locationsByKey.size();
    }

    /** Replaces one location's indexed snapshot, removing all stale memberships first. */
    public void replace(StorageLocationId location, @Nullable StorageLocationSnapshot previous,
            StorageLocationSnapshot replacement) {
        Objects.requireNonNull(location, "location");
        if (previous != null && !location.equals(previous.location())) {
            throw new IllegalArgumentException("Previous snapshot location does not match index location");
        }
        Objects.requireNonNull(replacement, "replacement");
        if (!location.equals(replacement.location())) {
            throw new IllegalArgumentException("Replacement snapshot location does not match index location");
        }

        removeLocationFromAllKeys(location);
        replacement.enumerate(
                (key, amount) -> locationsByKey.computeIfAbsent(key, ignored -> new HashSet<>()).add(location));
    }

    /** Removes one location's indexed snapshot, including any stale memberships. */
    public void remove(StorageLocationId location, StorageLocationSnapshot previous) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(previous, "previous");
        if (!location.equals(previous.location())) {
            throw new IllegalArgumentException("Previous snapshot location does not match index location");
        }

        removeLocationFromAllKeys(location);
    }

    /** Returns an independent mutable index copy. */
    public StorageLocationIndex copy() {
        Map<KeyId, Set<StorageLocationId>> copy = new HashMap<>();
        locationsByKey.forEach((key, locations) -> copy.put(key, new HashSet<>(locations)));
        return new StorageLocationIndex(copy);
    }

    private void removeLocationFromAllKeys(StorageLocationId location) {
        locationsByKey.entrySet().removeIf(entry -> {
            entry.getValue().remove(location);
            return entry.getValue().isEmpty();
        });
    }
}
