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

package appeng.me.cluster.implementations;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.config.Settings;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.events.GridCraftingCpuChange;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.util.IConfigManager;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.crafting.CraftingMonitorBlockEntity;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.IAECluster;
import appeng.me.cluster.MBCalculator;
import appeng.me.helpers.MachineSource;
import appeng.me.service.StorageService;
import appeng.rebuild.execution.CurrentPatternSnapshotSource;
import appeng.rebuild.execution.ExactCpuExecutionSession;
import appeng.rebuild.execution.ExactCraftingPlan;
import appeng.rebuild.execution.ExactRecoveryActivation;
import appeng.rebuild.execution.ExactRecoveryCheckpoint;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.util.ConfigManager;

public final class CraftingCPUCluster implements IAECluster, ICraftingCPU {

    private static final String LOG_MARK_AS_COMPLETE = "Completed job for %s.";

    private final BlockPos boundsMin;
    private final BlockPos boundsMax;
    // INSTANCE sate
    private final List<CraftingBlockEntity> blockEntities = new ArrayList<>();
    private final List<CraftingMonitorBlockEntity> status = new ArrayList<>();
    private final ConfigManager configManager = new ConfigManager(this::markDirty);
    private Component myName = null;
    private boolean isDestroyed = false;
    private long storage = 0;
    private MachineSource machineSrc = null;
    private int accelerator = 0;
    /**
     * crafting job info
     */
    public final CraftingCpuLogic craftingLogic = new CraftingCpuLogic(this);
    private final ExactCpuRecoveryPersistence exactRecovery = new ExactCpuRecoveryPersistence(this::markDirty);
    /** Exact execution is CPU-owned; no ledger, broker, or work-order reference is exposed from this cluster. */
    @Nullable
    private ExactCpuExecutionSession exactSession;

    public CraftingCPUCluster(BlockPos boundsMin, BlockPos boundsMax) {
        this.boundsMin = boundsMin.immutable();
        this.boundsMax = boundsMax.immutable();

        this.configManager.registerSetting(Settings.CPU_SELECTION_MODE, CpuSelectionMode.ANY);
    }

    @Override
    public boolean isDestroyed() {
        return this.isDestroyed;
    }

    @Override
    public BlockPos getBoundsMin() {
        return boundsMin;
    }

    @Override
    public BlockPos getBoundsMax() {
        return boundsMax;
    }

    @Override
    public void updateStatus(boolean updateGrid) {
        for (CraftingBlockEntity r : this.blockEntities) {
            r.updateSubType(true);
        }
    }

    @Override
    public void destroy() {
        if (this.isDestroyed) {
            return;
        }
        this.isDestroyed = true;

        boolean ownsModification = !MBCalculator.isModificationInProgress();
        if (ownsModification) {
            MBCalculator.setModificationInProgress(this);
        }
        try {
            boolean posted = false;

            for (CraftingBlockEntity r : this.blockEntities) {
                final IGridNode n = r.getActionableNode();
                if (n != null && !posted) {
                    n.getGrid().postEvent(new GridCraftingCpuChange(n));
                    posted = true;
                }

                r.updateStatus(null);
            }
        } finally {
            if (ownsModification) {
                MBCalculator.setModificationInProgress(null);
            }
        }
    }

    @Override
    public Iterator<CraftingBlockEntity> getBlockEntities() {
        return this.blockEntities.iterator();
    }

