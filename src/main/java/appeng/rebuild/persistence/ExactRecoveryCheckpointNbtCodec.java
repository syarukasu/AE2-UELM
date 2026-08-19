package appeng.rebuild.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.rebuild.execution.BrokerTransferDiscrepancy;
import appeng.rebuild.execution.CpuPlanHandle;
import appeng.rebuild.execution.CycleExecutionManifest;
import appeng.rebuild.execution.ExactCompletedCommandEvidence;
import appeng.rebuild.execution.ExactCpuLedgerSnapshot;
import appeng.rebuild.execution.ExactCpuLedgerState;
import appeng.rebuild.execution.ExactPlanId;
import appeng.rebuild.execution.ExactRecoveryCheckpoint;
import appeng.rebuild.execution.ExactTransferBrokerSnapshot;
import appeng.rebuild.execution.ExactTransferBrokerState;
import appeng.rebuild.execution.ExactWorkCommand;
import appeng.rebuild.execution.ExactWorkCommandLocation;
import appeng.rebuild.execution.ExactWorkDiscrepancy;
import appeng.rebuild.execution.ExactWorkOrderReleaseMode;
import appeng.rebuild.execution.ExactWorkOrderSnapshot;
import appeng.rebuild.execution.ExactWorkOrderState;
import appeng.rebuild.execution.ExactWorkOutput;
import appeng.rebuild.execution.ExecutionManifest;
import appeng.rebuild.execution.FailedHandoffRecovery;
import appeng.rebuild.execution.NormalExecutionManifest;
import appeng.rebuild.execution.ReleaseObligation;
import appeng.rebuild.execution.ReservationId;
import appeng.rebuild.execution.SealedPatternExecution;
import appeng.rebuild.execution.WorkCommandId;
import appeng.rebuild.execution.WorkOrderId;
import appeng.rebuild.execution.WorkOrderTransferDiscrepancy;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.planner.PlannedBatchId;
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.planner.PlannedRemainderReturn;
import appeng.rebuild.quantity.AEAmount;

/** Strict aggregate checkpoint codec. A single sparse key table covers both sealed plan and runtime evidence. */
public final class ExactRecoveryCheckpointNbtCodec {
    private static final String VERSION = "v";
    private static final String KEYS = "k";
    private static final String PLAN = "p";
    private static final String LEDGER = "l";
    private static final String BROKER = "b";
    private static final String WORK = "w";
    private static final String FAILED = "h";
    private static final String REQUIRED = "r";

    private ExactRecoveryCheckpointNbtCodec() {
    }

    /**
     * Performs the bounded, type-only check required before a native owner copies untrusted durable evidence.
     *
     * <p>
     * This intentionally does not decode or rebind a key table. Callers must retain a bounded malformed tag for a
     * later typed decode result, while an over-limit tag must be replaced by explicit durable rejection evidence rather
     * than copied into another unbounded object graph.
     */
    public static EnvelopePreflight preflightEnvelope(Tag tag) {
        if (tag == null) {
            return EnvelopePreflight.ABSENT;
        }
        if (!StrictNbt.valid(tag)) {
            return EnvelopePreflight.LIMIT_EXCEEDED;
        }
        return tag instanceof CompoundTag ? EnvelopePreflight.ACCEPTED : EnvelopePreflight.MALFORMED_TYPE;
    }

    public enum EnvelopePreflight {
        ABSENT,
        ACCEPTED,
        MALFORMED_TYPE,
        LIMIT_EXCEEDED
    }

    public static CompoundTag encode(ExactRecoveryCheckpoint checkpoint, KeyRegistry registry) {
        if (checkpoint == null || registry == null)
            throw new IllegalArgumentException("checkpoint and registry are required");
        if (checkpoint.plan().planningRevision().keyRegistryGeneration() != registry.generation()
                || checkpoint.plan().validationRevision().keyRegistryGeneration() != registry.generation()
                || checkpoint.plan().dependencies().gridRevision().keyRegistryGeneration() != registry.generation())
            throw new IllegalArgumentException("Checkpoint plan is not scoped to the supplied key registry");
        Set<KeyId> keys = new HashSet<>();
        addKeys(keys, ExactCraftingPlanNbtCodec.collectKeys(checkpoint.plan()));
        collectRuntimeKeys(checkpoint, keys);
        PersistedKeyTable table = PersistedKeyTable.capture(registry, keys);
        CompoundTag root = new CompoundTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        if (!budget.reserve(128, 8))
            throw new IllegalArgumentException("Recovery checkpoint header exceeds persistence limits");
        root.putInt(VERSION, PersistenceLimits.FORMAT_VERSION);
        put(root, KEYS, table.encode(), budget);
        put(root, PLAN, ExactCraftingPlanNbtCodec.encodePayload(checkpoint.plan()), budget);
        put(root, LEDGER, ledger(checkpoint.ledger()), budget);
        put(root, BROKER, broker(checkpoint.broker()), budget);
        put(root, WORK, optional(checkpoint.workOrder(), ExactRecoveryCheckpointNbtCodec::work), budget);
        put(root, FAILED, optional(checkpoint.failedHandoff(), ExactRecoveryCheckpointNbtCodec::failed), budget);
        put(root, REQUIRED, ByteTag.valueOf(checkpoint.recoveryRequired()), budget);
        if (!StrictNbt.valid(root))
            throw new IllegalArgumentException("Recovery checkpoint exceeds strict persistence limits");
        return root;
    }

