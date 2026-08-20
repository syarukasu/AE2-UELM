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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.config.CpuSelectionMode;
import appeng.api.config.PowerMultiplier;
import appeng.api.config.Settings;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.events.GridCraftingCpuChange;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.util.IConfigManager;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.crafting.CraftingMonitorBlockEntity;
import appeng.blockentity.crafting.MolecularAssemblerBlockEntity;
import appeng.core.AELog;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.CraftingJobStatusPacket;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.IAECluster;
import appeng.me.cluster.MBCalculator;
import appeng.me.helpers.MachineSource;
import appeng.me.service.CraftingService;
import appeng.me.service.StorageService;
import appeng.rebuild.addon.StandardAddonMachineExactAdapter;
import appeng.rebuild.api.legacy.LegacyAmountProjection;
import appeng.rebuild.execution.CurrentPatternSnapshotSource;
import appeng.rebuild.execution.ExactCpuExecutionSession;
import appeng.rebuild.execution.ExactCpuLedgerState;
import appeng.rebuild.execution.ExactCraftingPlan;
import appeng.rebuild.execution.ExactCraftingProvider;
import appeng.rebuild.execution.ExactRecoveryActivation;
import appeng.rebuild.execution.ExactRecoveryCheckpoint;
import appeng.rebuild.execution.ExactTransferBrokerState;
import appeng.rebuild.execution.ExactWorkCommand;
import appeng.rebuild.execution.ExactWorkOrderCommandResult;
import appeng.rebuild.execution.ExactWorkOrderState;
import appeng.rebuild.execution.WorkCommandId;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.BrokerExactStorage;
import appeng.util.ConfigManager;

public final class CraftingCPUCluster implements IAECluster, ICraftingCPU {

    private static final String LOG_MARK_AS_COMPLETE = "Completed job for %s.";
    private static final String EXACT_REQUESTER_LINK = "ae2RebuildExactRequesterLink";

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
    @Nullable
    private ExactWorkCommand exactInFlight;
    private boolean exactDispatching;
    private boolean exactOutputObserved;
    private final Map<KeyId, AEAmount> exactOutputRemaining = new HashMap<>();
    private final Map<KeyId, AEAmount> exactRemainderRemaining = new HashMap<>();
    @Nullable
    private UUID exactPlayerId;
    private long exactStartedNanos;
    private boolean exactCancellationRequested;
    @Nullable
    private CraftingLink exactRequesterLink;

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

