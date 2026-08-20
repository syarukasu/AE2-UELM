/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
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

package appeng.me.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridServiceProvider;
import appeng.api.networking.storage.IStorageService;
import appeng.api.networking.storage.IStorageWatcherNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.me.helpers.InterestManager;
import appeng.me.helpers.StackWatcher;
import appeng.me.storage.NetworkStorage;
import appeng.rebuild.api.legacy.LegacyAmountProjection;
import appeng.rebuild.api.legacy.LegacyNetworkBrokerStorage;
import appeng.rebuild.api.legacy.LegacyStorageFacade;
import appeng.rebuild.execution.ServerThreadGate;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.rebuild.storage.StorageServiceRebuild;

public class StorageService implements IStorageService, IGridServiceProvider {
    private static final AtomicLong NEXT_REGISTRY_GENERATION = new AtomicLong();

    /**
     * Tracks the storage service's state for each grid node that provides storage to the network.
     */
    private final Map<IGridNode, ProviderState> nodeProviders = new IdentityHashMap<>();
    /**
     * Tracks state for storage providers that are provided by other grid services (i.e. crafting).
     */
    private final List<ProviderState> globalProviders = new ArrayList<>();
    private final SetMultimap<AEKey, StackWatcher<IStorageWatcherNode>> interests = HashMultimap.create();
    private final InterestManager<StackWatcher<IStorageWatcherNode>> interestManager = new InterestManager<>(
            this.interests);
    private final NetworkStorage storage;
    private final StorageServiceRebuild exactStorage;
    private final LegacyStorageFacade inventory;
    /**
     * Publicly exposed cached available stacks.
     */
    private final KeyCounter cachedAvailableStacks = new KeyCounter();
    /**
     * Private cached amounts, to ensure that we send correct change notifications even if
     * {@link #cachedAvailableStacks} is modified by mistake.
     */
    private final Object2LongMap<AEKey> cachedAvailableAmounts = new Object2LongOpenHashMap<>();
    /**
     * Tracks the stack watcher associated with a given grid node. Needed to clean up watchers when the node leaves the
     * grid.
     */
    private final Map<IGridNode, StackWatcher<IStorageWatcherNode>> watchers = new IdentityHashMap<>();

    public StorageService() {
        KeyRegistry keyRegistry = new KeyRegistry(nextRegistryGeneration());
        this.exactStorage = new StorageServiceRebuild(keyRegistry);
        this.storage = new NetworkStorage(exactStorage);
        this.inventory = new LegacyStorageFacade(storage, exactStorage);
    }

    @Override
    public void onServerEndTick() {
        if (exactStorage.reconcileEndTick()) {
            updateCachedStacksFromExact();
        }
    }

    private void updateCachedStacksFromExact() {
        KeyCounter projectedStacks = new KeyCounter();
        exactStorage.ledger().enumerate((key, amount) -> projectedStacks.add(exactStorage.keyRegistry().resolve(key),
                LegacyAmountProjection.saturatingLong(amount)));
        replaceCachedStacks(projectedStacks);
    }

    private void replaceCachedStacks(KeyCounter projectedStacks) {
        cachedAvailableStacks.clear();
        cachedAvailableStacks.addAll(projectedStacks);
        // clear() only clears the inner maps,
        // so ensure that the outer map gets cleaned up too
        cachedAvailableStacks.removeEmptySubmaps();

        // Post watcher update for currently available stacks
        for (var entry : cachedAvailableStacks) {
            var what = entry.getKey();
            var newAmount = entry.getLongValue();
            if (newAmount != cachedAvailableAmounts.getLong(what)) {
                postWatcherUpdate(what, newAmount);
            }
        }
        // Post watcher update for removed stacks
        for (var what : cachedAvailableAmounts.keySet()) {
            var newAmount = cachedAvailableStacks.get(what);
            if (newAmount == 0) {
                postWatcherUpdate(what, newAmount);
            }
        }

        // Update private amounts
        cachedAvailableAmounts.clear();
        for (var entry : cachedAvailableStacks) {
            cachedAvailableAmounts.put(entry.getKey(), entry.getLongValue());
        }
    }

    private void postWatcherUpdate(AEKey what, long newAmount) {
        for (var watcher : interestManager.get(what)) {
            watcher.getHost().onStackChange(what, newAmount);
        }
        for (var watcher : interestManager.getAllStacksWatchers()) {
            watcher.getHost().onStackChange(what, newAmount);
        }
    }

