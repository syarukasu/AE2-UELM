package appeng.rebuild.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.rebuild.execution.CycleExecutionManifest;
import appeng.rebuild.execution.ExactCraftingPlan;
import appeng.rebuild.execution.ExactPlanId;
import appeng.rebuild.execution.ExecutionManifest;
import appeng.rebuild.execution.NormalExecutionManifest;
import appeng.rebuild.execution.SealedPatternExecution;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.CompiledRemainderSpec;
import appeng.rebuild.pattern.GraphGeneration;
import appeng.rebuild.pattern.MachineIntent;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternKind;
import appeng.rebuild.pattern.PatternLimits;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.rebuild.pattern.SubstitutionPolicy;
import appeng.rebuild.planner.DependencySet;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.planner.GridRevision;
import appeng.rebuild.planner.PlannedBatchCause;
import appeng.rebuild.planner.PlannedBatchId;
import appeng.rebuild.planner.PlannedCausalStep;
import appeng.rebuild.planner.PlannedCycleBatch;
import appeng.rebuild.planner.PlannedCycleLink;
import appeng.rebuild.planner.PlannedCycleMember;
import appeng.rebuild.planner.PlannedCycleOutput;
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.planner.PlannedPatternBatch;
import appeng.rebuild.planner.PlannedRemainderReturn;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.storage.StorageRevision;

/** Strict, versioned, whole-closure persistence for sealed exact crafting plans. */
public final class ExactCraftingPlanNbtCodec {
    private static final String VERSION = "v";
    private static final String KEYS = "k";
    private static final String ID = "i";
    private static final String PLANNING = "p";
    private static final String VALIDATION = "q";
    private static final String REQUEST = "r";
    private static final String STEPS = "s";
    private static final String MANIFESTS = "m";
    private static final String USED = "u";
    private static final String EXECUTIONS = "x";
    private static final String DEBITS = "d";
    private static final String SURPLUS = "f";
    private static final String DEPENDENCIES = "z";

    private ExactCraftingPlanNbtCodec() {
    }

    /** Package aggregate-checkpoint seam: strict plan payload without an embedded key table. */
    static CompoundTag encodePayload(ExactCraftingPlan plan) {
        if (plan == null)
            throw new IllegalArgumentException("plan is required");
        CompoundTag root = new CompoundTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        if (!budget.reserve(128, 13))
            throw new IllegalArgumentException("Plan payload header exceeds persistence limits");
        root.putInt(VERSION, PersistenceLimits.FORMAT_VERSION);
        putBounded(root, ID, uuid(plan.planId().value()), budget);
        putBounded(root, PLANNING, revision(plan.planningRevision()), budget);
        putBounded(root, VALIDATION, revision(plan.validationRevision()), budget);
        putBounded(root, REQUEST, request(plan.request()), budget);
        putBounded(root, STEPS, list(plan.causalSteps(), ExactCraftingPlanNbtCodec::step), budget);
        putBounded(root, MANIFESTS, list(plan.executionManifests(), ExactCraftingPlanNbtCodec::manifest), budget);
        putBounded(root, USED, patternRevisionMap(plan.usedPatternRevisions()), budget);
        putBounded(root, EXECUTIONS, patternAmountMap(plan.patternExecutions()), budget);
        putBounded(root, DEBITS, keyAmountMap(plan.initialStorageDebits()), budget);
        putBounded(root, SURPLUS, keyAmountMap(plan.finalSurplus()), budget);
        putBounded(root, DEPENDENCIES, dependencies(plan.dependencies()), budget);
        requireValid(root);
        return root;
    }

    /** Package aggregate-checkpoint seam: no registry mutation; caller commits the returned shared remap last. */
    static PersistenceDecodeResult<ProspectivePlan> decodeProspectivePayload(CompoundTag payload,
            PersistedKeyTable outerTable, KeyRegistry current) {
        if (payload == null || outerTable == null || current == null || !StrictNbt.valid(payload))
            return malformed();
        if (!shape(payload, 12, VERSION, Tag.TAG_INT, ID, Tag.TAG_COMPOUND, PLANNING, Tag.TAG_COMPOUND,
                VALIDATION, Tag.TAG_COMPOUND, REQUEST, Tag.TAG_COMPOUND, STEPS, Tag.TAG_LIST, MANIFESTS,
                Tag.TAG_LIST, USED, Tag.TAG_COMPOUND, EXECUTIONS, Tag.TAG_COMPOUND, DEBITS, Tag.TAG_COMPOUND,
                SURPLUS, Tag.TAG_COMPOUND, DEPENDENCIES, Tag.TAG_COMPOUND))
            return malformed();
        if (payload.getInt(VERSION) != PersistenceLimits.FORMAT_VERSION)
            return unsupported();
        PersistenceDecodeResult<OldPlan> decoded = oldPlan(payload);
        if (!(decoded instanceof PersistenceDecodeResult.Success<OldPlan> success))
            return propagate(decoded);
        OldPlan old = success.value();
        if (old.planning.keyRegistryGeneration() != outerTable.generation()
                || old.validation.keyRegistryGeneration() != outerTable.generation()
                || old.dependencies.gridRevision().keyRegistryGeneration() != outerTable.generation())
            return generation();
        if (!ids(outerTable.entries()).containsAll(old.keyIds()))
            return malformed();
        try {
            ExactKeyRemap remap = KeyTableRebinder.prospective(outerTable, current);
            return new PersistenceDecodeResult.Success<>(new ProspectivePlan(old.rebind(remap), remap));
        } catch (RuntimeException failure) {
            return malformed();
        }
    }

    record ProspectivePlan(ExactCraftingPlan plan, ExactKeyRemap remap) {
    }

    /** Encodes all authority-free sealed data, including the exact sparse key closure. */
    public static CompoundTag encode(ExactCraftingPlan plan, KeyRegistry registry) {
        if (plan == null || registry == null)
            throw new IllegalArgumentException("plan and registry are required");
        if (plan.planningRevision().keyRegistryGeneration() != registry.generation()
                || plan.validationRevision().keyRegistryGeneration() != registry.generation()
                || plan.dependencies().gridRevision().keyRegistryGeneration() != registry.generation())
            throw new IllegalArgumentException("Plan is not scoped to the supplied key registry");
        Set<KeyId> usedKeys = collectKeys(plan);
        PersistedKeyTable keys = PersistedKeyTable.capture(registry, usedKeys);
        CompoundTag root = new CompoundTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        if (!budget.reserve(128, 14))
            throw new IllegalArgumentException("Plan checkpoint header exceeds persistence limits");
        root.putInt(VERSION, PersistenceLimits.FORMAT_VERSION);
        putBounded(root, KEYS, keys.encode(), budget);
        putBounded(root, ID, uuid(plan.planId().value()), budget);
        putBounded(root, PLANNING, revision(plan.planningRevision()), budget);
        putBounded(root, VALIDATION, revision(plan.validationRevision()), budget);
        putBounded(root, REQUEST, request(plan.request()), budget);
        putBounded(root, STEPS, list(plan.causalSteps(), ExactCraftingPlanNbtCodec::step), budget);
        putBounded(root, MANIFESTS, list(plan.executionManifests(), ExactCraftingPlanNbtCodec::manifest), budget);
        putBounded(root, USED, patternRevisionMap(plan.usedPatternRevisions()), budget);
        putBounded(root, EXECUTIONS, patternAmountMap(plan.patternExecutions()), budget);
        putBounded(root, DEBITS, keyAmountMap(plan.initialStorageDebits()), budget);
        putBounded(root, SURPLUS, keyAmountMap(plan.finalSurplus()), budget);
        putBounded(root, DEPENDENCIES, dependencies(plan.dependencies()), budget);
        requireValid(root);
        return root;
    }

    private static void putBounded(CompoundTag root, String name, Tag value, StrictNbt.Budget budget) {
        if (!budget.add(value))
            throw new IllegalArgumentException("Whole plan checkpoint exceeds persistence limits");
        root.put(name, value);
    }

