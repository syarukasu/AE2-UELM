package appeng.me.cluster.implementations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.api.stacks.AEKey;
import appeng.rebuild.execution.CpuPlanHandle;
import appeng.rebuild.execution.ExactCpuLedgerSnapshot;
import appeng.rebuild.execution.ExactCpuLedgerState;
import appeng.rebuild.execution.ExactRecoveryCheckpoint;
import appeng.rebuild.execution.ExactTransferBrokerSnapshot;
import appeng.rebuild.execution.ExactTransferBrokerState;
import appeng.rebuild.execution.ReservationId;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.pattern.CompiledCandidateSpec;
import appeng.rebuild.pattern.CompiledInputSpec;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.CompiledPatternGraph;
import appeng.rebuild.pattern.CompiledPatternGraphBuilder;
import appeng.rebuild.pattern.GraphBuildResult;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternKind;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.rebuild.pattern.SubstitutionPolicy;
import appeng.rebuild.persistence.ExactRecoveryCheckpointNbtCodec;
import appeng.rebuild.persistence.PersistenceDecodeResult;
import appeng.rebuild.persistence.PersistenceLimits;
import appeng.rebuild.planner.ExactCraftPlanResult;
import appeng.rebuild.planner.ExactCraftPlanner;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.execution.ExactCraftingPlan;
import appeng.rebuild.execution.ExactCraftingPlanValidator;
import appeng.rebuild.execution.ExactPlanValidationResult;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Native CPU persistence tests; no Minecraft bootstrap, world callback, or live startup is used. */
class ExactCpuRecoveryPersistenceTest {
    private static final long KEY_GENERATION = 801L;
    private static final long SERVER_GENERATION = 803L;
    private static final KeyId INPUT = new KeyId(0);
    private static final KeyId OUTPUT = new KeyId(1);
    private static final CpuPlanHandle HANDLE = new CpuPlanHandle(
            UUID.fromString("00000000-0000-0000-0000-000000000801"), 1L);
    private static final ReservationId RESERVATION = new ReservationId(
            UUID.fromString("00000000-0000-0000-0000-000000000803"));

    @Test
    void legacyCpuNbtWithoutExactTagIsRetainedUnchanged() {
        CraftingCPUCluster cluster = new CraftingCPUCluster(BlockPos.ZERO, BlockPos.ZERO);
        CompoundTag data = new CompoundTag();
        ListTag inventory = new ListTag();
        data.put("inventory", inventory);
        CompoundTag legacyMarker = new CompoundTag();
        legacyMarker.putString("legacy", "untouched");
        data.put("legacy_marker", legacyMarker);

        cluster.readFromNBT(data);
        cluster.writeToNBT(data);

        assertFalse(data.contains(ExactCpuRecoveryPersistence.TAG_EXACT_RECOVERY));
        assertEquals(legacyMarker, data.getCompound("legacy_marker"));
        assertEquals(inventory, data.getList("inventory", Tag.TAG_COMPOUND));
        assertEquals(ExactCpuRecoveryPersistence.State.ABSENT, cluster.exactRecoveryState());
    }