    /**
     * When a node joins the grid, we automatically register provided {@link IStorageProvider} and
     * {@link IStorageWatcherNode}.
     */
    @Override
    public void addNode(IGridNode node, @Nullable CompoundTag savedData) {
        var storageProvider = node.getService(IStorageProvider.class);
        if (storageProvider != null) {
            ProviderState state = new ProviderState(storageProvider);
            this.nodeProviders.put(node, state);
            state.mount();
            bootstrapReconcileAndProject();
        }

        var watcher = node.getService(IStorageWatcherNode.class);
        if (watcher != null) {
            var iw = new StackWatcher<>(interestManager, watcher);
            this.watchers.put(node, iw);
            watcher.updateWatcher(iw);
        }
    }

    /**
     * When a node leaves the grid, we automatically unregister the previously registered {@link IStorageProvider} or
     * {@link IStorageWatcherNode}.
     */
    @Override
    public void removeNode(IGridNode node) {
        var watcher = this.watchers.remove(node);
        if (watcher != null) {
            watcher.destroy();
        }

        var providerState = this.nodeProviders.remove(node);
        if (providerState != null) {
            providerState.unmount();
            bootstrapReconcileAndProject();
        }
    }

    @Override
    public MEStorage getInventory() {
        return inventory;
    }

    @Override
    public KeyCounter getCachedInventory() {
        return cachedAvailableStacks;
    }

    /** Returns the exact storage reconstruction, which rejects access while invalid or dirty. */
    public StorageServiceRebuild getExactStorage() {
        return exactStorage;
    }

    /**
     * Returns a fail-closed, server-thread-bound exact broker endpoint over this native network's physical storage. It
     * deliberately accepts no unbounded long projection: each physical request must fit the exact bridge window.
     */
    public BrokerExactStorage getBrokerExactStorage(ServerThreadGate serverThread) {
        return new LegacyNetworkBrokerStorage(inventory, exactStorage, serverThread);
    }

    @Override
    public void addGlobalStorageProvider(IStorageProvider provider) {
        var state = new ProviderState(provider);
        this.globalProviders.add(state);
        state.mount();
        bootstrapReconcileAndProject();
    }

    @Override
    public void removeGlobalStorageProvider(IStorageProvider provider) {
        var it = this.globalProviders.iterator();
        boolean removed = false;
        while (it.hasNext()) {
            var state = it.next();
            if (state.provider == provider) {
                it.remove();
                state.unmount();
                removed = true;
            }
        }
        if (removed) {
            bootstrapReconcileAndProject();
        }
    }

    @Override
    public void refreshNodeStorageProvider(IGridNode node) {
        var state = nodeProviders.get(node);
        if (state == null) {
            throw new IllegalArgumentException("The given node is not part of this grid or has no storage provider.");
        }
        state.update();
        bootstrapReconcileAndProject();
    }

    @Override
    public void refreshGlobalStorageProvider(IStorageProvider provider) {
        for (var state : globalProviders) {
            if (state.provider == provider) {
                state.update();
                bootstrapReconcileAndProject();
                return;
            }
        }

        throw new IllegalArgumentException("The given node is not part of this grid or has no storage provider.");
    }

    @Override
    public void invalidateCache() {
        exactStorage.markDirty();
    }

    private void bootstrapReconcileAndProject() {
        if (exactStorage.reconcileEndTick()) {
            updateCachedStacksFromExact();
        }
    }

    private static long nextRegistryGeneration() {
        while (true) {
            long current = NEXT_REGISTRY_GENERATION.get();
            long following = Math.incrementExact(current);
            if (NEXT_REGISTRY_GENERATION.compareAndSet(current, following)) {
                return current;
            }
        }
    }

    /**
     * A {@link IStorageProvider}-specific mount table facade which allows the provider to easily mount/remount its
     * storage.
     */
    private class ProviderState implements IStorageMounts {
        private final IStorageProvider provider;
        private final Set<MEStorage> inventories = new HashSet<>();
        private boolean mounted;

        public ProviderState(IStorageProvider provider) {
            this.provider = provider;
        }

        /**
         * Performs the first mount operation on this storage provider, which does not assume any of the provider's
         * inventories are currently mounted and need to be removed first.
         */
        private void mount() {
            Preconditions.checkState(!mounted, "Can't mount a provider's inventories when it's already mounted");

            mounted = true;
            provider.mountInventories(this);
        }

        @Override
        public void mount(MEStorage inventory, int priority) {
            Preconditions.checkState(mounted, "Cannot use StorageMounts after the storage has been unmounted.");

            if (!inventories.add(inventory)) {
                throw new IllegalStateException("Cannot mount the same inventory twice.");
            }

            // Mount this inventory into the network storage
            storage.mount(priority, inventory);
        }

        public void update() {
            unmount();
            mount();
        }

        public void unmount() {
            if (!mounted) {
                return;
            }
            mounted = false;

            for (var inventory : inventories) {
                unmount(inventory);
            }
            inventories.clear();
        }

        private void unmount(MEStorage inventory) {
            storage.unmount(inventory);
        }
    }
}
