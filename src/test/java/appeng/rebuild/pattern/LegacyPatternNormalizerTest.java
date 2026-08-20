package appeng.rebuild.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.quantity.AEAmount;

/** Server-thread-confined legacy normalization contract tests. */
class LegacyPatternNormalizerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistriesForLevelMocks() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void trueServerThreadGateAllowsExactBuiltInNormalization() {
        Fixture fixture = fixture(AECraftingPattern.class);

        PatternNormalizationResult.Success result = success(fixture.normalizer.normalize(
                fixture.details, fixture.level, RecipeRevision.ZERO));

        assertEquals(PatternKind.CRAFTING, result.definition().kind());
        assertTrue(result.compiledPattern().keyRegistryGeneration() == fixture.registry().generation());
    }

    @Test
    void falseServerThreadGateRejectsBeforeReadingDefinition() {
        Fixture fixture = fixture(AECraftingPattern.class);
        when(fixture.server.isSameThread()).thenReturn(false);
        doThrow(new AssertionError("definition callback must not run"))
                .when(fixture.details).getDefinition();

        assertThrows(IllegalStateException.class,
                () -> fixture.normalizer.normalize(fixture.details, fixture.level, RecipeRevision.ZERO));
        verify(fixture.details, never()).getDefinition();
        assertEquals(0, fixture.registry().size());
    }

    @Test
    void unknownPatternIsTypedUnsupportedWithoutLegacyCallbacks() {
        Fixture fixture = fixture(IPatternDetails.class);

        PatternNormalizationResult.Failure failure = failure(
                fixture.normalizer.normalize(fixture.details, fixture.level, RecipeRevision.ZERO));

        assertEquals(PatternNormalizationResult.FailureReason.UNSUPPORTED_PATTERN, failure.reason());
        verify(fixture.details, never()).getDefinition();
        assertEquals(0, fixture.registry().size());
    }

    @Test
    void eachExactBuiltInClassMapsToItsPatternKind() {
        assertEquals(PatternKind.CRAFTING, normalizeKind(AECraftingPattern.class));
        assertEquals(PatternKind.PROCESSING, normalizeKind(AEProcessingPattern.class));
        assertEquals(PatternKind.SMITHING, normalizeKind(AESmithingTablePattern.class));
        assertEquals(PatternKind.STONECUTTING, normalizeKind(AEStonecuttingPattern.class));
    }

    @Test
    void addonSubclassUsesItsStandardAe2PatternContract() {
        assertEquals(PatternKind.PROCESSING, normalizeKind(AddonProcessingPattern.class));
    }

    @Test
    void preservesExactAmountsMultiplierFilteringOrderRemainderAndPrimaryOutputs() {
        Fixture fixture = fixture(AECraftingPattern.class);
        AEKey first = fixture.firstKey;
        AEKey filtered = fixture.filteredKey;
        AEKey second = fixture.secondKey;
        GenericStack firstInput = new GenericStack(first, 1000L);
        GenericStack filteredInput = new GenericStack(filtered, 7L);
        GenericStack secondInput = new GenericStack(second, 1L);
        when(fixture.input.getPossibleInputs()).thenReturn(new GenericStack[] {
                firstInput, filteredInput, secondInput
        });
        when(fixture.input.getMultiplier()).thenReturn(3L);
        when(fixture.input.isValid(first, fixture.level)).thenReturn(true);
        when(fixture.input.isValid(filtered, fixture.level)).thenReturn(false);
        when(fixture.input.isValid(second, fixture.level)).thenReturn(true);
        when(fixture.input.getRemainingKey(first)).thenReturn(fixture.remainderKey);
        when(fixture.input.getRemainingKey(second)).thenReturn(null);

        PatternNormalizationResult.Success result = success(
                fixture.normalizer.normalize(fixture.details, fixture.level, RecipeRevision.ZERO));
        InputSpec normalizedInput = result.definition().inputs().get(0);

        assertEquals(AEAmount.of(3L), normalizedInput.multiplier());
        assertEquals(List.of(first, second), normalizedInput.candidates().stream().map(CandidateSpec::key).toList());
        assertEquals(AEAmount.of(1000L), normalizedInput.candidates().get(0).amountPerTemplate());
        assertEquals(AEAmount.of(1L), normalizedInput.candidates().get(1).amountPerTemplate());
        assertEquals(fixture.remainderKey, normalizedInput.candidates().get(0).remainder().orElseThrow().key());
        assertEquals(AEAmount.ONE, normalizedInput.candidates().get(0).remainder().orElseThrow().amountPerTemplate());
        assertTrue(normalizedInput.candidates().get(1).remainder().isEmpty());

        assertEquals(2, result.definition().outputs().size());
        assertEquals(fixture.outputKey, result.definition().outputs().get(0).key());
        assertEquals(AEAmount.of(11L), result.definition().outputs().get(0).amountPerExecution());
        assertTrue(result.definition().outputs().get(0).primary());
        assertEquals(AEAmount.of(2L), result.definition().outputs().get(1).amountPerExecution());
        assertTrue(!result.definition().outputs().get(1).primary());
    }

    @Test
    void processingNormalizesDeterministicMachineIntent() {
        Fixture fixture = fixture(AEProcessingPattern.class);

        PatternNormalizationResult.Success result = success(
                fixture.normalizer.normalize(fixture.details, fixture.level, new RecipeRevision(8L)));
        MachineIntent intent = result.definition().machineIntent().orElseThrow();

        assertEquals("ae2-processing", intent.backendType());
        assertEquals(result.definition().id().value(), intent.recipeFingerprint());
        assertEquals("external-inputs", intent.capabilityFingerprint());
        assertEquals(Map.of(), intent.attributes());
        assertEquals(new PatternRevision(8L), result.definition().revision());
    }

    @Test
    void runtimeCallbackFailureIsTypedButErrorPropagates() {
        Fixture runtimeFixture = fixture(AECraftingPattern.class);
        when(runtimeFixture.details.getInputs()).thenThrow(new RuntimeException("legacy failure"));
        PatternNormalizationResult.Failure runtimeFailure = failure(runtimeFixture.normalizer.normalize(
                runtimeFixture.details, runtimeFixture.level, RecipeRevision.ZERO));
        assertEquals(PatternNormalizationResult.FailureReason.LEGACY_EXCEPTION, runtimeFailure.reason());
        assertEquals(0, runtimeFixture.registry().size());

        Fixture errorFixture = fixture(AECraftingPattern.class);
        when(errorFixture.details.getInputs()).thenThrow(new AssertionError("fatal legacy failure"));
        assertThrows(AssertionError.class, () -> errorFixture.normalizer.normalize(
                errorFixture.details, errorFixture.level, RecipeRevision.ZERO));
        assertEquals(0, errorFixture.registry().size());
    }

    @Test
    void invalidShapeAndIdentityFailuresDoNotMutateRegistry() {
        Fixture invalid = fixture(AECraftingPattern.class);
        when(invalid.details.getDefinition()).thenReturn(null);
        PatternNormalizationResult.Failure invalidFailure = failure(invalid.normalizer.normalize(
                invalid.details, invalid.level, RecipeRevision.ZERO));
        assertEquals(PatternNormalizationResult.FailureReason.INVALID_DEFINITION, invalidFailure.reason());
        assertEquals(0, invalid.registry().size());

        Fixture shape = fixture(AECraftingPattern.class);
        when(shape.details.getInputs()).thenReturn(new IInput[PatternLimits.MAX_INPUT_GROUPS + 1]);
        PatternNormalizationResult.Failure shapeFailure = failure(shape.normalizer.normalize(
                shape.details, shape.level, RecipeRevision.ZERO));
        assertEquals(PatternNormalizationResult.FailureReason.SHAPE_LIMIT, shapeFailure.reason());
        assertEquals(0, shape.registry().size());

        Fixture identity = fixture(AECraftingPattern.class);
        CompoundTag malformed = new CompoundTag();
        malformed.putString("bad", "\uD800");
        when(identity.definition.toTagGeneric()).thenReturn(malformed);
        PatternNormalizationResult.Failure identityFailure = failure(identity.normalizer.normalize(
                identity.details, identity.level, RecipeRevision.ZERO));
        assertEquals(PatternNormalizationResult.FailureReason.IDENTITY_FAILURE, identityFailure.reason());
        assertEquals(0, identity.registry().size());
    }

    @Test
    void successfulCompiledIdsResolveToOriginalKeys() {
        Fixture fixture = fixture(AECraftingPattern.class);
        PatternNormalizationResult.Success result = success(fixture.normalizer.normalize(
                fixture.details, fixture.level, RecipeRevision.ZERO));
        CompiledPattern compiled = result.compiledPattern();

        assertSame(fixture.firstKey, fixture.registry().resolve(compiled.inputs().get(0).candidates().get(0).key()));
        assertSame(fixture.remainderKey,
                fixture.registry()
                        .resolve(compiled.inputs().get(0).candidates().get(0).remainder().orElseThrow().key()));
        assertSame(fixture.filteredKey, fixture.registry().resolve(compiled.inputs().get(0).candidates().get(1).key()));
        assertSame(fixture.secondKey, fixture.registry().resolve(compiled.inputs().get(0).candidates().get(2).key()));
        assertSame(fixture.outputKey, fixture.registry().resolve(compiled.outputs().get(0).key()));
    }

    private static PatternKind normalizeKind(Class<? extends IPatternDetails> patternClass) {
        Fixture fixture = fixture(patternClass);
        return success(fixture.normalizer.normalize(fixture.details, fixture.level, RecipeRevision.ZERO))
                .definition().kind();
    }

    private static PatternNormalizationResult.Success success(PatternNormalizationResult result) {
        return assertInstanceOf(PatternNormalizationResult.Success.class, result);
    }

    private static PatternNormalizationResult.Failure failure(PatternNormalizationResult result) {
        return assertInstanceOf(PatternNormalizationResult.Failure.class, result);
    }

    private static Fixture fixture(Class<? extends IPatternDetails> patternClass) {
        Level level = mock(Level.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);

        AEItemKey definition = mock(AEItemKey.class);
        CompoundTag identity = new CompoundTag();
        identity.putString("id", "minecraft:stone");
        when(definition.toTagGeneric()).thenReturn(identity);

        AEKey firstKey = mock(AEKey.class);
        AEKey filteredKey = mock(AEKey.class);
        AEKey secondKey = mock(AEKey.class);
        AEKey remainderKey = mock(AEKey.class);
        AEKey outputKey = mock(AEKey.class);
        IInput input = mock(IInput.class);
        when(input.getPossibleInputs()).thenReturn(new GenericStack[] {
                new GenericStack(firstKey, 1000L), new GenericStack(filteredKey, 7L), new GenericStack(secondKey, 1L)
        });
        when(input.getMultiplier()).thenReturn(3L);
        when(input.isValid(any(AEKey.class), any(Level.class))).thenReturn(true);
        when(input.getRemainingKey(any(AEKey.class))).thenReturn(null);
        when(input.getRemainingKey(firstKey)).thenReturn(remainderKey);

        IPatternDetails details = mock(patternClass);
        when(details.getDefinition()).thenReturn(definition);
        when(details.getInputs()).thenReturn(new IInput[] { input });
        when(details.getOutputs()).thenReturn(new GenericStack[] {
                new GenericStack(outputKey, 11L), new GenericStack(secondKey, 2L)
        });
        KeyRegistry registry = new KeyRegistry(22L);
        return new Fixture(new LegacyPatternNormalizer(registry), details, level, server, definition, input, registry,
                firstKey, filteredKey, secondKey, remainderKey, outputKey);
    }

    private record Fixture(LegacyPatternNormalizer normalizer, IPatternDetails details, Level level,
            MinecraftServer server, AEItemKey definition, IInput input, KeyRegistry registry, AEKey firstKey,
            AEKey filteredKey, AEKey secondKey, AEKey remainderKey, AEKey outputKey) {
    }

    private static class AddonProcessingPattern extends AEProcessingPattern {
        AddonProcessingPattern(AEItemKey definition) {
            super(definition);
        }
    }
}