    public static PersistenceDecodeResult<ExactRecoveryCheckpoint> decode(CompoundTag root, KeyRegistry current) {
        if (current == null || root == null)
            return malformed();
        if (!StrictNbt.valid(root))
            return limited();
        try {
            if (!shape(root, 8, VERSION, Tag.TAG_INT, KEYS, Tag.TAG_COMPOUND, PLAN, Tag.TAG_COMPOUND, LEDGER,
                    Tag.TAG_COMPOUND, BROKER, Tag.TAG_COMPOUND, WORK, Tag.TAG_COMPOUND, FAILED, Tag.TAG_COMPOUND,
                    REQUIRED, Tag.TAG_BYTE))
                return malformed();
            if (root.getInt(VERSION) != PersistenceLimits.FORMAT_VERSION)
                return unsupported();
            PersistenceDecodeResult<PersistedKeyTable> decodedTable = PersistedKeyTable.decode(root.getCompound(KEYS));
            if (!(decodedTable instanceof PersistenceDecodeResult.Success<PersistedKeyTable> table))
                return propagate(decodedTable);
            PersistenceDecodeResult<ExactCraftingPlanNbtCodec.ProspectivePlan> decodedPlan = ExactCraftingPlanNbtCodec
                    .decodeProspectivePayload(root.getCompound(PLAN), table.value(), current);
            if (!(decodedPlan instanceof PersistenceDecodeResult.Success<ExactCraftingPlanNbtCodec.ProspectivePlan> plan))
                return propagate(decodedPlan);
            RefTracker refs = new RefTracker(plan.value().oldKeyIds());
            ExactCpuLedgerSnapshot ledger = ledger(root.getCompound(LEDGER), plan.value(), refs);
            ExactTransferBrokerSnapshot broker = broker(root.getCompound(BROKER), plan.value(), refs);
            Optional<ExactWorkOrderSnapshot> work = optional(root.getCompound(WORK),
                    tag -> work(tag, plan.value(), refs));
            Optional<FailedHandoffRecovery> failed = optional(root.getCompound(FAILED),
                    tag -> failed(tag, plan.value(), refs));
            if (!refs.ids.equals(ids(table.value().entries())))
                return malformed();
            ExactRecoveryCheckpoint checkpoint = new ExactRecoveryCheckpoint(plan.value().plan(), ledger, broker, work,
                    failed, canonicalBoolean(root, REQUIRED));
            KeyTableRebinder.commit(table.value(), current, plan.value().remap());
            return new PersistenceDecodeResult.Success<>(checkpoint);
        } catch (DecodeFailure failure) {
            return new PersistenceDecodeResult.Failure<>(failure.reason);
        } catch (RuntimeException failure) {
            return malformed();
        }
    }

    private static CompoundTag ledger(ExactCpuLedgerSnapshot value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("s", value.state().name());
        tag.putLong("v", value.lifecycleRevision());
        tag.put("h", optional(value.handle(), ExactRecoveryCheckpointNbtCodec::handle));
        tag.put("r", optional(value.reservationId(), ExactRecoveryCheckpointNbtCodec::reservation));
        tag.put("d", amounts(value.reservedDebits()));
        tag.put("o", optional(value.releaseObligation(), ExactRecoveryCheckpointNbtCodec::obligation));
        tag.put("x", optional(value.leaseIdentity(), ExactRecoveryCheckpointNbtCodec::uuid));
        return tag;
    }

    private static ExactCpuLedgerSnapshot ledger(CompoundTag tag, ExactCraftingPlanNbtCodec.ProspectivePlan plan,
            RefTracker refs) {
        require(shape(tag, 7, "s", Tag.TAG_STRING, "v", Tag.TAG_LONG, "h", Tag.TAG_COMPOUND, "r",
                Tag.TAG_COMPOUND, "d", Tag.TAG_COMPOUND, "o", Tag.TAG_COMPOUND, "x", Tag.TAG_COMPOUND));
        return new ExactCpuLedgerSnapshot(enumValue(tag.getString("s"), ExactCpuLedgerState.class),
                nonnegative(tag.getLong("v")),
                optional(tag.getCompound("h"), ExactRecoveryCheckpointNbtCodec::handle),
                optional(tag.getCompound("r"), ExactRecoveryCheckpointNbtCodec::reservation),
                amounts(tag.getCompound("d"), plan, refs),
                optional(tag.getCompound("o"), x -> obligation(x, plan, refs)),
                optional(tag.getCompound("x"), ExactRecoveryCheckpointNbtCodec::uuid));
    }

    private static CompoundTag broker(ExactTransferBrokerSnapshot value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("s", value.state().name());
        tag.put("h", optional(value.handle(), ExactRecoveryCheckpointNbtCodec::handle));
        tag.put("p", optional(value.planId(), ExactRecoveryCheckpointNbtCodec::planId));
        tag.put("r", optional(value.reservationId(), ExactRecoveryCheckpointNbtCodec::reservation));
        tag.put("w", optional(value.workOrderId(), ExactRecoveryCheckpointNbtCodec::workOrderId));
        tag.put("x", optional(value.leaseIdentity(), ExactRecoveryCheckpointNbtCodec::uuid));
        tag.put("e", amounts(value.escrowed()));
        tag.put("d", optional(value.transferDiscrepancy(), ExactRecoveryCheckpointNbtCodec::transferDiscrepancy));
        return tag;
    }

