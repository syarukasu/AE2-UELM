/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2021, TeamAppliedEnergistics, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.me.cells;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.IBasicCellItem;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.core.AELog;
import appeng.core.definitions.AEItems;
import appeng.rebuild.cell.ExactCellCapacityProvider;
import appeng.rebuild.cell.ExactCellDescriptor;
import appeng.rebuild.cell.ExactCellId;
import appeng.rebuild.cell.ExactCellSnapshot;
import appeng.rebuild.cell.ExactCellStorageManager;
import appeng.rebuild.persistence.AEAmountNbtCodec;
import appeng.rebuild.persistence.PersistenceDecodeResult;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.ExactMountedStorage;
import appeng.util.ConfigInventory;
import appeng.util.prioritylist.FuzzyPriorityList;
import appeng.util.prioritylist.IPartitionList;

public class BasicCellInventory implements StorageCell, ExactMountedStorage {
    private static final int MAX_ITEM_TYPES = 63;
    private static final String ITEM_COUNT_TAG = "ic";
    private static final String STACK_KEYS = "keys";
    private static final String STACK_AMOUNTS = "amts";
    private static final int MAX_TOOLTIP_PREVIEW = 16;

    private final ISaveProvider container;
    private final AEKeyType keyType;
    private final IPartitionList partitionList;
    private final IncludeExclude partitionListMode;
    private int maxItemTypes;
    private short storedItems;
    private AEAmount storedItemCount;
    private Map<AEKey, AEAmount> storedAmounts;
    private final ItemStack i;
    private final IBasicCellItem cellType;
    private final AEAmount maxItemsPerType;
    private final boolean hasVoidUpgrade;
    private boolean isPersisted = true;
    private ExactCellId exactCellId;
    private long exactCellRevision;
    private boolean exactMountRejected;
    private Object exactMountToken;

    private BasicCellInventory(IBasicCellItem cellType, ItemStack o, ISaveProvider container) {
        this.i = o;
        this.cellType = cellType;
        this.maxItemTypes = this.cellType.getTotalTypes(this.i);

        if (this.maxItemTypes > MAX_ITEM_TYPES) {
            this.maxItemTypes = MAX_ITEM_TYPES;
        }
        if (this.maxItemTypes < 1) {
            this.maxItemTypes = 1;
        }

        this.container = container;
        this.storedItems = readStoredItemTypesSummary();
        this.storedItemCount = readStoredItemCountSummary();
        this.storedAmounts = null;
        this.keyType = cellType.getKeyType();

        // Updates the partition list and mode based on installed upgrades and the configured filter.
        var builder = IPartitionList.builder();

        var upgrades = getUpgradesInventory();
        var config = getConfigInventory();

        boolean hasInverter = upgrades.isInstalled(AEItems.INVERTER_CARD);
        boolean isFuzzy = upgrades.isInstalled(AEItems.FUZZY_CARD);
        if (isFuzzy) {
            builder.fuzzyMode(getFuzzyMode());
        }

        builder.addAll(config.keySet());

        partitionListMode = (hasInverter ? IncludeExclude.BLACKLIST : IncludeExclude.WHITELIST);
        partitionList = builder.build();

        // Check for equal distribution card.
        if (upgrades.isInstalled(AEItems.EQUAL_DISTRIBUTION_CARD)) {
            // Compute max possible amount of types based on whitelist size, and bound by type limit.
            long maxTypes = Integer.MAX_VALUE;
            if (!isFuzzy && partitionListMode == IncludeExclude.WHITELIST && !config.keySet().isEmpty()) {
                maxTypes = config.keySet().size();
            }
            maxTypes = Math.min(maxTypes, this.maxItemTypes);

            BigInteger totalStorage = getExactTotalItemCapacity().toBigInteger()
                    .subtract(BigInteger.valueOf(getBytesPerType())
                            .multiply(BigInteger.valueOf(maxTypes))
                            .multiply(BigInteger.valueOf(keyType.getAmountPerByte())))
                    .max(BigInteger.ZERO);
            this.maxItemsPerType = AEAmount.of(totalStorage.add(BigInteger.valueOf(maxTypes - 1))
                    .divide(BigInteger.valueOf(maxTypes)));
        } else {
            this.maxItemsPerType = AEAmount.of(BigInteger.ONE.shiftLeft(65_536).subtract(BigInteger.ONE));
        }

        this.hasVoidUpgrade = upgrades.isInstalled(AEItems.VOID_CARD);
    }