    /**
     * Decodes and validates the entire old-id closure before rebinding or interning any current registry identity.
     */
    public static PersistenceDecodeResult<ExactCraftingPlan> decode(CompoundTag root, KeyRegistry current) {
        if (current == null)
            return malformed();
        try {
            if (root == null)
                return malformed();
            if (!StrictNbt.valid(root))
                return limited();
            if (!shape(root, 13, VERSION, Tag.TAG_INT, KEYS, Tag.TAG_COMPOUND, ID, Tag.TAG_COMPOUND, PLANNING,
                    Tag.TAG_COMPOUND, VALIDATION, Tag.TAG_COMPOUND, REQUEST, Tag.TAG_COMPOUND, STEPS, Tag.TAG_LIST,
                    MANIFESTS, Tag.TAG_LIST, USED, Tag.TAG_COMPOUND, EXECUTIONS, Tag.TAG_COMPOUND, DEBITS,
                    Tag.TAG_COMPOUND, SURPLUS, Tag.TAG_COMPOUND, DEPENDENCIES, Tag.TAG_COMPOUND))
                return malformed();
            if (root.getInt(VERSION) != PersistenceLimits.FORMAT_VERSION)
                return unsupported();
            PersistenceDecodeResult<PersistedKeyTable> table = PersistedKeyTable.decode(root.getCompound(KEYS));
            if (!(table instanceof PersistenceDecodeResult.Success<PersistedKeyTable> tableSuccess))
                return propagate(table);
            PersistenceDecodeResult<OldPlan> decoded = oldPlan(root);
            if (!(decoded instanceof PersistenceDecodeResult.Success<OldPlan> success))
                return propagate(decoded);
            OldPlan old = success.value();
            if (old.planning.keyRegistryGeneration() != tableSuccess.value().generation()
                    || old.validation.keyRegistryGeneration() != tableSuccess.value().generation()
                    || old.dependencies.gridRevision().keyRegistryGeneration() != tableSuccess.value().generation())
                return generation();
            if (!old.keyIds().equals(ids(tableSuccess.value().entries())))
                return malformed();
            // Fully construct and validate the prospective current-generation plan before the first registry intern.
            ExactKeyRemap remap = KeyTableRebinder.prospective(tableSuccess.value(), current);
            ExactCraftingPlan restored = old.rebind(remap);
            KeyTableRebinder.commit(tableSuccess.value(), current, remap);
            return new PersistenceDecodeResult.Success<>(restored);
        } catch (RuntimeException failure) {
            return malformed();
        }
    }

    private static PersistenceDecodeResult<OldPlan> oldPlan(CompoundTag root) {
        PersistenceDecodeResult<UUID> id = uuid(root.getCompound(ID));
        PersistenceDecodeResult<GridRevision> planning = revision(root.getCompound(PLANNING));
        PersistenceDecodeResult<GridRevision> validation = revision(root.getCompound(VALIDATION));
        PersistenceDecodeResult<ExactCraftRequest> request = request(root.getCompound(REQUEST));
        PersistenceDecodeResult<List<PlannedCausalStep>> steps = compoundList(root, STEPS,
                PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS,
                ExactCraftingPlanNbtCodec::step);
        PersistenceDecodeResult<List<ExecutionManifest>> manifests = compoundList(root, MANIFESTS,
                PlannerLimits.MAX_CRAFT_SEARCH_DECISIONS, ExactCraftingPlanNbtCodec::manifest);
        PersistenceDecodeResult<Map<PatternId, PatternRevision>> used = patternRevisionMap(root.getCompound(USED));
        PersistenceDecodeResult<Map<PatternId, AEAmount>> executions = patternAmountMap(root.getCompound(EXECUTIONS));
        PersistenceDecodeResult<Map<KeyId, AEAmount>> debits = keyAmountMap(root.getCompound(DEBITS));
        PersistenceDecodeResult<Map<KeyId, AEAmount>> surplus = keyAmountMap(root.getCompound(SURPLUS));
        PersistenceDecodeResult<DependencySet> dependencies = dependencies(root.getCompound(DEPENDENCIES));
        if (!(id instanceof PersistenceDecodeResult.Success<UUID> a)
                || !(planning instanceof PersistenceDecodeResult.Success<GridRevision> b)
                || !(validation instanceof PersistenceDecodeResult.Success<GridRevision> c)
                || !(request instanceof PersistenceDecodeResult.Success<ExactCraftRequest> d)
                || !(steps instanceof PersistenceDecodeResult.Success<List<PlannedCausalStep>> e)
                || !(manifests instanceof PersistenceDecodeResult.Success<List<ExecutionManifest>> f)
                || !(used instanceof PersistenceDecodeResult.Success<Map<PatternId, PatternRevision>> g)
                || !(executions instanceof PersistenceDecodeResult.Success<Map<PatternId, AEAmount>> h)
                || !(debits instanceof PersistenceDecodeResult.Success<Map<KeyId, AEAmount>> i)
                || !(surplus instanceof PersistenceDecodeResult.Success<Map<KeyId, AEAmount>> j)
                || !(dependencies instanceof PersistenceDecodeResult.Success<DependencySet> k))
            return firstFailure(id, planning, validation, request, steps, manifests, used, executions, debits, surplus,
                    dependencies);
        try {
            return new PersistenceDecodeResult.Success<>(new OldPlan(new ExactPlanId(a.value()), b.value(), c.value(),
                    d.value(), e.value(), f.value(), g.value(), h.value(), i.value(), j.value(), k.value()));
        } catch (RuntimeException failure) {
            return malformed();
        }
    }