    private static ExactTransferBrokerSnapshot broker(CompoundTag tag,
            ExactCraftingPlanNbtCodec.ProspectivePlan plan, RefTracker refs) {
        require(shape(tag, 8, "s", Tag.TAG_STRING, "h", Tag.TAG_COMPOUND, "p", Tag.TAG_COMPOUND, "r",
                Tag.TAG_COMPOUND, "w", Tag.TAG_COMPOUND, "x", Tag.TAG_COMPOUND, "e", Tag.TAG_COMPOUND, "d",
                Tag.TAG_COMPOUND));
        return new ExactTransferBrokerSnapshot(enumValue(tag.getString("s"), ExactTransferBrokerState.class),
                optional(tag.getCompound("h"), ExactRecoveryCheckpointNbtCodec::handle),
                optional(tag.getCompound("p"), ExactRecoveryCheckpointNbtCodec::planId),
                optional(tag.getCompound("r"), ExactRecoveryCheckpointNbtCodec::reservation),
                optional(tag.getCompound("w"), ExactRecoveryCheckpointNbtCodec::workOrderId),
                optional(tag.getCompound("x"), ExactRecoveryCheckpointNbtCodec::uuid),
                amounts(tag.getCompound("e"), plan, refs),
                optional(tag.getCompound("d"), x -> transferDiscrepancy(x, plan, refs)));
    }

    private static CompoundTag work(ExactWorkOrderSnapshot x) {
        CompoundTag t = new CompoundTag();
        t.putString("s", x.state().name());
        t.put("p", planId(x.planId()));
        t.put("h", handle(x.handle()));
        t.put("r", reservation(x.reservationId()));
        t.put("l", uuid(x.leaseIdentity()));
        t.put("w", workOrderId(x.workOrderId()));
        t.put("c", amounts(x.custody()));
        t.putInt("i", x.causalStepIndex());
        t.put("e", amount(x.remainingExecutions()));
        t.put("n", amountList(x.selectionRemaining()));
        t.put("q", amount(x.cycleRemainingRepetitions()));
        t.putInt("m", x.cycleMemberIndex());
        t.put("f", amount(x.cycleMemberRemainingExecutions()));
        t.put("g", amountList(x.cycleSelectionRemaining()));
        t.put("o", optional(x.releaseMode(), y -> enumTag(y)));
        t.putLong("z", x.nextGeneration());
        t.putByte("y", (byte) (x.generationExhausted() ? 1 : 0));
        t.put("a", optional(x.outstandingCommand(), ExactRecoveryCheckpointNbtCodec::command));
        t.put("b", optional(x.inFlightCommand(), ExactRecoveryCheckpointNbtCodec::command));
        t.put("d", optional(x.discrepancy(), ExactRecoveryCheckpointNbtCodec::discrepancy));
        t.put("v", optional(x.completedEvidence(), ExactRecoveryCheckpointNbtCodec::completed));
        t.putByte("u", (byte) (x.completedEvidenceProgressApplied() ? 1 : 0));
        t.put("t", optional(x.transferDiscrepancy(), ExactRecoveryCheckpointNbtCodec::workTransferDiscrepancy));
        return t;
    }

    private static ExactWorkOrderSnapshot work(CompoundTag t, ExactCraftingPlanNbtCodec.ProspectivePlan plan,
            RefTracker refs) {
        require(shape(t, 23, "s", Tag.TAG_STRING, "p", Tag.TAG_COMPOUND, "h", Tag.TAG_COMPOUND, "r", Tag.TAG_COMPOUND,
                "l", Tag.TAG_COMPOUND, "w", Tag.TAG_COMPOUND, "c", Tag.TAG_COMPOUND, "i", Tag.TAG_INT, "e",
                Tag.TAG_COMPOUND,
                "n", Tag.TAG_LIST, "q", Tag.TAG_COMPOUND, "m", Tag.TAG_INT, "f", Tag.TAG_COMPOUND, "g", Tag.TAG_LIST,
                "o", Tag.TAG_COMPOUND, "z", Tag.TAG_LONG, "y", Tag.TAG_BYTE, "a", Tag.TAG_COMPOUND, "b",
                Tag.TAG_COMPOUND,
                "d", Tag.TAG_COMPOUND, "v", Tag.TAG_COMPOUND, "u", Tag.TAG_BYTE, "t", Tag.TAG_COMPOUND));
        return new ExactWorkOrderSnapshot(enumValue(t.getString("s"), ExactWorkOrderState.class),
                planId(t.getCompound("p")),
                handle(t.getCompound("h")), reservation(t.getCompound("r")), uuid(t.getCompound("l")),
                workOrderId(t.getCompound("w")),
                amounts(t.getCompound("c"), plan, refs), nonnegative(t.getInt("i")), amount(t.getCompound("e")),
                amountList(compoundList(t, "n", PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN)),
                amount(t.getCompound("q")), t.getInt("m"), amount(t.getCompound("f")),
                amountList(compoundList(t, "g", PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN)),
                optional(t.getCompound("o"), x -> enumTag(x, ExactWorkOrderReleaseMode.class)),
                nonnegative(t.getLong("z")),
                canonicalBoolean(t, "y"), optional(t.getCompound("a"), x -> command(x, plan, refs)),
                optional(t.getCompound("b"), x -> command(x, plan, refs)),
                optional(t.getCompound("d"), x -> discrepancy(x, plan, refs)),
                optional(t.getCompound("v"), x -> completed(x, plan, refs)), canonicalBoolean(t, "u"),
                optional(t.getCompound("t"), x -> workTransferDiscrepancy(x, plan, refs)));
    }

