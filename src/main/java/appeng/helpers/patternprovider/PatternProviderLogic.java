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

package appeng.helpers.patternprovider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import appeng.api.config.Actionable;
import appeng.api.config.LockCraftingMode;
import appeng.api.config.Setting;
import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.util.IConfigManager;
import appeng.core.AELog;
import appeng.core.definitions.AEItems;
import appeng.core.localization.GuiText;
import appeng.core.localization.PlayerMessages;
import appeng.core.settings.TickRates;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import appeng.helpers.InterfaceLogicHost;
import appeng.me.helpers.MachineSource;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.pattern.PatternProviderRecipeReloadFailure;
import appeng.rebuild.pattern.PatternProviderRecipeReloadResult;
import appeng.rebuild.pattern.PreparedPatternProviderRecipeReload;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.util.ConfigManager;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import appeng.util.inv.PlayerInternalInventory;

/**
 * Shared code between the pattern provider block and part.
 */
public class PatternProviderLogic implements InternalInventoryHost, ICraftingProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(PatternProviderLogic.class);

    public static final String NBT_MEMORY_CARD_PATTERNS = "patterns";
    public static final String NBT_UNLOCK_EVENT = "unlockEvent";
    public static final String NBT_UNLOCK_STACK = "unlockStack";
    public static final String NBT_PRIORITY = "priority";
    public static final String NBT_SEND_LIST = "sendList";
    public static final String NBT_SEND_DIRECTION = "sendDirection";
    public static final String NBT_RETURN_INV = "returnInv";

    private final PatternProviderLogicHost host;
    private final IManagedGridNode mainNode;
    private final IActionSource actionSource;
    private final Object recipeReloadOwnerToken = new Object();
    private final ConfigManager configManager = new ConfigManager(this::configChanged);

    private int priority;

    // Pattern storing logic
    private final AppEngInternalInventory patternInventory;
    private List<IPatternDetails> patterns = List.of();
    /**
     * Keeps track of the inputs of all the patterns. When blocking mode is enabled, if any of these is contained in the
     * target, the pattern won't be pushed. Always contains keys with the secondary component dropped.
     */
    private Set<AEKey> patternInputs = Set.of();
    private long patternStateVersion;
    // Pattern sending logic
    private final List<GenericStack> sendList = new ArrayList<>();
    private Direction sendDirection;
    // Stack returning logic
    private final PatternProviderReturnInventory returnInv;

    private final PatternProviderTargetCache[] targetCaches = new PatternProviderTargetCache[6];

    private YesNo redstoneState = YesNo.UNDECIDED;

    @Nullable
    private UnlockCraftingEvent unlockEvent;
    @Nullable
    private GenericStack unlockStack;
    private int roundRobinIndex = 0;

    @Nullable
    public PatternProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host) {
        this(mainNode, host, 9);
    }

    public PatternProviderLogic(IManagedGridNode mainNode, PatternProviderLogicHost host, int patternInventorySize) {
        this.patternInventory = new AppEngInternalInventory(this, patternInventorySize);
        this.host = host;
        this.mainNode = mainNode
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .addService(IGridTickable.class, new Ticker())
                .addService(ICraftingProvider.class, this);
        this.actionSource = new MachineSource(mainNode::getNode);

        this.configManager.registerSetting(Settings.BLOCKING_MODE, YesNo.NO);
        this.configManager.registerSetting(Settings.PATTERN_ACCESS_TERMINAL, YesNo.YES);
        this.configManager.registerSetting(Settings.LOCK_CRAFTING_MODE, LockCraftingMode.NONE);

        this.returnInv = new PatternProviderReturnInventory(() -> {
            this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
            this.host.saveChanges();
        });
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
        this.host.saveChanges();

        ICraftingProvider.requestUpdate(mainNode);
    }

    public void writeToNBT(CompoundTag tag) {
        this.configManager.writeToNBT(tag);
        this.patternInventory.writeToNBT(tag, NBT_MEMORY_CARD_PATTERNS);
        tag.putInt(NBT_PRIORITY, this.priority);
        if (unlockEvent == UnlockCraftingEvent.REDSTONE_POWER) {
            tag.putByte(NBT_UNLOCK_EVENT, (byte) 1);
        } else if (unlockEvent == UnlockCraftingEvent.RESULT) {
            if (unlockStack != null) {
                tag.putByte(NBT_UNLOCK_EVENT, (byte) 2);
                tag.put(NBT_UNLOCK_STACK, GenericStack.writeTag(unlockStack));
            } else {
                LOGGER.error("Saving pattern provider {}, locked waiting for stack, but stack is null!", host);
            }
        } else if (unlockEvent == UnlockCraftingEvent.REDSTONE_PULSE) {
            tag.putByte(NBT_UNLOCK_EVENT, (byte) 3);
        }

        ListTag sendListTag = new ListTag();
        for (var toSend : sendList) {
            sendListTag.add(GenericStack.writeTag(toSend));
        }
        tag.put(NBT_SEND_LIST, sendListTag);
        if (sendDirection != null) {
            tag.putByte(NBT_SEND_DIRECTION, (byte) sendDirection.get3DDataValue());
        }

        tag.put(NBT_RETURN_INV, this.returnInv.writeToTag());
    }

    public void readFromNBT(CompoundTag tag) {
        this.configManager.readFromNBT(tag);
        this.patternInventory.readFromNBT(tag, NBT_MEMORY_CARD_PATTERNS);
        this.priority = tag.getInt(NBT_PRIORITY);

        var unlockEventType = tag.getByte(NBT_UNLOCK_EVENT);
        this.unlockEvent = switch (unlockEventType) {
            case 0 -> null;
            case 1 -> UnlockCraftingEvent.REDSTONE_POWER;
            case 2 -> UnlockCraftingEvent.RESULT;
            case 3 -> UnlockCraftingEvent.REDSTONE_PULSE;
            default -> {
                LOGGER.error("Unknown unlock event type {} in NBT for pattern provider: {}", unlockEventType, tag);
                yield null;
            }
        };
        if (this.unlockEvent == UnlockCraftingEvent.RESULT) {
            this.unlockStack = GenericStack.readTag(tag.getCompound(NBT_UNLOCK_STACK));
            if (this.unlockStack == null) {
                LOGGER.error("Could not load unlock stack for pattern provider from NBT: {}", tag);
            }
        } else {
            this.unlockStack = null;
        }

        var sendListTag = tag.getList("sendList", Tag.TAG_COMPOUND);
        for (int i = 0; i < sendListTag.size(); ++i) {
            var stack = GenericStack.readTag(sendListTag.getCompound(i));
            if (stack != null) {
                this.addToSendList(stack.what(), stack.amount());
            }
        }
        if (tag.contains("sendDirection")) {
            sendDirection = Direction.from3DDataValue(tag.getByte("sendDirection"));
        }

        this.returnInv.readFromTag(tag.getList("returnInv", Tag.TAG_COMPOUND));
    }

    public IConfigManager getConfigManager() {
        return this.configManager;
    }

    @Override
    public void saveChanges() {
        this.host.saveChanges();
    }

    @Override
    public void onChangeInventory(InternalInventory inv, int slot) {
        this.saveChanges();
        this.updatePatterns();
    }

    @Override
    public boolean isClientSide() {
        Level level = this.host.getBlockEntity().getLevel();
        return level == null || level.isClientSide();
    }

    public void updatePatterns() {
        var updatedPatterns = new ArrayList<IPatternDetails>();
        var updatedPatternInputs = new HashSet<AEKey>();

        for (var stack : this.patternInventory) {
            var details = PatternDetailsHelper.decodePattern(stack, this.host.getBlockEntity().getLevel());

            if (details != null) {
                updatedPatterns.add(details);

                for (var iinput : details.getInputs()) {
                    for (var inputCandidate : iinput.getPossibleInputs()) {
                        updatedPatternInputs.add(inputCandidate.what().dropSecondary());
                    }
                }
            }
        }

        replacePatternState(updatedPatterns, updatedPatternInputs);

        ICraftingProvider.requestUpdate(mainNode);
    }

    /**
     * Stages this provider's legacy pattern data for one exact recipe revision without mutating live provider state.
     *
     * <p>
     * This is server-thread-only. C2B2 can prepare every provider first and invoke
     * {@link #commitRecipeReload(PreparedPatternProviderRecipeReload, RecipeRevision)} only after all preparations
     * succeed.
     */
    public PatternProviderRecipeReloadResult prepareRecipeReload(RecipeRevision revision) {
        Objects.requireNonNull(revision, "revision");
        ServerLevel level = requireServerLevel();
        long capturedPatternStateVersion = patternStateVersion;
        int slot = -1;
        try {
            int inventorySlots = patternInventory.size();
            if (inventorySlots > PatternLimits.MAX_PATTERN_PROVIDER_PATTERNS) {
                return shapeLimitFailure("inventory slots");
            }

            var stagedPatterns = new ArrayList<IPatternDetails>();
            var stagedEligibility = new ArrayList<Boolean>();
            var stagedInputs = new HashSet<AEKey>();
            int totalCandidates = 0;
            for (slot = 0; slot < inventorySlots; slot++) {
                ItemStack stack = patternInventory.getStackInSlot(slot);
                if (stack == null) {
                    return malformedFailure(slot, "null stack");
                }
                if (stack.isEmpty()) {
                    continue;
                }

                IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level, false);
                if (details == null) {
                    return malformedFailure(slot, "undecodable pattern");
                }
                if (stagedPatterns.size() >= PatternLimits.MAX_PATTERN_PROVIDER_PATTERNS) {
                    return shapeLimitFailure("patterns");
                }

                totalCandidates = validateAndCollectPattern(details, slot, stagedInputs, totalCandidates);
                stagedPatterns.add(details);
                stagedEligibility.add(isRebuildEligible(details));
            }
            return new PreparedPatternProviderRecipeReload(recipeReloadOwnerToken, revision,
                    capturedPatternStateVersion,
                    stagedPatterns, stagedEligibility, stagedInputs);
        } catch (StagingFailureException exception) {
            return new PatternProviderRecipeReloadFailure(exception.reason, exception.context);
        } catch (RuntimeException exception) {
            return new PatternProviderRecipeReloadFailure(PatternProviderRecipeReloadFailure.Reason.LEGACY_EXCEPTION,
                    boundedSlotContext(slot, "legacy callback"));
        }
    }

    /**
     * Atomically replaces only legacy pattern state from a provider-owned, unconsumed preparation.
     *
     * <p>
     * This intentionally does not request a crafting-provider update or touch grid provider maps; C2B2 publishes the
     * all-provider transaction after every provider commit succeeds.
     */
    public void commitRecipeReload(PreparedPatternProviderRecipeReload prepared, RecipeRevision expectedRevision) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        requireServerLevel();
        if (!prepared.belongsTo(recipeReloadOwnerToken)) {
            throw new IllegalArgumentException("Recipe reload preparation belongs to another provider");
        }
        if (!prepared.revision().equals(expectedRevision)) {
            throw new IllegalArgumentException("Recipe reload preparation has a different revision");
        }
        if (prepared.patternStateVersion() != patternStateVersion) {
            throw new IllegalStateException("Recipe reload preparation is stale or already committed");
        }

        replacePatternState(prepared.legacyPatterns(), prepared.patternInputs());
    }

    private int validateAndCollectPattern(IPatternDetails details, int slot, Set<AEKey> stagedInputs,
            int totalCandidates) {
        if (details.getDefinition() == null) {
            throw malformed(slot, "null definition");
        }
        IPatternDetails.IInput[] inputs = details.getInputs();
        if (inputs == null || inputs.length > PatternLimits.MAX_INPUT_GROUPS) {
            throw inputs == null ? malformed(slot, "null inputs") : shapeLimit("input groups");
        }
        GenericStack[] outputs = details.getOutputs();
        if (outputs == null || outputs.length == 0) {
            throw malformed(slot, "missing outputs");
        }
        if (outputs.length > PatternLimits.MAX_OUTPUTS) {
            throw shapeLimit("outputs");
        }
        for (GenericStack output : outputs) {
            validateStack(output, slot, "output");
        }

        int patternCandidateCount = 0;
        for (IPatternDetails.IInput input : inputs) {
            if (input == null) {
                throw malformed(slot, "null input");
            }
            if (input.getMultiplier() <= 0) {
                throw malformed(slot, "non-positive input multiplier");
            }
            GenericStack[] candidates = input.getPossibleInputs();
            if (candidates == null || candidates.length == 0) {
                throw malformed(slot, "missing input candidates");
            }
            if (candidates.length > PatternLimits.MAX_CANDIDATES_PER_INPUT) {
                throw shapeLimit("candidates per input");
            }
            for (GenericStack candidate : candidates) {
                validateStack(candidate, slot, "input candidate");
                if (patternCandidateCount >= PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN
                        || totalCandidates >= PatternLimits.MAX_PATTERN_PROVIDER_TOTAL_INPUT_CANDIDATES) {
                    throw shapeLimit("total input candidates");
                }
                patternCandidateCount++;
                totalCandidates++;
                AEKey primaryKey = candidate.what().dropSecondary();
                if (primaryKey == null) {
                    throw malformed(slot, "null dropped-secondary key");
                }
                stagedInputs.add(primaryKey);
            }
        }
        return totalCandidates;
    }

    private static void validateStack(GenericStack stack, int slot, String description) {
        if (stack == null || stack.what() == null || stack.amount() <= 0) {
            throw malformed(slot, "invalid " + description);
        }
    }

    private static boolean isRebuildEligible(IPatternDetails details) {
        Class<?> type = details.getClass();
        return type == AECraftingPattern.class || type == AEProcessingPattern.class
                || type == AESmithingTablePattern.class
                || type == AEStonecuttingPattern.class;
    }

    private ServerLevel requireServerLevel() {
        Level level = host.getBlockEntity().getLevel();
        if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) {
            throw new IllegalStateException("Recipe reload preparation must run on the owning server thread");
        }
        return serverLevel;
    }

    private void replacePatternState(List<IPatternDetails> replacementPatterns, Set<AEKey> replacementInputs) {
        List<IPatternDetails> immutablePatterns = List.copyOf(replacementPatterns);
        Set<AEKey> immutableInputs = Set.copyOf(replacementInputs);
        long nextPatternStateVersion = Math.incrementExact(patternStateVersion);
        patterns = immutablePatterns;
        patternInputs = immutableInputs;
        patternStateVersion = nextPatternStateVersion;
    }

    private static PatternProviderRecipeReloadFailure shapeLimitFailure(String context) {
        return new PatternProviderRecipeReloadFailure(PatternProviderRecipeReloadFailure.Reason.SHAPE_LIMIT, context);
    }

    private static PatternProviderRecipeReloadFailure malformedFailure(int slot, String context) {
        return new PatternProviderRecipeReloadFailure(PatternProviderRecipeReloadFailure.Reason.MALFORMED_PATTERN,
                boundedSlotContext(slot, context));
    }

    private static StagingFailureException malformed(int slot, String context) {
        return new StagingFailureException(PatternProviderRecipeReloadFailure.Reason.MALFORMED_PATTERN,
                boundedSlotContext(slot, context));
    }

    private static StagingFailureException shapeLimit(String context) {
        return new StagingFailureException(PatternProviderRecipeReloadFailure.Reason.SHAPE_LIMIT, context);
    }

    private static String boundedSlotContext(int slot, String context) {
        return slot < 0 ? context : "slot " + slot + ": " + context;
    }

    private static final class StagingFailureException extends RuntimeException {
        private final PatternProviderRecipeReloadFailure.Reason reason;
        private final String context;

        private StagingFailureException(PatternProviderRecipeReloadFailure.Reason reason, String context) {
            this.reason = reason;
            this.context = context;
        }
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return this.patterns;
    }

    @Override
    public int getPatternPriority() {
        return this.priority;
    }

    /**
     * Apply round-robin to list.
     */
    private <T> void rearrangeRoundRobin(List<T> list) {
        if (list.isEmpty()) {
            return;
        }

        roundRobinIndex %= list.size();
        for (int i = 0; i < roundRobinIndex; ++i) {
            list.add(list.get(i));
        }
        list.subList(0, roundRobinIndex).clear();
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!sendList.isEmpty() || !this.mainNode.isActive() || !this.patterns.contains(patternDetails)) {
            return false;
        }

        var be = host.getBlockEntity();
        var level = be.getLevel();

        if (getCraftingLockedReason() != LockCraftingMode.NONE) {
            return false;
        }

        record PushTarget(Direction direction, PatternProviderTarget target) {
        }
        var possibleTargets = new ArrayList<PushTarget>();

        // Push to crafting machines first
        for (var direction : getActiveSides()) {
            var adjPos = be.getBlockPos().relative(direction);
            var adjBe = level.getBlockEntity(adjPos);
            var adjBeSide = direction.getOpposite();

            var craftingMachine = ICraftingMachine.of(level, adjPos, adjBeSide, adjBe);
            if (craftingMachine != null && craftingMachine.acceptsPlans()) {
                if (craftingMachine.pushPattern(patternDetails, inputHolder, adjBeSide)) {
                    onPushPatternSuccess(patternDetails);
                    return true;
                }
                continue;
            }

            var adapter = findAdapter(direction);
            if (adapter == null)
                continue;

            possibleTargets.add(new PushTarget(direction, adapter));
        }

        // If no dedicated crafting machine could be found, and the pattern does not support
        // generic external inventories, stop here.
        if (!patternDetails.supportsPushInputsToExternalInventory()) {
            return false;
        }

        // Rearrange for round-robin
        rearrangeRoundRobin(possibleTargets);

        // Push to other kinds of blocks
        for (int i = 0; i < possibleTargets.size(); ++i) {
            var target = possibleTargets.get(i);
            var direction = target.direction();
            var adapter = target.target();

            if (this.isBlocking() && adapter.containsPatternInput(this.patternInputs)) {
                continue;
            }

            if (this.adapterAcceptsAll(adapter, inputHolder)) {
                patternDetails.pushInputsToExternalInventory(inputHolder, (what, amount) -> {
                    var inserted = adapter.insert(what, amount, Actionable.MODULATE);
                    if (inserted < amount) {
                        this.addToSendList(what, amount - inserted);
                    }
                });
                onPushPatternSuccess(patternDetails);
                this.sendDirection = direction;
                this.sendStacksOut();
                roundRobinIndex += i + 1;
                return true;
            }
        }

        return false;
    }

    public void resetCraftingLock() {
        if (unlockEvent != null) {
            unlockEvent = null;
            unlockStack = null;
            saveChanges();
        }
    }

    private void onPushPatternSuccess(IPatternDetails pattern) {
        resetCraftingLock();

        var lockMode = configManager.getSetting(Settings.LOCK_CRAFTING_MODE);
        switch (lockMode) {
            case LOCK_UNTIL_PULSE -> {
                if (getRedstoneState()) {
                    // Already have signal, wait for no signal before switching to REDSTONE_POWER
                    unlockEvent = UnlockCraftingEvent.REDSTONE_PULSE;
                } else {
                    // No signal, wait for signal
                    unlockEvent = UnlockCraftingEvent.REDSTONE_POWER;
                }
                redstoneState = YesNo.UNDECIDED; // Check redstone state again next update
                saveChanges();
            }
            case LOCK_UNTIL_RESULT -> {
                unlockEvent = UnlockCraftingEvent.RESULT;
                unlockStack = pattern.getPrimaryOutput();
                saveChanges();
            }
        }
    }

    /**
     * Gets if the crafting lock is in effect and why.
     *
     * @return null if the lock isn't in effect
     */
    public LockCraftingMode getCraftingLockedReason() {
        var lockMode = configManager.getSetting(Settings.LOCK_CRAFTING_MODE);
        if (lockMode == LockCraftingMode.LOCK_WHILE_LOW && !getRedstoneState()) {
            // Crafting locked by redstone signal
            return LockCraftingMode.LOCK_WHILE_LOW;
        } else if (lockMode == LockCraftingMode.LOCK_WHILE_HIGH && getRedstoneState()) {
            return LockCraftingMode.LOCK_WHILE_HIGH;
        } else if (unlockEvent != null) {
            // Crafting locked by waiting for unlock event
            switch (unlockEvent) {
                case REDSTONE_POWER, REDSTONE_PULSE -> {
                    return LockCraftingMode.LOCK_UNTIL_PULSE;
                }
                case RESULT -> {
                    return LockCraftingMode.LOCK_UNTIL_RESULT;
                }
            }
        }
        return LockCraftingMode.NONE;
    }

    /**
     * @return Null if {@linkplain #getCraftingLockedReason()} is not {@link LockCraftingMode#LOCK_UNTIL_RESULT}.
     */
    @Nullable
    public GenericStack getUnlockStack() {
        return unlockStack;
    }

    private Set<Direction> getActiveSides() {
        var sides = host.getTargets();

        // Skip sides with grid connections to other pattern providers and to interfaces connected to the same network
        var node = mainNode.getNode();
        if (node != null) {
            for (var entry : node.getInWorldConnections().entrySet()) {
                var otherNode = entry.getValue().getOtherSide(node);
                if (otherNode.getOwner() instanceof PatternProviderLogicHost
                        || (otherNode.getOwner() instanceof InterfaceLogicHost
                                && otherNode.getGrid().equals(mainNode.getGrid()))) {
                    sides.remove(entry.getKey());
                }
            }
        }

        return sides;
    }

    public boolean isBlocking() {
        return this.configManager.getSetting(Settings.BLOCKING_MODE) == YesNo.YES;
    }

    @Nullable
    private PatternProviderTarget findAdapter(Direction side) {
        if (targetCaches[side.get3DDataValue()] == null) {
            var thisBe = host.getBlockEntity();
            targetCaches[side.get3DDataValue()] = new PatternProviderTargetCache(
                    (ServerLevel) thisBe.getLevel(),
                    thisBe.getBlockPos().relative(side),
                    side.getOpposite(),
                    actionSource);
        }

        return targetCaches[side.get3DDataValue()].find();
    }

    private boolean adapterAcceptsAll(PatternProviderTarget target, KeyCounter[] inputHolder) {
        for (var inputList : inputHolder) {
            for (var input : inputList) {
                var inserted = target.insert(input.getKey(), input.getLongValue(), Actionable.SIMULATE);
                if (inserted == 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private void addToSendList(AEKey what, long amount) {
        if (amount > 0) {
            this.sendList.add(new GenericStack(what, amount));

            this.mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
        }
    }

    private boolean sendStacksOut() {
        if (sendDirection == null) {
            if (!sendList.isEmpty()) {
                throw new IllegalStateException("Invalid pattern provider state, this is a bug.");
            }
            return false;
        }

        var adapter = findAdapter(sendDirection);
        if (adapter == null) {
            return false;
        }

        boolean didSomething = false;

        for (var it = sendList.listIterator(); it.hasNext();) {
            var stack = it.next();
            var what = stack.what();
            long amount = stack.amount();

            var inserted = adapter.insert(what, amount, Actionable.MODULATE);
            if (inserted >= amount) {
                it.remove();
                didSomething = true;
            } else if (inserted > 0) {
                it.set(new GenericStack(what, amount - inserted));
                didSomething = true;
            }
        }

        if (sendList.isEmpty()) {
            sendDirection = null;
        }

        return didSomething;
    }

    @Override
    public boolean isBusy() {
        return !sendList.isEmpty();
    }

    private boolean hasWorkToDo() {
        return !sendList.isEmpty() || !returnInv.isEmpty();
    }

    private boolean doWork() {
        // Note: bitwise OR to avoid short-circuiting.
        return returnInv.injectIntoNetwork(
                mainNode.getGrid().getStorageService().getInventory(), actionSource, this::onStackReturnedToNetwork)
                | sendStacksOut();
    }

    public InternalInventory getPatternInv() {
        return this.patternInventory;
    }

    public void onMainNodeStateChanged() {
        if (this.mainNode.isActive()) {
            this.mainNode.ifPresent((grid, node) -> {
                grid.getTickManager().alertDevice(node);
            });
        }
    }

    public void addDrops(List<ItemStack> drops) {
        for (var stack : this.patternInventory) {
            drops.add(stack);
        }

        for (var stack : this.sendList) {
            stack.what().addDrops(stack.amount(), drops, this.host.getBlockEntity().getLevel(),
                    this.host.getBlockEntity().getBlockPos());
        }

        this.returnInv.addDrops(drops, this.host.getBlockEntity().getLevel(), this.host.getBlockEntity().getBlockPos());
    }

    public void clearContent() {
        this.patternInventory.clear();
        this.sendList.clear();
        this.returnInv.clear();
    }

    public PatternProviderReturnInventory getReturnInv() {
        return this.returnInv;
    }

    public void exportSettings(CompoundTag output) {
        patternInventory.writeToNBT(output, NBT_MEMORY_CARD_PATTERNS);
    }

    public void importSettings(CompoundTag input, @Nullable Player player) {
        if (player != null && input.contains(NBT_MEMORY_CARD_PATTERNS) && !player.level().isClientSide) {
            clearPatternInventory(player);

            var desiredPatterns = new AppEngInternalInventory(patternInventory.size());
            desiredPatterns.readFromNBT(input, NBT_MEMORY_CARD_PATTERNS);

            // Restore from blank patterns in the player inv
            var playerInv = player.getInventory();
            var blankPatternsAvailable = player.getAbilities().instabuild ? Integer.MAX_VALUE
                    : playerInv.countItem(AEItems.BLANK_PATTERN.asItem());
            var blankPatternsUsed = 0;
            for (int i = 0; i < desiredPatterns.size(); i++) {
                // Don't restore junk
                var pattern = PatternDetailsHelper.decodePattern(desiredPatterns.getStackInSlot(i),
                        host.getBlockEntity().getLevel(), true);
                if (pattern == null) {
                    continue; // Skip junk / broken recipes
                }

                // Keep track of how many blank patterns we need
                ++blankPatternsUsed;
                if (blankPatternsAvailable >= blankPatternsUsed) {
                    if (!patternInventory.addItems(pattern.getDefinition().toStack()).isEmpty()) {
                        AELog.warn("Failed to add pattern to pattern provider");
                        blankPatternsUsed--;
                    }
                }
            }

            // Deduct the used blank patterns
            if (blankPatternsUsed > 0 && !player.getAbilities().instabuild) {
                new PlayerInternalInventory(playerInv)
                        .removeItems(blankPatternsUsed, AEItems.BLANK_PATTERN.stack(), null);
            }

            // Warn about not being able to restore all patterns due to lack of blank patterns
            if (blankPatternsUsed > blankPatternsAvailable) {
                player.sendSystemMessage(
                        PlayerMessages.MissingBlankPatterns.text(blankPatternsUsed - blankPatternsAvailable));
            }
        }
    }

    // Converts all patterns in this provider to blank patterns and give them to the player
    private void clearPatternInventory(Player player) {
        // Just clear it for creative mode players
        if (player.getAbilities().instabuild) {
            for (int i = 0; i < patternInventory.size(); i++) {
                patternInventory.setItemDirect(i, ItemStack.EMPTY);
            }
            return;
        }

        var playerInv = player.getInventory();

        // Clear out any existing patterns and give them to the player
        var blankPatternCount = 0;
        for (int i = 0; i < patternInventory.size(); i++) {
            var pattern = patternInventory.getStackInSlot(i);
            // Auto-Clear encoded patterns to allow them to stack
            if (pattern.is(AEItems.CRAFTING_PATTERN.asItem())
                    || pattern.is(AEItems.PROCESSING_PATTERN.asItem())
                    || pattern.is(AEItems.SMITHING_TABLE_PATTERN.asItem())
                    || pattern.is(AEItems.STONECUTTING_PATTERN.asItem())
                    || pattern.is(AEItems.BLANK_PATTERN.asItem())) {
                blankPatternCount += pattern.getCount();
            } else {
                // Give back any non-blank-patterns individually
                playerInv.placeItemBackInInventory(pattern);
            }
            patternInventory.setItemDirect(i, ItemStack.EMPTY);
        }

        // Place back the removed blank patterns all at once
        if (blankPatternCount > 0) {
            playerInv.placeItemBackInInventory(AEItems.BLANK_PATTERN.stack(blankPatternCount), false);
        }
    }

    private void onStackReturnedToNetwork(GenericStack genericStack) {
        if (unlockEvent != UnlockCraftingEvent.RESULT) {
            return; // If we're not waiting for the result, we don't care
        }

        if (unlockStack == null) {
            // Actually an error state...
            LOGGER.error("pattern provider was waiting for RESULT, but no result was set");
            unlockEvent = null;
        } else if (unlockStack.what().equals(genericStack.what())) {
            var remainingAmount = unlockStack.amount() - genericStack.amount();
            if (remainingAmount <= 0) {
                unlockEvent = null;
                unlockStack = null;
            } else {
                unlockStack = new GenericStack(unlockStack.what(), remainingAmount);
            }
        }
    }

    private class Ticker implements IGridTickable {

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(TickRates.Interface, !hasWorkToDo(), true);
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            if (!mainNode.isActive()) {
                return TickRateModulation.SLEEP;
            }
            boolean couldDoWork = doWork();
            return hasWorkToDo() ? couldDoWork ? TickRateModulation.URGENT : TickRateModulation.SLOWER
                    : TickRateModulation.SLEEP;
        }
    }

    /**
     * @return Gets the name used to show this pattern provider in the
     *         {@link appeng.menu.implementations.PatternAccessTermMenu}.
     */
    public PatternContainerGroup getTerminalGroup() {
        var host = this.host.getBlockEntity();
        var hostLevel = host.getLevel();

        // Prefer own custom name / icon if player has named it
        if (this.host instanceof Nameable nameable && nameable.hasCustomName()) {
            var name = nameable.getCustomName();
            return new PatternContainerGroup(
                    this.host.getTerminalIcon(),
                    name,
                    List.of());
        }

        var sides = getActiveSides();
        var groups = new LinkedHashSet<PatternContainerGroup>(sides.size());
        for (var side : sides) {
            var sidePos = host.getBlockPos().relative(side);
            var group = PatternContainerGroup.fromMachine(hostLevel, sidePos, side.getOpposite());
            if (group != null) {
                groups.add(group);
            }
        }

        // If there's just one group, group by that
        if (groups.size() == 1) {
            return groups.iterator().next();
        }

        List<Component> tooltip = List.of();
        // If there are multiple groups, show that in the tooltip
        if (groups.size() > 1) {
            tooltip = new ArrayList<>();
            tooltip.add(GuiText.AdjacentToDifferentMachines.text().withStyle(ChatFormatting.BOLD));
            for (var group : groups) {
                tooltip.add(group.name());
                for (var line : group.tooltip()) {
                    tooltip.add(Component.literal("  ").append(line));
                }
            }
        }

        // If nothing is adjacent, just use itself
        var hostIcon = this.host.getTerminalIcon();
        return new PatternContainerGroup(
                hostIcon,
                hostIcon.getDisplayName(),
                tooltip);
    }

    public long getSortValue() {
        final BlockEntity te = this.host.getBlockEntity();
        return te.getBlockPos().getZ() << 24 ^ te.getBlockPos().getX() << 8 ^ te.getBlockPos().getY();
    }

    public <T> LazyOptional<T> getCapability(Capability<T> capability) {
        return this.returnInv.getCapability(capability);
    }

    @Nullable
    public IGrid getGrid() {
        return mainNode.getGrid();
    }

    public void updateRedstoneState() {
        // If we're waiting for a pulse, update immediately
        if (unlockEvent == UnlockCraftingEvent.REDSTONE_POWER && getRedstoneState()) {
            unlockEvent = null; // Unlocked!
            saveChanges();
        } else if (unlockEvent == UnlockCraftingEvent.REDSTONE_PULSE && !getRedstoneState()) {
            unlockEvent = UnlockCraftingEvent.REDSTONE_POWER; // Wait for re-power
            redstoneState = YesNo.UNDECIDED; // Need to re-check signal on next update
            saveChanges();
        } else {
            // Otherwise, just reset back to undecided
            redstoneState = YesNo.UNDECIDED;
        }
    }

    private void configChanged(IConfigManager manager, Setting<?> setting) {
        if (setting == Settings.LOCK_CRAFTING_MODE) {
            resetCraftingLock();
        } else {
            saveChanges();
        }
    }

    private boolean getRedstoneState() {
        if (redstoneState == YesNo.UNDECIDED) {
            var be = this.host.getBlockEntity();
            redstoneState = be.getLevel().hasNeighborSignal(be.getBlockPos())
                    ? YesNo.YES
                    : YesNo.NO;
        }
        return redstoneState == YesNo.YES;
    }
}
