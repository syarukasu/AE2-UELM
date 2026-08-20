package appeng.rebuild.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.me.service.helpers.NetworkCraftingProviders;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.pattern.NormalizedPatternBuildResult.Failure;
import appeng.rebuild.pattern.NormalizedPatternBuildResult.Success;
import appeng.rebuild.quantity.AEAmount;
import appeng.util.BootstrapMinecraft;

/** Focused tests for the immutable normalized provider shadow and legacy compatibility paths. */
@BootstrapMinecraft
class NetworkCraftingProvidersNormalizedShadowTest {
    @Test
    void successfulBuildDeduplicatesCompiledIdentityRetainsMaximumPriorityAndCountsPhysicalBindings() {
        NetworkCraftingProviders providers = new NetworkCraftingProviders();
        PatternProviderLogic firstProvider = mock(PatternProviderLogic.class);
        PatternProviderLogic secondProvider = mock(PatternProviderLogic.class);
        BuiltIn firstBuiltIn = builtIn();
        BuiltIn secondBuiltIn = builtIn();
        IPatternDetails firstDetails = firstBuiltIn.details();
        IPatternDetails secondDetails = secondBuiltIn.details();
        AEKey inputKey = mock(AEKey.class);
        AEKey outputKey = mock(AEKey.class);
        stubPrimaryOutput(firstDetails, outputKey);
        stubPrimaryOutput(secondDetails, outputKey);
        CompiledPattern compiled = compiled(firstBuiltIn.id().value(), 17L);
        LegacyPatternNormalizer normalizer = mock(LegacyPatternNormalizer.class);
        when(normalizer.keyRegistryGeneration()).thenReturn(17L);
        PatternNormalizationResult.Success normalized = PatternNormalizationResult.success(
                PatternTestFixtures.crafting(new PatternId("same"),
                        List.of(PatternTestFixtures.input(
                                List.of(PatternTestFixtures.candidate(inputKey, 1)), SubstitutionPolicy.EXACT)),
                        List.of(PatternTestFixtures.output(outputKey, 1, true))),
                compiled);
        when(normalizer.normalize(any(), any(), any())).thenReturn(normalized);
        when(firstProvider.getAvailablePatterns()).thenReturn(List.of(firstDetails));
        when(secondProvider.getAvailablePatterns()).thenReturn(List.of(secondDetails));
        when(firstProvider.getPatternPriority()).thenReturn(2);
        when(secondProvider.getPatternPriority()).thenReturn(9);
        when(firstProvider.prepareRecipeReload(RecipeRevision.ZERO)).thenReturn(prepared(firstDetails));
        when(secondProvider.prepareRecipeReload(RecipeRevision.ZERO)).thenReturn(prepared(secondDetails));

        providers.addProvider(node(firstProvider));
        providers.addProvider(node(secondProvider));

        NormalizedPatternBuildResult buildResult = providers.buildNormalizedPatternSnapshot(
                new GraphGeneration(0L), 4L, RecipeRevision.ZERO, normalizer);
        Success success = assertInstanceOf(Success.class, buildResult, buildResult.toString());
        NormalizedPatternSnapshot snapshot = success.snapshot();

        assertEquals(Map.of(firstBuiltIn.id(), compiled), snapshot.patternsById());
        assertEquals(Map.of(firstBuiltIn.id(), 9), snapshot.maxProviderPriorities());
        assertEquals(2, snapshot.eligiblePhysicalBindingCount());
        assertEquals(new GraphGeneration(0L), snapshot.graph().generation());
        assertEquals(snapshot.patternsById(), snapshot.graph().patternsById());
        assertEquals(snapshot.graph().patternsById().keySet(), snapshot.maxProviderPriorities().keySet());
        assertTrue(snapshot.patternsById().keySet().stream().toList().equals(List.of(firstBuiltIn.id())));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.patternsById().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.maxProviderPriorities().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.diagnostics().clear());
    }

    @Test
    void unequalCompiledCollisionFailsWholeShadow() {
        NetworkCraftingProviders providers = new NetworkCraftingProviders();
        PatternProviderLogic firstProvider = mock(PatternProviderLogic.class);
        PatternProviderLogic secondProvider = mock(PatternProviderLogic.class);
        BuiltIn firstBuiltIn = builtIn();
        BuiltIn secondBuiltIn = builtIn();
        IPatternDetails firstDetails = firstBuiltIn.details();
        IPatternDetails secondDetails = secondBuiltIn.details();
        LegacyPatternNormalizer normalizer = mock(LegacyPatternNormalizer.class);
        when(normalizer.keyRegistryGeneration()).thenReturn(17L);
        when(firstProvider.getAvailablePatterns()).thenReturn(List.of(firstDetails));
        when(secondProvider.getAvailablePatterns()).thenReturn(List.of(secondDetails));
        when(firstProvider.prepareRecipeReload(RecipeRevision.ZERO)).thenReturn(prepared(firstDetails));
        when(secondProvider.prepareRecipeReload(RecipeRevision.ZERO)).thenReturn(prepared(secondDetails));
        PatternNormalizationResult.Success firstNormalized = normalized(compiled(firstBuiltIn.id().value(), 17L));
        PatternNormalizationResult.Success secondNormalized = normalized(
                compiled(secondBuiltIn.id().value(), 17L, 2L));
        when(normalizer.normalize(eq(firstDetails), any(Level.class), any(RecipeRevision.class)))
                .thenReturn(firstNormalized);
        when(normalizer.normalize(eq(secondDetails), any(Level.class), any(RecipeRevision.class)))
                .thenReturn(secondNormalized);
        providers.addProvider(node(firstProvider));
        providers.addProvider(node(secondProvider));

        Failure failure = assertInstanceOf(Failure.class,
                providers.buildNormalizedPatternSnapshot(new GraphGeneration(0L), 4L, RecipeRevision.ZERO,
                        normalizer));

        assertEquals(NormalizedPatternBuildResult.FailureReason.PATTERN_COLLISION, failure.reason());
        assertEquals("pattern-id", failure.context());
    }

    @Test
    void genericProviderCustomContractUsesLegacyFallbackAndDoesNotNormalize() {
        NetworkCraftingProviders providers = new NetworkCraftingProviders();
        ICraftingProvider generic = genericProvider();
        IPatternDetails details = mock(IPatternDetails.class);
        stubPrimaryOutput(details, mock(AEKey.class));
        when(generic.getAvailablePatterns()).thenReturn(List.of(details));
        IGridNode node = node(generic);
        providers.addProvider(node);
        LegacyPatternNormalizer normalizer = mock(LegacyPatternNormalizer.class);

        Success success = assertInstanceOf(Success.class,
                providers.buildNormalizedPatternSnapshot(new GraphGeneration(0L), 4L, RecipeRevision.ZERO,
                        normalizer));

        assertTrue(success.snapshot().hasLegacyFallback());
        assertEquals(List.of(new NormalizedPatternDiagnostic(
                NormalizedPatternDiagnostic.Reason.LEGACY_FALLBACK, "custom-pattern")),
                success.snapshot().diagnostics());
        assertTrue(success.snapshot().patternsById().isEmpty());
        verify(normalizer, never()).normalize(any(), any(), any());
    }

    @Test
    void genericAddonProviderNormalizesStandardPatternAndRetainsItsPhysicalBinding() {
        NetworkCraftingProviders providers = new NetworkCraftingProviders();
        BuiltIn builtIn = builtIn();
        ICraftingProvider generic = provider(List.of(builtIn.details()), 7);
        providers.addProvider(node(generic));
        LegacyPatternNormalizer normalizer = mock(LegacyPatternNormalizer.class);
        when(normalizer.keyRegistryGeneration()).thenReturn(17L);
        CompiledPattern compiled = compiled(builtIn.id().value(), 17L);
        when(normalizer.normalize(eq(builtIn.details()), any(Level.class), eq(RecipeRevision.ZERO)))
                .thenReturn(normalized(compiled));

        Success success = assertInstanceOf(Success.class,
                providers.buildNormalizedPatternSnapshot(new GraphGeneration(0L), 4L, RecipeRevision.ZERO,
                        normalizer));

        assertFalse(success.snapshot().hasLegacyFallback());
        assertEquals(Map.of(builtIn.id(), compiled), success.snapshot().patternsById());
        assertEquals(Map.of(builtIn.id(), 7), success.snapshot().maxProviderPriorities());
        assertEquals(1, success.snapshot().eligiblePhysicalBindingCount());
        assertEquals(List.of(generic), toList(providers.getMediums(builtIn.details())));
    }

    @Test
    void preparationFailureDisablesWholeShadowWithoutNormalizingPartialProviders() {
        NetworkCraftingProviders providers = new NetworkCraftingProviders();
        BuiltIn first = builtIn();
        BuiltIn failed = builtIn();
        PatternProviderLogic firstProvider = mock(PatternProviderLogic.class);
        PatternProviderLogic failedProvider = mock(PatternProviderLogic.class);
        when(firstProvider.getAvailablePatterns()).thenReturn(List.of(first.details()));
        when(failedProvider.getAvailablePatterns()).thenReturn(List.of(failed.details()));
        when(firstProvider.prepareRecipeReload(RecipeRevision.ZERO)).thenReturn(prepared(first.details()));
        when(failedProvider.prepareRecipeReload(RecipeRevision.ZERO)).thenReturn(
                new PatternProviderRecipeReloadFailure(PatternProviderRecipeReloadFailure.Reason.MALFORMED_PATTERN,
                        "fixture"));
        providers.addProvider(node(firstProvider));
        providers.addProvider(node(failedProvider));
        LegacyPatternNormalizer normalizer = mock(LegacyPatternNormalizer.class);

        Failure result = assertInstanceOf(Failure.class,
                providers.buildNormalizedPatternSnapshot(new GraphGeneration(0L), 4L, RecipeRevision.ZERO,
                        normalizer));

        assertEquals(NormalizedPatternBuildResult.FailureReason.PREPARATION_FAILURE, result.reason());
        assertEquals("provider-MALFORMED_PATTERN", result.context());
        verify(normalizer, never()).normalize(any(), any(), any());
    }

    @Test
    void customPatternEligibilityRetainsLegacyFallbackWithoutNormalization() {
        NetworkCraftingProviders providers = new NetworkCraftingProviders();
        PatternProviderLogic provider = mock(PatternProviderLogic.class);
        IPatternDetails custom = mock(IPatternDetails.class);
        stubPrimaryOutput(custom, mock(AEKey.class));
        when(provider.getAvailablePatterns()).thenReturn(List.of(custom));
        when(provider.prepareRecipeReload(RecipeRevision.ZERO)).thenReturn(prepared(custom, false));
        providers.addProvider(node(provider));
        LegacyPatternNormalizer normalizer = mock(LegacyPatternNormalizer.class);

        Success result = assertInstanceOf(Success.class,
                providers.buildNormalizedPatternSnapshot(new GraphGeneration(0L), 4L, RecipeRevision.ZERO,
                        normalizer));

        assertTrue(result.snapshot().hasLegacyFallback());
        assertTrue(result.snapshot().patternsById().isEmpty());
        assertEquals(0, result.snapshot().eligiblePhysicalBindingCount());
        assertEquals(List.of(new NormalizedPatternDiagnostic(
                NormalizedPatternDiagnostic.Reason.LEGACY_FALLBACK, "custom-pattern")),
                result.snapshot().diagnostics());
        verify(normalizer, never()).normalize(any(), any(), any());
    }

    @Test
    void errorFromProviderPreparationIsNotSwallowed() {
        NetworkCraftingProviders providers = new NetworkCraftingProviders();
        BuiltIn builtIn = builtIn();
        PatternProviderLogic provider = mock(PatternProviderLogic.class);
        when(provider.getAvailablePatterns()).thenReturn(List.of(builtIn.details()));
        AssertionError fatal = new AssertionError("fatal provider");
        when(provider.prepareRecipeReload(RecipeRevision.ZERO)).thenThrow(fatal);
        providers.addProvider(node(provider));

        assertEquals(fatal, assertThrows(AssertionError.class,
                () -> providers.buildNormalizedPatternSnapshot(new GraphGeneration(0L), 4L, RecipeRevision.ZERO,
                        mock(LegacyPatternNormalizer.class))));
    }

    @Test
    void graphKeyGenerationFailureDisablesWholeShadowWithoutPartialGraph() {
        NetworkCraftingProviders providers = new NetworkCraftingProviders();
        BuiltIn builtIn = builtIn();
        PatternProviderLogic provider = mock(PatternProviderLogic.class);
        when(provider.getAvailablePatterns()).thenReturn(List.of(builtIn.details()));
        when(provider.prepareRecipeReload(RecipeRevision.ZERO)).thenReturn(prepared(builtIn.details()));
        providers.addProvider(node(provider));
        LegacyPatternNormalizer normalizer = mock(LegacyPatternNormalizer.class);
        when(normalizer.keyRegistryGeneration()).thenReturn(17L);
        when(normalizer.normalize(any(), any(), any())).thenReturn(normalized(
                compiled(builtIn.id().value(), 18L)));

        Failure result = assertInstanceOf(Failure.class,
                providers.buildNormalizedPatternSnapshot(new GraphGeneration(0L), 4L, RecipeRevision.ZERO,
                        normalizer));

        assertEquals(NormalizedPatternBuildResult.FailureReason.GRAPH_FAILURE, result.reason());
        assertEquals("graph-MIXED_KEY_REGISTRY_GENERATION", result.context());
    }

    @Test
    void legacyCraftingAndMediumCollectionsKeepOrderAndMultiplicity() {
        NetworkCraftingProviders providers = new NetworkCraftingProviders();
        IPatternDetails firstPattern = patternWithOutput("first");
        IPatternDetails secondPattern = patternWithOutput("second");
        AEKey sharedOutput = mock(AEKey.class);
        when(firstPattern.getPrimaryOutput()).thenReturn(new GenericStack(sharedOutput, 1));
        when(secondPattern.getPrimaryOutput()).thenReturn(new GenericStack(sharedOutput, 1));
        ICraftingProvider first = provider(List.of(firstPattern), 1);
        ICraftingProvider second = provider(List.of(firstPattern), 1);
        ICraftingProvider third = provider(List.of(secondPattern), 1);
        providers.addProvider(node(first));
        providers.addProvider(node(second));
        providers.addProvider(node(third));

        List<IPatternDetails> legacyCrafting = List.copyOf(providers.getCraftingFor(sharedOutput));
        List<ICraftingProvider> mediums = new java.util.ArrayList<>();
        providers.getMediums(firstPattern).forEach(mediums::add);
        LegacyPatternNormalizer normalizer = mock(LegacyPatternNormalizer.class);
        assertInstanceOf(Success.class,
                providers.buildNormalizedPatternSnapshot(new GraphGeneration(0L), 4L, RecipeRevision.ZERO,
                        normalizer));

        assertEquals(legacyCrafting, List.copyOf(providers.getCraftingFor(sharedOutput)));
        List<ICraftingProvider> mediumsAfterBuild = new java.util.ArrayList<>();
        providers.getMediums(firstPattern).forEach(mediumsAfterBuild::add);
        assertEquals(mediums, mediumsAfterBuild);
    }

    private static PreparedPatternProviderRecipeReload prepared(IPatternDetails details) {
        return prepared(details, true);
    }

    private static PreparedPatternProviderRecipeReload prepared(IPatternDetails details, boolean eligible) {
        return new PreparedPatternProviderRecipeReload(new Object(), RecipeRevision.ZERO, 0,
                List.of(details), List.of(eligible), Set.of());
    }

    private static PatternNormalizationResult.Success normalized(CompiledPattern compiled) {
        return PatternNormalizationResult.success(
                PatternTestFixtures.crafting(compiled.id(), List.of(PatternTestFixtures.input(
                        List.of(PatternTestFixtures.candidate(mock(AEKey.class), 1)), SubstitutionPolicy.EXACT)),
                        List.of(PatternTestFixtures.output(mock(AEKey.class), 1, true))),
                compiled);
    }

    private static CompiledPattern compiled(String id, long generation) {
        return compiled(id, generation, 1L);
    }

    private static CompiledPattern compiled(String id, long generation, long outputAmount) {
        return new CompiledPattern(new PatternId(id), PatternKind.CRAFTING,
                List.of(new CompiledInputSpec(List.of(new CompiledCandidateSpec(new KeyId(0), AEAmount.ONE,
                        java.util.Optional.empty())), AEAmount.ONE, SubstitutionPolicy.EXACT)),
                List.of(new CompiledOutputSpec(new KeyId(1), AEAmount.of(outputAmount), true)),
                java.util.Optional.empty(), new PatternRevision(0), generation);
    }

    private static IGridNode node(ICraftingProvider provider) {
        IGridNode node = mock(IGridNode.class);
        when(node.getService(ICraftingProvider.class)).thenReturn(provider);
        when(provider.getEmitableItems()).thenReturn(Set.of());
        ServerLevel level = mock(ServerLevel.class);
        when(node.getLevel()).thenReturn(level);
        return node;
    }

    private static ICraftingProvider provider(List<IPatternDetails> patterns, int priority) {
        ICraftingProvider provider = mock(ICraftingProvider.class);
        when(provider.getAvailablePatterns()).thenReturn(patterns);
        when(provider.getPatternPriority()).thenReturn(priority);
        return provider;
    }

    private static ICraftingProvider genericProvider() {
        return provider(List.of(), 0);
    }

    private static List<ICraftingProvider> toList(Iterable<ICraftingProvider> providers) {
        List<ICraftingProvider> result = new java.util.ArrayList<>();
        providers.forEach(result::add);
        return result;
    }

    private static IPatternDetails patternWithOutput(String id) {
        IPatternDetails pattern = mock(IPatternDetails.class, id);
        when(pattern.getPrimaryOutput()).thenReturn(new GenericStack(mock(AEKey.class, id), 1));
        return pattern;
    }

    private static void stubPrimaryOutput(IPatternDetails pattern, AEKey key) {
        when(pattern.getPrimaryOutput()).thenReturn(new GenericStack(key, 1));
    }

    private static BuiltIn builtIn() {
        AECraftingPattern details = mock(AECraftingPattern.class);
        AEItemKey definition = AEItemKey.of(Items.STICK);
        when(details.getDefinition()).thenReturn(definition);
        stubPrimaryOutput(details, mock(AEKey.class));
        PatternId id = ((PatternIdCreationResult.Success) PatternIdFactory.create(PatternKind.CRAFTING, definition))
                .patternId();
        return new BuiltIn(details, id);
    }

    private record BuiltIn(IPatternDetails details, PatternId id) {
    }
}
