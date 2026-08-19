package appeng.rebuild.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.me.service.CraftingService;
import appeng.me.service.StorageService;
import appeng.rebuild.pattern.NormalizedPatternBuildResult.FailureReason;
import appeng.util.BootstrapMinecraft;

/** Server-thread lifecycle tests for the CraftingService normalized shadow. */
@BootstrapMinecraft
class CraftingServiceNormalizedShadowTest {
    @BeforeEach
    void resetReloadCoordinator() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        setCoordinatorField("activeServer", null);
        setCoordinatorField("active", false);
        setCoordinatorField("failClosed", false);
        setCoordinatorField("pendingRevision", null);
        setCoordinatorField("currentRevision", RecipeRevision.ZERO);
    }

    @AfterEach
    void clearReloadCoordinator() throws Exception {
        setCoordinatorField("activeServer", null);
        setCoordinatorField("active", false);
        setCoordinatorField("failClosed", false);
        setCoordinatorField("pendingRevision", null);
        setCoordinatorField("currentRevision", RecipeRevision.ZERO);
    }

    @Test
    void coordinatorFailureBeforeEndTickDisablesWithoutProviderPreparation() {
        MinecraftServer server = server();
        RecipeReloadCoordinator coordinator = RecipeReloadCoordinator.instance();
        coordinator.onServerAboutToStart(server);
        CraftingServiceFixture fixture = service(server);
        fixture.addProvider();

        coordinator.onServerAboutToStart(server);
        fixture.service().onServerEndTick();

        assertEquals(NormalizedPatternShadowState.Status.DISABLED,
                fixture.service().getNormalizedPatternShadowState().status());
        assertEquals(FailureReason.COORDINATOR_STATE,
                fixture.service().getNormalizedPatternShadowState().failure().orElseThrow().reason());
        verify(fixture.provider(), never()).prepareRecipeReload(any());
    }

    @Test
    void reentrantProviderRefreshDoesNotPublishStaleSuccessAndDoesOneBuildPerEndTick() {
        MinecraftServer server = server();
        RecipeReloadCoordinator.instance().onServerAboutToStart(server);
        CraftingServiceFixture fixture = service(server);
        fixture.addProvider();
        doAnswer(invocation -> {
            fixture.service().refreshNodeCraftingProvider(fixture.node());
            return fixture.prepared();
        }).when(fixture.provider()).prepareRecipeReload(any());

        fixture.service().onServerEndTick();

        assertEquals(NormalizedPatternShadowState.Status.DIRTY,
                fixture.service().getNormalizedPatternShadowState().status());
        assertTrue(fixture.service().getNormalizedPatternSnapshot().isEmpty());
        verify(fixture.provider()).prepareRecipeReload(any());

        doReturn(fixture.prepared()).when(fixture.provider()).prepareRecipeReload(any());
        fixture.service().onServerEndTick();
        assertEquals(new GraphGeneration(0L), fixture.service().getNormalizedPatternSnapshot().orElseThrow()
                .graph().generation());
    }

    @Test
    void successfulDirtyRebuildAdvancesGraphGenerationOnlyAfterPublication() {
        MinecraftServer server = server();
        RecipeReloadCoordinator.instance().onServerAboutToStart(server);
        CraftingServiceFixture fixture = service(server);
        fixture.addProvider();

        fixture.service().onServerEndTick();
        assertEquals(new GraphGeneration(0L), fixture.service().getNormalizedPatternSnapshot().orElseThrow()
                .graph().generation());

        fixture.service().refreshNodeCraftingProvider(fixture.node());
        fixture.service().onServerEndTick();
        assertEquals(new GraphGeneration(1L), fixture.service().getNormalizedPatternSnapshot().orElseThrow()
                .graph().generation());
    }

    @Test
    void failedBuildDoesNotConsumeGraphGeneration() {
        MinecraftServer server = server();
        RecipeReloadCoordinator.instance().onServerAboutToStart(server);
        CraftingServiceFixture fixture = service(server);
        fixture.addProvider();
        when(fixture.provider().prepareRecipeReload(any())).thenReturn(
                new PatternProviderRecipeReloadFailure(PatternProviderRecipeReloadFailure.Reason.MALFORMED_PATTERN,
                        "fixture"));

        fixture.service().onServerEndTick();
        assertEquals(NormalizedPatternShadowState.Status.DISABLED,
                fixture.service().getNormalizedPatternShadowState().status());
        when(fixture.provider().prepareRecipeReload(any())).thenReturn(fixture.prepared());
        fixture.service().refreshNodeCraftingProvider(fixture.node());
        fixture.service().onServerEndTick();
        assertEquals(new GraphGeneration(0L), fixture.service().getNormalizedPatternSnapshot().orElseThrow()
                .graph().generation());
    }

    @Test
    void graphGenerationOverflowDisablesWithoutWrapping() throws Exception {
        MinecraftServer server = server();
        RecipeReloadCoordinator.instance().onServerAboutToStart(server);
        CraftingServiceFixture fixture = service(server);
        fixture.addProvider();
        setServiceField(fixture.service(), "nextGraphGeneration", Long.MAX_VALUE);

        fixture.service().onServerEndTick();

        assertEquals(NormalizedPatternShadowState.Status.DISABLED,
                fixture.service().getNormalizedPatternShadowState().status());
        assertEquals(FailureReason.GRAPH_GENERATION_EXHAUSTED,
                fixture.service().getNormalizedPatternShadowState().failure().orElseThrow().reason());
        assertTrue(fixture.service().getNormalizedPatternSnapshot().isEmpty());
        verify(fixture.provider(), never()).prepareRecipeReload(any());
    }

    @Test
    void providerErrorDisablesShadowAndRethrowsWithoutFailingClosedLegacyProviderMaps() {
        MinecraftServer server = server();
        RecipeReloadCoordinator.instance().onServerAboutToStart(server);
        CraftingServiceFixture fixture = service(server);
        fixture.addProvider();
        AssertionError fatal = new AssertionError("fatal normalization");
        when(fixture.provider().prepareRecipeReload(any())).thenThrow(fatal);

        assertEquals(fatal, assertThrows(AssertionError.class, fixture.service()::onServerEndTick));
        assertEquals(NormalizedPatternShadowState.Status.DISABLED,
                fixture.service().getNormalizedPatternShadowState().status());
        assertEquals(FailureReason.LEGACY_EXCEPTION,
                fixture.service().getNormalizedPatternShadowState().failure().orElseThrow().reason());
        assertEquals(List.of(fixture.details()),
                List.copyOf(fixture.service().getCraftingFor(fixture.outputKey())));
    }

    private static CraftingServiceFixture service(MinecraftServer server) {
        IGrid grid = mock(IGrid.class);
        StorageService storage = new StorageService();
        CraftingService service = new CraftingService(grid, storage, mock(IEnergyService.class));
        IGridNode node = mock(IGridNode.class);
        PatternProviderLogic provider = mock(PatternProviderLogic.class);
        IPatternDetails details = details();
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(node.getLevel()).thenReturn(level);
        when(node.getService(ICraftingProvider.class)).thenReturn(provider);
        when(provider.getAvailablePatterns()).thenReturn(List.of(details));
        when(provider.getEmitableItems()).thenReturn(Set.of());
        when(provider.getPatternPriority()).thenReturn(0);
        PreparedPatternProviderRecipeReload prepared = new PreparedPatternProviderRecipeReload(
                new Object(), RecipeRevision.ZERO, 0, List.of(details), List.of(true), Set.of());
        when(provider.prepareRecipeReload(any())).thenReturn(prepared);
        return new CraftingServiceFixture(service, node, provider, details, prepared,
                (AEKey) details.getPrimaryOutput().what());
    }

    private static IPatternDetails details() {
        AECraftingPattern details = mock(AECraftingPattern.class);
        AEItemKey definition = AEItemKey.of(Items.STICK);
        AEKey key = AEItemKey.of(Items.COBBLESTONE);
        IInput input = mock(IInput.class);
        GenericStack inputStack = new GenericStack(key, 1);
        GenericStack output = new GenericStack(key, 1);
        when(details.getDefinition()).thenReturn(definition);
        when(details.getInputs()).thenReturn(new IInput[] { input });
        when(details.getOutputs()).thenReturn(new GenericStack[] { output });
        when(details.getPrimaryOutput()).thenReturn(output);
        when(input.getMultiplier()).thenReturn(1L);
        when(input.getPossibleInputs()).thenReturn(new GenericStack[] { inputStack });
        when(input.isValid(any(AEKey.class), any(Level.class))).thenReturn(true);
        when(input.getRemainingKey(any(AEKey.class))).thenReturn(null);
        return details;
    }

    private static MinecraftServer server() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        return server;
    }

    private static void setCoordinatorField(String name, Object value) throws Exception {
        Field field = RecipeReloadCoordinator.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(RecipeReloadCoordinator.instance(), value);
    }

    private static void setServiceField(CraftingService service, String name, long value) throws Exception {
        Field field = CraftingService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(service, value);
    }

    private record CraftingServiceFixture(CraftingService service, IGridNode node, PatternProviderLogic provider,
            IPatternDetails details, PreparedPatternProviderRecipeReload prepared, AEKey outputKey) {
        private void addProvider() {
            service.addNode(node, (CompoundTag) null);
        }
    }
}