    public IncludeExclude getPartitionListMode() {
        return partitionListMode;
    }

    public boolean isPreformatted() {
        return !partitionList.isEmpty();
    }

    public boolean isFuzzy() {
        return partitionList instanceof FuzzyPriorityList;
    }

    private CompoundTag getTag() {
        // On Fabric, the tag itself may be copied and then replaced later in case a portable cell is being charged.
        // In that case however, we can rely on the itemstack reference not changing due to the special logic in the
        // transactional inventory wrappers. So we must always re-query the tag from the stack.
        return this.i.getOrCreateTag();
    }

    public static BasicCellInventory createInventory(ItemStack o, ISaveProvider container) {
        Objects.requireNonNull(o, "Cannot create cell inventory for null itemstack");

        if (!(o.getItem() instanceof IBasicCellItem cellType)) {
            return null;
        }

        if (!cellType.isStorageCell(o)) {
            // This is not an error. Items may decide to not be a storage cell temporarily.
            return null;
        }

        // The cell type's channel matches, so this cast is safe
        return new BasicCellInventory(cellType, o, container);
    }

    public static boolean isCell(ItemStack input) {
        return getStorageCell(input) != null;
    }

    private static IBasicCellItem getStorageCell(ItemStack input) {
        if (input != null && input.getItem() instanceof IBasicCellItem basicCellItem) {
            return basicCellItem;
        }

        return null;
    }

    @Override
    public boolean canFitInsideCell() {
        return cellType.storableInStorageCell() || getAvailableStacks().isEmpty();
    }

    protected Map<AEKey, AEAmount> getCellItems() {
        if (this.storedAmounts == null) {
            this.storedAmounts = new LinkedHashMap<>();
            this.loadCellItems();
        } else if (isPersisted && exactCellId != null) {
            ExactCellStorageManager manager = currentManager();
            if (manager != null) {
                ExactCellSnapshot snapshot = manager.snapshot(exactCellId);
                if (snapshot.revision() != exactCellRevision) {
                    storedAmounts = new LinkedHashMap<>(snapshot.amounts());
                    exactCellRevision = snapshot.revision();
                    recalculateSummaries();
                }
            }
        }

        return this.storedAmounts;
    }

    @Override
    public void persist() {
        if (this.isPersisted) {
            return;
        }
        recalculateSummaries();
        ExactCellStorageManager manager = currentManager();
        if (manager != null) {
            ExactCellId id = ensureExactCellIdentity(manager);
            ExactCellSnapshot snapshot = manager.replace(id, storedAmounts);
            exactCellRevision = snapshot.revision();
            writeExactItemSummary(id);
        } else {
            persistLegacyFallback();
        }
        isPersisted = true;
    }

    protected void saveChanges() {
        recalculateSummaries();
        this.isPersisted = false;
        if (currentManager() != null) {
            persist();
        }
        if (this.container != null) {
            this.container.saveChanges();
        } else if (!this.isPersisted) {
            this.persist();
        }
    }