    private static CompoundTag revision(GridRevision value) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("s", value.serverGeneration());
        tag.putLong("k", value.keyRegistryGeneration());
        tag.putLong("g", value.graphGeneration().value());
        tag.putLong("r", value.recipeRevision().value());
        tag.putLong("t", value.storageRevision().value());
        return tag;
    }

    private static PersistenceDecodeResult<GridRevision> revision(CompoundTag tag) {
        if (!shape(tag, 5, "s", Tag.TAG_LONG, "k", Tag.TAG_LONG, "g", Tag.TAG_LONG, "r", Tag.TAG_LONG, "t",
                Tag.TAG_LONG))
            return malformed();
        try {
            return new PersistenceDecodeResult.Success<>(
                    new GridRevision(tag.getLong("s"), tag.getLong("k"), new GraphGeneration(tag.getLong("g")),
                            new RecipeRevision(tag.getLong("r")), new StorageRevision(tag.getLong("t"))));
        } catch (RuntimeException x) {
            return malformed();
        }
    }

    private static CompoundTag uuid(UUID value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("u", PersistenceNbtCodec.encodeString(value.toString()));
        return tag;
    }

    private static PersistenceDecodeResult<UUID> uuid(CompoundTag tag) {
        if (!shape(tag, 1, "u", Tag.TAG_STRING))
            return malformed();
        return PersistenceNbtCodec.decodeUuid(tag, "u");
    }

    private static CompoundTag request(ExactCraftRequest value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("k", value.output().value());
        tag.put("a", amount(value.amount()));
        return tag;
    }

    private static PersistenceDecodeResult<ExactCraftRequest> request(CompoundTag tag) {
        if (!shape(tag, 2, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND))
            return malformed();
        PersistenceDecodeResult<AEAmount> amount = amount(tag.getCompound("a"));
        if (!(amount instanceof PersistenceDecodeResult.Success<AEAmount> value) || value.value().equals(AEAmount.ZERO))
            return propagate(amount);
        try {
            return new PersistenceDecodeResult.Success<>(
                    new ExactCraftRequest(new KeyId(tag.getInt("k")), value.value()));
        } catch (RuntimeException x) {
            return malformed();
        }
    }

    private static CompoundTag amount(AEAmount value) {
        return AEAmountNbtCodec.encode(value);
    }

    private static PersistenceDecodeResult<AEAmount> amount(CompoundTag tag) {
        return AEAmountNbtCodec.decode(tag);
    }

    private static CompoundTag step(PlannedCausalStep value) {
        CompoundTag tag = base(value.id(), value.cause());
        if (value instanceof PlannedPatternBatch normal) {
            tag.putString("t", "n");
            tag.putString("p", normal.patternId().value());
            tag.put("e", amount(normal.executions()));
            tag.put("n", list(normal.inputs(), ExactCraftingPlanNbtCodec::selection));
        } else {
            PlannedCycleBatch cycle = (PlannedCycleBatch) value;
            tag.putString("t", "c");
            tag.put("e", amount(cycle.repetitions()));
            tag.putInt("k", cycle.seedKey().value());
            tag.put("a", amount(cycle.seedAmount()));
            tag.put("n", list(cycle.members(), ExactCraftingPlanNbtCodec::member));
            tag.put("l", list(cycle.links(), ExactCraftingPlanNbtCodec::link));
            tag.put("f", keyAmountMap(cycle.finalCredits()));
        }
        return tag;
    }

    private static PersistenceDecodeResult<PlannedCausalStep> step(Tag raw) {
        if (!(raw instanceof CompoundTag tag) || !tag.contains("t", Tag.TAG_STRING))
            return malformed();
        PersistenceDecodeResult<Header> header = header(tag);
        if (!(header instanceof PersistenceDecodeResult.Success<Header> h))
            return propagate(header);
        try {
            if (tag.getString("t").equals("n")) {
                if (!shape(tag, 6, "i", Tag.TAG_LONG, "c", Tag.TAG_COMPOUND, "t", Tag.TAG_STRING, "p", Tag.TAG_STRING,
                        "e", Tag.TAG_COMPOUND, "n", Tag.TAG_LIST))
                    return malformed();
                PersistenceDecodeResult<AEAmount> e = amount(tag.getCompound("e"));
                PersistenceDecodeResult<List<PlannedInputSelection>> n = compoundList(tag, "n",
                        PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN, ExactCraftingPlanNbtCodec::selection);
                if (!(e instanceof PersistenceDecodeResult.Success<AEAmount> amount)
                        || !(n instanceof PersistenceDecodeResult.Success<List<PlannedInputSelection>> inputs))
                    return firstFailure(e, n);
                return new PersistenceDecodeResult.Success<>(new PlannedPatternBatch(h.value().id, h.value().cause,
                        new PatternId(tag.getString("p")), amount.value(), inputs.value()));
            }
            if (tag.getString("t").equals("c")) {
                if (!shape(tag, 9, "i", Tag.TAG_LONG, "c", Tag.TAG_COMPOUND, "t", Tag.TAG_STRING, "e", Tag.TAG_COMPOUND,
                        "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND, "n", Tag.TAG_LIST, "l", Tag.TAG_LIST, "f",
                        Tag.TAG_COMPOUND))
                    return malformed();
                PersistenceDecodeResult<AEAmount> e = amount(tag.getCompound("e")), a = amount(tag.getCompound("a"));
                PersistenceDecodeResult<List<PlannedCycleMember>> n = compoundList(tag, "n",
                        PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS, ExactCraftingPlanNbtCodec::member);
                PersistenceDecodeResult<List<PlannedCycleLink>> l = compoundList(tag, "l",
                        PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS, ExactCraftingPlanNbtCodec::link);
                PersistenceDecodeResult<Map<KeyId, AEAmount>> f = keyAmountMap(tag.getCompound("f"));
                if (!(e instanceof PersistenceDecodeResult.Success<AEAmount> repetitions)
                        || !(a instanceof PersistenceDecodeResult.Success<AEAmount> seed)
                        || !(n instanceof PersistenceDecodeResult.Success<List<PlannedCycleMember>> members)
                        || !(l instanceof PersistenceDecodeResult.Success<List<PlannedCycleLink>> links)
                        || !(f instanceof PersistenceDecodeResult.Success<Map<KeyId, AEAmount>> credits))
                    return firstFailure(e, a, n, l, f);
                return new PersistenceDecodeResult.Success<>(new PlannedCycleBatch(h.value().id, h.value().cause,
                        repetitions.value(), new KeyId(tag.getInt("k")), seed.value(), members.value(), links.value(),
                        credits.value()));
            }
        } catch (RuntimeException x) {
            return malformed();
        }
        return malformed();
    }

    private static CompoundTag base(PlannedBatchId id, PlannedBatchCause cause) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("i", id.value());
        tag.put("c", cause(cause));
        return tag;
    }

    private static PersistenceDecodeResult<Header> header(CompoundTag tag) {
        if (!tag.contains("i", Tag.TAG_LONG) || !tag.contains("c", Tag.TAG_COMPOUND))
            return malformed();
        PersistenceDecodeResult<PlannedBatchCause> cause = cause(tag.getCompound("c"));
        if (!(cause instanceof PersistenceDecodeResult.Success<PlannedBatchCause> c))
            return propagate(cause);
        try {
            return new PersistenceDecodeResult.Success<>(new Header(new PlannedBatchId(tag.getLong("i")), c.value()));
        } catch (RuntimeException x) {
            return malformed();
        }
    }

    private static CompoundTag cause(PlannedBatchCause value) {
        CompoundTag tag = new CompoundTag();
        if (value instanceof PlannedBatchCause.Root root) {
            tag.putString("t", "r");
            tag.putInt("k", root.output().value());
            tag.put("a", amount(root.demandedAmount()));
        } else {
            PlannedBatchCause.Input input = (PlannedBatchCause.Input) value;
            tag.putString("t", "i");
            tag.putLong("b", input.consumerBatchId().value());
            tag.putInt("m", input.cycleMemberIndex());
            tag.putInt("n", input.inputIndex());
            tag.putInt("c", input.candidateIndex());
            tag.putInt("k", input.key().value());
            tag.put("a", amount(input.demandedAmount()));
        }
        return tag;
    }

    private static PersistenceDecodeResult<PlannedBatchCause> cause(CompoundTag tag) {
        if (tag == null || !tag.contains("t", Tag.TAG_STRING))
            return malformed();
        try {
            if (tag.getString("t").equals("r")) {
                if (!shape(tag, 3, "t", Tag.TAG_STRING, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND))
                    return malformed();
                PersistenceDecodeResult<AEAmount> a = amount(tag.getCompound("a"));
                if (!(a instanceof PersistenceDecodeResult.Success<AEAmount> x))
                    return propagate(a);
                return new PersistenceDecodeResult.Success<>(
                        new PlannedBatchCause.Root(new KeyId(tag.getInt("k")), x.value()));
            }
            if (tag.getString("t").equals("i")) {
                if (!shape(tag, 7, "t", Tag.TAG_STRING, "b", Tag.TAG_LONG, "m", Tag.TAG_INT, "n", Tag.TAG_INT, "c",
                        Tag.TAG_INT, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND))
                    return malformed();
                PersistenceDecodeResult<AEAmount> a = amount(tag.getCompound("a"));
                if (!(a instanceof PersistenceDecodeResult.Success<AEAmount> x))
                    return propagate(a);
                return new PersistenceDecodeResult.Success<>(
                        new PlannedBatchCause.Input(new PlannedBatchId(tag.getLong("b")), tag.getInt("m"),
                                tag.getInt("n"), tag.getInt("c"), new KeyId(tag.getInt("k")), x.value()));
            }
        } catch (RuntimeException x) {
            return malformed();
        }
        return malformed();
    }

    private static CompoundTag selection(PlannedInputSelection value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("i", value.inputIndex());
        tag.putInt("c", value.candidateIndex());
        tag.put("u", amount(value.templateUnits()));
        tag.putInt("k", value.consumedKey().value());
        tag.put("g", amount(value.grossConsumedAmount()));
        tag.put("a", amount(value.initialRequiredAmount()));
        value.remainderReturn().ifPresent(r -> tag.put("r", remainder(r)));
        return tag;
    }

    private static PersistenceDecodeResult<PlannedInputSelection> selection(Tag raw) {
        if (!(raw instanceof CompoundTag tag) || (tag.size() != 6 && tag.size() != 7) || !tag.contains("i", Tag.TAG_INT)
                || !tag.contains("c", Tag.TAG_INT) || !tag.contains("u", Tag.TAG_COMPOUND)
                || !tag.contains("k", Tag.TAG_INT) || !tag.contains("g", Tag.TAG_COMPOUND)
                || !tag.contains("a", Tag.TAG_COMPOUND) || (tag.size() == 7 && !tag.contains("r", Tag.TAG_COMPOUND)))
            return malformed();
        PersistenceDecodeResult<AEAmount> u = amount(tag.getCompound("u")), g = amount(tag.getCompound("g")),
                a = amount(tag.getCompound("a"));
        PersistenceDecodeResult<Optional<PlannedRemainderReturn>> r = tag.contains("r")
                ? optionalRemainder(tag.getCompound("r"))
                : new PersistenceDecodeResult.Success<>(Optional.empty());
        if (!(u instanceof PersistenceDecodeResult.Success<AEAmount> uu)
                || !(g instanceof PersistenceDecodeResult.Success<AEAmount> gg)
                || !(a instanceof PersistenceDecodeResult.Success<AEAmount> aa)
                || !(r instanceof PersistenceDecodeResult.Success<Optional<PlannedRemainderReturn>> rr))
            return firstFailure(u, g, a, r);
        try {
            return new PersistenceDecodeResult.Success<>(new PlannedInputSelection(tag.getInt("i"), tag.getInt("c"),
                    uu.value(), new KeyId(tag.getInt("k")), gg.value(), aa.value(), rr.value()));
        } catch (RuntimeException x) {
            return malformed();
        }
    }

    private static CompoundTag remainder(PlannedRemainderReturn value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("k", value.key().value());
        tag.put("a", amount(value.amount()));
        return tag;
    }

    private static PersistenceDecodeResult<Optional<PlannedRemainderReturn>> optionalRemainder(CompoundTag tag) {
        if (!shape(tag, 2, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND))
            return malformed();
        PersistenceDecodeResult<AEAmount> a = amount(tag.getCompound("a"));
        if (!(a instanceof PersistenceDecodeResult.Success<AEAmount> x))
            return propagate(a);
        try {
            return new PersistenceDecodeResult.Success<>(
                    Optional.of(new PlannedRemainderReturn(new KeyId(tag.getInt("k")), x.value())));
        } catch (RuntimeException failure) {
            return malformed();
        }
    }

    private static CompoundTag output(PlannedCycleOutput value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("i", value.outputIndex());
        tag.putInt("k", value.key().value());
        tag.put("a", amount(value.amountPerTurn()));
        return tag;
    }

    private static PersistenceDecodeResult<PlannedCycleOutput> output(Tag raw) {
        if (!(raw instanceof CompoundTag tag)
                || !shape(tag, 3, "i", Tag.TAG_INT, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND))
            return malformed();
        PersistenceDecodeResult<AEAmount> a = amount(tag.getCompound("a"));
        if (!(a instanceof PersistenceDecodeResult.Success<AEAmount> x))
            return propagate(a);
        try {
            return new PersistenceDecodeResult.Success<>(
                    new PlannedCycleOutput(tag.getInt("i"), new KeyId(tag.getInt("k")), x.value()));
        } catch (RuntimeException failure) {
            return malformed();
        }
    }

    private static CompoundTag member(PlannedCycleMember value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("p", value.patternId().value());
        tag.put("e", amount(value.executionsPerTurn()));
        tag.put("i", list(value.inputsPerTurn(), ExactCraftingPlanNbtCodec::selection));
        tag.put("o", list(value.outputsPerTurn(), ExactCraftingPlanNbtCodec::output));
        return tag;
    }

    private static PersistenceDecodeResult<PlannedCycleMember> member(Tag raw) {
        if (!(raw instanceof CompoundTag tag)
                || !shape(tag, 4, "p", Tag.TAG_STRING, "e", Tag.TAG_COMPOUND, "i", Tag.TAG_LIST, "o", Tag.TAG_LIST))
            return malformed();
        PersistenceDecodeResult<AEAmount> e = amount(tag.getCompound("e"));
        PersistenceDecodeResult<List<PlannedInputSelection>> i = compoundList(tag, "i",
                PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN, ExactCraftingPlanNbtCodec::selection);
        PersistenceDecodeResult<List<PlannedCycleOutput>> o = compoundList(tag, "o", PatternLimits.MAX_OUTPUTS,
                ExactCraftingPlanNbtCodec::output);
        if (!(e instanceof PersistenceDecodeResult.Success<AEAmount> ee)
                || !(i instanceof PersistenceDecodeResult.Success<List<PlannedInputSelection>> ii)
                || !(o instanceof PersistenceDecodeResult.Success<List<PlannedCycleOutput>> oo))
            return firstFailure(e, i, o);
        try {
            return new PersistenceDecodeResult.Success<>(
                    new PlannedCycleMember(new PatternId(tag.getString("p")), ee.value(), ii.value(), oo.value()));
        } catch (RuntimeException x) {
            return malformed();
        }
    }

    private static CompoundTag link(PlannedCycleLink value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("p", value.producerMemberIndex());
        tag.putInt("o", value.outputIndex());
        tag.putInt("c", value.consumerMemberIndex());
        tag.putInt("i", value.inputIndex());
        tag.putInt("n", value.candidateIndex());
        tag.putInt("k", value.key().value());
        tag.put("a", amount(value.amountPerTurn()));
        return tag;
    }

    private static PersistenceDecodeResult<PlannedCycleLink> link(Tag raw) {
        if (!(raw instanceof CompoundTag tag) || !shape(tag, 7, "p", Tag.TAG_INT, "o", Tag.TAG_INT, "c", Tag.TAG_INT,
                "i", Tag.TAG_INT, "n", Tag.TAG_INT, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND))
            return malformed();
        PersistenceDecodeResult<AEAmount> a = amount(tag.getCompound("a"));
        if (!(a instanceof PersistenceDecodeResult.Success<AEAmount> x))
            return propagate(a);
        try {
            return new PersistenceDecodeResult.Success<>(new PlannedCycleLink(tag.getInt("p"), tag.getInt("o"),
                    tag.getInt("c"), tag.getInt("i"), tag.getInt("n"), new KeyId(tag.getInt("k")), x.value()));
        } catch (RuntimeException failure) {
            return malformed();
        }
    }

    private static CompoundTag manifest(ExecutionManifest value) {
        CompoundTag tag = base(value.batchId(), value.cause());
        if (value instanceof NormalExecutionManifest n) {
            tag.putString("t", "n");
            tag.put("e", sealed(n.execution()));
        } else {
            CycleExecutionManifest c = (CycleExecutionManifest) value;
            tag.putString("t", "c");
            tag.put("r", amount(c.repetitions()));
            tag.putInt("k", c.seedKey().value());
            tag.put("a", amount(c.seedAmount()));
            tag.put("e", list(c.memberExecutions(), ExactCraftingPlanNbtCodec::sealed));
            tag.put("l", list(c.links(), ExactCraftingPlanNbtCodec::link));
            tag.put("f", keyAmountMap(c.finalCredits()));
        }
        return tag;
    }

    private static PersistenceDecodeResult<ExecutionManifest> manifest(Tag raw) {
        if (!(raw instanceof CompoundTag tag) || !tag.contains("t", Tag.TAG_STRING))
            return malformed();
        PersistenceDecodeResult<Header> h = header(tag);
        if (!(h instanceof PersistenceDecodeResult.Success<Header> x))
            return propagate(h);
        try {
            if (tag.getString("t").equals("n")) {
                if (!shape(tag, 4, "i", Tag.TAG_LONG, "c", Tag.TAG_COMPOUND, "t", Tag.TAG_STRING, "e",
                        Tag.TAG_COMPOUND))
                    return malformed();
                PersistenceDecodeResult<SealedPatternExecution> e = sealed(tag.getCompound("e"));
                if (!(e instanceof PersistenceDecodeResult.Success<SealedPatternExecution> ee))
                    return propagate(e);
                return new PersistenceDecodeResult.Success<>(
                        new NormalExecutionManifest(x.value().id, x.value().cause, ee.value()));
            }
            if (tag.getString("t").equals("c")) {
                if (!shape(tag, 9, "i", Tag.TAG_LONG, "c", Tag.TAG_COMPOUND, "t", Tag.TAG_STRING, "r", Tag.TAG_COMPOUND,
                        "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND, "e", Tag.TAG_LIST, "l", Tag.TAG_LIST, "f",
                        Tag.TAG_COMPOUND))
                    return malformed();
                PersistenceDecodeResult<AEAmount> r = amount(tag.getCompound("r")), a = amount(tag.getCompound("a"));
                PersistenceDecodeResult<List<SealedPatternExecution>> e = compoundList(tag, "e",
                        PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS, ExactCraftingPlanNbtCodec::sealed);
                PersistenceDecodeResult<List<PlannedCycleLink>> l = compoundList(tag, "l",
                        PlannerLimits.MAX_PRODUCTIVE_CYCLE_MEMBERS, ExactCraftingPlanNbtCodec::link);
                PersistenceDecodeResult<Map<KeyId, AEAmount>> f = keyAmountMap(tag.getCompound("f"));
                if (!(r instanceof PersistenceDecodeResult.Success<AEAmount> rr)
                        || !(a instanceof PersistenceDecodeResult.Success<AEAmount> aa)
                        || !(e instanceof PersistenceDecodeResult.Success<List<SealedPatternExecution>> ee)
                        || !(l instanceof PersistenceDecodeResult.Success<List<PlannedCycleLink>> ll)
                        || !(f instanceof PersistenceDecodeResult.Success<Map<KeyId, AEAmount>> ff))
                    return firstFailure(r, a, e, l, f);
                return new PersistenceDecodeResult.Success<>(new CycleExecutionManifest(x.value().id, x.value().cause,
                        rr.value(), new KeyId(tag.getInt("k")), aa.value(), ee.value(), ll.value(), ff.value()));
            }
        } catch (RuntimeException q) {
            return malformed();
        }
        return malformed();
    }

    private static CompoundTag sealed(SealedPatternExecution value) {
        CompoundTag tag = new CompoundTag();
        tag.put("p", pattern(value.pattern()));
        tag.put("e", amount(value.executions()));
        tag.put("s", list(value.plannedSelections(), ExactCraftingPlanNbtCodec::selection));
        tag.put("o", list(value.plannedOutputs(), ExactCraftingPlanNbtCodec::output));
        return tag;
    }

    private static PersistenceDecodeResult<SealedPatternExecution> sealed(Tag raw) {
        if (!(raw instanceof CompoundTag tag)
                || !shape(tag, 4, "p", Tag.TAG_COMPOUND, "e", Tag.TAG_COMPOUND, "s", Tag.TAG_LIST, "o", Tag.TAG_LIST))
            return malformed();
        PersistenceDecodeResult<CompiledPattern> p = pattern(tag.getCompound("p"));
        PersistenceDecodeResult<AEAmount> e = amount(tag.getCompound("e"));
        PersistenceDecodeResult<List<PlannedInputSelection>> s = compoundList(tag, "s",
                PatternLimits.MAX_TOTAL_CANDIDATES_PER_PATTERN, ExactCraftingPlanNbtCodec::selection);
        PersistenceDecodeResult<List<PlannedCycleOutput>> o = compoundList(tag, "o", PatternLimits.MAX_OUTPUTS,
                ExactCraftingPlanNbtCodec::output);
        if (!(p instanceof PersistenceDecodeResult.Success<CompiledPattern> pp)
                || !(e instanceof PersistenceDecodeResult.Success<AEAmount> ee)
                || !(s instanceof PersistenceDecodeResult.Success<List<PlannedInputSelection>> ss)
                || !(o instanceof PersistenceDecodeResult.Success<List<PlannedCycleOutput>> oo))
            return firstFailure(p, e, s, o);
        try {
            return new PersistenceDecodeResult.Success<>(
                    new SealedPatternExecution(pp.value(), ee.value(), ss.value(), oo.value()));
        } catch (RuntimeException x) {
            return malformed();
        }
    }

    private static CompoundTag pattern(CompiledPattern value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("i", value.id().value());
        tag.putString("k", value.kind().name());
        tag.put("n", list(value.inputs(), ExactCraftingPlanNbtCodec::input));
        tag.put("o", list(value.outputs(), ExactCraftingPlanNbtCodec::compiledOutput));
        value.machineIntent().ifPresent(m -> tag.put("m", machine(m)));
        tag.putLong("r", value.revision().value());
        tag.putLong("g", value.keyRegistryGeneration());
        return tag;
    }

    private static PersistenceDecodeResult<CompiledPattern> pattern(CompoundTag tag) {
        if (tag == null || (tag.size() != 6 && tag.size() != 7) || !tag.contains("i", Tag.TAG_STRING)
                || !tag.contains("k", Tag.TAG_STRING) || !tag.contains("n", Tag.TAG_LIST)
                || !tag.contains("o", Tag.TAG_LIST) || !tag.contains("r", Tag.TAG_LONG)
                || !tag.contains("g", Tag.TAG_LONG) || (tag.size() == 7 && !tag.contains("m", Tag.TAG_COMPOUND)))
            return malformed();
        PersistenceDecodeResult<List<CompiledInputSpec>> n = compoundList(tag, "n", PatternLimits.MAX_INPUT_GROUPS,
                ExactCraftingPlanNbtCodec::input);
        PersistenceDecodeResult<List<CompiledOutputSpec>> o = compoundList(tag, "o", PatternLimits.MAX_OUTPUTS,
                ExactCraftingPlanNbtCodec::compiledOutput);
        PersistenceDecodeResult<Optional<MachineIntent>> m = tag.contains("m") ? optionalMachine(tag.getCompound("m"))
                : new PersistenceDecodeResult.Success<>(Optional.empty());
        if (!(n instanceof PersistenceDecodeResult.Success<List<CompiledInputSpec>> nn)
                || !(o instanceof PersistenceDecodeResult.Success<List<CompiledOutputSpec>> oo)
                || !(m instanceof PersistenceDecodeResult.Success<Optional<MachineIntent>> mm))
            return firstFailure(n, o, m);
        try {
            return new PersistenceDecodeResult.Success<>(new CompiledPattern(new PatternId(tag.getString("i")),
                    Enum.valueOf(PatternKind.class, tag.getString("k")), nn.value(), oo.value(), mm.value(),
                    new PatternRevision(tag.getLong("r")), tag.getLong("g")));
        } catch (RuntimeException x) {
            return malformed();
        }
    }

    private static CompoundTag input(CompiledInputSpec value) {
        CompoundTag tag = new CompoundTag();
        tag.put("c", list(value.candidates(), ExactCraftingPlanNbtCodec::candidate));
        tag.put("m", amount(value.multiplier()));
        tag.putString("p", value.policy().name());
        return tag;
    }

    private static PersistenceDecodeResult<CompiledInputSpec> input(Tag raw) {
        if (!(raw instanceof CompoundTag tag)
                || !shape(tag, 3, "c", Tag.TAG_LIST, "m", Tag.TAG_COMPOUND, "p", Tag.TAG_STRING))
            return malformed();
        PersistenceDecodeResult<List<CompiledCandidateSpec>> c = compoundList(tag, "c",
                PatternLimits.MAX_CANDIDATES_PER_INPUT, ExactCraftingPlanNbtCodec::candidate);
        PersistenceDecodeResult<AEAmount> m = amount(tag.getCompound("m"));
        if (!(c instanceof PersistenceDecodeResult.Success<List<CompiledCandidateSpec>> cc)
                || !(m instanceof PersistenceDecodeResult.Success<AEAmount> mm))
            return firstFailure(c, m);
        try {
            return new PersistenceDecodeResult.Success<>(new CompiledInputSpec(cc.value(), mm.value(),
                    Enum.valueOf(SubstitutionPolicy.class, tag.getString("p"))));
        } catch (RuntimeException x) {
            return malformed();
        }
    }

    private static CompoundTag candidate(CompiledCandidateSpec value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("k", value.key().value());
        tag.put("a", amount(value.amountPerTemplate()));
        value.remainder().ifPresent(r -> tag.put("r", compiledRemainder(r)));
        return tag;
    }

    private static PersistenceDecodeResult<CompiledCandidateSpec> candidate(Tag raw) {
        if (!(raw instanceof CompoundTag tag) || (tag.size() != 2 && tag.size() != 3) || !tag.contains("k", Tag.TAG_INT)
                || !tag.contains("a", Tag.TAG_COMPOUND) || (tag.size() == 3 && !tag.contains("r", Tag.TAG_COMPOUND)))
            return malformed();
        PersistenceDecodeResult<AEAmount> a = amount(tag.getCompound("a"));
        PersistenceDecodeResult<Optional<CompiledRemainderSpec>> r = tag.contains("r")
                ? optionalCompiledRemainder(tag.getCompound("r"))
                : new PersistenceDecodeResult.Success<>(Optional.empty());
        if (!(a instanceof PersistenceDecodeResult.Success<AEAmount> aa)
                || !(r instanceof PersistenceDecodeResult.Success<Optional<CompiledRemainderSpec>> rr))
            return firstFailure(a, r);
        try {
            return new PersistenceDecodeResult.Success<>(
                    new CompiledCandidateSpec(new KeyId(tag.getInt("k")), aa.value(), rr.value()));
        } catch (RuntimeException x) {
            return malformed();
        }
    }

    private static CompoundTag compiledRemainder(CompiledRemainderSpec value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("k", value.key().value());
        tag.put("a", amount(value.amountPerTemplate()));
        return tag;
    }

    private static PersistenceDecodeResult<Optional<CompiledRemainderSpec>> optionalCompiledRemainder(CompoundTag tag) {
        if (!shape(tag, 2, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND))
            return malformed();
        PersistenceDecodeResult<AEAmount> a = amount(tag.getCompound("a"));
        if (!(a instanceof PersistenceDecodeResult.Success<AEAmount> x))
            return propagate(a);
        try {
            return new PersistenceDecodeResult.Success<>(
                    Optional.of(new CompiledRemainderSpec(new KeyId(tag.getInt("k")), x.value())));
        } catch (RuntimeException failure) {
            return malformed();
        }
    }

    private static CompoundTag compiledOutput(CompiledOutputSpec value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("k", value.key().value());
        tag.put("a", amount(value.amountPerExecution()));
        tag.putBoolean("p", value.primary());
        return tag;
    }

    private static PersistenceDecodeResult<CompiledOutputSpec> compiledOutput(Tag raw) {
        if (!(raw instanceof CompoundTag tag)
                || !shape(tag, 3, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND, "p", Tag.TAG_BYTE))
            return malformed();
        PersistenceDecodeResult<AEAmount> a = amount(tag.getCompound("a"));
        if (!(a instanceof PersistenceDecodeResult.Success<AEAmount> x))
            return propagate(a);
        byte primary = tag.getByte("p");
        if (primary != 0 && primary != 1)
            return malformed();
        try {
            return new PersistenceDecodeResult.Success<>(
                    new CompiledOutputSpec(new KeyId(tag.getInt("k")), x.value(), primary == 1));
        } catch (RuntimeException failure) {
            return malformed();
        }
    }

    private static CompoundTag machine(MachineIntent value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("b", PersistenceNbtCodec.encodeString(value.backendType()));
        tag.putString("r", PersistenceNbtCodec.encodeString(value.recipeFingerprint()));
        tag.putString("c", PersistenceNbtCodec.encodeString(value.capabilityFingerprint()));
        CompoundTag attributes = new CompoundTag();
        value.attributes().forEach((k, v) -> attributes.putString(PersistenceNbtCodec.encodeString(k),
                PersistenceNbtCodec.encodeString(v)));
        tag.put("a", attributes);
        return tag;
    }

    private static PersistenceDecodeResult<Optional<MachineIntent>> optionalMachine(CompoundTag tag) {
        if (!shape(tag, 4, "b", Tag.TAG_STRING, "r", Tag.TAG_STRING, "c", Tag.TAG_STRING, "a", Tag.TAG_COMPOUND))
            return malformed();
        PersistenceDecodeResult<String> b = PersistenceNbtCodec.decodeString(tag, "b",
                PatternLimits.MAX_MACHINE_FIELD_LENGTH),
                r = PersistenceNbtCodec.decodeString(tag, "r", PatternLimits.MAX_MACHINE_FIELD_LENGTH),
                c = PersistenceNbtCodec.decodeString(tag, "c", PatternLimits.MAX_MACHINE_FIELD_LENGTH);
        if (!(b instanceof PersistenceDecodeResult.Success<String> bb)
                || !(r instanceof PersistenceDecodeResult.Success<String> rr)
                || !(c instanceof PersistenceDecodeResult.Success<String> cc))
            return firstFailure(b, r, c);
        CompoundTag attrs = tag.getCompound("a");
        if (attrs.size() > PatternLimits.MAX_MACHINE_ATTRIBUTES)
            return limited();
        TreeMap<String, String> map = new TreeMap<>();
        for (String key : attrs.getAllKeys()) {
            PersistenceDecodeResult<String> k = string(key, PatternLimits.MAX_MACHINE_ATTRIBUTE_KEY_LENGTH),
                    v = PersistenceNbtCodec.decodeString(attrs, key, PatternLimits.MAX_MACHINE_ATTRIBUTE_VALUE_LENGTH);
            if (!(k instanceof PersistenceDecodeResult.Success<String> kk)
                    || !(v instanceof PersistenceDecodeResult.Success<String> vv))
                return firstFailure(k, v);
            if (map.put(kk.value(), vv.value()) != null)
                return malformed();
        }
        try {
            return new PersistenceDecodeResult.Success<>(
                    Optional.of(new MachineIntent(bb.value(), rr.value(), cc.value(), map)));
        } catch (RuntimeException x) {
            return malformed();
        }
    }

    private static CompoundTag patternRevisionMap(Map<PatternId, PatternRevision> source) {
        ListTag list = new ListTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        if (!budget.reserve(16, 2))
            throw new IllegalArgumentException("Pattern revision map header exceeds persistence limits");
        source.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            CompoundTag tag = new CompoundTag();
            tag.putString("p", e.getKey().value());
            tag.putLong("r", e.getValue().value());
            if (!budget.add(tag))
                throw new IllegalArgumentException("Pattern revision map exceeds persistence limits");
            list.add(tag);
        });
        CompoundTag root = new CompoundTag();
        root.put("e", list);
        return root;
    }

    private static PersistenceDecodeResult<Map<PatternId, PatternRevision>> patternRevisionMap(CompoundTag root) {
        if (!shape(root, 1, "e", Tag.TAG_LIST))
            return malformed();
        return patternRevisionMap(rawList(root, "e"));
    }

    private static PersistenceDecodeResult<Map<PatternId, PatternRevision>> patternRevisionMap(ListTag list) {
        if (list != null && list.size() > PatternLimits.MAX_GRAPH_NODES)
            return limited();
        if (!canonicalList(list, Tag.TAG_COMPOUND, PatternLimits.MAX_GRAPH_NODES))
            return malformed();
        TreeMap<PatternId, PatternRevision> map = new TreeMap<>();
        PatternId prior = null;
        for (Tag raw : list) {
            if (!(raw instanceof CompoundTag tag) || !shape(tag, 2, "p", Tag.TAG_STRING, "r", Tag.TAG_LONG))
                return malformed();
            try {
                PatternId id = new PatternId(tag.getString("p"));
                PatternRevision revision = new PatternRevision(tag.getLong("r"));
                if (prior != null && prior.compareTo(id) >= 0 || map.put(id, revision) != null)
                    return malformed();
                prior = id;
            } catch (RuntimeException x) {
                return malformed();
            }
        }
        return new PersistenceDecodeResult.Success<>(Map.copyOf(map));
    }

    private static CompoundTag patternAmountMap(Map<PatternId, AEAmount> source) {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        if (!budget.reserve(16, 2))
            throw new IllegalArgumentException("Pattern execution map header exceeds persistence limits");
        source.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            CompoundTag tag = new CompoundTag();
            tag.putString("p", e.getKey().value());
            tag.put("a", amount(e.getValue()));
            if (!budget.add(tag))
                throw new IllegalArgumentException("Pattern execution map exceeds persistence limits");
            list.add(tag);
        });
        root.put("e", list);
        return root;
    }

    private static PersistenceDecodeResult<Map<PatternId, AEAmount>> patternAmountMap(CompoundTag root) {
        if (!shape(root, 1, "e", Tag.TAG_LIST))
            return malformed();
        return patternAmountMap(rawList(root, "e"));
    }

    private static PersistenceDecodeResult<Map<PatternId, AEAmount>> patternAmountMap(ListTag list) {
        if (list != null && list.size() > PatternLimits.MAX_GRAPH_NODES)
            return limited();
        if (!canonicalList(list, Tag.TAG_COMPOUND, PatternLimits.MAX_GRAPH_NODES))
            return malformed();
        TreeMap<PatternId, AEAmount> map = new TreeMap<>();
        PatternId prior = null;
        for (Tag raw : list) {
            if (!(raw instanceof CompoundTag tag) || !shape(tag, 2, "p", Tag.TAG_STRING, "a", Tag.TAG_COMPOUND))
                return malformed();
            PersistenceDecodeResult<AEAmount> a = amount(tag.getCompound("a"));
            if (!(a instanceof PersistenceDecodeResult.Success<AEAmount> x) || x.value().equals(AEAmount.ZERO))
                return propagate(a);
            try {
                PatternId id = new PatternId(tag.getString("p"));
                if (prior != null && prior.compareTo(id) >= 0 || map.put(id, x.value()) != null)
                    return malformed();
                prior = id;
            } catch (RuntimeException q) {
                return malformed();
            }
        }
        return new PersistenceDecodeResult.Success<>(Map.copyOf(map));
    }

    private static CompoundTag keyAmountMap(Map<KeyId, AEAmount> source) {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        if (!budget.reserve(16, 2))
            throw new IllegalArgumentException("Key amount map header exceeds persistence limits");
        source.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparingInt(KeyId::value)))
                .forEach(e -> {
                    CompoundTag tag = new CompoundTag();
                    tag.putInt("k", e.getKey().value());
                    tag.put("a", amount(e.getValue()));
                    if (!budget.add(tag))
                        throw new IllegalArgumentException("Key amount map exceeds persistence limits");
                    list.add(tag);
                });
        root.put("e", list);
        return root;
    }

    private static PersistenceDecodeResult<Map<KeyId, AEAmount>> keyAmountMap(CompoundTag root) {
        if (!shape(root, 1, "e", Tag.TAG_LIST))
            return malformed();
        return keyAmountMap(rawList(root, "e"));
    }

    private static PersistenceDecodeResult<Map<KeyId, AEAmount>> keyAmountMap(ListTag list) {
        if (list != null && list.size() > PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS)
            return limited();
        if (!canonicalList(list, Tag.TAG_COMPOUND, PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS))
            return malformed();
        TreeMap<KeyId, AEAmount> map = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        int prior = -1;
        for (Tag raw : list) {
            if (!(raw instanceof CompoundTag tag) || !shape(tag, 2, "k", Tag.TAG_INT, "a", Tag.TAG_COMPOUND)
                    || tag.getInt("k") <= prior)
                return malformed();
            PersistenceDecodeResult<AEAmount> a = amount(tag.getCompound("a"));
            if (!(a instanceof PersistenceDecodeResult.Success<AEAmount> x) || x.value().equals(AEAmount.ZERO))
                return propagate(a);
            try {
                map.put(new KeyId(tag.getInt("k")), x.value());
                prior = tag.getInt("k");
            } catch (RuntimeException q) {
                return malformed();
            }
        }
        return new PersistenceDecodeResult.Success<>(Map.copyOf(map));
    }

    private static CompoundTag dependencies(DependencySet value) {
        CompoundTag tag = new CompoundTag();
        tag.put("g", revision(value.gridRevision()));
        ListTag storage = new ListTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        if (!budget.reserve(16, 2))
            throw new IllegalArgumentException("Dependency map header exceeds persistence limits");
        value.storageKeyRevisions().forEach((k, v) -> {
            CompoundTag e = new CompoundTag();
            e.putInt("k", k.value());
            e.putLong("r", v);
            if (!budget.add(e))
                throw new IllegalArgumentException("Dependency map exceeds persistence limits");
            storage.add(e);
        });
        tag.put("s", storage);
        tag.put("p", patternRevisionMap(value.patternRevisions()));
        return tag;
    }

    private static PersistenceDecodeResult<DependencySet> dependencies(CompoundTag tag) {
        if (!shape(tag, 3, "g", Tag.TAG_COMPOUND, "s", Tag.TAG_LIST, "p", Tag.TAG_COMPOUND))
            return malformed();
        PersistenceDecodeResult<GridRevision> g = revision(tag.getCompound("g"));
        PersistenceDecodeResult<Map<PatternId, PatternRevision>> p = patternRevisionMap(tag.getCompound("p"));
        if (!(g instanceof PersistenceDecodeResult.Success<GridRevision> gg)
                || !(p instanceof PersistenceDecodeResult.Success<Map<PatternId, PatternRevision>> pp))
            return firstFailure(g, p);
        ListTag list = rawList(tag, "s");
        if (!canonicalList(list, Tag.TAG_COMPOUND, PlannerLimits.MAX_STORAGE_SNAPSHOT_KEYS))
            return malformed();
        try {
            DependencySet.Builder builder = new DependencySet.Builder(gg.value());
            int prior = -1;
            for (Tag raw : list) {
                CompoundTag e = (CompoundTag) raw;
                if (!shape(e, 2, "k", Tag.TAG_INT, "r", Tag.TAG_LONG) || e.getInt("k") <= prior || e.getLong("r") < 0)
                    return malformed();
                builder.recordStorageRead(new KeyId(e.getInt("k")), e.getLong("r"));
                prior = e.getInt("k");
            }
            pp.value().forEach(builder::recordPatternRead);
            return new PersistenceDecodeResult.Success<>(builder.build());
        } catch (RuntimeException x) {
            return malformed();
        }
    }

    private static <T> ListTag list(Collection<T> values, java.util.function.Function<T, CompoundTag> encoder) {
        ListTag list = new ListTag();
        StrictNbt.Budget budget = StrictNbt.budget();
        if (!budget.reserve(8, 1))
            throw new IllegalArgumentException("List header exceeds persistence limits");
        for (T value : values) {
            CompoundTag encoded = encoder.apply(value);
            if (!budget.add(encoded))
                throw new IllegalArgumentException("List exceeds persistence limits");
            list.add(encoded);
        }
        return list;
    }

    private static <T> PersistenceDecodeResult<List<T>> compoundList(CompoundTag parent, String field, int max,
            java.util.function.Function<Tag, PersistenceDecodeResult<T>> decoder) {
        if (parent == null || !parent.contains(field, Tag.TAG_LIST))
            return malformed();
        ListTag list = rawList(parent, field);
        if (list != null && list.size() > max)
            return limited();
        if (!canonicalList(list, Tag.TAG_COMPOUND, max))
            return malformed();
        ArrayList<T> result = new ArrayList<>(list.size());
        for (Tag tag : list) {
            PersistenceDecodeResult<T> decoded;
            try {
                decoded = decoder.apply(tag);
            } catch (RuntimeException x) {
                return malformed();
            }
            if (!(decoded instanceof PersistenceDecodeResult.Success<T> success))
                return propagate(decoded);
            result.add(success.value());
        }
        return new PersistenceDecodeResult.Success<>(List.copyOf(result));
    }

    private static ListTag rawList(CompoundTag parent, String field) {
        Tag raw = parent.get(field);
        return raw instanceof ListTag list ? list : null;
    }

    private static boolean canonicalList(ListTag list, int element, int max) {
        return list != null && list.size() <= max
                && (list.size() == 0 || Byte.toUnsignedInt(list.getElementType()) == element)
                && list.stream().allMatch(t -> Byte.toUnsignedInt(t.getId()) == element);
    }

    private static boolean shape(CompoundTag tag, int fields, Object... spec) {
        if (tag == null || !StrictNbt.valid(tag) || tag.size() != fields || spec.length != fields * 2)
            return false;
        for (int i = 0; i < spec.length; i += 2) {
            if (!(spec[i] instanceof String name) || !(spec[i + 1] instanceof Number type))
                return false;
            int typeId = type.intValue();
            if (type.longValue() != typeId || typeId < Byte.toUnsignedInt(Tag.TAG_END)
                    || typeId > Byte.toUnsignedInt(Tag.TAG_LONG_ARRAY)
                    || !tag.contains(name, typeId))
                return false;
        }
        return true;
    }

    private static PersistenceDecodeResult<String> string(String value, int limit) {
        return StrictNbt.utf8Length(value) >= 0 && StrictNbt.utf8Length(value) <= limit
                ? new PersistenceDecodeResult.Success<>(value)
                : limited();
    }

    private static Set<KeyId> collectKeys(ExactCraftingPlan plan) {
        Set<KeyId> keys = new HashSet<>() {
            @Override
            public boolean add(KeyId key) {
                if (key == null)
                    throw new IllegalArgumentException("Plan key closure contains null");
                if (!contains(key) && size() >= PersistenceLimits.MAX_KEYS)
                    throw new IllegalArgumentException("Plan key closure exceeds persistence limit");
                return super.add(key);
            }
        };
        addKeys(keys, plan.request());
        for (PlannedCausalStep step : plan.causalSteps())
            addKeys(keys, step);
        for (ExecutionManifest manifest : plan.executionManifests())
            addKeys(keys, manifest);
        keys.addAll(plan.initialStorageDebits().keySet());
        keys.addAll(plan.finalSurplus().keySet());
        keys.addAll(plan.dependencies().storageKeyRevisions().keySet());
        return keys;
    }

    private static Set<KeyId> ids(List<PersistedKeyTable.Entry> entries) {
        Set<KeyId> ids = new HashSet<>();
        for (PersistedKeyTable.Entry e : entries)
            ids.add(e.oldId());
        return ids;
    }

    private static void addKeys(Set<KeyId> k, ExactCraftRequest r) {
        k.add(r.output());
    }

    private static void addKeys(Set<KeyId> k, PlannedCausalStep s) {
        if (s.cause() instanceof PlannedBatchCause.Root r)
            k.add(r.output());
        else
            k.add(((PlannedBatchCause.Input) s.cause()).key());
        if (s instanceof PlannedPatternBatch n) {
            for (PlannedInputSelection x : n.inputs())
                addKeys(k, x);
        } else {
            PlannedCycleBatch c = (PlannedCycleBatch) s;
            k.add(c.seedKey());
            for (PlannedCycleMember m : c.members()) {
                for (PlannedInputSelection x : m.inputsPerTurn())
                    addKeys(k, x);
                for (PlannedCycleOutput x : m.outputsPerTurn())
                    k.add(x.key());
            }
            for (PlannedCycleLink x : c.links())
                k.add(x.key());
            k.addAll(c.finalCredits().keySet());
        }
    }

    private static void addKeys(Set<KeyId> k, PlannedInputSelection x) {
        k.add(x.consumedKey());
        x.remainderReturn().ifPresent(r -> k.add(r.key()));
    }

    private static void addKeys(Set<KeyId> k, ExecutionManifest m) {
        if (m.cause() instanceof PlannedBatchCause.Root r)
            k.add(r.output());
        else
            k.add(((PlannedBatchCause.Input) m.cause()).key());
        if (m instanceof CycleExecutionManifest c) {
            k.add(c.seedKey());
            for (PlannedCycleLink x : c.links())
                k.add(x.key());
            k.addAll(c.finalCredits().keySet());
        }
        for (SealedPatternExecution e : m.patternExecutions()) {
            for (CompiledInputSpec i : e.pattern().inputs())
                for (CompiledCandidateSpec c : i.candidates()) {
                    k.add(c.key());
                    c.remainder().ifPresent(r -> k.add(r.key()));
                }
            for (CompiledOutputSpec o : e.pattern().outputs())
                k.add(o.key());
            for (PlannedInputSelection x : e.plannedSelections())
                addKeys(k, x);
            for (PlannedCycleOutput x : e.plannedOutputs())
                k.add(x.key());
        }
    }

    private record Header(PlannedBatchId id, PlannedBatchCause cause) {
    }

    private record OldPlan(ExactPlanId id, GridRevision planning, GridRevision validation, ExactCraftRequest request,
            List<PlannedCausalStep> steps, List<ExecutionManifest> manifests, Map<PatternId, PatternRevision> used,
            Map<PatternId, AEAmount> executions, Map<KeyId, AEAmount> debits, Map<KeyId, AEAmount> surplus,
            DependencySet dependencies) {
        Set<KeyId> keyIds() {
            ExactCraftingPlan plan = ExactCraftingPlan.restoreValidated(id, planning, validation, request, steps,
                    manifests, used, executions, debits, surplus, dependencies);
            return collectKeys(plan);
        }

        ExactCraftingPlan rebind(ExactKeyRemap map) {
            return ExactCraftingPlan.restoreValidated(id, rebase(planning, map), rebase(validation, map),
                    map(request, map), remapSteps(steps, map), remapManifests(manifests, map), used,
                    remapPatternAmounts(executions, map), remapKeyAmounts(debits, map), remapKeyAmounts(surplus, map),
                    map(dependencies, map));
        }
    }

    private static GridRevision rebase(GridRevision x, ExactKeyRemap map) {
        return new GridRevision(x.serverGeneration(), map.newGeneration(), x.graphGeneration(), x.recipeRevision(),
                x.storageRevision());
    }

    private static ExactCraftRequest map(ExactCraftRequest x, ExactKeyRemap m) {
        return new ExactCraftRequest(m.require(x.output()), x.amount());
    }

    private static List<PlannedCausalStep> remapSteps(List<PlannedCausalStep> x, ExactKeyRemap m) {
        ArrayList<PlannedCausalStep> r = new ArrayList<>(x.size());
        for (PlannedCausalStep s : x) {
            PlannedBatchCause c = map(s.cause(), m);
            if (s instanceof PlannedPatternBatch n)
                r.add(new PlannedPatternBatch(n.id(), c, n.patternId(), n.executions(), mapSelections(n.inputs(), m)));
            else {
                PlannedCycleBatch q = (PlannedCycleBatch) s;
                ArrayList<PlannedCycleMember> a = new ArrayList<>();
                for (PlannedCycleMember z : q.members())
                    a.add(new PlannedCycleMember(z.patternId(), z.executionsPerTurn(),
                            mapSelections(z.inputsPerTurn(), m), mapOutputs(z.outputsPerTurn(), m)));
                r.add(new PlannedCycleBatch(q.id(), c, q.repetitions(), m.require(q.seedKey()), q.seedAmount(), a,
                        mapLinks(q.links(), m), remapKeyAmounts(q.finalCredits(), m)));
            }
        }
        return List.copyOf(r);
    }

    private static PlannedBatchCause map(PlannedBatchCause x, ExactKeyRemap m) {
        return x instanceof PlannedBatchCause.Root r
                ? new PlannedBatchCause.Root(m.require(r.output()), r.demandedAmount())
                : new PlannedBatchCause.Input(((PlannedBatchCause.Input) x).consumerBatchId(),
                        ((PlannedBatchCause.Input) x).cycleMemberIndex(), ((PlannedBatchCause.Input) x).inputIndex(),
                        ((PlannedBatchCause.Input) x).candidateIndex(), m.require(((PlannedBatchCause.Input) x).key()),
                        ((PlannedBatchCause.Input) x).demandedAmount());
    }

    private static List<PlannedInputSelection> mapSelections(List<PlannedInputSelection> x, ExactKeyRemap m) {
        ArrayList<PlannedInputSelection> r = new ArrayList<>();
        for (PlannedInputSelection s : x)
            r.add(new PlannedInputSelection(s.inputIndex(), s.candidateIndex(), s.templateUnits(),
                    m.require(s.consumedKey()), s.grossConsumedAmount(), s.initialRequiredAmount(),
                    s.remainderReturn().map(v -> new PlannedRemainderReturn(m.require(v.key()), v.amount()))));
        return List.copyOf(r);
    }

    private static List<PlannedCycleOutput> mapOutputs(List<PlannedCycleOutput> x, ExactKeyRemap m) {
        ArrayList<PlannedCycleOutput> r = new ArrayList<>();
        for (PlannedCycleOutput s : x)
            r.add(new PlannedCycleOutput(s.outputIndex(), m.require(s.key()), s.amountPerTurn()));
        return List.copyOf(r);
    }

    private static List<PlannedCycleLink> mapLinks(List<PlannedCycleLink> x, ExactKeyRemap m) {
        ArrayList<PlannedCycleLink> r = new ArrayList<>();
        for (PlannedCycleLink s : x)
            r.add(new PlannedCycleLink(s.producerMemberIndex(), s.outputIndex(), s.consumerMemberIndex(),
                    s.inputIndex(), s.candidateIndex(), m.require(s.key()), s.amountPerTurn()));
        return List.copyOf(r);
    }

    private static List<ExecutionManifest> remapManifests(List<ExecutionManifest> x, ExactKeyRemap m) {
        ArrayList<ExecutionManifest> r = new ArrayList<>();
        for (ExecutionManifest e : x) {
            if (e instanceof NormalExecutionManifest n)
                r.add(new NormalExecutionManifest(n.batchId(), map(n.cause(), m), map(n.execution(), m)));
            else {
                CycleExecutionManifest c = (CycleExecutionManifest) e;
                ArrayList<SealedPatternExecution> a = new ArrayList<>();
                for (SealedPatternExecution s : c.memberExecutions())
                    a.add(map(s, m));
                r.add(new CycleExecutionManifest(c.batchId(), map(c.cause(), m), c.repetitions(),
                        m.require(c.seedKey()), c.seedAmount(), a, mapLinks(c.links(), m),
                        remapKeyAmounts(c.finalCredits(), m)));
            }
        }
        return List.copyOf(r);
    }

    private static SealedPatternExecution map(SealedPatternExecution x, ExactKeyRemap m) {
        CompiledPattern p = x.pattern();
        ArrayList<CompiledInputSpec> ins = new ArrayList<>();
        for (CompiledInputSpec i : p.inputs()) {
            ArrayList<CompiledCandidateSpec> cs = new ArrayList<>();
            for (CompiledCandidateSpec c : i.candidates())
                cs.add(new CompiledCandidateSpec(m.require(c.key()), c.amountPerTemplate(),
                        c.remainder().map(r -> new CompiledRemainderSpec(m.require(r.key()), r.amountPerTemplate()))));
            ins.add(new CompiledInputSpec(cs, i.multiplier(), i.policy()));
        }
        ArrayList<CompiledOutputSpec> out = new ArrayList<>();
        for (CompiledOutputSpec o : p.outputs())
            out.add(new CompiledOutputSpec(m.require(o.key()), o.amountPerExecution(), o.primary()));
        return new SealedPatternExecution(
                new CompiledPattern(p.id(), p.kind(), ins, out, p.machineIntent(), p.revision(), m.newGeneration()),
                x.executions(), mapSelections(x.plannedSelections(), m), mapOutputs(x.plannedOutputs(), m));
    }

    private static Map<KeyId, AEAmount> remapKeyAmounts(Map<KeyId, AEAmount> x, ExactKeyRemap m) {
        TreeMap<KeyId, AEAmount> r = new TreeMap<>(Comparator.comparingInt(KeyId::value));
        for (Map.Entry<KeyId, AEAmount> entry : x.entrySet()) {
            KeyId mapped = m.require(entry.getKey());
            if (r.put(mapped, entry.getValue()) != null)
                throw new IllegalArgumentException("Distinct persisted keys collide after remap");
        }
        if (r.size() != x.size())
            throw new IllegalArgumentException("Persisted key amount map shrank during remap");
        return Map.copyOf(r);
    }

    private static Map<PatternId, AEAmount> remapPatternAmounts(Map<PatternId, AEAmount> x, ExactKeyRemap ignored) {
        return Map.copyOf(new TreeMap<>(x));
    }

    private static DependencySet map(DependencySet x, ExactKeyRemap m) {
        DependencySet.Builder b = new DependencySet.Builder(rebase(x.gridRevision(), m));
        x.storageKeyRevisions().forEach((k, v) -> b.recordStorageRead(m.require(k), v));
        x.patternRevisions().forEach(b::recordPatternRead);
        return b.build();
    }

    private static <T> PersistenceDecodeResult<T> malformed() {
        return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.MALFORMED);
    }

    private static <T> PersistenceDecodeResult<T> unsupported() {
        return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.UNSUPPORTED_VERSION);
    }

    private static <T> PersistenceDecodeResult<T> limited() {
        return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.LIMIT_EXCEEDED);
    }

    private static <T> PersistenceDecodeResult<T> generation() {
        return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.GENERATION_MISMATCH);
    }

    private static <T> PersistenceDecodeResult<T> propagate(PersistenceDecodeResult<?> x) {
        return x instanceof PersistenceDecodeResult.Failure<?> f ? new PersistenceDecodeResult.Failure<>(f.reason())
                : malformed();
    }

    private static <T> PersistenceDecodeResult<T> firstFailure(PersistenceDecodeResult<?>... values) {
        for (PersistenceDecodeResult<?> v : values)
            if (v instanceof PersistenceDecodeResult.Failure<?> f)
                return new PersistenceDecodeResult.Failure<>(f.reason());
        return malformed();
    }

    private static void requireValid(CompoundTag tag) {
        if (!StrictNbt.valid(tag))
            throw new IllegalArgumentException("Plan checkpoint exceeds strict persistence limits");
    }
}