    @Test
    void malformedAndUnknownEvidenceRemainsRawAndFailsClosedWithoutRegistryMutation() {
        AtomicInteger changed = new AtomicInteger();
        ExactCpuRecoveryPersistence persistence = new ExactCpuRecoveryPersistence(changed::incrementAndGet);
        CompoundTag malformedOwner = new CompoundTag();
        malformedOwner.putString(ExactCpuRecoveryPersistence.TAG_EXACT_RECOVERY, "not-a-compound");
        KeyRegistry registry = new KeyRegistry(KEY_GENERATION);

        persistence.readFromNbt(malformedOwner, registry);
        assertEquals(ExactCpuRecoveryPersistence.State.DECODE_REJECTED, persistence.state());
        assertEquals(PersistenceDecodeResult.Reason.MALFORMED, persistence.decodeFailure());
        CompoundTag malformedSaved = new CompoundTag();
        persistence.writeToNbt(malformedSaved);
        assertEquals(malformedOwner, malformedSaved);
        assertEquals(0, registry.size());

        Fixture fixture = fixture(AEAmount.ONE);
        CompoundTag unknown = ExactRecoveryCheckpointNbtCodec.encode(reserved(fixture), fixture.source());
        CompoundTag unknownIdentity = unknown.getCompound("k").getList("e", Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("k");
        unknownIdentity.putString("#c", "native:unknown-key");
        CompoundTag unknownOwner = new CompoundTag();
        unknownOwner.put(ExactCpuRecoveryPersistence.TAG_EXACT_RECOVERY, unknown);
        KeyRegistry empty = new KeyRegistry(KEY_GENERATION + 1);
        try (MockedStatic<AEKey> ignored = decoder(fixture)) {
            persistence.readFromNbt(unknownOwner, empty);
        }
        assertEquals(ExactCpuRecoveryPersistence.State.DECODE_REJECTED, persistence.state());
        assertEquals(PersistenceDecodeResult.Reason.MALFORMED, persistence.decodeFailure());
        assertEquals(0, empty.size(), "unknown evidence must not intern a partial registry");
        CompoundTag unknownSaved = new CompoundTag();
        persistence.writeToNbt(unknownSaved);
        assertEquals(unknownOwner, unknownSaved);
        assertEquals(0, changed.get(), "rejected evidence must not dirty native state");
    }

    @Test
    void decodeMayWaitForRegistryAndRepeatedDecodeHasNoStoragePatternOrRegistrySideEffect() {
        Fixture fixture = fixture(AEAmount.ONE);
        CompoundTag encoded = ExactRecoveryCheckpointNbtCodec.encode(reserved(fixture), fixture.source());
        CompoundTag owner = new CompoundTag();
        owner.put(ExactCpuRecoveryPersistence.TAG_EXACT_RECOVERY, encoded);
        ExactCpuRecoveryPersistence persistence = new ExactCpuRecoveryPersistence(() -> {
            throw new AssertionError("decode must not dirty native CPU");
        });

        persistence.readFromNbt(owner, null);
        assertEquals(ExactCpuRecoveryPersistence.State.PENDING_KEY_REGISTRY, persistence.state());
        CompoundTag beforeDecode = new CompoundTag();
        persistence.writeToNbt(beforeDecode);
        assertEquals(owner, beforeDecode);

        KeyRegistry current = new KeyRegistry(KEY_GENERATION + 1);
        try (MockedStatic<AEKey> ignored = decoder(fixture)) {
            persistence.decodeIfPossible(current);
        }
        assertEquals(ExactCpuRecoveryPersistence.State.PENDING_ACTIVATION, persistence.state());
        assertEquals(2, current.size());
        CompoundTag afterFirstDecode = new CompoundTag();
        persistence.writeToNbt(afterFirstDecode);
        assertEquals(owner, afterFirstDecode, "decode must retain canonical raw evidence");

        int registrySize = current.size();
        persistence.decodeIfPossible(current);
        persistence.decodeIfPossible(current);
        assertEquals(registrySize, current.size());
        assertEquals(ExactCpuRecoveryPersistence.State.PENDING_ACTIVATION, persistence.state());
    }

    @Test
    void exactQuantitiesSurviveNativeSaveReloadWithoutNarrowing() {
        for (BigInteger value : List.of(BigInteger.ONE.shiftLeft(64), BigInteger.ONE.shiftLeft(128),
                BigInteger.TEN.pow(1000))) {
            Fixture fixture = fixture(AEAmount.of(value));
            CompoundTag owner = new CompoundTag();
            ExactCpuRecoveryPersistence writer = new ExactCpuRecoveryPersistence(() -> {
                // No native core is involved in this test; replacement callback is asserted separately.
            });
            try (MockedStatic<AEKey> ignored = decoder(fixture)) {
                writer.replace(reserved(fixture), fixture.source());
            }
            writer.writeToNbt(owner);

            ExactCpuRecoveryPersistence reader = new ExactCpuRecoveryPersistence(() -> {
                throw new AssertionError("reload decode must not dirty native CPU");
            });
            KeyRegistry current = new KeyRegistry(KEY_GENERATION + 1);
            try (MockedStatic<AEKey> ignored = decoder(fixture)) {
                reader.readFromNbt(owner, current);
            }
            assertEquals(ExactCpuRecoveryPersistence.State.PENDING_ACTIVATION, reader.state());
            assertEquals(2, current.size());
        }
    }

    @Test
    void overLimitEvidenceBecomesBoundedTombstoneAndIsNeverRetried() {
        CompoundTag overLimit = new CompoundTag();
        CompoundTag cursor = overLimit;
        for (int i = 0; i <= PersistenceLimits.MAX_NBT_DEPTH; i++) {
            CompoundTag child = new CompoundTag();
            cursor.put("n", child);
            cursor = child;
        }
        CompoundTag owner = new CompoundTag();
        owner.put(ExactCpuRecoveryPersistence.TAG_EXACT_RECOVERY, overLimit);
        ExactCpuRecoveryPersistence persistence = new ExactCpuRecoveryPersistence(() -> {
            throw new AssertionError("rejected evidence must not dirty native CPU");
        });
        KeyRegistry registry = new KeyRegistry(KEY_GENERATION);

        persistence.readFromNbt(owner, null);
        assertEquals(ExactCpuRecoveryPersistence.State.DECODE_REJECTED, persistence.state());
        assertEquals(PersistenceDecodeResult.Reason.LIMIT_EXCEEDED, persistence.decodeFailure());
        CompoundTag saved = new CompoundTag();
        persistence.writeToNbt(saved);
        assertEquals(PersistenceDecodeResult.Reason.LIMIT_EXCEEDED.name(),
                saved.getCompound(ExactCpuRecoveryPersistence.TAG_EXACT_RECOVERY)
                        .getString("exact_recovery_rejected"));

        persistence.decodeIfPossible(registry);
        assertEquals(0, registry.size());
        assertEquals(ExactCpuRecoveryPersistence.State.DECODE_REJECTED, persistence.state());
        assertEquals(PersistenceDecodeResult.Reason.LIMIT_EXCEEDED, persistence.decodeFailure());
    }

    @Test
    void rejectedEvidenceIsNotRetriedAfterAKeyRegistryBecomesAvailable() {
        CompoundTag owner = new CompoundTag();
        CompoundTag malformed = new CompoundTag();
        malformed.putInt("v", PersistenceLimits.FORMAT_VERSION + 1);
        owner.put(ExactCpuRecoveryPersistence.TAG_EXACT_RECOVERY, malformed);
        ExactCpuRecoveryPersistence persistence = new ExactCpuRecoveryPersistence(() -> {
            throw new AssertionError("decode rejection must not dirty native CPU");
        });
        KeyRegistry registry = new KeyRegistry(KEY_GENERATION);
        persistence.readFromNbt(owner, null);
        assertEquals(ExactCpuRecoveryPersistence.State.PENDING_KEY_REGISTRY, persistence.state());
        persistence.decodeIfPossible(registry);
        assertEquals(ExactCpuRecoveryPersistence.State.DECODE_REJECTED, persistence.state());
        assertEquals(PersistenceDecodeResult.Reason.MALFORMED, persistence.decodeFailure());
        persistence.decodeIfPossible(registry);
        assertEquals(0, registry.size());
        assertEquals(PersistenceDecodeResult.Reason.MALFORMED, persistence.decodeFailure());
    }

    @Test
    void replaceSchedulesDirtyBeforePublishingAndCallbackFailureIsAtomic() {
        Fixture fixture = fixture(AEAmount.ONE);
        AtomicInteger changed = new AtomicInteger();
        AtomicBoolean callbackSawPriorEvidence = new AtomicBoolean();
        final ExactCpuRecoveryPersistence[] holder = new ExactCpuRecoveryPersistence[1];
        holder[0] = new ExactCpuRecoveryPersistence(() -> {
            changed.incrementAndGet();
            callbackSawPriorEvidence.set(holder[0].state() == ExactCpuRecoveryPersistence.State.ABSENT);
            CompoundTag visible = new CompoundTag();
            holder[0].writeToNbt(visible);
            callbackSawPriorEvidence.compareAndSet(true,
                    !visible.contains(ExactCpuRecoveryPersistence.TAG_EXACT_RECOVERY));
        });
        try (MockedStatic<AEKey> ignored = decoder(fixture)) {
            holder[0].replace(reserved(fixture), fixture.source());
        }
        assertEquals(1, changed.get());
        assertTrue(callbackSawPriorEvidence.get(), "markDirty must run before the new exact authority is published");

        Fixture replacement = fixture(AEAmount.of(2L));
        AtomicBoolean failNow = new AtomicBoolean();
        ExactCpuRecoveryPersistence failing = new ExactCpuRecoveryPersistence(() -> {
            if (failNow.get())
                throw new IllegalStateException("native save failed");
        });
        try (MockedStatic<AEKey> ignored = decoder(fixture)) {
            failing.replace(reserved(fixture), fixture.source());
        }
        CompoundTag retained = new CompoundTag();
        failing.writeToNbt(retained);
        failNow.set(true);
        assertThrows(IllegalStateException.class, () -> {
            try (MockedStatic<AEKey> ignored = decoder(replacement)) {
                failing.replace(reserved(replacement), replacement.source());
            }
        });
        CompoundTag afterFailure = new CompoundTag();
        failing.writeToNbt(afterFailure);
        assertEquals(retained, afterFailure, "throwing dirty callback must retain the prior exact authority");
        assertEquals(ExactCpuRecoveryPersistence.State.PENDING_ACTIVATION, failing.state());
    }

    @Test
    void clusterRejectsReplacementWithoutCurrentServerThreadGridRegistryAndDoesNotDirty() {
        Fixture fixture = fixture(AEAmount.ONE);
        CraftingCPUCluster cluster = new CraftingCPUCluster(BlockPos.ZERO, BlockPos.ZERO);
        assertThrows(IllegalStateException.class, () -> cluster.replaceExactRecovery(reserved(fixture)));
        assertEquals(ExactCpuRecoveryPersistence.State.ABSENT, cluster.exactRecoveryState());
        CompoundTag saved = new CompoundTag();
        cluster.writeToNBT(saved);
        assertFalse(saved.contains(ExactCpuRecoveryPersistence.TAG_EXACT_RECOVERY));
    }

    private static ExactRecoveryCheckpoint reserved(Fixture fixture) {
        ExactCpuLedgerSnapshot ledger = new ExactCpuLedgerSnapshot(ExactCpuLedgerState.RESERVED, 1L,
                Optional.of(HANDLE), Optional.of(RESERVATION), fixture.plan().initialStorageDebits(), Optional.empty(),
                Optional.empty());
        ExactTransferBrokerSnapshot broker = new ExactTransferBrokerSnapshot(ExactTransferBrokerState.RESERVED,
                Optional.of(HANDLE), Optional.of(fixture.plan().planId()), Optional.of(RESERVATION), Optional.empty(),
                Optional.empty(), fixture.plan().initialStorageDebits(), Optional.empty());
        return new ExactRecoveryCheckpoint(fixture.plan(), ledger, broker, Optional.empty(), false);
    }

    private static Fixture fixture(AEAmount demand) {
        AEKey inputKey = mock(AEKey.class);
        AEKey outputKey = mock(AEKey.class);
        Map<Integer, AEKey> keys = Map.of(0, inputKey, 1, outputKey);
        stubTags(keys);
        KeyRegistry source = new KeyRegistry(KEY_GENERATION);
        source.intern(inputKey);
        source.intern(outputKey);

        CompiledPattern pattern = new CompiledPattern(new PatternId("native-persistence"), PatternKind.CRAFTING,
                List.of(new CompiledInputSpec(
                        List.of(new CompiledCandidateSpec(INPUT, AEAmount.ONE, Optional.empty())), AEAmount.ONE,
                        SubstitutionPolicy.EXACT)),
                List.of(new CompiledOutputSpec(OUTPUT, AEAmount.ONE, true)), Optional.empty(),
                new PatternRevision(0L), KEY_GENERATION);
        CompiledPatternGraph graph = assertInstanceOf(GraphBuildResult.Success.class,
                new CompiledPatternGraphBuilder(new appeng.rebuild.pattern.GraphGeneration(811L), KEY_GENERATION)
                        .build(List.of(pattern)))
                .graph();
        NormalizedPatternSnapshot patterns = new NormalizedPatternSnapshot(SERVER_GENERATION,
                new RecipeRevision(813L), KEY_GENERATION, graph.patternsById(), graph, Map.of(pattern.id(), 0), 1,
                false, List.of());
        AmountVector amounts = new AmountVector(2);
        amounts.set(INPUT.value(), demand);
        StorageSnapshot storage = new StorageSnapshot(KEY_GENERATION, StorageRevision.ZERO, 2, amounts,
                new long[2]);
        ExactCraftPlanResult.Success planned = assertInstanceOf(ExactCraftPlanResult.Success.class,
                new ExactCraftPlanner().plan(new ExactCraftRequest(OUTPUT, demand), storage, patterns));
        ExactPlanValidationResult.Success validated = assertInstanceOf(ExactPlanValidationResult.Success.class,
                new ExactCraftingPlanValidator().validate(planned.draft(), storage, patterns));
        return new Fixture(validated.plan(), source, keys);
    }

    private static void stubTags(Map<Integer, AEKey> keys) {
        keys.forEach((id, key) -> when(key.toTagGeneric()).thenReturn(identity("native:key:" + id)));
    }

    private static MockedStatic<AEKey> decoder(Fixture fixture) {
        Map<String, AEKey> byIdentity = new HashMap<>();
        fixture.keys().forEach((id, key) -> byIdentity.put("native:key:" + id, key));
        MockedStatic<AEKey> decoder = mockStatic(AEKey.class);
        decoder.when(() -> AEKey.fromTagGeneric(any(CompoundTag.class))).thenAnswer(call -> {
            CompoundTag identity = call.getArgument(0, CompoundTag.class);
            AEKey key = byIdentity.get(identity.getString("#c"));
            if (key == null)
                throw new IllegalArgumentException("unknown native test key " + identity);
            return key;
        });
        return decoder;
    }

    private static CompoundTag identity(String value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("#c", value);
        return tag;
    }

    private record Fixture(ExactCraftingPlan plan, KeyRegistry source, Map<Integer, AEKey> keys) {
    }
}