    private static CompoundTag command(ExactWorkCommand x) {
        CompoundTag t = new CompoundTag();
        t.put("i", commandId(x.id()));
        t.put("h", handle(x.handle()));
        t.put("r", reservation(x.reservationId()));
        t.putLong("b", x.batchId().value());
        t.put("l", location(x.location()));
        t.putString("p", x.pattern().id().value());
        t.putLong("v", x.pattern().revision().value());
        t.putLong("w", x.executionWindow());
        t.put("s", selections(x.plannedSelections()));
        t.put("c", amounts(x.custodyInputs()));
        t.put("o", outputs(x.expectedOutputSlots()));
        t.put("e", amounts(x.expectedOutputs()));
        t.put("m", amounts(x.expectedRemainders()));
        return t;
    }

    private static ExactWorkCommand command(CompoundTag t, ExactCraftingPlanNbtCodec.ProspectivePlan plan,
            RefTracker refs) {
        require(shape(t, 13, "i", Tag.TAG_COMPOUND, "h", Tag.TAG_COMPOUND, "r", Tag.TAG_COMPOUND, "b", Tag.TAG_LONG,
                "l", Tag.TAG_COMPOUND,
                "p", Tag.TAG_STRING, "v", Tag.TAG_LONG, "w", Tag.TAG_LONG, "s", Tag.TAG_LIST, "c", Tag.TAG_COMPOUND,
                "o", Tag.TAG_LIST, "e", Tag.TAG_COMPOUND, "m", Tag.TAG_COMPOUND));
        PlannedBatchId batch = new PlannedBatchId(nonnegative(t.getLong("b")));
        ExactWorkCommandLocation location = location(t.getCompound("l"));
        CompiledPattern pattern = pattern(plan.plan(), batch, location, t.getString("p"), t.getLong("v"));
        return new ExactWorkCommand(commandId(t.getCompound("i")), handle(t.getCompound("h")),
                reservation(t.getCompound("r")), batch, location, pattern,
                positiveLong(t.getLong("w")),
                selections(compoundList(t, "s", PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN), plan, refs),
                amounts(t.getCompound("c"), plan, refs),
                outputs(compoundList(t, "o", PatternLimits.MAX_OUTPUTS), plan, refs),
                amounts(t.getCompound("e"), plan, refs), amounts(t.getCompound("m"), plan, refs));
    }

    private static CompoundTag discrepancy(ExactWorkDiscrepancy x) {
        CompoundTag t = new CompoundTag();
        t.put("c", command(x.command()));
        t.put("e", amounts(x.expectedOutputs()));
        t.put("a", amounts(x.actualOutputs()));
        t.put("r", amounts(x.expectedRemainders()));
        t.put("b", amounts(x.actualRemainders()));
        return t;
    }

    private static ExactWorkDiscrepancy discrepancy(CompoundTag t, ExactCraftingPlanNbtCodec.ProspectivePlan p,
            RefTracker r) {
        require(shape(t, 5, "c", Tag.TAG_COMPOUND, "e", Tag.TAG_COMPOUND, "a", Tag.TAG_COMPOUND, "r", Tag.TAG_COMPOUND,
                "b", Tag.TAG_COMPOUND));
        return new ExactWorkDiscrepancy(command(t.getCompound("c"), p, r), amounts(t.getCompound("e"), p, r),
                amounts(t.getCompound("a"), p, r), amounts(t.getCompound("r"), p, r),
                amounts(t.getCompound("b"), p, r));
    }

    private static CompoundTag completed(ExactCompletedCommandEvidence x) {
        CompoundTag t = new CompoundTag();
        t.put("c", command(x.command()));
        t.put("o", amounts(x.actualOutputs()));
        t.put("r", amounts(x.actualRemainders()));
        return t;
    }

    private static ExactCompletedCommandEvidence completed(CompoundTag t, ExactCraftingPlanNbtCodec.ProspectivePlan p,
            RefTracker r) {
        require(shape(t, 3, "c", Tag.TAG_COMPOUND, "o", Tag.TAG_COMPOUND, "r", Tag.TAG_COMPOUND));
        return new ExactCompletedCommandEvidence(command(t.getCompound("c"), p, r), amounts(t.getCompound("o"), p, r),
                amounts(t.getCompound("r"), p, r));
    }

    private static CompoundTag workTransferDiscrepancy(WorkOrderTransferDiscrepancy x) {
        CompoundTag t = new CompoundTag();
        t.putString("r", x.reason().name());
        t.put("p", planId(x.planId()));
        t.put("h", handle(x.handle()));
        t.put("i", reservation(x.reservationId()));
        t.put("l", uuid(x.leaseIdentity()));
        t.put("w", workOrderId(x.workOrderId()));
        t.putInt("k", x.key().value());
        t.put("a", amount(x.requested()));
        t.put("v", optional(x.reported(), ExactRecoveryCheckpointNbtCodec::amount));
        t.put("c", amounts(x.knownCustody()));
        return t;
    }

