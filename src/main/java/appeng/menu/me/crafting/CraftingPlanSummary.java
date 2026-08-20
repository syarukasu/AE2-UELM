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

package appeng.menu.me.crafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import com.google.common.collect.ImmutableList;

import net.minecraft.network.FriendlyByteBuf;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.ExactCraftingPlanAdapter;
import appeng.me.service.StorageService;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.StorageSnapshotCaptureResult;

/**
 * A crafting plan intended to be sent to the client.
 */
public class CraftingPlanSummary {

    /**
     * @see ICraftingPlan#bytes()
     */
    private final long usedBytes;

    /**
     * @see ICraftingPlan#simulation()
     */
    private final boolean simulation;

    private final List<CraftingPlanSummaryEntry> entries;

    public CraftingPlanSummary(long usedBytes, boolean simulation, List<CraftingPlanSummaryEntry> entries) {
        this.usedBytes = usedBytes;
        this.simulation = simulation;
        this.entries = entries;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    public boolean isSimulation() {
        return simulation;
    }

    public List<CraftingPlanSummaryEntry> getEntries() {
        return entries;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarLong(usedBytes);
        buffer.writeBoolean(simulation);
        buffer.writeVarInt(entries.size());
        for (CraftingPlanSummaryEntry entry : entries) {
            entry.write(buffer);
        }
    }

    public static CraftingPlanSummary read(FriendlyByteBuf buffer) {

        long bytesUsed = buffer.readVarLong();
        boolean simulation = buffer.readBoolean();
        int entryCount = buffer.readVarInt();
        ImmutableList.Builder<CraftingPlanSummaryEntry> entries = ImmutableList.builder();
        for (int i = 0; i < entryCount; i++) {
            entries.add(CraftingPlanSummaryEntry.read(buffer));
        }

        return new CraftingPlanSummary(bytesUsed, simulation, entries.build());
    }

    private static class KeyStats {
        public AEAmount stored = AEAmount.ZERO;
        public AEAmount crafting = AEAmount.ZERO;
    }

    /**
     * Creates a plan summary from the given planning result.
     *
     * @param grid         The grid used to determine the amount of items already stored.
     * @param actionSource The action source used to determine the amount of items already stored.
     */
    public static CraftingPlanSummary fromJob(IGrid grid, IActionSource actionSource, ICraftingPlan job) {
        if (job instanceof ExactCraftingPlanAdapter exact) {
            return fromExactJob(grid, exact);
        }
        var plan = new HashMap<AEKey, KeyStats>() {
            private KeyStats mapping(AEKey key) {
                Objects.requireNonNull(key, "Key may not be null");

                return computeIfAbsent(key, k -> new KeyStats());
            }
        };

        for (var used : job.usedItems()) {
            var stats = plan.mapping(used.getKey());
            stats.stored = stats.stored.add(AEAmount.of(used.getLongValue()));
        }
        for (var missing : job.missingItems()) {
            var stats = plan.mapping(missing.getKey());
            stats.stored = stats.stored.add(AEAmount.of(missing.getLongValue()));
        }
        for (var emitted : job.emittedItems()) {
            var entry = plan.mapping(emitted.getKey());
            entry.stored = entry.stored.add(AEAmount.of(emitted.getLongValue()));
            entry.crafting = entry.crafting.add(AEAmount.of(emitted.getLongValue()));
        }
        for (var entry : job.patternTimes().entrySet()) {
            for (var out : entry.getKey().getOutputs()) {
                var stats = plan.mapping(out.what());
                stats.crafting = stats.crafting.add(AEAmount.of(out.amount()).multiply(AEAmount.of(entry.getValue())));
            }
        }

        var entries = new ArrayList<CraftingPlanSummaryEntry>();

        var storage = grid.getStorageService().getInventory();
        var crafting = grid.getCraftingService();
        var cachedInv = grid.getStorageService().getCachedInventory(); // we do not need to be that precise here

        for (var out : plan.entrySet()) {
            long missingAmount;
            long storedAmount;
            if (job.simulation() && !crafting.canEmitFor(out.getKey())) {
                long requested = out.getValue().stored.longValueExact();
                storedAmount = storage.extract(out.getKey(), requested, Actionable.SIMULATE, actionSource);
                missingAmount = requested - storedAmount;
            } else {
                storedAmount = out.getValue().stored.longValueExact();
                missingAmount = 0;
            }
            long craftAmount = out.getValue().crafting.longValueExact();
            long availableAmount = cachedInv.get(out.getKey());

            entries.add(new CraftingPlanSummaryEntry(
                    out.getKey(),
                    AEAmount.of(missingAmount),
                    AEAmount.of(storedAmount),
                    AEAmount.of(craftAmount),
                    AEAmount.of(availableAmount)));
        }

        Collections.sort(entries);

        return new CraftingPlanSummary(job.bytes(), job.simulation(), List.copyOf(entries));

    }

    private static CraftingPlanSummary fromExactJob(IGrid grid, ExactCraftingPlanAdapter adapter) {
        var plan = new HashMap<AEKey, KeyStats>();
        var exact = adapter.exactPlan();
        exact.initialStorageDebits().forEach(
                (key, amount) -> plan.computeIfAbsent(adapter.resolve(key), ignored -> new KeyStats()).stored = amount);
        for (var manifest : exact.executionManifests()) {
            for (var execution : manifest.patternExecutions()) {
                for (var output : execution.pattern().outputs()) {
                    var stats = plan.computeIfAbsent(adapter.resolve(output.key()), ignored -> new KeyStats());
                    stats.crafting = stats.crafting
                            .add(output.amountPerExecution().multiply(execution.executions()));
                }
            }
        }

        appeng.rebuild.storage.StorageSnapshot snapshot = null;
        StorageService exactStorage = grid.getStorageService() instanceof StorageService storage ? storage : null;
        if (exactStorage != null) {
            var captured = exactStorage.getExactStorage().captureSnapshot();
            if (captured instanceof StorageSnapshotCaptureResult.Success success) {
                snapshot = success.snapshot();
            }
        }
        var entries = new ArrayList<CraftingPlanSummaryEntry>(plan.size());
        for (var entry : plan.entrySet()) {
            var keyId = exactStorage == null ? null
                    : exactStorage.getExactStorage().keyRegistry().lookup(entry.getKey());
            AEAmount available = snapshot != null && keyId != null ? snapshot.amount(keyId) : AEAmount.ZERO;
            entries.add(new CraftingPlanSummaryEntry(entry.getKey(), AEAmount.ZERO, entry.getValue().stored,
                    entry.getValue().crafting, available));
        }
        Collections.sort(entries);
        return new CraftingPlanSummary(adapter.bytes(), false, List.copyOf(entries));
    }

}
