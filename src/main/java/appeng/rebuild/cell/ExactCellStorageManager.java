package appeng.rebuild.cell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEKey;
import appeng.api.storage.cells.IBasicCellItem;
import appeng.core.worlddata.AESavedData;
import appeng.rebuild.persistence.AEAmountNbtCodec;
import appeng.rebuild.persistence.PersistenceDecodeResult;
import appeng.rebuild.quantity.AEAmount;

/**
 * Overworld-owned exact cell database inspired by AE2 Things' UUID-backed DISK storage.
 *
 * <p>
 * Cell item NBT carries only the stable UUID and compatibility summary. Exact contents and revisions live here. All
 * mutations are confined to the owning server thread and mark crash-resistant saved data dirty before returning.
 */
public final class ExactCellStorageManager extends AESavedData {
    public static final String CELL_ID_TAG = "ae2ExactCellId";
    public static final String CELL_COUNT_TAG = "ae2ExactCellCount";
    public static final String CELL_TYPES_TAG = "ae2ExactCellTypes";
    public static final int MAX_CELLS = 65_536;
    public static final int MAX_KEYS_PER_CELL = 65_536;

    private static final String DATA_NAME = "ae2_rebuild_exact_cells";
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_UUID_ATTEMPTS = 16;
    private static final String VERSION = "version";
    private static final String CELLS = "cells";
    private static final String ID = "id";
    private static final String REVISION = "revision";
    private static final String ENTRIES = "entries";
    private static final String KEY = "key";
    private static final String AMOUNT = "amount";
    private static final String DESCRIPTOR = "descriptor";
    private static final String ITEM = "item";
    private static final String KEY_TYPE = "keyType";
    private static final String BYTES = "bytes";
    private static final String BYTES_PER_TYPE = "bytesPerType";
    private static final String TYPES = "types";

    private final MinecraftServer server;
    private final Map<ExactCellId, CellRecord> cells;
    private final Map<ExactCellId, Object> mountedOwners = new HashMap<>();
    private final IdentityHashMap<Object, ExactCellId> identitiesByOwner = new IdentityHashMap<>();

    private ExactCellStorageManager(MinecraftServer server) {
        this(server, new HashMap<>());
    }

    private ExactCellStorageManager(MinecraftServer server, Map<ExactCellId, CellRecord> cells) {
        this.server = Objects.requireNonNull(server, "server");
        this.cells = cells;
    }