    private void loadCellItems() {
        ExactCellStorageManager manager = currentManager();
        if (hasExactCellId()) {
            if (manager == null) {
                return;
            }
            exactCellId = new ExactCellId(getTag().getUUID(ExactCellStorageManager.CELL_ID_TAG));
            ExactCellSnapshot snapshot = manager.snapshot(exactCellId);
            exactCellRevision = snapshot.revision();
            storedAmounts.putAll(snapshot.amounts());
            recalculateSummaries();
            return;
        }
        boolean corruptedTag = false;

        var amounts = getTag().getLongArray(STACK_AMOUNTS);
        var tags = getTag().getList(STACK_KEYS, Tag.TAG_COMPOUND);
        if (amounts.length != tags.size()) {
            AELog.warn("Loading storage cell with mismatched amounts/tags: %d != %d",
                    amounts.length, tags.size());
        }

        for (int i = 0; i < amounts.length; i++) {
            var amount = amounts[i];
            AEKey key = AEKey.fromTagGeneric(tags.getCompound(i));

            if (amount <= 0 || key == null) {
                corruptedTag = true;
            } else {
                storedAmounts.put(key, AEAmount.of(amount));
            }
        }

        if (corruptedTag) {
            this.saveChanges();
        }
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (exactMountRejected) {
            return;
        }
        for (var entry : getCellItems().entrySet()) {
            out.add(entry.getKey(), projectLong(entry.getValue()));
        }
    }

    @Override
    public double getIdleDrain() {
        return this.cellType.getIdleDrain();
    }

    public FuzzyMode getFuzzyMode() {
        return this.cellType.getFuzzyMode(this.i);
    }

    public ConfigInventory getConfigInventory() {
        return this.cellType.getConfigInventory(this.i);
    }

    public IUpgradeInventory getUpgradesInventory() {
        return this.cellType.getUpgrades(this.i);
    }

    public int getBytesPerType() {
        return this.cellType.getBytesPerType(this.i);
    }

    public boolean canHoldNewItem() {
        AEAmount typeCost = AEAmount.of((long) getBytesPerType() * keyType.getAmountPerByte());
        return getExactRemainingItemCount().compareTo(typeCost) > 0 && getRemainingItemTypes() > 0;
    }

    public long getTotalBytes() {
        return this.cellType.getBytes(this.i);
    }

    public long getFreeBytes() {
        return Math.max(0, this.getTotalBytes() - this.getUsedBytes());
    }

    public long getTotalItemTypes() {
        return this.maxItemTypes;
    }

    public long getStoredItemCount() {
        getCellItems();
        return projectLong(storedItemCount);
    }

    public AEAmount getExactStoredItemCount() {
        getCellItems();
        return storedItemCount;
    }

    /** Exact bounded item-NBT preview used when the server-owned UUID database is unavailable on the client. */
    public Map<AEKey, AEAmount> getExactTooltipContents() {
        if (currentManager() != null || !hasExactCellId()) {
            return Map.copyOf(getCellItems());
        }
        ListTag preview = getTag().getList(ExactCellStorageManager.CELL_PREVIEW_TAG, Tag.TAG_COMPOUND);
        if (preview.size() > MAX_TOOLTIP_PREVIEW) {
            return Map.of();
        }
        Map<AEKey, AEAmount> result = new LinkedHashMap<>();
        try {
            for (int index = 0; index < preview.size(); index++) {
                CompoundTag entry = preview.getCompound(index);
                if (entry.size() != 2 || !entry.contains("key", Tag.TAG_COMPOUND)
                        || !entry.contains("amount", Tag.TAG_COMPOUND)) {
                    return Map.of();
                }
                AEKey key = AEKey.fromTagGeneric(entry.getCompound("key"));
                PersistenceDecodeResult<AEAmount> decoded = AEAmountNbtCodec.decode(entry.getCompound("amount"));
                if (key == null || !(decoded instanceof PersistenceDecodeResult.Success<AEAmount> success)
                        || success.value().equals(AEAmount.ZERO) || result.put(key, success.value()) != null) {
                    return Map.of();
                }
            }
        } catch (RuntimeException ignored) {
            return Map.of();
        }
        return Map.copyOf(result);
    }

    public long getStoredItemTypes() {
        getCellItems();
        return this.storedItems;
    }

    public long getRemainingItemTypes() {
        long basedOnStorage;
        if (getBytesPerType() == 0) {
            basedOnStorage = getTotalItemTypes();
        } else {
            BigInteger typeCost = BigInteger.valueOf(getBytesPerType())
                    .multiply(BigInteger.valueOf(keyType.getAmountPerByte()));
            basedOnStorage = getExactRemainingItemCount().toBigInteger().divide(typeCost)
                    .min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
        }
        var baseOnTotal = this.getTotalItemTypes() - this.getStoredItemTypes();
        return Math.min(basedOnStorage, baseOnTotal);
    }