    private static WorkOrderTransferDiscrepancy workTransferDiscrepancy(CompoundTag t,
            ExactCraftingPlanNbtCodec.ProspectivePlan p, RefTracker r) {
        require(shape(t, 10, "r", Tag.TAG_STRING, "p", Tag.TAG_COMPOUND, "h", Tag.TAG_COMPOUND, "i",
                Tag.TAG_COMPOUND, "l", Tag.TAG_COMPOUND, "w", Tag.TAG_COMPOUND, "k", Tag.TAG_INT, "a",
                Tag.TAG_COMPOUND, "v", Tag.TAG_COMPOUND, "c", Tag.TAG_COMPOUND));
        return new WorkOrderTransferDiscrepancy(enumValue(t.getString("r"), WorkOrderTransferDiscrepancy.Reason.class),
                planId(t.getCompound("p")), handle(t.getCompound("h")), reservation(t.getCompound("i")),
                uuid(t.getCompound("l")), workOrderId(t.getCompound("w")), key(t.getInt("k"), p, r),
                amount(t.getCompound("a")), optional(t.getCompound("v"), ExactRecoveryCheckpointNbtCodec::amount),
                amounts(t.getCompound("c"), p, r));
    }

    private static CompoundTag transferDiscrepancy(BrokerTransferDiscrepancy x) {
        CompoundTag t = new CompoundTag();
        t.putString("o", x.operation().name());
        t.putString("r", x.reason().name());
        t.put("p", planId(x.planId()));
        t.put("h", handle(x.handle()));
        t.put("i", reservation(x.reservationId()));
        t.putInt("k", x.key().value());
        t.put("a", amount(x.requested()));
        t.put("v", optional(x.reported(), ExactRecoveryCheckpointNbtCodec::amount));
        t.put("e", amounts(x.knownEscrow()));
        return t;
    }

    private static BrokerTransferDiscrepancy transferDiscrepancy(CompoundTag t,
            ExactCraftingPlanNbtCodec.ProspectivePlan p, RefTracker r) {
        require(shape(t, 9, "o", Tag.TAG_STRING, "r", Tag.TAG_STRING, "p", Tag.TAG_COMPOUND, "h", Tag.TAG_COMPOUND, "i",
                Tag.TAG_COMPOUND, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND, "v", Tag.TAG_COMPOUND, "e",
                Tag.TAG_COMPOUND));
        return new BrokerTransferDiscrepancy(enumValue(t.getString("o"), BrokerTransferDiscrepancy.Operation.class),
                enumValue(t.getString("r"), BrokerTransferDiscrepancy.Reason.class), planId(t.getCompound("p")),
                handle(t.getCompound("h")), reservation(t.getCompound("i")), key(t.getInt("k"), p, r),
                amount(t.getCompound("a")), optional(t.getCompound("v"), ExactRecoveryCheckpointNbtCodec::amount),
                amounts(t.getCompound("e"), p, r));
    }

    private static CompoundTag failed(FailedHandoffRecovery x) {
        CompoundTag t = new CompoundTag();
        t.put("l", uuid(x.leaseIdentity()));
        t.put("h", handle(x.handle()));
        t.put("r", reservation(x.reservationId()));
        t.put("p", planId(x.planId()));
        t.put("d", amounts(x.reservedDebits()));
        return t;
    }

    private static FailedHandoffRecovery failed(CompoundTag t, ExactCraftingPlanNbtCodec.ProspectivePlan p,
            RefTracker r) {
        require(shape(t, 5, "l", Tag.TAG_COMPOUND, "h", Tag.TAG_COMPOUND, "r", Tag.TAG_COMPOUND, "p", Tag.TAG_COMPOUND,
                "d", Tag.TAG_COMPOUND));
        return new FailedHandoffRecovery(uuid(t.getCompound("l")), handle(t.getCompound("h")),
                reservation(t.getCompound("r")), planId(t.getCompound("p")), amounts(t.getCompound("d"), p, r));
    }

    private static CompoundTag obligation(ReleaseObligation x) {
        CompoundTag t = new CompoundTag();
        t.put("h", handle(x.handle()));
        t.put("r", reservation(x.reservationId()));
        t.put("d", amounts(x.reservedDebits()));
        return t;
    }

    private static ReleaseObligation obligation(CompoundTag t, ExactCraftingPlanNbtCodec.ProspectivePlan p,
            RefTracker r) {
        require(shape(t, 3, "h", Tag.TAG_COMPOUND, "r", Tag.TAG_COMPOUND, "d", Tag.TAG_COMPOUND));
        return ReleaseObligation.restoreForRecovery(handle(t.getCompound("h")), reservation(t.getCompound("r")),
                amounts(t.getCompound("d"), p, r));
    }

    private static ListTag selections(List<PlannedInputSelection> x) {
        ListTag list = new ListTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        for (PlannedInputSelection s : x) {
            CompoundTag t = new CompoundTag();
            t.putInt("i", s.inputIndex());
            t.putInt("c", s.candidateIndex());
            t.put("u", amount(s.templateUnits()));
            t.putInt("k", s.consumedKey().value());
            t.put("g", amount(s.grossConsumedAmount()));
            t.put("a", amount(s.initialRequiredAmount()));
            t.put("r", optional(s.remainderReturn(), ExactRecoveryCheckpointNbtCodec::remainder));
            if (!budget.add(t))
                throw new IllegalArgumentException("Selection persistence exceeds strict limits");
            list.add(t);
        }
        return list;
    }

