package appeng.rebuild.persistence;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import appeng.rebuild.key.KeyId;

/** Immutable complete old-to-current key identity rebind for one persisted sparse key table. */
public final class ExactKeyRemap {
    private final long oldGeneration;
    private final long newGeneration;
    private final Map<KeyId, KeyId> mappings;

    ExactKeyRemap(long oldGeneration, long newGeneration, Map<KeyId, KeyId> mappings, int expectedSize) {
        if (oldGeneration < 0 || newGeneration < 0 || expectedSize < 0 || expectedSize > PersistenceLimits.MAX_KEYS
                || mappings == null || mappings.size() != expectedSize)
            throw new IllegalArgumentException("Invalid remap generations or completeness");
        for (Map.Entry<KeyId, KeyId> entry : mappings.entrySet()) {
            if (entry == null)
                throw new IllegalArgumentException("Null remap entry");
            Objects.requireNonNull(entry.getKey(), "old id");
            Objects.requireNonNull(entry.getValue(), "new id");
        }
        TreeMap<KeyId, KeyId> copy = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, KeyId> entry : mappings.entrySet())
            copy.put(Objects.requireNonNull(entry.getKey(), "old id"),
                    Objects.requireNonNull(entry.getValue(), "new id"));
        this.oldGeneration = oldGeneration;
        this.newGeneration = newGeneration;
        this.mappings = Collections.unmodifiableMap(copy);
    }

    public long oldGeneration() {
        return oldGeneration;
    }

    public long newGeneration() {
        return newGeneration;
    }

    public Map<KeyId, KeyId> mappings() {
        return mappings;
    }

    public KeyId require(KeyId oldId) {
        KeyId resolved = mappings.get(Objects.requireNonNull(oldId, "oldId"));
        if (resolved == null)
            throw new IllegalArgumentException("Unknown persisted key id: " + oldId.value());
        return resolved;
    }
}
