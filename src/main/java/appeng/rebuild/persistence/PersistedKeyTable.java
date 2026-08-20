package appeng.rebuild.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;

/** A bounded sparse old-id table of generic AEKey identities, captured without mutating its source registry. */
public record PersistedKeyTable(long generation, List<Entry> entries) {

    private static final String VERSION = "v";
    private static final String GENERATION = "g";
    private static final String ENTRIES = "e";
    private static final String ID = "i";
    private static final String KEY = "k";

    public PersistedKeyTable {
        if (generation < 0)
            throw new IllegalArgumentException("generation must be non-negative");
        entries = copyEntries(entries);
    }

    public record Entry(KeyId oldId, AEKey key) {
        public Entry {
            Objects.requireNonNull(oldId, "oldId");
            Objects.requireNonNull(key, "key");
        }
    }

    public static PersistedKeyTable capture(KeyRegistry source, Collection<KeyId> usedIds) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(usedIds, "usedIds");
        if (usedIds.size() > PersistenceLimits.MAX_KEYS)
            throw new IllegalArgumentException("Too many persisted keys");
        ArrayList<KeyId> sorted = new ArrayList<>(usedIds.size());
        for (KeyId id : usedIds)
            sorted.add(Objects.requireNonNull(id, "used key id"));
        sorted.sort(Comparator.comparingInt(KeyId::value));
        ArrayList<Entry> captured = new ArrayList<>(sorted.size());
        int prior = -1;
        for (KeyId id : sorted) {
            if (id.value() == prior)
                throw new IllegalArgumentException("Duplicate used key id");
            prior = id.value();
            captured.add(new Entry(id, source.resolve(id)));
        }
        return new PersistedKeyTable(source.generation(), captured);
    }

    public CompoundTag encode() {
        CompoundTag root = new CompoundTag();
        root.putInt(VERSION, PersistenceLimits.FORMAT_VERSION);
        root.putLong(GENERATION, generation);
        ListTag list = new ListTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        // Root/list fields and their short fixed names are charged once; every entry is charged before list.add.
        if (!budget.reserve(64, 5))
            throw new IllegalArgumentException("Key table persistence header exceeds limits");
        for (Entry entry : entries) {
            CompoundTag encoded = new CompoundTag();
            encoded.putInt(ID, entry.oldId().value());
            CompoundTag key = entry.key().toTagGeneric();
            if (!StrictNbt.valid(key))
                throw new IllegalArgumentException("Generic key exceeds persistence limits");
            encoded.put(KEY, key);
            if (!budget.add(encoded))
                throw new IllegalArgumentException("Whole key table exceeds persistence limits");
            list.add(encoded);
        }
        root.put(ENTRIES, list);
        if (!StrictNbt.valid(root))
            throw new IllegalArgumentException("Whole key table exceeds persistence limits");
        return root;
    }

    public static PersistenceDecodeResult<PersistedKeyTable> decode(CompoundTag root) {
        if (root == null || !StrictNbt.valid(root) || root.size() != 3 || !root.contains(VERSION, Tag.TAG_INT)
                || !root.contains(GENERATION, Tag.TAG_LONG) || !root.contains(ENTRIES, Tag.TAG_LIST)
                || root.getLong(GENERATION) < 0) {
            return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.MALFORMED);
        }
        if (root.getInt(VERSION) != PersistenceLimits.FORMAT_VERSION)
            return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.UNSUPPORTED_VERSION);
        Tag rawEntries = root.get(ENTRIES);
        if (!(rawEntries instanceof ListTag list)
                || list.size() > 0 && Byte.toUnsignedInt(list.getElementType()) != Tag.TAG_COMPOUND)
            return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.MALFORMED);
        if (list.size() > PersistenceLimits.MAX_KEYS)
            return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.LIMIT_EXCEEDED);
        ArrayList<Entry> decoded = new ArrayList<>(list.size());
        Set<AEKey> identities = new HashSet<>();
        int prior = -1;
        for (int index = 0; index < list.size(); index++) {
            CompoundTag entry = list.getCompound(index);
            if (entry.size() != 2 || !entry.contains(ID, Tag.TAG_INT) || !entry.contains(KEY, Tag.TAG_COMPOUND)) {
                return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.MALFORMED);
            }
            int oldId = entry.getInt(ID);
            CompoundTag generic = entry.getCompound(KEY);
            if (oldId < 0 || oldId <= prior || !StrictNbt.valid(generic)) {
                return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.MALFORMED);
            }
            final AEKey key;
            try {
                key = AEKey.fromTagGeneric(generic);
            } catch (RuntimeException failure) {
                return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.MALFORMED);
            }
            if (key == null || !identities.add(key))
                return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.UNKNOWN_KEY);
            decoded.add(new Entry(new KeyId(oldId), key));
            prior = oldId;
        }
        try {
            return new PersistenceDecodeResult.Success<>(new PersistedKeyTable(root.getLong(GENERATION), decoded));
        } catch (RuntimeException failure) {
            return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.MALFORMED);
        }
    }

    private static List<Entry> copyEntries(List<Entry> source) {
        Objects.requireNonNull(source, "entries");
        if (source.size() > PersistenceLimits.MAX_KEYS)
            throw new IllegalArgumentException("Too many persisted keys");
        ArrayList<Entry> copy = new ArrayList<>(source.size());
        Set<AEKey> keys = new HashSet<>();
        int prior = -1;
        for (Entry entry : source) {
            entry = Objects.requireNonNull(entry, "entry");
            if (entry.oldId().value() <= prior || !keys.add(entry.key()))
                throw new IllegalArgumentException("Non-canonical key table");
            prior = entry.oldId().value();
            copy.add(entry);
        }
        return List.copyOf(copy);
    }
}
