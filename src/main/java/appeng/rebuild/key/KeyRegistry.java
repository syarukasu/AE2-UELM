package appeng.rebuild.key;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    /** Recovery-batch cap; larger persistence input is rejected before copying registry containers. */
    private static final int MAX_ATOMIC_RECOVERY_BATCH = 65_536;
    private final long generation;
    private Map<AEKey, KeyId> idsByKey = new HashMap<>();
    private ArrayList<AEKey> keysById = new ArrayList<>();

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

    /**
     * Atomically applies one bounded persistence/recovery intern batch, or leaves this registry entirely unchanged.
     *
     * <p>
     * The expected ids bind each input position and are checked against local copies before the reference swap. This
     * method deliberately performs no callback after the swap.
     */
    public void internAllAtomically(List<AEKey> keys, List<KeyId> expectedIds) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(expectedIds, "expectedIds");
        if (keys.size() > MAX_ATOMIC_RECOVERY_BATCH || expectedIds.size() > MAX_ATOMIC_RECOVERY_BATCH
                || keys.size() != expectedIds.size())
            throw new IllegalArgumentException("Atomic key intern batch identities differ in size");
        Map<AEKey, KeyId> nextIds = new HashMap<>(idsByKey);
        ArrayList<AEKey> nextKeys = new ArrayList<>(keysById);
        for (int index = 0; index < keys.size(); index++) {
            AEKey key = Objects.requireNonNull(keys.get(index), "atomic key");
            KeyId expected = Objects.requireNonNull(expectedIds.get(index), "expected key id");
            KeyId actual = nextIds.get(key);
            if (actual == null) {
                actual = new KeyId(nextKeys.size());
                nextIds.put(key, actual);
                nextKeys.add(key);
            }
            if (!actual.equals(expected))
                throw new IllegalArgumentException("Atomic key intern batch no longer matches its prospective ids");
        }
        if (nextIds.size() != nextKeys.size())
            throw new IllegalArgumentException("Atomic key intern batch produced inconsistent registry containers");
        for (int index = 0; index < keys.size(); index++) {
            if (!expectedIds.get(index).equals(nextIds.get(keys.get(index))))
                throw new IllegalArgumentException("Atomic key intern batch lost its expected identity");
        }
        idsByKey = nextIds;
        keysById = nextKeys;
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