        // Retain or release exact physical custody before this cluster stops receiving service ticks. The durable
        // checkpoint remains attached to the core if an in-flight machine command must finish after reformation.
        if (this.exactSession != null && isExactServerThread()) {
            cancelExactExecution();
        }

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
        if (this.exactSession != null) {
            return this.exactInFlight == null ? 0 : insertExactResult(what, amount, mode);
        }
        return craftingLogic.insert(what, amount, mode);
    }

    /** Advances one bounded exact physical command or one bounded settlement pass. Server thread only. */
    public void tickExactExecution(IEnergyService energyService, CraftingService craftingService) {
        requireExactServerThread();
        if (exactSession == null || exactInFlight != null) {
            return;
        }
        var snapshot = exactSession.snapshot();
        if (snapshot.workOrder().isEmpty()) {
            if (snapshot.broker().state() == ExactTransferBrokerState.ROLLBACK_PENDING
                    || snapshot.broker().state() == ExactTransferBrokerState.RELEASE_PENDING) {
                exactSession.progressReservationRelease(1024);
            }
            var after = exactSession.snapshot();
            if (after.ledger().state() == ExactCpuLedgerState.IDLE
                    && after.broker().state() == ExactTransferBrokerState.IDLE) {
                finishExactRequester(false);
                exactSession = null;
            }
            return;
        }
        ExactWorkOrderState state = snapshot.workOrder().orElseThrow().state();
        if (state == ExactWorkOrderState.SETTLEMENT_PENDING || state == ExactWorkOrderState.RELEASE_PENDING) {
            exactSession.progressWorkRelease(1024);
            if (exactSession.snapshot().workOrder().map(s -> s.state() == ExactWorkOrderState.COMPLETED)
                    .orElse(false)) {
                notifyExactJob(exactCancellationRequested ? CraftingJobStatusPacket.Status.CANCELLED
                        : CraftingJobStatusPacket.Status.FINISHED);
                finishExactRequester(!exactCancellationRequested);
                exactSession = null;
            }
            return;
        }
        if (state != ExactWorkOrderState.READY) {
            return;
        }
        var issuedResult = exactSession.issueNext(1);
        if (!(issuedResult instanceof ExactCpuExecutionSession.Command issued)
                || !(issued.result() instanceof ExactWorkOrderCommandResult.Issued commandResult)) {
            return;
        }
        ExactWorkCommand command = commandResult.command();
        if (!craftingService.claimExactCommand(this, command)) {
            exactSession.rejectIssued(command);
            return;
        }
        var binding = craftingService.getExactBinding(command.pattern());
        if (binding == null) {
            craftingService.releaseExactCommand(this, command);
            exactSession.rejectIssued(command);
            exactSession.requestCancellation();
            return;
        }
        KeyCounter[] inputs = exactInputs(command, craftingService.getExactKeyRegistry());
        double power = CraftingCpuHelper.calculatePatternPower(inputs);
        stageExactCommand(command);
        for (var provider : binding.providers()) {
            if (provider.isBusy()
                    || energyService.extractAEPower(power, Actionable.SIMULATE, PowerMultiplier.CONFIG) < power
                            - 0.01) {
                continue;
            }
            exactDispatching = true;
            boolean pushed;
            try {
                pushed = provider instanceof ExactCraftingProvider exactProvider
                        ? exactProvider.pushExactPattern(command, binding.details(), inputs)
                        : StandardAddonMachineExactAdapter.pushPattern(provider, binding.details(), inputs);
            } catch (RuntimeException providerFailure) {
                if (exactOutputObserved) {
                    exactSession.acceptIssued(command);
                    exactSession.requestCancellation();
                } else {
                    clearStagedExactCommand();
                    craftingService.releaseExactCommand(this, command);
                    exactSession.rejectIssued(command);
                }
                AELog.warn("Exact crafting provider failed while dispatching a sealed command", providerFailure);
                return;
            } finally {
                exactDispatching = false;
            }
            if (pushed || exactOutputObserved) {
                energyService.extractAEPower(power, Actionable.MODULATE, PowerMultiplier.CONFIG);
                var accepted = exactSession.acceptIssued(command);
                if (!(accepted instanceof ExactCpuExecutionSession.Transition transition)
                        || !(transition
                                .result() instanceof appeng.rebuild.execution.ExactWorkOrderTransitionResult.Accepted)) {
                    craftingService.releaseExactCommand(this, command);
                    exactSession.requestCancellation();
                    return;
                }
                if (exactOutputRemaining.isEmpty() && exactRemainderRemaining.isEmpty()) {
                    exactSession.completeIssued(command, command.expectedOutputs(), command.expectedRemainders());
                    exactInFlight = null;
                    craftingService.releaseExactCommand(this, command);
                }
                markDirty();
                return;
            }
        }
        clearStagedExactCommand();
        craftingService.releaseExactCommand(this, command);
        exactSession.rejectIssued(command);
    }

    public boolean hasExactExecution() {
        return exactSession != null;
    }

    public boolean isExactCommandInFlight(ExactWorkCommand command) {
        return exactInFlight != null && exactInFlight.equals(command);
    }

    /** Atomically transfers one identity-bound native machine result into exact work-order custody. */
    public boolean completeExactMachineCommand(WorkCommandId commandId, Map<AEKey, AEAmount> outputs,
            Map<AEKey, AEAmount> remainders) {
        requireExactServerThread();
        ExactWorkCommand command = exactInFlight;
        KeyRegistry keys = exactKeyRegistry();
        if (command == null || keys == null || !command.id().equals(commandId)) {
            return false;
        }
        Map<KeyId, AEAmount> keyedOutputs = exactResultMap(keys, outputs);
        Map<KeyId, AEAmount> keyedRemainders = exactResultMap(keys, remainders);
        if (keyedOutputs == null || keyedRemainders == null) {
            return false;
        }
        var result = exactSession.completeIssued(command, keyedOutputs, keyedRemainders);
        if (!(result instanceof ExactCpuExecutionSession.Transition transition)
                || !(transition
                        .result() instanceof appeng.rebuild.execution.ExactWorkOrderTransitionResult.Completed)) {
            return false;
        }
        clearStagedExactCommand();
        markDirty();
        return true;
    }

    private static @Nullable Map<KeyId, AEAmount> exactResultMap(KeyRegistry keys, Map<AEKey, AEAmount> amounts) {
        if (amounts == null || amounts.size() > appeng.rebuild.planner.PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS) {
            return null;
        }
        Map<KeyId, AEAmount> result = new HashMap<>();
        for (var entry : amounts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().equals(AEAmount.ZERO)) {
                return null;
            }
            KeyId id = keys.lookup(entry.getKey());
            if (id == null || result.put(id, entry.getValue()) != null) {
                return null;
            }
        }
        return Map.copyOf(result);
    }

    /** Discards only a plan which has not acquired physical custody. */
    public void discardPreparedExactPlan() {
        requireExactServerThread();
        if (exactSession == null) {
            return;
        }
        var snapshot = exactSession.snapshot();
        if (snapshot.ledger().state() == ExactCpuLedgerState.PREPARED
                && snapshot.broker().state() == ExactTransferBrokerState.IDLE) {
            exactSession.discardPrepared();
            exactSession = null;
        }
    }

    private KeyCounter[] exactInputs(ExactWorkCommand command, KeyRegistry keys) {
        KeyCounter[] result = new KeyCounter[command.pattern().inputs().size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = new KeyCounter();
        }
        for (var selection : command.plannedSelections()) {
            result[selection.inputIndex()].add(keys.resolve(selection.consumedKey()),
                    selection.initialRequiredAmount().longValueExact());
        }
        return result;
    }

    private long insertExactResult(AEKey what, long amount, Actionable mode) {
        if (amount <= 0) {
            return 0;
        }
        KeyRegistry keys = exactKeyRegistry();
        if (keys == null) {
            return 0;
        }
        KeyId key = keys.lookup(what);
        if (key == null) {
            return 0;
        }
        AEAmount requested = AEAmount.of(amount);
        AEAmount acceptedOutput = minimum(requested, exactOutputRemaining.getOrDefault(key, AEAmount.ZERO));
        AEAmount left = requested.subtractExact(acceptedOutput);
        AEAmount acceptedRemainder = minimum(left, exactRemainderRemaining.getOrDefault(key, AEAmount.ZERO));
        AEAmount accepted = acceptedOutput.add(acceptedRemainder);
        if (mode == Actionable.SIMULATE || accepted.equals(AEAmount.ZERO)) {
            return accepted.longValueExact();
        }
        subtractRemaining(exactOutputRemaining, key, acceptedOutput);
        subtractRemaining(exactRemainderRemaining, key, acceptedRemainder);
        exactOutputObserved = true;
        if (!exactDispatching && exactOutputRemaining.isEmpty() && exactRemainderRemaining.isEmpty()) {
            ExactWorkCommand completed = Objects.requireNonNull(exactInFlight);
            exactSession.completeIssued(completed, completed.expectedOutputs(), completed.expectedRemainders());
            exactInFlight = null;
            markDirty();
        }
        return accepted.longValueExact();
    }

    private void stageExactCommand(ExactWorkCommand command) {
        exactInFlight = command;
        exactDispatching = false;
        exactOutputObserved = false;
        exactOutputRemaining.clear();
        exactOutputRemaining.putAll(command.expectedOutputs());
        exactRemainderRemaining.clear();
        exactRemainderRemaining.putAll(command.expectedRemainders());
    }

    private void clearStagedExactCommand() {
        exactInFlight = null;
        exactDispatching = false;
        exactOutputObserved = false;
        exactOutputRemaining.clear();
        exactRemainderRemaining.clear();
    }

    private static AEAmount minimum(AEAmount left, AEAmount right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static void subtractRemaining(Map<KeyId, AEAmount> amounts, KeyId key, AEAmount consumed) {
        if (consumed.equals(AEAmount.ZERO)) {
            return;
        }
        AEAmount remaining = amounts.get(key).subtractExact(consumed);
        if (remaining.equals(AEAmount.ZERO)) {
            amounts.remove(key);
        } else {
            amounts.put(key, remaining);
        }
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
        if (exactSession != null) {
            requireExactServerThread();
            cancelExactExecution();
        } else {
            craftingLogic.cancel();
        }
    }

    public ICraftingSubmitResult submitJob(IGrid g, ICraftingPlan plan, IActionSource src,
            ICraftingRequester requestingMachine) {
        return craftingLogic.trySubmitJob(g, plan, src, requestingMachine);
    }

    @Override
    public boolean isBusy() {
        return exactSession != null || craftingLogic.hasJob();
    }

    @Nullable
    @Override
    public CraftingJobStatus getJobStatus() {
        if (exactSession != null) {
            var request = exactSession.request().orElse(null);
            KeyRegistry registry = exactKeyRegistry();
            if (request == null || registry == null) {
                return null;
            }
            long total = LegacyAmountProjection.saturatingLong(request.amount());
            long progress = exactSession.snapshot().workOrder()
                    .filter(order -> order.state() == ExactWorkOrderState.COMPLETED)
                    .map(order -> total).orElse(0L);
            return new CraftingJobStatus(new GenericStack(registry.resolve(request.output()), total), total, progress,
                    0L);
        }
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

    private void cancelExactExecution() {
        var snapshot = exactSession.snapshot();
        if (snapshot.workOrder().isPresent()) {
            exactCancellationRequested = true;
            exactSession.requestCancellation();
        } else if (snapshot.broker().state() == ExactTransferBrokerState.RESERVED) {
            exactSession.cancelReservation();
        } else if (snapshot.ledger().state() == ExactCpuLedgerState.PREPARED
                && snapshot.broker().state() == ExactTransferBrokerState.IDLE) {
            exactSession.discardPrepared();
            exactSession = null;
        }
    }

    private void notifyExactJob(CraftingJobStatusPacket.Status status) {
        if (exactSession == null || exactPlayerId == null) {
            return;
        }
        CraftingBlockEntity core = getCore();
        if (core == null || !(core.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var player = level.getServer().getPlayerList().getPlayer(exactPlayerId);
        var request = exactSession.request().orElse(null);
        var planId = exactSession.planId().orElse(null);
        KeyRegistry registry = exactKeyRegistry();
        if (player == null || request == null || planId == null || registry == null) {
            return;
        }
        AEAmount remaining = status == CraftingJobStatusPacket.Status.FINISHED ? AEAmount.ZERO : request.amount();
        long elapsed = exactStartedNanos == 0L ? 0L : Math.max(0L, System.nanoTime() - exactStartedNanos);
        NetworkHandler.instance().sendTo(new CraftingJobStatusPacket(planId.value(), registry.resolve(request.output()),
                request.amount(), remaining, elapsed, status), player);
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
        if (exactRequesterLink != null) {
            CompoundTag link = new CompoundTag();
            exactRequesterLink.writeToNBT(link);
            data.put(EXACT_REQUESTER_LINK, link);
        } else {
            data.remove(EXACT_REQUESTER_LINK);
        }
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
        exactRequesterLink = data.contains(EXACT_REQUESTER_LINK, net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? new CraftingLink(data.getCompound(EXACT_REQUESTER_LINK), this)
                : null;
    }

    /** Creates the durable requester link after exact custody has been handed to this CPU. */
    public CraftingLink attachExactRequester(IGrid grid, ICraftingRequester requester) {
        requireExactServerThread();
        Objects.requireNonNull(grid, "grid");
        Objects.requireNonNull(requester, "requester");
        if (exactSession == null || exactSession.snapshot().workOrder().isEmpty() || exactRequesterLink != null) {
            throw new IllegalStateException("CPU cannot attach an exact requester in its current state");
        }
        UUID craftId = UUID.randomUUID();
        CraftingLink cpuLink = new CraftingLink(CraftingCpuHelper.generateLinkData(craftId, false, false), this);
        CraftingLink requesterLink = new CraftingLink(CraftingCpuHelper.generateLinkData(craftId, false, true),
                requester);
        CraftingService service = (CraftingService) grid.getCraftingService();
        service.addLink(cpuLink);
        service.addLink(requesterLink);
        exactRequesterLink = cpuLink;
        markDirty();
        return requesterLink;
    }

    public @Nullable ICraftingLink getExactRequesterLink() {
        return exactRequesterLink;
    }

    private void finishExactRequester(boolean completed) {
        if (exactRequesterLink == null) {
            return;
        }
        if (completed) {
            exactRequesterLink.markDone();
        } else {
            exactRequesterLink.cancel();
        }
        exactRequesterLink = null;
        markDirty();
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
        var result = this.exactSession.prepare(plan);
        this.exactPlayerId = source.player().map(player -> player.getUUID()).orElse(null);
        this.exactStartedNanos = 0L;
        this.exactCancellationRequested = false;
        return result;
    }

    /** Advances the CPU-owned exact reservation; callers receive immutable state only. */
    public ExactCpuExecutionSession.ExactCpuSessionResult reserveExactPlan() {
        return requireExactSession().reserve();
    }

    /** Transfers the exact reservation into the CPU-owned work order. */
    public ExactCpuExecutionSession.ExactCpuSessionResult startExactWorkOrder() {
        var result = requireExactSession().startWorkOrder();
        if (result instanceof ExactCpuExecutionSession.Broker broker
                && broker.result().type() == ExactCpuExecutionSession.BrokerOutcomeType.STARTED) {
            exactStartedNanos = System.nanoTime();
            notifyExactJob(CraftingJobStatusPacket.Status.STARTED);
        }
        return result;
    }

    /** Cancels a reservation which has not been handed to a work order. */
    public ExactCpuExecutionSession.ExactCpuSessionResult cancelExactReservation() {
        return requireExactSession().cancelReservation();
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
        ExactWorkCommand confirmedCommand = findRetainedNativeCommand(checkpoint);
        ExactCpuExecutionSession.ActivationResult result = confirmedCommand == null
                ? ExactCpuExecutionSession.activate(checkpoint, storage, patterns, this::isExactServerThread, source,
                        this::replaceExactRecovery, this::clearExactRecovery)
                : ExactCpuExecutionSession.activateConfirmedInFlight(checkpoint, confirmedCommand.id(), storage,
                        patterns, this::isExactServerThread, source, this::replaceExactRecovery,
                        this::clearExactRecovery);
        if (result instanceof ExactCpuExecutionSession.Activated activated) {
            this.exactSession = activated.session();
            if (confirmedCommand != null) {
                stageExactCommand(confirmedCommand);
            }
            this.exactRecovery.markActivated();
        }
        return result;
    }

    private @Nullable ExactWorkCommand findRetainedNativeCommand(ExactRecoveryCheckpoint checkpoint) {
        ExactWorkCommand command = checkpoint.workOrder().flatMap(order -> order.inFlightCommand()).orElse(null);
        IGrid grid = getGrid();
        if (command == null || grid == null) {
            return null;
        }
        int matches = 0;
        for (MolecularAssemblerBlockEntity assembler : grid.getMachines(MolecularAssemblerBlockEntity.class)) {
            if (assembler.retainsExactCommand(command.id()) && ++matches > 1) {
                return null;
            }
        }
        return matches == 1 ? command : null;
    }

    /** True when native NBT contains a decoded inert exact checkpoint awaiting this grid's activation. */
    public boolean hasPendingExactRecovery() {
        return this.exactSession == null
                && this.exactRecovery.state() == ExactCpuRecoveryPersistence.State.PENDING_ACTIVATION;
    }

    /** Activates native recovery with the CPU core as its action source. */
    public ExactCpuExecutionSession.ActivationResult activateExactRecovery(BrokerExactStorage storage,
            CurrentPatternSnapshotSource patterns) {
        if (machineSrc == null) {
            return new ExactCpuExecutionSession.Rejected(ExactRecoveryActivation.Reason.INVALID_CHECKPOINT);
        }
        return activateExactRecovery(storage, patterns, machineSrc);
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