    private static List<PlannedInputSelection> selections(ListTag list, ExactCraftingPlanNbtCodec.ProspectivePlan p,
            RefTracker r) {
        require(list.size() <= PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN);
        ArrayList<PlannedInputSelection> out = new ArrayList<>();
        for (Tag raw : list) {
            CompoundTag t = compound(raw);
            require(shape(t, 7, "i", Tag.TAG_INT, "c", Tag.TAG_INT, "u", Tag.TAG_COMPOUND, "k", Tag.TAG_INT, "g",
                    Tag.TAG_COMPOUND, "a", Tag.TAG_COMPOUND, "r", Tag.TAG_COMPOUND));
            out.add(new PlannedInputSelection(nonnegative(t.getInt("i")), nonnegative(t.getInt("c")),
                    amount(t.getCompound("u")), key(t.getInt("k"), p, r), amount(t.getCompound("g")),
                    amount(t.getCompound("a")), optional(t.getCompound("r"), x -> remainder(x, p, r))));
        }
        return List.copyOf(out);
    }

    private static CompoundTag remainder(PlannedRemainderReturn x) {
        CompoundTag t = new CompoundTag();
        t.putInt("k", x.key().value());
        t.put("a", amount(x.amount()));
        return t;
    }

    private static PlannedRemainderReturn remainder(CompoundTag t, ExactCraftingPlanNbtCodec.ProspectivePlan p,
            RefTracker r) {
        require(shape(t, 2, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND));
        return new PlannedRemainderReturn(key(t.getInt("k"), p, r), amount(t.getCompound("a")));
    }

    private static ListTag outputs(List<ExactWorkOutput> x) {
        ListTag l = new ListTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        for (ExactWorkOutput o : x) {
            CompoundTag t = new CompoundTag();
            t.putInt("i", o.outputIndex());
            t.putInt("k", o.key().value());
            t.put("a", amount(o.amount()));
            if (!budget.add(t))
                throw new IllegalArgumentException("Output persistence exceeds strict limits");
            l.add(t);
        }
        return l;
    }

    private static List<ExactWorkOutput> outputs(ListTag l, ExactCraftingPlanNbtCodec.ProspectivePlan p, RefTracker r) {
        require(l.size() <= PatternLimits.MAX_OUTPUTS);
        ArrayList<ExactWorkOutput> out = new ArrayList<>();
        for (Tag raw : l) {
            CompoundTag t = compound(raw);
            require(shape(t, 3, "i", Tag.TAG_INT, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND));
            out.add(new ExactWorkOutput(nonnegative(t.getInt("i")), key(t.getInt("k"), p, r),
                    amount(t.getCompound("a"))));
        }
        return List.copyOf(out);
    }

