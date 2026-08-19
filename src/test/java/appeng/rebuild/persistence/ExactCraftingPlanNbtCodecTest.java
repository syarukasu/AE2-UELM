package appeng.rebuild.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.api.stacks.AEKey;
import appeng.rebuild.execution.CycleExecutionManifest;
import appeng.rebuild.execution.ExactCraftingPlan;
import appeng.rebuild.execution.ExactCraftingPlanValidator;
import appeng.rebuild.execution.ExactPlanId;
import appeng.rebuild.execution.ExactPlanValidationResult;
import appeng.rebuild.execution.ExecutionManifest;
import appeng.rebuild.execution.SealedPatternExecution;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.CompiledPatternGraph;
import appeng.rebuild.pattern.CompiledPatternGraphBuilder;
import appeng.rebuild.pattern.GraphBuildResult;
import appeng.rebuild.pattern.GraphGeneration;
import appeng.rebuild.pattern.MachineIntent;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternKind;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.rebuild.pattern.SubstitutionPolicy;
import appeng.rebuild.planner.ExactCraftPlanDraft;
import appeng.rebuild.planner.ExactCraftPlanResult;
import appeng.rebuild.planner.ExactCraftPlanner;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.planner.PlannedBatchCause;
import appeng.rebuild.planner.PlannedCausalStep;
import appeng.rebuild.planner.PlannedCycleBatch;
import appeng.rebuild.planner.PlannedInputSelection;
import appeng.rebuild.planner.PlannedPatternBatch;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Tests the whole immutable sealed-plan persistence closure without starting Minecraft. */
class ExactCraftingPlanNbtCodecTest {
    private static final long KEY_GENERATION = 7L;
    private static final long CURRENT_KEY_GENERATION = 97L;
    private static final long SERVER_GENERATION = 9L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(3L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(5L);
    private static final ExactCraftPlanner PLANNER = new ExactCraftPlanner();
    private static final ExactCraftingPlanValidator VALIDATOR = new ExactCraftingPlanValidator();

    @Test
    void normalPlanRoundTripsEverySealedFieldAndDeterministically() {
        NormalFixture fixture = normalFixture();
        CompoundTag encoded = ExactCraftingPlanNbtCodec.encode(fixture.plan(), fixture.source());
        int sourceSize = fixture.source().size();
        KeyRegistry current = new KeyRegistry(KEY_GENERATION);

        try (MockedStatic<AEKey> decoder = decoder(fixture.keys())) {
            ExactCraftingPlan restored = success(ExactCraftingPlanNbtCodec.decode(encoded, current));
            assertPlanEquals(fixture.plan(), restored);
            assertEquals(encoded, ExactCraftingPlanNbtCodec.encode(restored, current));
        }

        assertEquals(sourceSize, fixture.source().size());
        assertEquals(3, current.size());
        assertEquals(new ExactPlanId(fixture.plan().planId().value()), fixture.plan().planId());
        assertUnmodifiable(fixture.plan());
    }

    @Test
    void productiveCycleRoundTripsHugeExactQuantitiesAndRetainsFixedPlanIdentity() {
        for (BigInteger demand : List.of(BigInteger.ONE.shiftLeft(64), BigInteger.ONE.shiftLeft(128),
                BigInteger.TEN.pow(1000))) {
            CycleFixture fixture = cycleFixture(AEAmount.of(demand));
            CompoundTag encoded = ExactCraftingPlanNbtCodec.encode(fixture.plan(), fixture.source());
            KeyRegistry current = new KeyRegistry(KEY_GENERATION);
            try (MockedStatic<AEKey> decoder = decoder(fixture.keys())) {
                ExactCraftingPlan restored = success(ExactCraftingPlanNbtCodec.decode(encoded, current));
                assertPlanEquals(fixture.plan(), restored);
                assertEquals(demand, restored.request().amount().toBigInteger());
                assertTrue(restored.executionManifests().stream().anyMatch(CycleExecutionManifest.class::isInstance));
                assertEquals(encoded, ExactCraftingPlanNbtCodec.encode(restored, current));
            }
        }
    }

    @Test
    void prepopulatedRegistryRebasesEveryTransitiveKeyAndCompiledGeneration() {
        NormalFixture fixture = normalFixture();
        CompoundTag encoded = ExactCraftingPlanNbtCodec.encode(fixture.plan(), fixture.source());
        KeyRegistry current = new KeyRegistry(CURRENT_KEY_GENERATION);
        current.intern(fixture.keys().get(2));
        assertEquals(new KeyId(0), current.lookup(fixture.keys().get(2)));

        try (MockedStatic<AEKey> decoder = decoder(fixture.keys())) {
            ExactCraftingPlan restored = success(ExactCraftingPlanNbtCodec.decode(encoded, current));
            assertEquals(new KeyId(1), current.lookup(fixture.keys().get(0)));
            assertEquals(new KeyId(2), current.lookup(fixture.keys().get(1)));
            assertEquals(new KeyId(0), current.lookup(fixture.keys().get(2)));
            assertEquals(new KeyId(2), restored.request().output());
            assertEquals(Map.of(new KeyId(1), AEAmount.of(2L)), restored.finalSurplus());
            assertEquals(CURRENT_KEY_GENERATION, restored.planningRevision().keyRegistryGeneration());
            assertEquals(CURRENT_KEY_GENERATION, restored.validationRevision().keyRegistryGeneration());
            assertEquals(CURRENT_KEY_GENERATION, restored.dependencies().gridRevision().keyRegistryGeneration());
            assertEveryReferencedKeyResolves(restored, current);
            for (ExecutionManifest manifest : restored.executionManifests()) {
                for (SealedPatternExecution execution : manifest.patternExecutions()) {
                    assertEquals(CURRENT_KEY_GENERATION, execution.pattern().keyRegistryGeneration());
                }
            }
        }

        assertEquals(3, current.size());
    }

    @Test
    void malformedPlanAfterValidKeyTableDoesNotMutateCurrentRegistry() {
        NormalFixture fixture = normalFixture();
        CompoundTag encoded = ExactCraftingPlanNbtCodec.encode(fixture.plan(), fixture.source());
        KeyRegistry current = new KeyRegistry(CURRENT_KEY_GENERATION);
        try (MockedStatic<AEKey> decoder = decoder(fixture.keys())) {
            CompoundTag malformedRequest = encoded.copy();
            malformedRequest.getCompound("r").putString("extra", "reject");
            assertFailure(ExactCraftingPlanNbtCodec.decode(malformedRequest, current),
                    PersistenceDecodeResult.Reason.MALFORMED);
            assertEquals(0, current.size());

            CompoundTag unknownKey = encoded.copy();
            unknownKey.getCompound("r").putInt("k", 99);
            assertFailure(ExactCraftingPlanNbtCodec.decode(unknownKey, current),
                    PersistenceDecodeResult.Reason.MALFORMED);
            assertEquals(0, current.size());

            CompoundTag forgedDependency = encoded.copy();
            forgedDependency.getCompound("z").getCompound("g").putLong("s", SERVER_GENERATION + 1);
            assertFailure(ExactCraftingPlanNbtCodec.decode(forgedDependency, current),
                    PersistenceDecodeResult.Reason.MALFORMED);
            assertEquals(0, current.size());
        }
    }

    @Test
    void wrongVersionShapeOrderBooleanUtf8AndDepthAreRejectedBeforeRebind() {
        NormalFixture fixture = normalFixture();
        CompoundTag encoded = ExactCraftingPlanNbtCodec.encode(fixture.plan(), fixture.source());
        KeyRegistry current = new KeyRegistry(CURRENT_KEY_GENERATION);
        try (MockedStatic<AEKey> decoder = decoder(fixture.keys())) {
            CompoundTag wrongVersion = encoded.copy();
            wrongVersion.putInt("v", PersistenceLimits.FORMAT_VERSION + 1);
            assertFailure(ExactCraftingPlanNbtCodec.decode(wrongVersion, current),
                    PersistenceDecodeResult.Reason.UNSUPPORTED_VERSION);

            CompoundTag missing = encoded.copy();
            missing.remove("m");
            assertFailure(ExactCraftingPlanNbtCodec.decode(missing, current),
                    PersistenceDecodeResult.Reason.MALFORMED);

            CompoundTag extra = encoded.copy();
            extra.putInt("extra", 1);
            assertFailure(ExactCraftingPlanNbtCodec.decode(extra, current),
                    PersistenceDecodeResult.Reason.MALFORMED);

            CompoundTag wrongType = encoded.copy();
            wrongType.putString("s", "not-a-list");
            assertFailure(ExactCraftingPlanNbtCodec.decode(wrongType, current),
                    PersistenceDecodeResult.Reason.MALFORMED);

            CompoundTag unsortedKeys = encoded.copy();
            ListTag keyEntries = unsortedKeys.getCompound("k").getList("e", Tag.TAG_COMPOUND);
            Tag firstKey = keyEntries.get(0).copy();
            keyEntries.set(0, keyEntries.get(1).copy());
            keyEntries.set(1, firstKey);
            assertFailure(ExactCraftingPlanNbtCodec.decode(unsortedKeys, current),
                    PersistenceDecodeResult.Reason.MALFORMED);

            CompoundTag unsortedPatterns = encoded.copy();
            ListTag patternEntries = unsortedPatterns.getCompound("u").getList("e", Tag.TAG_COMPOUND);
            Tag firstPattern = patternEntries.get(0).copy();
            patternEntries.set(0, patternEntries.get(1).copy());
            patternEntries.set(1, firstPattern);
            assertFailure(ExactCraftingPlanNbtCodec.decode(unsortedPatterns, current),
                    PersistenceDecodeResult.Reason.MALFORMED);

            CompoundTag invalidBoolean = encoded.copy();
            CompoundTag firstManifest = invalidBoolean.getList("m", Tag.TAG_COMPOUND).getCompound(0);
            CompoundTag sealed = firstManifest.getCompound("e");
            CompoundTag pattern = sealed.getCompound("p");
            pattern.getList("o", Tag.TAG_COMPOUND).getCompound(0).putByte("p", (byte) 2);
            assertFailure(ExactCraftingPlanNbtCodec.decode(invalidBoolean, current),
                    PersistenceDecodeResult.Reason.MALFORMED);

            CompoundTag invalidUtf8 = encoded.copy();
            invalidUtf8.getCompound("i").putString("u", "\uD800");
            assertFailure(ExactCraftingPlanNbtCodec.decode(invalidUtf8, current),
                    PersistenceDecodeResult.Reason.LIMIT_EXCEEDED);

            CompoundTag tooDeep = encoded.copy();
            tooDeep.put("deep", nested(PersistenceLimits.MAX_NBT_DEPTH + 1));
            assertFailure(ExactCraftingPlanNbtCodec.decode(tooDeep, current),
                    PersistenceDecodeResult.Reason.LIMIT_EXCEEDED);
            assertEquals(0, current.size());
        }
    }

    @Test
    void forgedCausalAndPatternRevisionDataIsRejectedWithoutFallback() {
        NormalFixture fixture = normalFixture();
        CompoundTag encoded = ExactCraftingPlanNbtCodec.encode(fixture.plan(), fixture.source());
        KeyRegistry current = new KeyRegistry(CURRENT_KEY_GENERATION);
        try (MockedStatic<AEKey> decoder = decoder(fixture.keys())) {
            CompoundTag forgedCause = encoded.copy();
            CompoundTag firstStep = forgedCause.getList("s", Tag.TAG_COMPOUND).getCompound(0);
            firstStep.getCompound("c").putInt("k", 99);
            assertFailure(ExactCraftingPlanNbtCodec.decode(forgedCause, current),
                    PersistenceDecodeResult.Reason.MALFORMED);
            assertEquals(0, current.size());

            CompoundTag forgedManifest = encoded.copy();
            CompoundTag manifest = forgedManifest.getList("m", Tag.TAG_COMPOUND).getCompound(0);
            manifest.putLong("i", manifest.getLong("i") + 1);
            assertFailure(ExactCraftingPlanNbtCodec.decode(forgedManifest, current),
                    PersistenceDecodeResult.Reason.MALFORMED);
            assertEquals(0, current.size());

            CompoundTag forgedPatternRevision = encoded.copy();
            CompoundTag sealed = forgedPatternRevision.getList("m", Tag.TAG_COMPOUND).getCompound(0)
                    .getCompound("e");
            sealed.getCompound("p").putLong("r", 999);
            assertFailure(ExactCraftingPlanNbtCodec.decode(forgedPatternRevision, current),
                    PersistenceDecodeResult.Reason.MALFORMED);
            assertEquals(0, current.size());
        }
    }

    private static NormalFixture normalFixture() {
        AEKey key0 = mock(AEKey.class);
        AEKey key1 = mock(AEKey.class);
        AEKey key2 = mock(AEKey.class);
        Map<Integer, AEKey> keys = Map.of(0, key0, 1, key1, 2, key2);
        stubTags(keys);
        KeyRegistry registry = new KeyRegistry(KEY_GENERATION);
        registry.intern(key0);
        registry.intern(key1);
        registry.intern(key2);

        MachineIntent intent = new MachineIntent("processing-backend", "recipe-fingerprint", "capability-fingerprint",
                Map.of("z-last", "last", "a-first", "first"));
        CompiledPattern producer = pattern("codec-producer", List.of(),
                List.of(output(2, 2L, true)), intent);
        CompiledPattern consumer = pattern("codec-consumer",
                List.of(input(2, 2L, List.of(candidate(2, 1L, Optional.of(remainder(0, 1L)))),
                        SubstitutionPolicy.EXACT)),
                List.of(output(1, 1L, true)), intent);
        StorageSnapshot storage = snapshot(3, Map.of());
        Map<PatternId, Integer> priorities = Map.of(producer.id(), 0, consumer.id(), 10);
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(consumer, producer), priorities);
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(new ExactCraftRequest(new KeyId(1), AEAmount.ONE), storage, patterns)).draft();
        ExactCraftingPlan plan = assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, storage, patterns)).plan();
        return new NormalFixture(plan, registry, keys);
    }

    private static CycleFixture cycleFixture(AEAmount demand) {
        AEKey key0 = mock(AEKey.class);
        Map<Integer, AEKey> keys = Map.of(0, key0);
        stubTags(keys);
        KeyRegistry registry = new KeyRegistry(KEY_GENERATION);
        registry.intern(key0);
        MachineIntent intent = new MachineIntent("cycle-backend", "cycle-recipe", "cycle-capability",
                Map.of("mode", "productive"));
        CompiledPattern self = pattern("codec-cycle", List.of(input(0, 1L,
                List.of(candidate(0, 1L, Optional.empty())), SubstitutionPolicy.EXACT)),
                List.of(output(0, 2L, true)), intent);
        StorageSnapshot storage = snapshot(1, Map.of(0, AEAmount.ONE));
        Map<PatternId, Integer> priorities = Map.of(self.id(), 10);
        NormalizedPatternSnapshot patterns = patternSnapshot(List.of(self), priorities);
        ExactCraftPlanDraft draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                PLANNER.plan(new ExactCraftRequest(new KeyId(0), demand), storage, patterns)).draft();
        ExactCraftingPlan plan = assertInstanceOf(ExactPlanValidationResult.Success.class,
                VALIDATOR.validate(draft, storage, patterns)).plan();
        return new CycleFixture(plan, registry, keys);
    }

    private static void stubTags(Map<Integer, AEKey> keys) {
        keys.forEach((id, key) -> when(key.toTagGeneric()).thenReturn(identity("codec:key:" + id)));
    }

    private static MockedStatic<AEKey> decoder(Map<Integer, AEKey> keys) {
        Map<String, AEKey> byIdentity = new HashMap<>();
        keys.forEach((id, key) -> byIdentity.put("codec:key:" + id, key));
        MockedStatic<AEKey> decoder = mockStatic(AEKey.class);
        decoder.when(() -> AEKey.fromTagGeneric(any(CompoundTag.class)))
                .thenAnswer(call -> {
                    CompoundTag identity = call.getArgument(0, CompoundTag.class);
                    String channel = identity.getString("#c");
                    AEKey key = byIdentity.get(channel);
                    if (key == null)
                        throw new IllegalArgumentException("unknown test key identity: " + channel);
                    return key;
                });
        return decoder;
    }

    private static CompoundTag identity(String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("#c", value);
        return tag;
    }

    private static CompoundTag nested(int depth) {
        CompoundTag result = new CompoundTag();
        CompoundTag cursor = result;
        for (int i = 0; i < depth; i++) {
            CompoundTag child = new CompoundTag();
            cursor.put("n", child);
            cursor = child;
        }
        return result;
    }

    private static void assertPlanEquals(ExactCraftingPlan expected, ExactCraftingPlan actual) {
        assertEquals(expected.planId(), actual.planId());
        assertEquals(expected.planningRevision(), actual.planningRevision());
        assertEquals(expected.validationRevision(), actual.validationRevision());
        assertEquals(expected.request(), actual.request());
        assertEquals(expected.causalSteps(), actual.causalSteps());
        assertEquals(expected.executionManifests(), actual.executionManifests());
        assertEquals(expected.usedPatternRevisions(), actual.usedPatternRevisions());
        assertEquals(expected.patternExecutions(), actual.patternExecutions());
        assertEquals(expected.initialStorageDebits(), actual.initialStorageDebits());
        assertEquals(expected.finalSurplus(), actual.finalSurplus());
        assertEquals(expected.dependencies().gridRevision(), actual.dependencies().gridRevision());
        assertEquals(expected.dependencies().storageKeyRevisions(), actual.dependencies().storageKeyRevisions());
        assertEquals(expected.dependencies().patternRevisions(), actual.dependencies().patternRevisions());
    }

    private static void assertEveryReferencedKeyResolves(ExactCraftingPlan plan, KeyRegistry registry) {
        for (KeyId key : referencedKeys(plan))
            assertNotNull(registry.resolve(key), "rebased key must remain in current registry: " + key);
    }

    private static Set<KeyId> referencedKeys(ExactCraftingPlan plan) {
        Set<KeyId> keys = new HashSet<>();
        keys.add(plan.request().output());
        plan.initialStorageDebits().keySet().forEach(keys::add);
        plan.finalSurplus().keySet().forEach(keys::add);
        plan.dependencies().storageKeyRevisions().keySet().forEach(keys::add);
        for (PlannedCausalStep step : plan.causalSteps()) {
            addCause(keys, step.cause());
            if (step instanceof PlannedPatternBatch normal) {
                normal.inputs().forEach(input -> addSelection(keys, input));
            } else {
                PlannedCycleBatch cycle = (PlannedCycleBatch) step;
                keys.add(cycle.seedKey());
                cycle.finalCredits().keySet().forEach(keys::add);
                cycle.members().forEach(member -> {
                    member.inputsPerTurn().forEach(input -> addSelection(keys, input));
                    member.outputsPerTurn().forEach(output -> keys.add(output.key()));
                });
                cycle.links().forEach(link -> keys.add(link.key()));
            }
        }
        for (ExecutionManifest manifest : plan.executionManifests()) {
            addCause(keys, manifest.cause());
            if (manifest instanceof CycleExecutionManifest cycle) {
                keys.add(cycle.seedKey());
                cycle.finalCredits().keySet().forEach(keys::add);
                cycle.links().forEach(link -> keys.add(link.key()));
            }
            for (SealedPatternExecution execution : manifest.patternExecutions()) {
                execution.pattern().inputs().forEach(input -> input.candidates().forEach(candidate -> {
                    keys.add(candidate.key());
                    candidate.remainder().ifPresent(remainder -> keys.add(remainder.key()));
                }));
                execution.pattern().outputs().forEach(output -> keys.add(output.key()));
                execution.plannedSelections().forEach(input -> addSelection(keys, input));
                execution.plannedOutputs().forEach(output -> keys.add(output.key()));
            }
        }
        return keys;
    }

    private static void addCause(Set<KeyId> keys, PlannedBatchCause cause) {
        if (cause instanceof PlannedBatchCause.Root root)
            keys.add(root.output());
        else
            keys.add(((PlannedBatchCause.Input) cause).key());
    }

    private static void addSelection(Set<KeyId> keys, PlannedInputSelection selection) {
        keys.add(selection.consumedKey());
        selection.remainderReturn().ifPresent(remainder -> keys.add(remainder.key()));
    }

    private static void assertUnmodifiable(ExactCraftingPlan plan) {
        assertFalse(plan.causalSteps().isEmpty());
        try {
            plan.causalSteps().clear();
            throw new AssertionError("causal steps must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            plan.finalSurplus().clear();
            throw new AssertionError("plan maps must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }

    private static ExactCraftingPlan success(PersistenceDecodeResult<ExactCraftingPlan> result) {
        PersistenceDecodeResult.Success<ExactCraftingPlan> success = assertInstanceOf(
                PersistenceDecodeResult.Success.class, result);
        return success.value();
    }

    private static void assertFailure(PersistenceDecodeResult<?> result, PersistenceDecodeResult.Reason reason) {
        PersistenceDecodeResult.Failure<?> failure = assertInstanceOf(PersistenceDecodeResult.Failure.class, result);
        assertEquals(reason, failure.reason());
    }

    private static CompiledPattern pattern(String id, List<CompiledInputSpec> inputs,
            List<CompiledOutputSpec> outputs, MachineIntent intent) {
        return new CompiledPattern(new PatternId(id), PatternKind.PROCESSING, inputs, outputs, Optional.of(intent),
                new PatternRevision(0L), KEY_GENERATION);
    }

    private static CompiledInputSpec input(int key, long multiplier, List<CompiledCandidateSpec> candidates,
            SubstitutionPolicy policy) {
        return new CompiledInputSpec(candidates, AEAmount.of(multiplier), policy);
    }

    private static CompiledCandidateSpec candidate(int key, long amount,
            Optional<appeng.rebuild.pattern.CompiledRemainderSpec> remainder) {
        return new CompiledCandidateSpec(new KeyId(key), AEAmount.of(amount), remainder);
    }

    private static appeng.rebuild.pattern.CompiledRemainderSpec remainder(int key, long amount) {
        return new appeng.rebuild.pattern.CompiledRemainderSpec(new KeyId(key), AEAmount.of(amount));
    }

    private static CompiledOutputSpec output(int key, long amount, boolean primary) {
        return new CompiledOutputSpec(new KeyId(key), AEAmount.of(amount), primary);
    }

    private static NormalizedPatternSnapshot patternSnapshot(List<CompiledPattern> patterns,
            Map<PatternId, Integer> priorities) {
        CompiledPatternGraph graph = assertInstanceOf(GraphBuildResult.Success.class,
                new CompiledPatternGraphBuilder(GRAPH_GENERATION, KEY_GENERATION).build(patterns)).graph();
        return new NormalizedPatternSnapshot(SERVER_GENERATION, RECIPE_REVISION, KEY_GENERATION, graph.patternsById(),
                graph, priorities, patterns.size(), false, List.of());
    }

    private static StorageSnapshot snapshot(int keyCount, Map<Integer, AEAmount> values) {
        AmountVector amounts = new AmountVector(keyCount);
        long[] revisions = new long[keyCount];
        for (Map.Entry<Integer, AEAmount> value : values.entrySet())
            amounts.set(value.getKey(), value.getValue());
        return new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, keyCount, amounts, revisions);
    }

    private record NormalFixture(ExactCraftingPlan plan, KeyRegistry source, Map<Integer, AEKey> keys) {
    }

    private record CycleFixture(ExactCraftingPlan plan, KeyRegistry source, Map<Integer, AEKey> keys) {
    }
}
