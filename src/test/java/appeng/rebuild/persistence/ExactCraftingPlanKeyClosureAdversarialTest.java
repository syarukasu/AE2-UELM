package appeng.rebuild.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.api.stacks.AEKey;
import appeng.rebuild.execution.ExactCraftingPlan;
import appeng.rebuild.execution.ExactCraftingPlanValidator;
import appeng.rebuild.execution.ExactPlanValidationResult;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.pattern.CompiledOutputSpec;
import appeng.rebuild.pattern.CompiledPattern;
import appeng.rebuild.pattern.CompiledPatternGraph;
import appeng.rebuild.pattern.CompiledPatternGraphBuilder;
import appeng.rebuild.pattern.GraphBuildResult;
import appeng.rebuild.pattern.GraphGeneration;
import appeng.rebuild.pattern.NormalizedPatternSnapshot;
import appeng.rebuild.pattern.PatternId;
import appeng.rebuild.pattern.PatternKind;
import appeng.rebuild.pattern.PatternRevision;
import appeng.rebuild.pattern.RecipeRevision;
import appeng.rebuild.planner.ExactCraftPlanResult;
import appeng.rebuild.planner.ExactCraftPlanner;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.quantity.AEAmount;
import appeng.rebuild.quantity.AmountVector;
import appeng.rebuild.storage.StorageRevision;
import appeng.rebuild.storage.StorageSnapshot;

/** Ensures the persisted plan key closure is exact and failures cannot partially rebind a registry. */
class ExactCraftingPlanKeyClosureAdversarialTest {
    private static final long OLD_GENERATION = 7L;
    private static final long CURRENT_GENERATION = 19L;
    private static final long SERVER_GENERATION = 9L;
    private static final GraphGeneration GRAPH_GENERATION = new GraphGeneration(3L);
    private static final RecipeRevision RECIPE_REVISION = new RecipeRevision(5L);

    @Test
    void unknownExtraAndMissingKeyClosureRejectTypedAndPreserveCurrentRegistry() {
        AEKey output = key("closure:output");
        AEKey extra = key("closure:extra");
        ExactCraftingPlan plan = noInputPlan();
        KeyRegistry source = new KeyRegistry(OLD_GENERATION);
        source.intern(output);
        CompoundTag encoded = ExactCraftingPlanNbtCodec.encode(plan, source);

        KeyRegistry current = new KeyRegistry(CURRENT_GENERATION);
        AEKey existing = key("closure:existing");
        KeyId existingId = current.intern(existing);
        Map<String, AEKey> decodedKeys = Map.of("closure:output", output, "closure:extra", extra);

        try (MockedStatic<AEKey> decoder = mockStatic(AEKey.class)) {
            decoder.when(() -> AEKey.fromTagGeneric(any(CompoundTag.class))).thenAnswer(call -> decodedKeys.get(
                    call.getArgument(0, CompoundTag.class).getString("#c")));

            CompoundTag unknown = encoded.copy();
            unknown.getCompound("r").putInt("k", 1);
            assertMalformedAndUnchanged(unknown, current, existing, existingId);

            CompoundTag missing = encoded.copy();
            missing.getCompound("k").getList("e", Tag.TAG_COMPOUND).remove(0);
            assertMalformedAndUnchanged(missing, current, existing, existingId);

            CompoundTag extraTableEntry = encoded.copy();
            ListTag entries = extraTableEntry.getCompound("k").getList("e", Tag.TAG_COMPOUND);
            CompoundTag extraEntry = new CompoundTag();
            extraEntry.putInt("i", 1);
            extraEntry.put("k", identity("closure:extra"));
            entries.add(extraEntry);
            assertMalformedAndUnchanged(extraTableEntry, current, existing, existingId);

            CompoundTag forgedPlanField = encoded.copy();
            forgedPlanField.getCompound("r").putString("forged", "reject");
            assertMalformedAndUnchanged(forgedPlanField, current, existing, existingId);
        }
    }

    private static void assertMalformedAndUnchanged(CompoundTag root, KeyRegistry current, AEKey existing,
            KeyId existingId) {
        PersistenceDecodeResult.Failure<?> failure = assertInstanceOf(PersistenceDecodeResult.Failure.class,
                ExactCraftingPlanNbtCodec.decode(root, current));
        assertEquals(PersistenceDecodeResult.Reason.MALFORMED, failure.reason());
        assertEquals(1, current.size());
        assertSame(existing, current.resolve(existingId));
    }

    private static ExactCraftingPlan noInputPlan() {
        CompiledPattern pattern = new CompiledPattern(new PatternId("closure-no-input"), PatternKind.CRAFTING,
                List.of(), List.of(new CompiledOutputSpec(new KeyId(0), AEAmount.ONE, true)), Optional.empty(),
                new PatternRevision(0L), OLD_GENERATION);
        CompiledPatternGraph graph = assertInstanceOf(GraphBuildResult.Success.class,
                new CompiledPatternGraphBuilder(GRAPH_GENERATION, OLD_GENERATION).build(List.of(pattern))).graph();
        NormalizedPatternSnapshot patterns = new NormalizedPatternSnapshot(SERVER_GENERATION, RECIPE_REVISION,
                OLD_GENERATION, graph.patternsById(), graph, Map.of(pattern.id(), 0), 1, false, List.of());
        StorageSnapshot storage = new StorageSnapshot(OLD_GENERATION, StorageRevision.ZERO, 1,
                new AmountVector(1), new long[1]);
        ExactCraftPlanResult.Success draft = assertInstanceOf(ExactCraftPlanResult.Success.class,
                new ExactCraftPlanner().plan(new ExactCraftRequest(new KeyId(0), AEAmount.ONE), storage, patterns));
        return assertInstanceOf(ExactPlanValidationResult.Success.class,
                new ExactCraftingPlanValidator().validate(draft.draft(), storage, patterns)).plan();
    }

    private static AEKey key(String channel) {
        AEKey key = mock(AEKey.class);
        when(key.toTagGeneric()).thenReturn(identity(channel));
        return key;
    }

    private static CompoundTag identity(String channel) {
        CompoundTag tag = new CompoundTag();
        tag.putString("#c", channel);
        return tag;
    }
}
