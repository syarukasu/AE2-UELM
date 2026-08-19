package appeng.rebuild.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;

/** Validates every restored identity before the deterministic current-registry intern phase. */
public final class KeyTableRebinder {
    private KeyTableRebinder() {
    }

    public static ExactKeyRemap rebind(PersistedKeyTable table, KeyRegistry current) {
        ExactKeyRemap prospective = prospective(table, current);
        commit(table, current, prospective);
        return prospective;
    }

    /** Computes the exact sequential target ids without mutating the current registry. */
    static ExactKeyRemap prospective(PersistedKeyTable table, KeyRegistry current) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(current, "current");
        // Entire read-only preflight completes before the first current-registry intern, preventing malformed later
        // entries from causing a partially rebound current registry.
        ArrayList<PersistedKeyTable.Entry> entries = new ArrayList<>(table.entries().size());
        Map<KeyId, KeyId> observed = new HashMap<>(table.entries().size());
        StrictNbt.Budget budget = StrictNbt.budget();
        if (!budget.reserve(64, 5))
            throw new IllegalArgumentException("Persisted key table header exceeds strict limits");
        for (PersistedKeyTable.Entry entry : table.entries()) {
            AEKey key = Objects.requireNonNull(entry.key(), "persisted key");
            var generic = key.toTagGeneric();
            if (!StrictNbt.valid(generic) || !budget.reserve(24, 1) || !budget.add(generic))
                throw new IllegalArgumentException("Persisted key identity exceeds strict persistence limits");
            entries.add(entry);
            observed.put(entry.oldId(), current.lookup(key));
        }
        Map<KeyId, KeyId> result = new HashMap<>(table.entries().size());
        Set<KeyId> assignedIds = new HashSet<>(table.entries().size());
        int next = current.size();
        for (PersistedKeyTable.Entry entry : entries) {
            KeyId currentId = observed.get(entry.oldId());
            if (currentId == null)
                currentId = new KeyId(next++);
            if (!assignedIds.add(currentId))
                throw new IllegalArgumentException("Persisted key remap is not one-to-one");
            result.put(entry.oldId(), currentId);
        }
        return new ExactKeyRemap(table.generation(), current.generation(), result, table.entries().size());
    }

    /** Atomically commits a caller's fully restored prospective remap. */
    static void commit(PersistedKeyTable table, KeyRegistry current, ExactKeyRemap expected) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(expected, "expected");
        if (expected.oldGeneration() != table.generation() || expected.newGeneration() != current.generation()
                || expected.mappings().size() != table.entries().size())
            throw new IllegalArgumentException("Prospective remap identity mismatch");
        ArrayList<AEKey> keys = new ArrayList<>(table.entries().size());
        ArrayList<KeyId> expectedIds = new ArrayList<>(table.entries().size());
        for (PersistedKeyTable.Entry entry : table.entries()) {
            keys.add(entry.key());
            expectedIds.add(expected.require(entry.oldId()));
        }
        current.internAllAtomically(keys, expectedIds);
    }
}