    void addBlockEntity(CraftingBlockEntity te) {
        if (this.machineSrc == null || te.isCoreBlock()) {
            this.machineSrc = new MachineSource(te);
        }

        te.setCoreBlock(false);
        te.saveChanges();
        this.blockEntities.add(0, te);

        if (te instanceof CraftingMonitorBlockEntity) {
            this.status.add((CraftingMonitorBlockEntity) te);
        }
        if (te.getStorageBytes() > 0) {
            this.storage += te.getStorageBytes();
        }
        if (te.getAcceleratorThreads() > 0) {
            if (te.getAcceleratorThreads() <= 16) {
                this.accelerator += te.getAcceleratorThreads();
            } else {
                throw new IllegalArgumentException("Co-processor threads may not exceed 16 per single unit block.");
            }
        }
    }

    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        return craftingLogic.insert(what, amount, mode);
    }

    public void markDirty() {
        this.getCore().saveChanges();
    }

    public void updateOutput(GenericStack finalOutput) {
        var send = finalOutput;

        if (finalOutput != null && finalOutput.amount() <= 0) {
            send = null;
        }

        for (var t : this.status) {
            t.setJob(send);
        }
    }

    public IActionSource getSrc() {
        return Objects.requireNonNull(this.machineSrc);
    }

    private CraftingBlockEntity getCore() {
        if (this.machineSrc == null) {
            return null;
        }
        return (CraftingBlockEntity) this.machineSrc.machine().get();
    }

    @Nullable
    public IGrid getGrid() {
        IGridNode node = getNode();
        return node != null ? node.getGrid() : null;
    }

    @Override
    public void cancelJob() {
        craftingLogic.cancel();
    }

    public ICraftingSubmitResult submitJob(IGrid g, ICraftingPlan plan, IActionSource src,
            ICraftingRequester requestingMachine) {
        return craftingLogic.trySubmitJob(g, plan, src, requestingMachine);
    }

    @Override
    public boolean isBusy() {
        return craftingLogic.hasJob();
    }

    @Nullable
    @Override
    public CraftingJobStatus getJobStatus() {
        var finalOutput = craftingLogic.getFinalJobOutput();
        if (finalOutput != null) {
            var elapsedTimeTracker = craftingLogic.getElapsedTimeTracker();
            var progress = Math.max(
                    0,
                    elapsedTimeTracker.getStartItemCount() - elapsedTimeTracker.getRemainingItemCount());
            return new CraftingJobStatus(
                    finalOutput,
                    elapsedTimeTracker.getStartItemCount(),
                    progress,
                    elapsedTimeTracker.getElapsedTime());
        } else {
            return null;
        }
    }

    @Override
    public long getAvailableStorage() {
        return this.storage;
    }

    @Override
    public int getCoProcessors() {
        return this.accelerator;
    }

    @Override
    public Component getName() {
        return this.myName;
    }

    @Nullable
    public IGridNode getNode() {
        CraftingBlockEntity core = getCore();
        return core != null ? core.getActionableNode() : null;
    }

    public boolean isActive() {
        IGridNode node = getNode();
        return node != null && node.isActive();
    }

    public void writeToNBT(CompoundTag data) {
        this.craftingLogic.writeToNBT(data);
        this.configManager.writeToNBT(data);
        this.exactRecovery.writeToNbt(data);
    }

    void done() {
        final CraftingBlockEntity core = this.getCore();

        core.setCoreBlock(true);

        if (core.getPreviousState() != null) {
            this.readFromNBT(core.getPreviousState());
            core.setPreviousState(null);
        }

        // A load can precede grid formation. Decode only after the grid exposes the concrete, server-owned registry.
        this.exactRecovery.decodeIfPossible(this.exactKeyRegistry());

        this.updateName();
    }

    public void readFromNBT(CompoundTag data) {
        this.craftingLogic.readFromNBT(data);
        this.configManager.readFromNBT(data);
        this.exactRecovery.readFromNbt(data, this.exactKeyRegistry());
    }

    /** Publishes a validated exact checkpoint and dirties the native core only after its canonical NBT is available. */
    void replaceExactRecovery(ExactRecoveryCheckpoint checkpoint) {
        KeyRegistry registry = this.exactKeyRegistry();
        if (registry == null) {
            throw new IllegalStateException(
                    "Exact recovery publication requires the current server-thread grid registry");
        }
        this.exactRecovery.replace(checkpoint, registry);
    }

    ExactCpuRecoveryPersistence.State exactRecoveryState() {
        return this.exactRecovery.state();
    }

    /**
     * Creates the native CPU's one exact session and prepares a sealed plan. The caller supplies an explicitly
     * revisioned physical endpoint; legacy long storage is never silently adapted into this authority.
     */
    public ExactCpuExecutionSession.ExactCpuSessionResult prepareExactPlan(ExactCraftingPlan plan,
            BrokerExactStorage storage, CurrentPatternSnapshotSource patterns, IActionSource source) {
        requireExactServerThread();
        if (this.exactSession != null || this.craftingLogic.hasJob()
                || this.exactRecovery.state() != ExactCpuRecoveryPersistence.State.ABSENT) {
            throw new IllegalStateException("CPU cannot replace an existing crafting authority with an exact plan");
        }
        this.exactSession = ExactCpuExecutionSession.create(storage, patterns, this::isExactServerThread, source,
                this::replaceExactRecovery, this::clearExactRecovery);
        return this.exactSession.prepare(plan);
    }

    /** Advances the CPU-owned exact reservation; callers receive immutable state only. */
    public ExactCpuExecutionSession.ExactCpuSessionResult reserveExactPlan() {
        return requireExactSession().reserve();
    }

    /** Transfers the exact reservation into the CPU-owned work order. */
    public ExactCpuExecutionSession.ExactCpuSessionResult startExactWorkOrder() {
        return requireExactSession().startWorkOrder();
    }

    /**
     * Activates a decoded native recovery tag exclusively through the CPU-owned exact-session boundary. No legacy job,
     * storage facade, or raw saved tag is made authoritative on this path.
     */
    public ExactCpuExecutionSession.ActivationResult activateExactRecovery(BrokerExactStorage storage,
            CurrentPatternSnapshotSource patterns, IActionSource source) {
        requireExactServerThread();
        if (this.exactSession != null || this.craftingLogic.hasJob()) {
            return new ExactCpuExecutionSession.Rejected(ExactRecoveryActivation.Reason.REENTRANT);
        }
        ExactRecoveryCheckpoint checkpoint = this.exactRecovery.checkpointForActivation();
        if (checkpoint == null) {
            return new ExactCpuExecutionSession.Rejected(ExactRecoveryActivation.Reason.INVALID_CHECKPOINT);
        }
        ExactCpuExecutionSession.ActivationResult result = ExactCpuExecutionSession.activate(checkpoint, storage,
                patterns, this::isExactServerThread, source, this::replaceExactRecovery, this::clearExactRecovery);
        if (result instanceof ExactCpuExecutionSession.Activated activated) {
            this.exactSession = activated.session();
            this.exactRecovery.markActivated();
        }
        return result;
    }

    /** Typed exact status for packet/UI integrations; legacy views remain their explicit bounded projection. */
    public @Nullable ExactCpuExecutionSession.ExactCpuSessionSnapshot getExactSessionSnapshot() {
        return this.exactSession == null ? null : this.exactSession.snapshot();
    }

    private ExactCpuExecutionSession requireExactSession() {
        requireExactServerThread();
        if (this.exactSession == null) {
            throw new IllegalStateException("CPU has no active exact execution session");
        }
        return this.exactSession;
    }

    private void clearExactRecovery() {
        this.exactRecovery.clear();
    }

    private void requireExactServerThread() {
        if (!this.isExactServerThread()) {
            throw new IllegalStateException("Exact CPU execution requires its owning server thread");
        }
    }

    private boolean isExactServerThread() {
        CraftingBlockEntity core = this.getCore();
        return core != null && core.getLevel() instanceof ServerLevel serverLevel
                && serverLevel.getServer().isSameThread();
    }

    private @Nullable KeyRegistry exactKeyRegistry() {
        CraftingBlockEntity core = this.getCore();
        if (core == null || !(core.getLevel() instanceof ServerLevel serverLevel)
                || !serverLevel.getServer().isSameThread()) {
            return null;
        }
        IGrid grid = this.getGrid();
        if (grid == null || !(grid.getStorageService() instanceof StorageService storage)) {
            return null;
        }
        return storage.getExactStorage().keyRegistry();
    }

    public void updateName() {
        this.myName = null;
        for (CraftingBlockEntity te : this.blockEntities) {

            if (te.hasCustomName()) {
                if (this.myName != null) {
                    this.myName.copy().append(" ").append(te.getCustomName());
                } else {
                    this.myName = te.getCustomName().copy();
                }
            }
        }
    }

    public Level getLevel() {
        return this.getCore().getLevel();
    }

    public void breakCluster() {
        final CraftingBlockEntity t = this.getCore();

        if (t != null) {
            t.breakCluster();
        }
    }

    public CpuSelectionMode getSelectionMode() {
        return this.configManager.getSetting(Settings.CPU_SELECTION_MODE);
    }

    public IConfigManager getConfigManager() {
        return configManager;
    }

    /**
     * Checks if this CPU cluster can be automatically selected for a crafting request by the given action source.
     */
    public boolean canBeAutoSelectedFor(IActionSource source) {
        return switch (getSelectionMode()) {
            case ANY -> true;
            case PLAYER_ONLY -> source.player().isPresent();
            case MACHINE_ONLY -> source.player().isEmpty();
        };
    }

    /**
     * Checks if this CPU cluster is preferred for crafting requests by the given action source.
     */
    public boolean isPreferredFor(IActionSource source) {
        return switch (getSelectionMode()) {
            case ANY -> false;
            case PLAYER_ONLY -> source.player().isPresent();
            case MACHINE_ONLY -> source.player().isEmpty();
        };
    }
}