    private static CompoundTag amounts(Map<KeyId, AEAmount> x) {
        ListTag l = new ListTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        x.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingInt(KeyId::value))).forEach(e -> {
            CompoundTag t = new CompoundTag();
            t.putInt("k", e.getKey().value());
            t.put("a", amount(e.getValue()));
            if (!budget.add(t))
                throw new IllegalArgumentException("Amount-map persistence exceeds strict limits");
            l.add(t);
        });
        CompoundTag t = new CompoundTag();
        t.put("l", l);
        return t;
    }

    private static Map<KeyId, AEAmount> amounts(CompoundTag t, ExactCraftingPlanNbtCodec.ProspectivePlan p,
            RefTracker r) {
        require(shape(t, 1, "l", Tag.TAG_LIST));
        ListTag l = compoundList(t, "l", PersistenceLimits.MAX_KEYS);
        TreeMap<KeyId, AEAmount> out = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        int prior = -1;
        for (Tag raw : l) {
            CompoundTag e = compound(raw);
            require(shape(e, 2, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND));
            int old = e.getInt("k");
            require(old > prior);
            prior = old;
            KeyId k = key(old, p, r);
            require(out.put(k, amount(e.getCompound("a"))) == null);
        }
        return Map.copyOf(out);
    }

    private static ListTag amountList(List<AEAmount> x) {
        ListTag l = new ListTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        for (AEAmount a : x) {
            CompoundTag value = amount(a);
            if (!budget.add(value))
                throw new IllegalArgumentException("Amount-list persistence exceeds strict limits");
            l.add(value);
        }
        return l;
    }

    private static List<AEAmount> amountList(ListTag l) {
        require(l.size() <= PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN);
        ArrayList<AEAmount> out = new ArrayList<>();
        for (Tag x : l)
            out.add(amount(compound(x)));
        return List.copyOf(out);
    }

    private static CompoundTag amount(AEAmount x) {
        return AEAmountNbtCodec.encode(x);
    }

    private static AEAmount amount(CompoundTag x) {
        PersistenceDecodeResult<AEAmount> d = AEAmountNbtCodec.decode(x);
        if (d instanceof PersistenceDecodeResult.Success<AEAmount> s)
            return s.value();
        if (d instanceof PersistenceDecodeResult.Failure<AEAmount> f)
            throw new DecodeFailure(f.reason());
        throw new Bad();
    }

    private static CompoundTag planId(ExactPlanId x) {
        return uuid(x.value());
    }

    private static ExactPlanId planId(CompoundTag x) {
        return new ExactPlanId(uuid(x));
    }

    private static CompoundTag uuid(UUID x) {
        CompoundTag t = new CompoundTag();
        t.putString("u", x.toString());
        return t;
    }

    private static UUID uuid(CompoundTag t) {
        require(shape(t, 1, "u", Tag.TAG_STRING));
        try {
            String raw = t.getString("u");
            UUID value = UUID.fromString(raw);
            require(value.toString().equals(raw));
            return value;
        } catch (IllegalArgumentException x) {
            throw new Bad();
        }
    }

    private static CompoundTag handle(CpuPlanHandle x) {
        CompoundTag t = new CompoundTag();
        t.put("u", uuid(x.ledgerIdentity()));
        t.putLong("r", x.revision());
        return t;
    }

    private static CpuPlanHandle handle(CompoundTag t) {
        require(shape(t, 2, "u", Tag.TAG_COMPOUND, "r", Tag.TAG_LONG));
        return new CpuPlanHandle(uuid(t.getCompound("u")), t.getLong("r"));
    }

    private static CompoundTag reservation(ReservationId x) {
        return uuid(x.value());
    }

    private static ReservationId reservation(CompoundTag t) {
        return new ReservationId(uuid(t));
    }

    private static CompoundTag workOrderId(WorkOrderId x) {
        return uuid(x.value());
    }

    private static WorkOrderId workOrderId(CompoundTag t) {
        return new WorkOrderId(uuid(t));
    }

    private static CompoundTag commandId(WorkCommandId x) {
        CompoundTag t = new CompoundTag();
        t.put("p", planId(x.planId()));
        t.put("l", uuid(x.leaseIdentity()));
        t.put("w", workOrderId(x.workOrderId()));
        t.putLong("g", x.generation());
        return t;
    }

    private static WorkCommandId commandId(CompoundTag t) {
        require(shape(t, 4, "p", Tag.TAG_COMPOUND, "l", Tag.TAG_COMPOUND, "w", Tag.TAG_COMPOUND, "g", Tag.TAG_LONG));
        return new WorkCommandId(planId(t.getCompound("p")), uuid(t.getCompound("l")), workOrderId(t.getCompound("w")),
                nonnegative(t.getLong("g")));
    }

    private static CompoundTag location(ExactWorkCommandLocation x) {
        CompoundTag t = new CompoundTag();
        t.putInt("s", x.causalStepIndex());
        t.putInt("m", x.cycleMemberIndex());
        t.put("r", amount(x.remainingRepetitions()));
        t.put("e", amount(x.remainingMemberExecutions()));
        return t;
    }

    private static ExactWorkCommandLocation location(CompoundTag t) {
        require(shape(t, 4, "s", Tag.TAG_INT, "m", Tag.TAG_INT, "r", Tag.TAG_COMPOUND, "e", Tag.TAG_COMPOUND));
        return new ExactWorkCommandLocation(nonnegative(t.getInt("s")), t.getInt("m"), amount(t.getCompound("r")),
                amount(t.getCompound("e")));
    }

    private static CompoundTag enumTag(Enum<?> x) {
        CompoundTag t = new CompoundTag();
        t.putString("v", x.name());
        return t;
    }

    private static <E extends Enum<E>> E enumTag(CompoundTag tag, Class<E> type) {
        require(shape(tag, 1, "v", Tag.TAG_STRING));
        return enumValue(tag.getString("v"), type);
    }

    private static <T> CompoundTag optional(Optional<T> x, Function<T, CompoundTag> encoder) {
        CompoundTag t = new CompoundTag();
        t.putByte("p", (byte) (x.isPresent() ? 1 : 0));
        x.ifPresent(v -> t.put("v", encoder.apply(v)));
        return t;
    }

    private static <T> Optional<T> optional(CompoundTag t, Function<CompoundTag, T> decoder) {
        require(t.contains("p", Tag.TAG_BYTE));
        if (!canonicalBoolean(t, "p")) {
            require(t.size() == 1);
            return Optional.empty();
        }
        require(shape(t, 2, "p", Tag.TAG_BYTE, "v", Tag.TAG_COMPOUND));
        return Optional.of(decoder.apply(t.getCompound("v")));
    }

    private static <E extends Enum<E>> E enumValue(String x, Class<E> type) {
        try {
            return Enum.valueOf(type, x);
        } catch (IllegalArgumentException ex) {
            throw new Bad();
        }
    }

    private static long nonnegative(long x) {
        if (x < 0)
            throw new Bad();
        return x;
    }

    private static int nonnegative(int x) {
        if (x < 0)
            throw new Bad();
        return x;
    }

    private static long positiveLong(long x) {
        if (x <= 0)
            throw new Bad();
        return x;
    }

    private static boolean canonicalBoolean(CompoundTag t, String name) {
        byte value = t.getByte(name);
        if (value != 0 && value != 1)
            throw new Bad();
        return value == 1;
    }

    private static CompoundTag compound(Tag x) {
        if (!(x instanceof CompoundTag t))
            throw new Bad();
        return t;
    }

    private static ListTag compoundList(CompoundTag parent, String name, int limit) {
        Tag raw = parent.get(name);
        if (!(raw instanceof ListTag list) || list.size() > limit
                || list.size() == 0 && Byte.toUnsignedInt(list.getElementType()) != Tag.TAG_END
                || list.size() > 0 && Byte.toUnsignedInt(list.getElementType()) != Tag.TAG_COMPOUND)
            throw new Bad();
        return list;
    }

    private static boolean shape(CompoundTag t, int n, Object... p) {
        if (t == null || n < 0 || p == null || p.length != n * 2 || t.size() != n)
            return false;
        for (int i = 0; i < p.length; i += 2) {
            if (!(p[i] instanceof String name) || !(p[i + 1] instanceof Number type)
                    || !t.contains(name, type.intValue()))
                return false;
        }
        return true;
    }

    private static void require(boolean x) {
        if (!x)
            throw new Bad();
    }

    private static void put(CompoundTag t, String n, Tag v, StrictNbt.Budget b) {
        if (!b.add(v))
            throw new IllegalArgumentException("Recovery checkpoint exceeds strict persistence limits");
        t.put(n, v);
    }

    private static KeyId key(int old, ExactCraftingPlanNbtCodec.ProspectivePlan p, RefTracker r) {
        KeyId id = new KeyId(old);
        r.ids.add(id);
        return p.remap().require(id);
    }

    private static Set<KeyId> ids(List<PersistedKeyTable.Entry> entries) {
        Set<KeyId> x = new HashSet<>();
        for (PersistedKeyTable.Entry e : entries)
            x.add(e.oldId());
        return x;
    }

    private static <T> PersistenceDecodeResult<T> malformed() {
        return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.MALFORMED);
    }

    private static <T> PersistenceDecodeResult<T> limited() {
        return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.LIMIT_EXCEEDED);
    }

    private static <T> PersistenceDecodeResult<T> unsupported() {
        return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.UNSUPPORTED_VERSION);
    }

    private static <T> PersistenceDecodeResult<T> propagate(PersistenceDecodeResult<?> x) {
        return x instanceof PersistenceDecodeResult.Failure<?> f ? new PersistenceDecodeResult.Failure<>(f.reason())
                : malformed();
    }

    private static CompiledPattern pattern(appeng.rebuild.execution.ExactCraftingPlan plan, PlannedBatchId id,
            ExactWorkCommandLocation loc, String patternId, long revision) {
        SealedPatternExecution match = null;
        for (ExecutionManifest m : plan.executionManifests())
            if (m.batchId().equals(id)) {
                if (match != null || loc.cycleMemberIndex() < 0 && !(m instanceof NormalExecutionManifest)
                        || loc.cycleMemberIndex() >= 0 && !(m instanceof CycleExecutionManifest))
                    throw new Bad();
                int i = loc.cycleMemberIndex() < 0 ? 0 : loc.cycleMemberIndex();
                if (i < 0 || i >= m.patternExecutions().size())
                    throw new Bad();
                match = m.patternExecutions().get(i);
            }
        if (match == null || !match.pattern().id().value().equals(patternId)
                || match.pattern().revision().value() != revision)
            throw new Bad();
        return match.pattern();
    }

    private static void collectRuntimeKeys(ExactRecoveryCheckpoint c, Set<KeyId> keys) {
        addKeys(keys, c.ledger().reservedDebits().keySet());
        c.ledger().releaseObligation().ifPresent(x -> addKeys(keys, x.reservedDebits().keySet()));
        addKeys(keys, c.broker().escrowed().keySet());
        c.broker().transferDiscrepancy().ifPresent(x -> {
            addKey(keys, x.key());
            addKeys(keys, x.knownEscrow().keySet());
        });
        c.workOrder().ifPresent(x -> {
            addKeys(keys, x.custody().keySet());
            collectCommand(x.outstandingCommand(), keys);
            collectCommand(x.inFlightCommand(), keys);
            x.discrepancy().ifPresent(d -> {
                collectCommand(Optional.of(d.command()), keys);
                addKeys(keys, d.expectedOutputs().keySet());
                addKeys(keys, d.actualOutputs().keySet());
                addKeys(keys, d.expectedRemainders().keySet());
                addKeys(keys, d.actualRemainders().keySet());
            });
            x.completedEvidence().ifPresent(e -> {
                collectCommand(Optional.of(e.command()), keys);
                addKeys(keys, e.actualOutputs().keySet());
                addKeys(keys, e.actualRemainders().keySet());
            });
            x.transferDiscrepancy().ifPresent(d -> {
                addKey(keys, d.key());
                addKeys(keys, d.knownCustody().keySet());
            });
        });
        c.failedHandoff().ifPresent(x -> addKeys(keys, x.reservedDebits().keySet()));
    }

    private static void collectCommand(Optional<ExactWorkCommand> command, Set<KeyId> keys) {
        command.ifPresent(x -> {
            for (PlannedInputSelection s : x.plannedSelections()) {
                addKey(keys, s.consumedKey());
                s.remainderReturn().ifPresent(r -> addKey(keys, r.key()));
            }
            addKeys(keys, x.custodyInputs().keySet());
            for (ExactWorkOutput o : x.expectedOutputSlots())
                addKey(keys, o.key());
            addKeys(keys, x.expectedOutputs().keySet());
            addKeys(keys, x.expectedRemainders().keySet());
        });
    }

    private static void addKeys(Set<KeyId> target, Iterable<KeyId> source) {
        for (KeyId key : source) {
            addKey(target, key);
        }
    }

    private static void addKey(Set<KeyId> target, KeyId key) {
        if (!target.contains(key) && target.size() >= PersistenceLimits.MAX_KEYS)
            throw new IllegalArgumentException("Recovery checkpoint key closure exceeds persistence bounds");
        target.add(key);
    }

    private static final class RefTracker {
        private final Set<KeyId> ids;

        private RefTracker(Set<KeyId> planIds) {
            ids = new HashSet<>(planIds);
        }
    }

    private static final class Bad extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class DecodeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final PersistenceDecodeResult.Reason reason;

        private DecodeFailure(PersistenceDecodeResult.Reason reason) {
            this.reason = reason;
        }
    }
}
