package appeng.rebuild.key;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;

/**
 * Server-thread-owned, sequential registry for densely addressing {@link AEKey AEKeys}.
 *
 * <p>
 * Instances are intentionally neither global nor persistent. Calls to {@link #intern(AEKey)} are sequential and
 * idempotent; {@link #lookup(AEKey)} never assigns an id.
 */
public final class KeyRegistry {
    private final long generation;
    private final Map<AEKey, KeyId> idsByKey = new HashMap<>();
    private final ArrayList<AEKey> keysById = new ArrayList<>();

    public KeyRegistry(long generation) {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative: " + generation);
        }
        this.generation = generation;
    }

    /** Assigns or returns the sequential id for {@code key}. Server thread only. */
    public KeyId intern(AEKey key) {
        Objects.requireNonNull(key, "key");
        var existing = idsByKey.get(key);
        if (existing != null) {
            return existing;
        }

        var id = new KeyId(keysById.size());
        idsByKey.put(key, id);
        keysById.add(key);
        return id;
    }

    /** Returns the existing id for {@code key}, or {@code null} when it has not been interned. */
    @Nullable
    public KeyId lookup(AEKey key) {
        return idsByKey.get(Objects.requireNonNull(key, "key"));
    }

    /** Resolves a registry-scoped id, rejecting ids outside this registry. */
    public AEKey resolve(KeyId id) {
        Objects.requireNonNull(id, "id");
        int value = id.value();
        if (value >= keysById.size()) {
            throw new IndexOutOfBoundsException("Key id " + value + " outside [0, " + keysById.size() + ')');
        }
        return keysById.get(value);
    }

    public int size() {
        return keysById.size();
    }

    public long generation() {
        return generation;
    }
}