    public static ExactCellStorageManager get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        ServerLevel overworld = Objects.requireNonNull(server.getLevel(ServerLevel.OVERWORLD), "overworld");
        return overworld.getDataStorage().computeIfAbsent(tag -> load(server, tag),
                () -> new ExactCellStorageManager(server), DATA_NAME);
    }

    public ExactCellId create(ExactCellDescriptor descriptor, Map<AEKey, AEAmount> initialAmounts) {
        requireServerThread();
        Objects.requireNonNull(descriptor, "descriptor");
        Map<AEKey, AEAmount> amounts = ExactCellSnapshot.copyAmounts(initialAmounts);
        if (cells.size() >= MAX_CELLS) {
            throw new IllegalStateException("exact cell limit exhausted");
        }
        for (int attempt = 0; attempt < MAX_UUID_ATTEMPTS; attempt++) {
            ExactCellId id = new ExactCellId(UUID.randomUUID());
            if (!cells.containsKey(id)) {
                cells.put(id, new CellRecord(descriptor, 0, amounts));
                setDirty();
                return id;
            }
        }
        throw new IllegalStateException("unable to allocate a unique exact cell identity");
    }

    public boolean contains(ExactCellId id) {
        requireServerThread();
        return cells.containsKey(Objects.requireNonNull(id, "id"));
    }

    public ExactCellSnapshot snapshot(ExactCellId id) {
        requireServerThread();
        CellRecord record = cells.get(Objects.requireNonNull(id, "id"));
        if (record == null) {
            throw new IllegalStateException("exact cell identity is not registered: " + id.value());
        }
        return new ExactCellSnapshot(id, record.descriptor, record.revision, record.amounts);
    }

    public ExactCellSnapshot replace(ExactCellId id, Map<AEKey, AEAmount> replacement) {
        requireServerThread();
        Objects.requireNonNull(id, "id");
        Map<AEKey, AEAmount> amounts = ExactCellSnapshot.copyAmounts(replacement);
        CellRecord previous = cells.get(id);
        if (previous == null) {
            throw new IllegalStateException("exact cell identity is not registered: " + id.value());
        }
        long revision;
        try {
            revision = Math.incrementExact(previous.revision);
        } catch (ArithmeticException failure) {
            throw new IllegalStateException("exact cell revision exhausted", failure);
        }
        CellRecord next = new CellRecord(previous.descriptor, revision, amounts);
        cells.put(id, next);
        setDirty();
        return new ExactCellSnapshot(id, previous.descriptor, revision, amounts);
    }

    /** Rotates a lost cell's identity before returning a replacement stack, invalidating every stale copy. */
    public ItemStack recoverAndRotate(ExactCellId lostId) {
        requireServerThread();
        Objects.requireNonNull(lostId, "lostId");
        if (mountedOwners.containsKey(lostId)) {
            throw new IllegalStateException("cannot recover a mounted exact cell");
        }
        CellRecord record = cells.get(lostId);
        if (record == null) {
            throw new IllegalStateException("unknown exact cell identity");
        }
        ExactCellId replacement = null;
        for (int attempt = 0; attempt < MAX_UUID_ATTEMPTS; attempt++) {
            ExactCellId candidate = new ExactCellId(UUID.randomUUID());
            if (!cells.containsKey(candidate)) {
                replacement = candidate;
                break;
            }
        }
        if (replacement == null) {
            throw new IllegalStateException("unable to rotate exact cell identity");
        }
        var item = BuiltInRegistries.ITEM.getOptional(record.descriptor.itemId())
                .orElseThrow(() -> new IllegalStateException("exact cell item type is unavailable"));
        if (!(item instanceof IBasicCellItem cellItem)) {
            throw new IllegalStateException("recovered item is no longer a basic storage cell");
        }
        ItemStack stack = new ItemStack(item);
        ExactCellDescriptor current = new ExactCellDescriptor(record.descriptor.itemId(), cellItem.getKeyType().getId(),
                cellItem.getBytes(stack), cellItem.getBytesPerType(stack), cellItem.getTotalTypes(stack));
        if (!current.equals(record.descriptor)) {
            throw new IllegalStateException("recovered cell type no longer matches its persisted capacity");
        }
        cells.remove(lostId);
        cells.put(replacement, record);
        stack.getOrCreateTag().putUUID(CELL_ID_TAG, replacement.value());
        AEAmount total = AEAmount.ZERO;
        for (AEAmount amount : record.amounts.values()) {
            total = total.add(amount);
        }
        stack.getOrCreateTag().put(CELL_COUNT_TAG, AEAmountNbtCodec.encode(total));
        stack.getOrCreateTag().putInt(CELL_TYPES_TAG, record.amounts.size());
        setDirty();
        return stack;
    }

    /** Claims one UUID for a mounted storage owner. A second distinct owner is rejected. */
    public boolean claimMount(ExactCellId id, Object owner) {
        requireServerThread();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        if (!cells.containsKey(id)) {
            throw new IllegalStateException("cannot mount an unregistered exact cell");
        }
        ExactCellId priorIdentity = identitiesByOwner.get(owner);
        if (priorIdentity != null && !priorIdentity.equals(id)) {
            throw new IllegalStateException("mount owner is already bound to another exact cell");
        }
        Object mounted = mountedOwners.get(id);
        if (mounted != null && mounted != owner) {
            return false;
        }
        mountedOwners.put(id, owner);
        identitiesByOwner.put(owner, id);
        return true;
    }

    public void releaseMount(ExactCellId id, Object owner) {
        requireServerThread();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        if (mountedOwners.get(id) == owner) {
            mountedOwners.remove(id);
            identitiesByOwner.remove(owner);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        requireServerThread();
        tag.putInt(VERSION, FORMAT_VERSION);
        ListTag cellList = new ListTag();
        List<ExactCellId> ids = new ArrayList<>(cells.keySet());
        ids.sort(ExactCellId::compareTo);
        for (ExactCellId id : ids) {
            CellRecord record = cells.get(id);
            CompoundTag cellTag = new CompoundTag();
            cellTag.putUUID(ID, id.value());
            cellTag.putLong(REVISION, record.revision);
            cellTag.put(DESCRIPTOR, encodeDescriptor(record.descriptor));
            ListTag entryList = new ListTag();
            for (Map.Entry<AEKey, AEAmount> entry : record.amounts.entrySet()) {
                CompoundTag entryTag = new CompoundTag();
                entryTag.put(KEY, entry.getKey().toTagGeneric());
                entryTag.put(AMOUNT, AEAmountNbtCodec.encode(entry.getValue()));
                entryList.add(entryTag);
            }
            cellTag.put(ENTRIES, entryList);
            cellList.add(cellTag);
        }
        tag.put(CELLS, cellList);
        return tag;
    }

    private static ExactCellStorageManager load(MinecraftServer server, CompoundTag tag) {
        if (tag.size() != 2 || !tag.contains(VERSION, Tag.TAG_INT) || !tag.contains(CELLS, Tag.TAG_LIST)
                || tag.getInt(VERSION) != FORMAT_VERSION) {
            throw new IllegalStateException("malformed or unsupported exact cell database");
        }
        ListTag cellList = tag.getList(CELLS, Tag.TAG_COMPOUND);
        if (cellList.size() > MAX_CELLS) {
            throw new IllegalStateException("exact cell database exceeds cell limit");
        }
        Map<ExactCellId, CellRecord> cells = new HashMap<>(cellList.size());
        for (int cellIndex = 0; cellIndex < cellList.size(); cellIndex++) {
            CompoundTag cellTag = cellList.getCompound(cellIndex);
            if (cellTag.size() != 4 || !cellTag.hasUUID(ID) || !cellTag.contains(REVISION, Tag.TAG_LONG)
                    || !cellTag.contains(DESCRIPTOR, Tag.TAG_COMPOUND)
                    || !cellTag.contains(ENTRIES, Tag.TAG_LIST)) {
                throw new IllegalStateException("malformed exact cell record");
            }
            long revision = cellTag.getLong(REVISION);
            ListTag entryList = cellTag.getList(ENTRIES, Tag.TAG_COMPOUND);
            if (revision < 0 || entryList.size() > MAX_KEYS_PER_CELL) {
                throw new IllegalStateException("invalid exact cell record bounds");
            }
            Map<AEKey, AEAmount> amounts = new LinkedHashMap<>(entryList.size());
            for (int entryIndex = 0; entryIndex < entryList.size(); entryIndex++) {
                CompoundTag entryTag = entryList.getCompound(entryIndex);
                if (entryTag.size() != 2 || !entryTag.contains(KEY, Tag.TAG_COMPOUND)
                        || !entryTag.contains(AMOUNT, Tag.TAG_COMPOUND)) {
                    throw new IllegalStateException("malformed exact cell entry");
                }
                AEKey key = AEKey.fromTagGeneric(entryTag.getCompound(KEY));
                PersistenceDecodeResult<AEAmount> decoded = AEAmountNbtCodec.decode(entryTag.getCompound(AMOUNT));
                if (key == null || !(decoded instanceof PersistenceDecodeResult.Success<AEAmount> success)
                        || success.value().equals(AEAmount.ZERO) || amounts.put(key, success.value()) != null) {
                    throw new IllegalStateException("invalid exact cell key or amount");
                }
            }
            ExactCellId id = new ExactCellId(cellTag.getUUID(ID));
            ExactCellDescriptor descriptor = decodeDescriptor(cellTag.getCompound(DESCRIPTOR));
            if (cells.put(id, new CellRecord(descriptor, revision, ExactCellSnapshot.copyAmounts(amounts))) != null) {
                throw new IllegalStateException("duplicate exact cell identity");
            }
        }
        return new ExactCellStorageManager(server, cells);
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("exact cell database accessed off the server thread");
        }
    }

    private static CompoundTag encodeDescriptor(ExactCellDescriptor descriptor) {
        CompoundTag tag = new CompoundTag();
        tag.putString(ITEM, descriptor.itemId().toString());
        tag.putString(KEY_TYPE, descriptor.keyTypeId().toString());
        tag.putInt(BYTES, descriptor.totalBytes());
        tag.putInt(BYTES_PER_TYPE, descriptor.bytesPerType());
        tag.putInt(TYPES, descriptor.totalTypes());
        return tag;
    }

    private static ExactCellDescriptor decodeDescriptor(CompoundTag tag) {
        if (tag.size() != 5 || !tag.contains(ITEM, Tag.TAG_STRING) || !tag.contains(KEY_TYPE, Tag.TAG_STRING)
                || !tag.contains(BYTES, Tag.TAG_INT) || !tag.contains(BYTES_PER_TYPE, Tag.TAG_INT)
                || !tag.contains(TYPES, Tag.TAG_INT)) {
            throw new IllegalStateException("malformed exact cell descriptor");
        }
        try {
            return new ExactCellDescriptor(new ResourceLocation(tag.getString(ITEM)),
                    new ResourceLocation(tag.getString(KEY_TYPE)), tag.getInt(BYTES), tag.getInt(BYTES_PER_TYPE),
                    tag.getInt(TYPES));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("invalid exact cell descriptor", failure);
        }
    }

    private record CellRecord(ExactCellDescriptor descriptor, long revision, Map<AEKey, AEAmount> amounts) {
    }
}
