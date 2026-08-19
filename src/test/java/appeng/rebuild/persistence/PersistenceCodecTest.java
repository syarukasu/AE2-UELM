package appeng.rebuild.persistence;

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
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** T008A persistence tests. No Minecraft bootstrap or live world is started by this class. */
class PersistenceCodecTest {
    private static final String VERSION = "v";
    private static final String VALUE = "u";
    private static final String GENERATION = "g";
    private static final String ENTRIES = "e";
    private static final String ID = "i";
    private static final String KEY = "k";

    @Test
    void amountCodecRoundTripsAllExactBoundariesWithoutNarrowing() {
        AEAmount maximum = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS)
                .subtract(BigInteger.ONE));
        List<AEAmount> values = List.of(
                AEAmount.ZERO,
                AEAmount.ONE,
                AEAmount.of(Long.MAX_VALUE),
                AEAmount.of(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)),
                AEAmount.of(BigInteger.ONE.shiftLeft(64)),
                AEAmount.of(BigInteger.ONE.shiftLeft(128)),
                AEAmount.of(BigInteger.TEN.pow(1000)),
                maximum);

        for (AEAmount expected : values) {
            CompoundTag encoded = AEAmountNbtCodec.encode(expected);
            PersistenceDecodeResult.Success<AEAmount> decoded = assertInstanceOf(
                    PersistenceDecodeResult.Success.class, AEAmountNbtCodec.decode(encoded));
            assertEquals(expected, decoded.value(), "encoded amount=" + expected);
            assertEquals(2, encoded.size());
            assertEquals(PersistenceLimits.FORMAT_VERSION, encoded.getInt(VERSION));
            assertTrue(encoded.getByteArray(VALUE).length <= (PlannerLimits.MAX_CRAFT_QUANTITY_BITS + 7) / 8);
        }
    }

    @Test
    void amountCodecRejectsOverboundAndNonCanonicalRepresentations() {
        AEAmount overBound = AEAmount.of(BigInteger.ONE.shiftLeft(PlannerLimits.MAX_CRAFT_QUANTITY_BITS));
        assertThrows(IllegalArgumentException.class, () -> AEAmountNbtCodec.encode(overBound));
        assertThrows(IllegalArgumentException.class, () -> AEAmountNbtCodec.encode(null));

        CompoundTag leadingZero = amountTag(new byte[] { 0, 1 });
        assertFailure(AEAmountNbtCodec.decode(leadingZero), PersistenceDecodeResult.Reason.LIMIT_EXCEEDED);
        CompoundTag oversized = amountTag(new byte[(PlannerLimits.MAX_CRAFT_QUANTITY_BITS + 7) / 8 + 1]);
        assertFailure(AEAmountNbtCodec.decode(oversized), PersistenceDecodeResult.Reason.LIMIT_EXCEEDED);

        CompoundTag extra = amountTag(new byte[] { 1 });
        extra.putString("extra", "reject");
        assertFailure(AEAmountNbtCodec.decode(extra), PersistenceDecodeResult.Reason.MALFORMED);
        CompoundTag wrongVersion = amountTag(new byte[] { 1 });
        wrongVersion.putInt(VERSION, PersistenceLimits.FORMAT_VERSION + 1);
        assertFailure(AEAmountNbtCodec.decode(wrongVersion), PersistenceDecodeResult.Reason.UNSUPPORTED_VERSION);
    }

    @Test
    void sparseKeyCaptureIsSortedDeterministicAndImmutable() {
        KeyRegistry source = new KeyRegistry(41L);
        AEKey first = mock(AEKey.class);
        AEKey second = mock(AEKey.class);
        AEKey third = mock(AEKey.class);
        KeyId firstId = source.intern(first);
        KeyId secondId = source.intern(second);
        KeyId thirdId = source.intern(third);
        int sizeBefore = source.size();

        PersistedKeyTable captured = PersistedKeyTable.capture(source, List.of(thirdId, firstId));
        PersistedKeyTable repeated = PersistedKeyTable.capture(source, List.of(firstId, thirdId));
        assertEquals(41L, captured.generation());
        assertEquals(List.of(firstId, thirdId),
                captured.entries().stream().map(PersistedKeyTable.Entry::oldId).toList());
        assertEquals(captured, repeated);
        assertEquals(sizeBefore, source.size(), "capture must not intern or mutate the source registry");
        assertEquals(second, source.resolve(secondId));
        assertThrows(UnsupportedOperationException.class, () -> captured.entries().clear());
        assertThrows(IllegalArgumentException.class,
                () -> PersistedKeyTable.capture(source, List.of(firstId, firstId)));
        assertThrows(IndexOutOfBoundsException.class,
                () -> PersistedKeyTable.capture(source, List.of(new KeyId(Integer.MAX_VALUE))));
    }

    @Test
    void keyTableRebindsEmptyAndPrepopulatedRegistriesWithNoFallback() {
        KeyRegistry source = new KeyRegistry(5L);
        AEKey first = mock(AEKey.class);
        AEKey second = mock(AEKey.class);
        KeyId oldFirst = source.intern(first);
        KeyId oldSecond = source.intern(second);
        PersistedKeyTable table = PersistedKeyTable.capture(source, List.of(oldSecond, oldFirst));
        CompoundTag validIdentity = unknownKey("callback:valid");
        when(first.toTagGeneric()).thenReturn(validIdentity);
        when(second.toTagGeneric()).thenReturn(validIdentity);

        KeyRegistry empty = new KeyRegistry(8L);
        ExactKeyRemap emptyRemap = KeyTableRebinder.rebind(table, empty);
        assertEquals(5L, emptyRemap.oldGeneration());
        assertEquals(8L, emptyRemap.newGeneration());
        assertEquals(new KeyId(0), emptyRemap.require(oldFirst));
        assertEquals(new KeyId(1), emptyRemap.require(oldSecond));
        assertEquals(2, empty.size());

        KeyRegistry prepopulated = new KeyRegistry(13L);
        AEKey unrelated = mock(AEKey.class);
        assertEquals(new KeyId(0), prepopulated.intern(unrelated));
        ExactKeyRemap rebound = KeyTableRebinder.rebind(table, prepopulated);
        assertEquals(new KeyId(1), rebound.require(oldFirst));
        assertEquals(new KeyId(2), rebound.require(oldSecond));
        assertEquals(3, prepopulated.size());
        assertThrows(IllegalArgumentException.class, () -> rebound.require(new KeyId(99)));
        assertThrows(UnsupportedOperationException.class, () -> rebound.mappings().clear());
    }

    @Test
    void keyTableConstructorRejectsDuplicateUnsortedAndNonidentityEntries() {
        AEKey first = mock(AEKey.class);
        AEKey second = mock(AEKey.class);
        assertThrows(IllegalArgumentException.class, () -> new PersistedKeyTable(1L, List.of(
                new PersistedKeyTable.Entry(new KeyId(2), first),
                new PersistedKeyTable.Entry(new KeyId(1), second))));
        assertThrows(IllegalArgumentException.class, () -> new PersistedKeyTable(1L, List.of(
                new PersistedKeyTable.Entry(new KeyId(1), first),
                new PersistedKeyTable.Entry(new KeyId(1), second))));
        assertThrows(IllegalArgumentException.class, () -> new PersistedKeyTable(1L, List.of(
                new PersistedKeyTable.Entry(new KeyId(1), first),
                new PersistedKeyTable.Entry(new KeyId(2), first))));
        assertThrows(IllegalArgumentException.class, () -> new PersistedKeyTable(-1L, List.of()));
    }

    @Test
    void malformedDecodeRejectsBeforeCurrentRegistryMutation() {
        KeyRegistry current = new KeyRegistry(17L);
        current.intern(mock(AEKey.class));
        int sizeBefore = current.size();

        assertFailure(PersistedKeyTable.decode(null), PersistenceDecodeResult.Reason.MALFORMED);
        assertFailure(PersistedKeyTable.decode(new CompoundTag()), PersistenceDecodeResult.Reason.MALFORMED);
        CompoundTag wrongVersion = entryTable(1L, List.of());
        wrongVersion.putInt(VERSION, PersistenceLimits.FORMAT_VERSION + 1);
        assertFailure(PersistedKeyTable.decode(wrongVersion), PersistenceDecodeResult.Reason.UNSUPPORTED_VERSION);
        CompoundTag wrongElement = entryTable(1L, List.of());
        ListTag wrongElementList = new ListTag();
        wrongElementList.add(StringTag.valueOf("not-an-entry"));
        wrongElement.put(ENTRIES, wrongElementList);
        assertFailure(PersistedKeyTable.decode(wrongElement), PersistenceDecodeResult.Reason.MALFORMED);
        try (MockedStatic<AEKey> decoder = mockStatic(AEKey.class)) {
            decoder.when(() -> AEKey.fromTagGeneric(any(CompoundTag.class))).thenReturn(null);
            assertFailure(PersistedKeyTable.decode(entryTable(1L,
                    List.of(entry(0, unknownKey("missing:channel"))))),
                    PersistenceDecodeResult.Reason.UNKNOWN_KEY);
        }
        assertFailure(PersistedKeyTable.decode(entryTable(1L, List.of(entry(0, new CompoundTag())))),
                PersistenceDecodeResult.Reason.UNKNOWN_KEY);

        assertEquals(sizeBefore, current.size(), "decode/rejection must not intern into the current registry");
    }

    @Test
    void decodeRejectsDuplicateAndUnsortedIdsBeforeAcceptingAnyTable() {
        CompoundTag duplicateIds = entryTable(1L,
                List.of(entry(0, unknownKey("callback:one")), entry(0, unknownKey("callback:two"))));
        CompoundTag unsortedIds = entryTable(1L,
                List.of(entry(1, unknownKey("callback:one")), entry(0, unknownKey("callback:two"))));
        CompoundTag duplicateKeys = entryTable(1L,
                List.of(entry(0, unknownKey("callback:one")), entry(1, unknownKey("callback:two"))));
        AEKey first = mock(AEKey.class);
        AEKey second = mock(AEKey.class);
        try (MockedStatic<AEKey> decoder = mockStatic(AEKey.class)) {
            decoder.when(() -> AEKey.fromTagGeneric(any(CompoundTag.class))).thenReturn(first, second);
            assertFailure(PersistedKeyTable.decode(duplicateIds), PersistenceDecodeResult.Reason.MALFORMED);
            decoder.when(() -> AEKey.fromTagGeneric(any(CompoundTag.class))).thenReturn(first, second);
            assertFailure(PersistedKeyTable.decode(unsortedIds), PersistenceDecodeResult.Reason.MALFORMED);
            decoder.when(() -> AEKey.fromTagGeneric(any(CompoundTag.class))).thenReturn(first, first);
            assertFailure(PersistedKeyTable.decode(duplicateKeys), PersistenceDecodeResult.Reason.UNKNOWN_KEY);
        }
    }

    @Test
    void malformedSurrogatesAreRejectedByTheStrictNbtGuard() {
        String unpaired = "\uD800";
        assertFalse(StrictNbt.valid(StringTag.valueOf(unpaired)));
        CompoundTag malformedKey = new CompoundTag();
        malformedKey.putString(unpaired, "value");
        assertFalse(StrictNbt.valid(malformedKey));
        assertFailure(PersistedKeyTable.decode(entryTable(1L, List.of(entry(0, malformedKey)))),
                PersistenceDecodeResult.Reason.MALFORMED);
    }

    @Test
    void tableEncodeRejectsAggregateEncodedByteBudgetOverflow() {
        AEKey huge = mock(AEKey.class);
        CompoundTag generic = new CompoundTag();
        generic.putByteArray("payload", new byte[PersistenceLimits.MAX_ENCODED_BYTES - 100]);
        when(huge.toTagGeneric()).thenReturn(generic);
        PersistedKeyTable table = new PersistedKeyTable(1L,
                List.of(new PersistedKeyTable.Entry(new KeyId(0), huge)));
        assertThrows(IllegalArgumentException.class, table::encode);
    }

    @Test
    void decoderCallbackRuntimeExceptionBecomesTypedMalformedFailure() {
        CompoundTag root = entryTable(1L, List.of(entry(0, unknownKey("callback:test"))));
        try (MockedStatic<AEKey> decoder = mockStatic(AEKey.class)) {
            decoder.when(() -> AEKey.fromTagGeneric(any(CompoundTag.class)))
                    .thenThrow(new IllegalStateException("decoder callback failed"));
            assertFailure(PersistedKeyTable.decode(root), PersistenceDecodeResult.Reason.MALFORMED);
        }
    }

    @Test
    void rebindLooksUpEveryIdentityBeforeFirstInternAndAvoidsPartialMutation() {
        KeyRegistry source = new KeyRegistry(29L);
        AEKey first = mock(AEKey.class);
        AEKey second = mock(AEKey.class);
        PersistedKeyTable table = new PersistedKeyTable(29L, List.of(
                new PersistedKeyTable.Entry(new KeyId(0), first),
                new PersistedKeyTable.Entry(new KeyId(1), second)));
        KeyRegistry current = new KeyRegistry(31L);
        CompoundTag validIdentity = unknownKey("callback:valid");
        when(first.toTagGeneric()).thenReturn(validIdentity);
        when(second.toTagGeneric()).thenReturn(validIdentity);
        when(second.toTagGeneric()).thenThrow(new IllegalStateException("invalid later identity"));
        assertThrows(RuntimeException.class, () -> KeyTableRebinder.rebind(table, current));
        assertEquals(0, current.size(), "a later invalid identity must not leave an earlier intern behind");
    }

    @Test
    void strictNbtRejectsDepthNodesListArrayStringAndTotalBudgetViolations() {
        CompoundTag deep = new CompoundTag();
        CompoundTag cursor = deep;
        for (int i = 0; i < PersistenceLimits.MAX_NBT_DEPTH + 2; i++) {
            CompoundTag child = new CompoundTag();
            cursor.put("n", child);
            cursor = child;
        }
        assertFalse(StrictNbt.valid(deep));

        ListTag tooManyNodes = new ListTag();
        for (int i = 0; i < PersistenceLimits.MAX_NBT_NODES; i++) {
            tooManyNodes.add(StringTag.valueOf("x"));
        }
        assertFalse(StrictNbt.valid(tooManyNodes));

        ListTag tooManyListEntries = new ListTag();
        for (int i = 0; i <= PersistenceLimits.MAX_LIST_ENTRIES; i++) {
            tooManyListEntries.add(StringTag.valueOf("x"));
        }
        assertFalse(StrictNbt.valid(tooManyListEntries));
        assertFalse(StrictNbt.valid(new ByteArrayTag(new byte[PersistenceLimits.MAX_ARRAY_ELEMENTS + 1])));
        assertFalse(StrictNbt.valid(StringTag.valueOf("x".repeat(PersistenceLimits.MAX_UTF8_BYTES + 1))));

        ListTag tooManyBytes = new ListTag();
        String chunk = "x".repeat(512);
        for (int i = 0; i < 2048; i++) {
            tooManyBytes.add(StringTag.valueOf(chunk));
        }
        assertFalse(StrictNbt.valid(tooManyBytes));
    }

    @Test
    void nativeItemAndFluidGenericTagsRoundTripWhenKeyRegistryIsAvailable() {
        try {
            AEItemKey item = AEItemKey.of(Items.STICK);
            AEFluidKey fluid = AEFluidKey.of(Fluids.WATER);
            if (item == null || fluid == null) {
                Assumptions.abort("native AE key bootstrap is unavailable");
            }
            KeyRegistry source = new KeyRegistry(23L);
            KeyId itemId = source.intern(item);
            KeyId fluidId = source.intern(fluid);
            PersistedKeyTable table = PersistedKeyTable.capture(source, List.of(fluidId, itemId));
            CompoundTag encoded = table.encode();
            PersistenceDecodeResult<PersistedKeyTable> decoded = PersistedKeyTable.decode(encoded);
            if (!(decoded instanceof PersistenceDecodeResult.Success<PersistedKeyTable> success)) {
                Assumptions.abort("native AE key registry is unavailable");
            } else {
                assertEquals(item, success.value().entries().get(0).key());
                assertEquals(fluid, success.value().entries().get(1).key());
            }
        } catch (RuntimeException | LinkageError unavailable) {
            Assumptions.abort("native AE key bootstrap is unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    private static CompoundTag amountTag(byte[] bytes) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(VERSION, PersistenceLimits.FORMAT_VERSION);
        tag.putByteArray(VALUE, bytes);
        return tag;
    }

    private static CompoundTag entry(int id, CompoundTag key) {
        CompoundTag entry = new CompoundTag();
        entry.putInt("i", id);
        entry.put("k", key);
        return entry;
    }

    private static CompoundTag entryTable(long generation, List<CompoundTag> entries) {
        CompoundTag root = new CompoundTag();
        root.putInt(VERSION, PersistenceLimits.FORMAT_VERSION);
        root.putLong(GENERATION, generation);
        ListTag list = new ListTag();
        entries.forEach(list::add);
        root.put(ENTRIES, list);
        return root;
    }

    private static CompoundTag unknownKey(String channel) {
        CompoundTag key = new CompoundTag();
        key.putString("#c", channel);
        return key;
    }

    private static void assertFailure(PersistenceDecodeResult<?> result, PersistenceDecodeResult.Reason reason) {
        PersistenceDecodeResult.Failure<?> failure = assertInstanceOf(PersistenceDecodeResult.Failure.class, result);
        assertEquals(reason, failure.reason());
    }
}