    public long getUsedBytes() {
        getCellItems();
        BigInteger amountPerByte = BigInteger.valueOf(keyType.getAmountPerByte());
        BigInteger bytesForItemCount = storedItemCount.toBigInteger().add(amountPerByte).subtract(BigInteger.ONE)
                .divide(amountPerByte);
        BigInteger used = BigInteger.valueOf(getStoredItemTypes()).multiply(BigInteger.valueOf(getBytesPerType()))
                .add(bytesForItemCount);
        return used.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    public long getRemainingItemCount() {
        return projectLong(getExactRemainingItemCount());
    }

    public AEAmount getExactRemainingItemCount() {
        getCellItems();
        BigInteger totalCapacity = getExactTotalItemCapacity().toBigInteger();
        BigInteger typeOverhead = BigInteger.valueOf(getStoredItemTypes())
                .multiply(BigInteger.valueOf(getBytesPerType()))
                .multiply(BigInteger.valueOf(keyType.getAmountPerByte()));
        BigInteger remaining = totalCapacity.subtract(typeOverhead).subtract(storedItemCount.toBigInteger());
        return AEAmount.of(remaining.max(BigInteger.ZERO));
    }

    public AEAmount getExactTotalItemCapacity() {
        return ExactCellCapacityProvider.capacityOf(cellType, i);
    }

    public int getUnusedItemCount() {
        getCellItems();
        final int div = storedItemCount.toBigInteger().mod(BigInteger.valueOf(keyType.getAmountPerByte()))
                .intValueExact();

        if (div == 0) {
            return 0;
        }

        return keyType.getAmountPerByte() - div;
    }

    @Override
    public CellState getStatus() {
        if (this.getStoredItemTypes() == 0) {
            return CellState.EMPTY;
        }
        if (this.canHoldNewItem()) {
            return CellState.NOT_EMPTY;
        }
        if (this.getRemainingItemCount() > 0) {
            return CellState.TYPES_FULL;
        }
        return CellState.FULL;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (exactMountRejected || amount == 0 || !keyType.contains(what)) {
            return 0;
        }

        if (!this.partitionList.matchesFilter(what, this.partitionListMode)) {
            return 0;
        }

        if (this.cellType.isBlackListed(this.i, what)) {
            return 0;
        }

        // Run regular insert logic and then apply void upgrade to the returned value.
        long inserted = innerInsert(what, amount, mode);

        // In the event that a void card is being used on a (full) unformatted cell, ensure it doesn't void any items
        // that the cell isn't even storing and cannot store to begin with
        if (!isPreformatted() && hasVoidUpgrade && !canHoldNewItem()) {
            return getCellItems().containsKey(what) ? amount : inserted;
        }

        return hasVoidUpgrade ? amount : inserted;
    }

    // Inner insert for items that pass the filter.
    private long innerInsert(AEKey what, long amount, Actionable mode) {
        // Prevent non-empty storage cells from being recursively stored inside this cell
        if (what instanceof AEItemKey itemKey) {
            var stack = itemKey.toStack();

            var cellInv = StorageCells.getCellInventory(stack, null);
            if (cellInv != null && !cellInv.canFitInsideCell()) {
                return 0;
            }
        }

        AEAmount currentAmount = getCellItems().getOrDefault(what, AEAmount.ZERO);
        AEAmount remainingItemCount = getExactRemainingItemCount();

        // Deduct the required storage for a new type if the type is new
        if (currentAmount.equals(AEAmount.ZERO)) {
            if (!canHoldNewItem()) {
                // No space for more types
                return 0;
            }

            remainingItemCount = remainingItemCount
                    .subtractExact(AEAmount.of((long) getBytesPerType() * keyType.getAmountPerByte()));
            if (remainingItemCount.equals(AEAmount.ZERO)) {
                return 0;
            }
        }

        // Apply max items per type
        AEAmount perTypeRemaining = maxItemsPerType.compareTo(currentAmount) > 0
                ? maxItemsPerType.subtractExact(currentAmount)
                : AEAmount.ZERO;
        AEAmount inserted = AEAmount.of(amount).min(remainingItemCount).min(perTypeRemaining);

        if (mode == Actionable.MODULATE) {
            if (!inserted.equals(AEAmount.ZERO)) {
                getCellItems().put(what, currentAmount.add(inserted));
            }
            this.saveChanges();
        }

        return inserted.longValueExact();
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (exactMountRejected || amount == 0) {
            return 0;
        }
        AEAmount currentAmount = getCellItems().getOrDefault(what, AEAmount.ZERO);
        AEAmount extracted = currentAmount.min(AEAmount.of(amount));
        if (mode == Actionable.MODULATE && !extracted.equals(AEAmount.ZERO)) {
            AEAmount remaining = currentAmount.subtractExact(extracted);
            if (remaining.equals(AEAmount.ZERO)) {
                getCellItems().remove(what);
            } else {
                getCellItems().put(what, remaining);
            }
            saveChanges();
        }
        return extracted.longValueExact();
    }

    @Override
    public ExactCellId exactCellId() {
        ExactCellStorageManager manager = requireCurrentManager();
        return ensureExactCellIdentity(manager);
    }

    @Override
    public long exactCellRevision() {
        getCellItems();
        return exactCellRevision;
    }

    @Override
    public void enumerateExact(java.util.function.BiConsumer<AEKey, AEAmount> visitor) {
        Objects.requireNonNull(visitor, "visitor");
        if (exactMountRejected) {
            throw new IllegalStateException("duplicate exact cell UUID is fail-closed");
        }
        getCellItems().forEach(visitor);
    }

    @Override
    public boolean claimExactMount(Object owner) {
        Objects.requireNonNull(owner, "owner");
        ExactCellStorageManager manager = requireCurrentManager();
        ExactCellId id = ensureExactCellIdentity(manager);
        if (exactMountToken == null) {
            exactMountToken = new Object();
        }
        boolean claimed = manager.claimMount(id, exactMountToken);
        exactMountRejected = !claimed;
        return claimed;
    }

    @Override
    public void releaseExactMount(Object owner) {
        Objects.requireNonNull(owner, "owner");
        if (exactMountToken != null && exactCellId != null) {
            requireCurrentManager().releaseMount(exactCellId, exactMountToken);
        }
        exactMountToken = null;
        exactMountRejected = false;
    }

    private AEAmount readStoredItemCountSummary() {
        CompoundTag tag = getTag();
        if (tag.contains(ExactCellStorageManager.CELL_COUNT_TAG, Tag.TAG_COMPOUND)) {
            PersistenceDecodeResult<AEAmount> decoded = AEAmountNbtCodec
                    .decode(tag.getCompound(ExactCellStorageManager.CELL_COUNT_TAG));
            if (decoded instanceof PersistenceDecodeResult.Success<?> success
                    && success.value() instanceof AEAmount amount) {
                return amount;
            }
            throw new IllegalStateException("malformed exact cell amount summary");
        }
        long legacy = tag.getLong(ITEM_COUNT_TAG);
        return legacy < 0 ? AEAmount.ZERO : AEAmount.of(legacy);
    }

    private short readStoredItemTypesSummary() {
        CompoundTag tag = getTag();
        if (tag.contains(ExactCellStorageManager.CELL_TYPES_TAG, Tag.TAG_INT)) {
            int types = tag.getInt(ExactCellStorageManager.CELL_TYPES_TAG);
            if (types < 0 || types > MAX_ITEM_TYPES) {
                throw new IllegalStateException("malformed exact cell type summary");
            }
            return (short) types;
        }
        return (short) tag.getLongArray(STACK_AMOUNTS).length;
    }

    private void recalculateSummaries() {
        storedItems = (short) getCellItems().size();
        AEAmount total = AEAmount.ZERO;
        for (AEAmount amount : getCellItems().values()) {
            total = total.add(amount);
        }
        storedItemCount = total;
    }

    private void persistLegacyFallback() {
        ListTag keys = new ListTag();
        long[] amounts = new long[getCellItems().size()];
        int index = 0;
        for (Map.Entry<AEKey, AEAmount> entry : getCellItems().entrySet()) {
            keys.add(entry.getKey().toTagGeneric());
            amounts[index++] = entry.getValue().longValueExact();
        }
        if (keys.isEmpty()) {
            getTag().remove(STACK_KEYS);
            getTag().remove(STACK_AMOUNTS);
            getTag().remove(ITEM_COUNT_TAG);
        } else {
            getTag().put(STACK_KEYS, keys);
            getTag().putLongArray(STACK_AMOUNTS, amounts);
            getTag().putLong(ITEM_COUNT_TAG, storedItemCount.longValueExact());
        }
    }

    private void writeExactItemSummary(ExactCellId id) {
        CompoundTag tag = getTag();
        tag.putUUID(ExactCellStorageManager.CELL_ID_TAG, id.value());
        tag.put(ExactCellStorageManager.CELL_COUNT_TAG, AEAmountNbtCodec.encode(storedItemCount));
        tag.putInt(ExactCellStorageManager.CELL_TYPES_TAG, storedItems);
        tag.putLong(ITEM_COUNT_TAG, projectLong(storedItemCount));
        ListTag preview = new ListTag();
        getCellItems().entrySet().stream().sorted(Map.Entry.<AEKey, AEAmount>comparingByValue().reversed())
                .limit(MAX_TOOLTIP_PREVIEW).forEach(entry -> {
                    CompoundTag previewEntry = new CompoundTag();
                    previewEntry.put("key", entry.getKey().toTagGeneric());
                    previewEntry.put("amount", AEAmountNbtCodec.encode(entry.getValue()));
                    preview.add(previewEntry);
                });
        if (preview.isEmpty()) {
            tag.remove(ExactCellStorageManager.CELL_PREVIEW_TAG);
        } else {
            tag.put(ExactCellStorageManager.CELL_PREVIEW_TAG, preview);
        }
        tag.remove(STACK_KEYS);
        tag.remove(STACK_AMOUNTS);
    }

    private boolean hasExactCellId() {
        CompoundTag tag = getTag();
        if (!tag.contains(ExactCellStorageManager.CELL_ID_TAG)) {
            return false;
        }
        if (!tag.hasUUID(ExactCellStorageManager.CELL_ID_TAG)) {
            throw new IllegalStateException("malformed exact cell UUID");
        }
        return true;
    }

    private ExactCellId ensureExactCellIdentity(ExactCellStorageManager manager) {
        if (exactCellId != null) {
            manager.snapshot(exactCellId);
            return exactCellId;
        }
        if (hasExactCellId()) {
            exactCellId = new ExactCellId(getTag().getUUID(ExactCellStorageManager.CELL_ID_TAG));
            ExactCellSnapshot snapshot = manager.snapshot(exactCellId);
            exactCellRevision = snapshot.revision();
            return exactCellId;
        }
        getCellItems();
        exactCellId = manager.create(exactCellDescriptor(), storedAmounts);
        exactCellRevision = 0;
        writeExactItemSummary(exactCellId);
        if (container != null) {
            container.saveChanges();
        }
        return exactCellId;
    }

    private ExactCellDescriptor exactCellDescriptor() {
        return new ExactCellDescriptor(BuiltInRegistries.ITEM.getKey(i.getItem()), keyType.getId(),
                Math.toIntExact(getTotalBytes()), getBytesPerType(), Math.toIntExact(getTotalItemTypes()),
                getExactTotalItemCapacity());
    }

    private static long projectLong(AEAmount amount) {
        return amount.toBigInteger().min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    private static ExactCellStorageManager currentManager() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isSameThread() ? ExactCellStorageManager.get(server) : null;
    }

    private static ExactCellStorageManager requireCurrentManager() {
        ExactCellStorageManager manager = currentManager();
        if (manager == null) {
            throw new IllegalStateException("exact cell storage requires the server thread");
        }
        return manager;
    }

    @Override
    public Component getDescription() {
        return i.getHoverName();
    }
}
