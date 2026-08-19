package appeng.rebuild.persistence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;

/** Validates every restored identity before the deterministic current-registry intern phase. */
public final class KeyTableRebinder {
    private KeyTableRebinder() {
    }

    public static ExactKeyRemap rebind(PersistedKeyTable table, KeyRegistry current) {
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
        for (PersistedKeyTable.Entry entry : entries) {
            AEKey key = entry.key();
            KeyId currentId = observed.get(entry.oldId());
            if (currentId == null)
                currentId = current.intern(key);
            result.put(entry.oldId(), currentId);
        }
        return new ExactKeyRemap(table.generation(), current.generation(), result, table.entries().size());
    }
}
