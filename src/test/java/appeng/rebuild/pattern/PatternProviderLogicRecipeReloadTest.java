package appeng.rebuild.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetails.IInput;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;

/** Tests the staged legacy pattern refresh boundary without starting Minecraft. */
class PatternProviderLogicRecipeReloadTest {
    @BeforeAll
    static void bootstrapMinecraftRegistriesForMocks() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void serverThreadGateRunsBeforeDecoderAndRejectsNonServerLevel() {
        Fixture fixture = fixture(1);
        Level clientLevel = mock(Level.class);
        when(fixture.blockEntity.getLevel()).thenReturn(clientLevel);

        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            assertThrows(IllegalStateException.class,
                    () -> fixture.logic.prepareRecipeReload(RecipeRevision.ZERO));
            decoder.verify(() -> PatternDetailsHelper.decodePattern(any(ItemStack.class), any(Level.class), eq(false)),
                    never());
        }
    }

    @Test
    void serverThreadGateRejectsFalseServerThreadBeforeDecoder() {
        Fixture fixture = fixture(1);
        fixture.logic.getPatternInv().setItemDirect(0, new ItemStack(Items.STONE));
        when(fixture.server.isSameThread()).thenReturn(false);

        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            assertThrows(IllegalStateException.class,
                    () -> fixture.logic.prepareRecipeReload(RecipeRevision.ZERO));
            decoder.verify(() -> PatternDetailsHelper.decodePattern(any(ItemStack.class), any(Level.class), eq(false)),
                    never());
        }
    }

    @Test
    void prepareUsesFreshSlotOrderAndNeverRequestsProviderUpdate() {
        Fixture fixture = fixture(2);
        ItemStack first = new ItemStack(Items.STONE);
        ItemStack second = new ItemStack(Items.DIRT);
        fixture.logic.getPatternInv().setItemDirect(0, first);
        fixture.logic.getPatternInv().setItemDirect(1, second);
        IPatternDetails firstDetails = details(IPatternDetails.class, fixture.key, 1);
        IPatternDetails secondDetails = details(IPatternDetails.class, fixture.otherKey, 2);

        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            decoder.when(() -> PatternDetailsHelper.decodePattern(first, fixture.level, false))
                    .thenReturn(firstDetails);
            decoder.when(() -> PatternDetailsHelper.decodePattern(second, fixture.level, false))
                    .thenReturn(secondDetails);
            clearInvocations(fixture.mainNode);

            PatternProviderRecipeReloadResult result = fixture.logic.prepareRecipeReload(new RecipeRevision(7));
            PreparedPatternProviderRecipeReload prepared = assertInstanceOf(PreparedPatternProviderRecipeReload.class,
                    result);
            assertEquals(List.of(firstDetails, secondDetails), prepared.legacyPatterns());
            assertEquals(Set.of(fixture.key, fixture.otherKey), prepared.patternInputs());
            assertEquals(patternStateVersion(fixture.logic), prepared.patternStateVersion());
            assertThrows(UnsupportedOperationException.class, () -> fixture.logic.getAvailablePatterns().clear());
            verifyNoInteractions(fixture.mainNode);
            decoder.verify(() -> PatternDetailsHelper.decodePattern(first, fixture.level, false));
            decoder.verify(() -> PatternDetailsHelper.decodePattern(second, fixture.level, false));
        }
    }

    @Test
    void decoderRuntimeExceptionBecomesTypedFailureAndErrorPropagates() {
        Fixture fixture = fixture(1);
        ItemStack stack = new ItemStack(Items.STONE);
        fixture.logic.getPatternInv().setItemDirect(0, stack);
        RuntimeException callbackFailure = new IllegalStateException("decoder");

        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            decoder.when(() -> PatternDetailsHelper.decodePattern(stack, fixture.level, false))
                    .thenThrow(callbackFailure);
            PatternProviderRecipeReloadFailure failure = assertInstanceOf(PatternProviderRecipeReloadFailure.class,
                    fixture.logic.prepareRecipeReload(RecipeRevision.ZERO));
            assertEquals(PatternProviderRecipeReloadFailure.Reason.LEGACY_EXCEPTION, failure.reason());

            decoder.when(() -> PatternDetailsHelper.decodePattern(stack, fixture.level, false))
                    .thenThrow(new AssertionError("fatal"));
            assertThrows(AssertionError.class, () -> fixture.logic.prepareRecipeReload(RecipeRevision.ZERO));
        }
    }

    @Test
    void malformedNullAndShapeLimitsRejectWithoutNarrowing() {
        Fixture nullFixture = fixture(1);
        ItemStack nullStack = new ItemStack(Items.STONE);
        nullFixture.logic.getPatternInv().setItemDirect(0, nullStack);
        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            decoder.when(() -> PatternDetailsHelper.decodePattern(nullStack, nullFixture.level, false))
                    .thenReturn(null);
            PatternProviderRecipeReloadFailure failure = assertInstanceOf(PatternProviderRecipeReloadFailure.class,
                    nullFixture.logic.prepareRecipeReload(RecipeRevision.ZERO));
            assertEquals(PatternProviderRecipeReloadFailure.Reason.MALFORMED_PATTERN, failure.reason());
        }

        Fixture invalidStack = fixture(1);
        ItemStack invalidStackItem = new ItemStack(Items.STONE);
        invalidStack.logic.getPatternInv().setItemDirect(0, invalidStackItem);
        IPatternDetails invalidDetails = details(IPatternDetails.class, invalidStack.key, 0);
        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            decoder.when(() -> PatternDetailsHelper.decodePattern(invalidStackItem, invalidStack.level, false))
                    .thenReturn(invalidDetails);
            PatternProviderRecipeReloadFailure failure = assertInstanceOf(PatternProviderRecipeReloadFailure.class,
                    invalidStack.logic.prepareRecipeReload(RecipeRevision.ZERO));
            assertEquals(PatternProviderRecipeReloadFailure.Reason.MALFORMED_PATTERN, failure.reason());
            assertTrue(invalidStack.logic.getAvailablePatterns().isEmpty());
        }

        Fixture overLimit = fixture(PatternLimits.MAX_PATTERN_PROVIDER_PATTERNS + 1);
        PatternProviderRecipeReloadFailure shape = assertInstanceOf(PatternProviderRecipeReloadFailure.class,
                overLimit.logic.prepareRecipeReload(RecipeRevision.ZERO));
        assertEquals(PatternProviderRecipeReloadFailure.Reason.SHAPE_LIMIT, shape.reason());
    }

    @Test
    void customDetailsRemainLegacyButBuiltInsAreExactlyEligible() {
        Fixture customFixture = fixture(1);
        ItemStack customStack = new ItemStack(Items.STONE);
        customFixture.logic.getPatternInv().setItemDirect(0, customStack);
        IPatternDetails custom = details(IPatternDetails.class, customFixture.key, 1);
        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            decoder.when(() -> PatternDetailsHelper.decodePattern(customStack, customFixture.level, false))
                    .thenReturn(custom);
            PreparedPatternProviderRecipeReload prepared = assertInstanceOf(PreparedPatternProviderRecipeReload.class,
                    customFixture.logic.prepareRecipeReload(RecipeRevision.ZERO));
            assertSame(custom, prepared.legacyPatterns().get(0));
            assertFalse(prepared.rebuildEligible(0));
        }

        Fixture builtIns = fixture(4);
        ItemStack[] stacks = { new ItemStack(Items.STONE), new ItemStack(Items.DIRT), new ItemStack(Items.SAND),
                new ItemStack(Items.GRAVEL) };
        Class<?>[] kinds = { AECraftingPattern.class, AEProcessingPattern.class, AESmithingTablePattern.class,
                AEStonecuttingPattern.class };
        for (int i = 0; i < stacks.length; i++) {
            builtIns.logic.getPatternInv().setItemDirect(i, stacks[i]);
        }
        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            for (int i = 0; i < stacks.length; i++) {
                IPatternDetails builtIn = details(kinds[i], builtIns.key, i + 1);
                ItemStack stack = stacks[i];
                decoder.when(() -> PatternDetailsHelper.decodePattern(stack, builtIns.level, false))
                        .thenReturn(builtIn);
            }
            PreparedPatternProviderRecipeReload prepared = assertInstanceOf(PreparedPatternProviderRecipeReload.class,
                    builtIns.logic.prepareRecipeReload(RecipeRevision.ZERO));
            assertEquals(List.of(true, true, true, true), prepared.rebuildEligibility());
        }
    }

    @Test
    void preparedCollectionsAreImmutableAndExternalPatternViewIsImmutable() {
        Fixture fixture = fixture(1);
        ItemStack stack = new ItemStack(Items.STONE);
        fixture.logic.getPatternInv().setItemDirect(0, stack);
        IPatternDetails details = details(IPatternDetails.class, fixture.key, 1);
        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            decoder.when(() -> PatternDetailsHelper.decodePattern(stack, fixture.level, false)).thenReturn(details);
            PreparedPatternProviderRecipeReload prepared = assertInstanceOf(PreparedPatternProviderRecipeReload.class,
                    fixture.logic.prepareRecipeReload(RecipeRevision.ZERO));
            assertThrows(UnsupportedOperationException.class, () -> prepared.legacyPatterns().add(details));
            assertThrows(UnsupportedOperationException.class, () -> prepared.rebuildEligibility().add(false));
            assertThrows(UnsupportedOperationException.class, () -> prepared.patternInputs().clear());

            fixture.logic.commitRecipeReload(prepared, RecipeRevision.ZERO);
            List<IPatternDetails> exposed = fixture.logic.getAvailablePatterns();
            assertThrows(UnsupportedOperationException.class, () -> exposed.clear());
            assertEquals(prepared.legacyPatterns(), exposed);
        }
    }

    @Test
    void commitChecksRevisionOwnerEpochAndDoubleCommitBeforeMutation() {
        Fixture fixture = fixture(1);
        ItemStack stack = new ItemStack(Items.STONE);
        fixture.logic.getPatternInv().setItemDirect(0, stack);
        IPatternDetails details = details(IPatternDetails.class, fixture.key, 1);
        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            decoder.when(() -> PatternDetailsHelper.decodePattern(stack, fixture.level, false)).thenReturn(details);
            PreparedPatternProviderRecipeReload prepared = assertInstanceOf(PreparedPatternProviderRecipeReload.class,
                    fixture.logic.prepareRecipeReload(new RecipeRevision(3)));
            clearInvocations(fixture.mainNode);
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.logic.commitRecipeReload(prepared, new RecipeRevision(4)));
            assertTrue(fixture.logic.getAvailablePatterns().isEmpty());
            verifyNoInteractions(fixture.mainNode);

            fixture.logic.commitRecipeReload(prepared, new RecipeRevision(3));
            assertEquals(List.of(details), fixture.logic.getAvailablePatterns());
            assertThrows(IllegalStateException.class,
                    () -> fixture.logic.commitRecipeReload(prepared, new RecipeRevision(3)));

            PreparedPatternProviderRecipeReload stale = assertInstanceOf(PreparedPatternProviderRecipeReload.class,
                    fixture.logic.prepareRecipeReload(new RecipeRevision(5)));
            fixture.logic.updatePatterns();
            assertThrows(IllegalStateException.class,
                    () -> fixture.logic.commitRecipeReload(stale, new RecipeRevision(5)));
        }
    }

    @Test
    void commitRejectsPreparationFromAnotherProviderOwner() {
        Fixture source = fixture(1);
        Fixture target = fixture(1);
        ItemStack stack = new ItemStack(Items.STONE);
        source.logic.getPatternInv().setItemDirect(0, stack);
        IPatternDetails details = details(IPatternDetails.class, source.key, 1);

        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            decoder.when(() -> PatternDetailsHelper.decodePattern(stack, source.level, false)).thenReturn(details);
            PreparedPatternProviderRecipeReload prepared = assertInstanceOf(PreparedPatternProviderRecipeReload.class,
                    source.logic.prepareRecipeReload(RecipeRevision.ZERO));
            assertThrows(IllegalArgumentException.class,
                    () -> target.logic.commitRecipeReload(prepared, RecipeRevision.ZERO));
            assertTrue(target.logic.getAvailablePatterns().isEmpty());
        }
    }

    @Test
    void commitRejectsPatternStateEpochOverflowWithoutWrappingOrMutatingLiveState() throws Exception {
        Fixture fixture = fixture(1);
        ItemStack stack = new ItemStack(Items.STONE);
        fixture.logic.getPatternInv().setItemDirect(0, stack);
        IPatternDetails details = details(IPatternDetails.class, fixture.key, 1);

        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            decoder.when(() -> PatternDetailsHelper.decodePattern(stack, fixture.level, false)).thenReturn(details);
            setPatternStateVersion(fixture.logic, Long.MAX_VALUE);
            PreparedPatternProviderRecipeReload prepared = assertInstanceOf(PreparedPatternProviderRecipeReload.class,
                    fixture.logic.prepareRecipeReload(RecipeRevision.ZERO));
            assertEquals(Long.MAX_VALUE, prepared.patternStateVersion());
            assertThrows(ArithmeticException.class,
                    () -> fixture.logic.commitRecipeReload(prepared, RecipeRevision.ZERO));
            assertEquals(Long.MAX_VALUE, patternStateVersion(fixture.logic));
            assertTrue(fixture.logic.getAvailablePatterns().isEmpty());
        }
    }

    @Test
    void preparedConstructorRejectsMalformedOwnershipBoundsAndMismatchedCollections() {
        Object owner = new Object();
        IPatternDetails details = mock(IPatternDetails.class);
        AEKey key = mock(AEKey.class);

        assertThrows(NullPointerException.class,
                () -> new PreparedPatternProviderRecipeReload(null, RecipeRevision.ZERO, 0,
                        List.of(), List.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> new PreparedPatternProviderRecipeReload(owner, null, 0,
                        List.of(), List.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> new PreparedPatternProviderRecipeReload(owner, RecipeRevision.ZERO, 0,
                        null, List.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> new PreparedPatternProviderRecipeReload(owner, RecipeRevision.ZERO, 0,
                        List.of(), null, Set.of()));
        assertThrows(NullPointerException.class,
                () -> new PreparedPatternProviderRecipeReload(owner, RecipeRevision.ZERO, 0,
                        List.of(), List.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedPatternProviderRecipeReload(owner, RecipeRevision.ZERO, -1,
                        List.of(), List.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedPatternProviderRecipeReload(owner, RecipeRevision.ZERO, 0,
                        List.of(details), List.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> new PreparedPatternProviderRecipeReload(owner, RecipeRevision.ZERO, 0,
                        List.of((IPatternDetails) null), List.of(), Set.of()));
        assertThrows(NullPointerException.class,
                () -> new PreparedPatternProviderRecipeReload(owner, RecipeRevision.ZERO, 0,
                        List.of(), List.of((Boolean) null), Set.of()));
        assertThrows(NullPointerException.class,
                () -> new PreparedPatternProviderRecipeReload(owner, RecipeRevision.ZERO, 0,
                        List.of(), List.of(), Set.of((AEKey) null)));
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedPatternProviderRecipeReload(owner, RecipeRevision.ZERO, 0,
                        Collections.nCopies(PatternLimits.MAX_PATTERN_PROVIDER_PATTERNS + 1, details),
                        Collections.nCopies(PatternLimits.MAX_PATTERN_PROVIDER_PATTERNS + 1, true), Set.of()));
        Set<AEKey> overInputs = new java.util.HashSet<>();
        for (int index = 0; index <= PatternLimits.MAX_PATTERN_PROVIDER_TOTAL_INPUT_CANDIDATES; index++) {
            overInputs.add(mock(AEKey.class));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new PreparedPatternProviderRecipeReload(owner, RecipeRevision.ZERO, 0,
                        List.of(), List.of(), overInputs));
    }

    @Test
    void updatePatternsDoesNotClearLiveStateBeforeDecodeFailure() {
        Fixture fixture = fixture(1);
        ItemStack stack = new ItemStack(Items.STONE);
        fixture.logic.getPatternInv().setItemDirect(0, stack);
        IPatternDetails details = details(IPatternDetails.class, fixture.key, 1);

        try (MockedStatic<PatternDetailsHelper> decoder = mockStatic(PatternDetailsHelper.class)) {
            decoder.when(() -> PatternDetailsHelper.decodePattern(stack, fixture.level)).thenReturn(details);
            fixture.logic.updatePatterns();
            assertEquals(List.of(details), fixture.logic.getAvailablePatterns());

            decoder.when(() -> PatternDetailsHelper.decodePattern(stack, fixture.level))
                    .thenThrow(new IllegalStateException("decode"));
            assertThrows(IllegalStateException.class, fixture.logic::updatePatterns);
            assertEquals(List.of(details), fixture.logic.getAvailablePatterns());
        }
    }

    private static Fixture fixture(int inventorySize) {
        IManagedGridNode mainNode = mock(IManagedGridNode.class);
        doReturn(mainNode).when(mainNode).setFlags(any());
        doReturn(mainNode).when(mainNode).addService(any(), any());
        PatternProviderLogicHost host = mock(PatternProviderLogicHost.class);
        BlockEntity blockEntity = mock(BlockEntity.class);
        ServerLevel level = mock(ServerLevel.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(host.getBlockEntity()).thenReturn(blockEntity);
        when(blockEntity.getLevel()).thenReturn(level);
        when(level.getServer()).thenReturn(server);
        when(server.isSameThread()).thenReturn(true);
        PatternProviderLogic logic = new PatternProviderLogic(mainNode, host, inventorySize);
        AEKey key = mock(AEKey.class);
        AEKey otherKey = mock(AEKey.class);
        when(key.dropSecondary()).thenReturn(key);
        when(otherKey.dropSecondary()).thenReturn(otherKey);
        return new Fixture(logic, mainNode, blockEntity, level, server, key, otherKey);
    }

    private static <T extends IPatternDetails> T details(Class<?> type, AEKey key, long amount) {
        @SuppressWarnings("unchecked")
        T details = (T) mock(type);
        AEItemKey definition = mock(AEItemKey.class);
        IInput input = mock(IInput.class);
        when(details.getDefinition()).thenReturn(definition);
        when(details.getInputs()).thenReturn(new IInput[] { input });
        when(details.getOutputs()).thenReturn(new GenericStack[] { new GenericStack(key, amount) });
        when(input.getMultiplier()).thenReturn(1L);
        when(input.getPossibleInputs()).thenReturn(new GenericStack[] { new GenericStack(key, 1L) });
        return details;
    }

    private static long patternStateVersion(PatternProviderLogic logic) {
        try {
            var field = PatternProviderLogic.class.getDeclaredField("patternStateVersion");
            field.setAccessible(true);
            return field.getLong(logic);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect pattern state epoch", exception);
        }
    }

    private static void setPatternStateVersion(PatternProviderLogic logic, long value) {
        try {
            var field = PatternProviderLogic.class.getDeclaredField("patternStateVersion");
            field.setAccessible(true);
            field.setLong(logic, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot set pattern state epoch fixture", exception);
        }
    }

    private record Fixture(PatternProviderLogic logic, IManagedGridNode mainNode, BlockEntity blockEntity,
            ServerLevel level, MinecraftServer server, AEKey key, AEKey otherKey) {
    }
}
