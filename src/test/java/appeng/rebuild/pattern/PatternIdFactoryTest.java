package appeng.rebuild.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import appeng.api.stacks.AEItemKey;

/** Restart-stable canonical NBT identity and typed failure contract tests. */
class PatternIdFactoryTest {
    private static final String ID_PATTERN = "sha256:[0-9a-f]{64}";

    @Test
    void semanticCompoundOrderProducesTheSameExactSha256AndDoesNotMutateSources() {
        CompoundTag first = new CompoundTag();
        first.putInt("b", 2);
        first.putInt("a", 1);
        CompoundTag second = new CompoundTag();
        second.putInt("a", 1);
        second.putInt("b", 2);
        CompoundTag firstBefore = first.copy();
        CompoundTag secondBefore = second.copy();

        PatternId firstId = success(PatternKind.CRAFTING, first).patternId();
        PatternId secondId = success(PatternKind.CRAFTING, second).patternId();

        assertEquals(firstId, secondId);
        assertTrue(firstId.value().matches(ID_PATTERN));
        assertEquals(firstBefore, first);
        assertEquals(secondBefore, second);
    }

    @Test
    void patternKindIsHashSensitiveAcrossAllFourKinds() {
        CompoundTag source = genericItemTag();
        Set<PatternId> ids = java.util.EnumSet.allOf(PatternKind.class).stream()
                .map(kind -> success(kind, source).patternId())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(PatternKind.values().length, ids.size());
        for (PatternId id : ids) {
            assertTrue(id.value().matches(ID_PATTERN));
        }
    }

    @Test
    void everyBuiltinTagTypeAndEmptyListMetadataAreHashSensitive() {
        CompoundTag baseline = allBuiltinTypes();
        PatternId baselineId = success(PatternKind.CRAFTING, baseline).patternId();
        List<Consumer<CompoundTag>> mutations = List.of(
                tag -> tag.putByte("byte", (byte) 2),
                tag -> tag.putShort("short", (short) 3),
                tag -> tag.putInt("int", 4),
                tag -> tag.putLong("long", 5L),
                tag -> tag.putFloat("float", 2.0F),
                tag -> tag.putDouble("double", 2.0D),
                tag -> tag.putByteArray("byte-array", new byte[] { 2, 3 }),
                tag -> tag.putString("string", "changed"),
                tag -> tag.getList("list", Tag.TAG_INT).set(0, IntTag.valueOf(9)),
                tag -> tag.getCompound("compound").putString("inner", "changed"),
                tag -> tag.putIntArray("int-array", new int[] { 2, 3 }),
                tag -> tag.putLongArray("long-array", new long[] { 2L, 3L }),
                tag -> {
                    ListTag changed = new ListTag();
                    changed.add(ByteTag.valueOf((byte) 1));
                    tag.put("empty-list", changed);
                });

        for (Consumer<CompoundTag> mutation : mutations) {
            CompoundTag variant = baseline.copy();
            mutation.accept(variant);
            assertNotEquals(baselineId, success(PatternKind.CRAFTING, variant).patternId());
        }

        CompoundTag reorderedList = baseline.copy();
        ListTag list = reorderedList.getList("list", Tag.TAG_INT);
        list.set(0, IntTag.valueOf(2));
        list.set(1, IntTag.valueOf(1));
        assertNotEquals(baselineId, success(PatternKind.CRAFTING, reorderedList).patternId());

        CompoundTag reversedCompound = new CompoundTag();
        reversedCompound.put("compound", baseline.getCompound("compound").copy());
        reversedCompound.put("empty-list", baseline.getList("empty-list", Tag.TAG_END).copy());
        reversedCompound.put("long-array", new LongArrayTag(new long[] { 1L, 2L }));
        reversedCompound.put("int-array", new IntArrayTag(new int[] { 1, 2 }));
        reversedCompound.put("list", baseline.getList("list", Tag.TAG_INT).copy());
        reversedCompound.putString("string", "value");
        reversedCompound.put("byte-array", new ByteArrayTag(new byte[] { 1, 2 }));
        reversedCompound.putDouble("double", 1.0D);
        reversedCompound.putFloat("float", 1.0F);
        reversedCompound.putLong("long", 4L);
        reversedCompound.putInt("int", 3);
        reversedCompound.putShort("short", (short) 2);
        reversedCompound.putByte("byte", (byte) 1);
        assertEquals(baselineId, success(PatternKind.CRAFTING, reversedCompound).patternId());
    }

    @Test
    void nestedGenericFieldsAndFreshEqualKeysRemainDeterministicAndSensitive() {
        CompoundTag first = genericItemTag();
        CompoundTag second = new CompoundTag();
        second.putString("author", "alice");
        second.putString("#c", "ae2:i");
        second.put("caps", first.getCompound("caps").copy());
        second.put("tag", first.getCompound("tag").copy());
        second.putString("id", "minecraft:stone");
        assertEquals(success(PatternKind.CRAFTING, first).patternId(),
                success(PatternKind.CRAFTING, second).patternId());

        List<Consumer<CompoundTag>> mutations = List.of(
                tag -> tag.putString("id", "minecraft:dirt"),
                tag -> tag.getCompound("tag").putInt("Damage", 2),
                tag -> tag.getCompound("caps").putString("energy", "different"),
                tag -> tag.putString("#c", "other:channel"),
                tag -> tag.putString("author", "bob"));
        PatternId firstId = success(PatternKind.CRAFTING, first).patternId();
        for (Consumer<CompoundTag> mutation : mutations) {
            CompoundTag variant = first.copy();
            mutation.accept(variant);
            assertNotEquals(firstId, success(PatternKind.CRAFTING, variant).patternId());
        }

        CompoundTag nestedOne = nestedTag(false);
        CompoundTag nestedTwo = nestedTag(true);
        assertEquals(success(PatternKind.PROCESSING, nestedOne).patternId(),
                success(PatternKind.PROCESSING, nestedTwo).patternId());
    }

    @Test
    void canonicalizesNaNPayloadsButDistinguishesSignedZeroForFloatAndDouble() {
        CompoundTag floatNanOne = single("value", FloatTag.valueOf(Float.intBitsToFloat(0x7FC0_0001)));
        CompoundTag floatNanTwo = single("value", FloatTag.valueOf(Float.intBitsToFloat(0x7FC0_1234)));
        assertEquals(success(PatternKind.CRAFTING, floatNanOne).patternId(),
                success(PatternKind.CRAFTING, floatNanTwo).patternId());

        CompoundTag doubleNanOne = single("value", DoubleTag.valueOf(Double.longBitsToDouble(0x7FF8_0000_0000_0001L)));
        CompoundTag doubleNanTwo = single("value", DoubleTag.valueOf(Double.longBitsToDouble(0x7FF8_0000_0000_1234L)));
        assertEquals(success(PatternKind.CRAFTING, doubleNanOne).patternId(),
                success(PatternKind.CRAFTING, doubleNanTwo).patternId());

        assertNotEquals(success(PatternKind.CRAFTING, single("value", signedZeroFloat(false))).patternId(),
                success(PatternKind.CRAFTING, single("value", signedZeroFloat(true))).patternId());
        assertNotEquals(success(PatternKind.CRAFTING, single("value", signedZeroDouble(false))).patternId(),
                success(PatternKind.CRAFTING, single("value", signedZeroDouble(true))).patternId());
    }

    @Test
    void malformedSurrogatesProduceTypedFailureWithoutPartialIdOrSourceMutation() {
        CompoundTag malformedValue = single("value", StringTag.valueOf("\uD800"));
        CompoundTag malformedValueBefore = malformedValue.copy();
        PatternIdCreationResult.Failure valueFailure = failure(PatternKind.CRAFTING, malformedValue);
        assertFalse(valueFailure.isSuccess());
        assertEquals(PatternIdCreationResult.FailureReason.MALFORMED_TAG, valueFailure.reason());
        assertEquals(malformedValueBefore, malformedValue);

        CompoundTag malformedKey = new CompoundTag();
        malformedKey.putString("\uDC00", "value");
        PatternIdCreationResult.Failure keyFailure = failure(PatternKind.CRAFTING, malformedKey);
        assertEquals(PatternIdCreationResult.FailureReason.MALFORMED_TAG, keyFailure.reason());
    }

    @Test
    void depthStringAndCompoundLimitsAcceptBoundaryAndRejectSuccessor() {
        assertTrue(success(PatternKind.CRAFTING, nestedCompounds(PatternLimits.MAX_PATTERN_ID_NBT_DEPTH))
                .patternId().value().matches(ID_PATTERN));
        assertEquals(PatternIdCreationResult.FailureReason.IDENTITY_LIMIT,
                failure(PatternKind.CRAFTING, nestedCompounds(PatternLimits.MAX_PATTERN_ID_NBT_DEPTH + 1)).reason());

        CompoundTag maxString = single("value",
                StringTag.valueOf("a".repeat(PatternLimits.MAX_PATTERN_ID_NBT_STRING_UTF8_BYTES)));
        assertTrue(success(PatternKind.CRAFTING, maxString).patternId().value().matches(ID_PATTERN));
        CompoundTag overString = single("value",
                StringTag.valueOf("a".repeat(PatternLimits.MAX_PATTERN_ID_NBT_STRING_UTF8_BYTES + 1)));
        assertEquals(PatternIdCreationResult.FailureReason.IDENTITY_LIMIT,
                failure(PatternKind.CRAFTING, overString).reason());

        CompoundTag maxCompound = new CompoundTag();
        for (int index = 0; index < PatternLimits.MAX_PATTERN_ID_NBT_COMPOUND_ENTRIES; index++) {
            maxCompound.putInt("k" + index, index);
        }
        assertTrue(success(PatternKind.CRAFTING, maxCompound).patternId().value().matches(ID_PATTERN));
        CompoundTag overCompound = maxCompound.copy();
        overCompound.putInt("over", 1);
        assertEquals(PatternIdCreationResult.FailureReason.IDENTITY_LIMIT,
                failure(PatternKind.CRAFTING, overCompound).reason());
    }

    @Test
    void listNodeAndTotalEncodedLimitsAreBoundedWithStandardTags() {
        CompoundTag maxNodes = single("nodes", intList(PatternLimits.MAX_PATTERN_ID_NBT_NODES - 2));
        assertTrue(success(PatternKind.CRAFTING, maxNodes).patternId().value().matches(ID_PATTERN));
        CompoundTag overNodes = single("nodes", intList(PatternLimits.MAX_PATTERN_ID_NBT_NODES - 1));
        assertEquals(PatternIdCreationResult.FailureReason.IDENTITY_LIMIT,
                failure(PatternKind.CRAFTING, overNodes).reason());

        CompoundTag overList = single("list", intList(PatternLimits.MAX_PATTERN_ID_NBT_LIST_ENTRIES + 1));
        assertEquals(PatternIdCreationResult.FailureReason.IDENTITY_LIMIT,
                failure(PatternKind.CRAFTING, overList).reason());

        int exactPayload = PatternLimits.MAX_PATTERN_ID_NBT_ENCODED_BYTES - 56 - Integer.BYTES;
        CompoundTag exactTotal = single("bytes", new ByteArrayTag(new byte[exactPayload]));
        assertTrue(success(PatternKind.CRAFTING, exactTotal).patternId().value().matches(ID_PATTERN));
        CompoundTag overTotal = single("bytes", new ByteArrayTag(new byte[exactPayload + 1]));
        assertEquals(PatternIdCreationResult.FailureReason.IDENTITY_LIMIT,
                failure(PatternKind.CRAFTING, overTotal).reason());
    }

    @Test
    void canonicalGoldenVectorFreezesTheWireSchema() {
        CompoundTag tag = single("x", IntTag.valueOf(1));
        PatternIdCreationResult.Success result = success(PatternKind.CRAFTING, tag);

        assertEquals("sha256:9f9b71fa25a79f6d35595eae786ae80737b018d478254dabdc7e8c02685ab28b",
                result.patternId().value());
    }

    private static PatternIdCreationResult.Success success(PatternKind kind, CompoundTag tag) {
        return assertInstanceOf(PatternIdCreationResult.Success.class,
                PatternIdFactory.create(kind, itemKey(tag)));
    }

    private static PatternIdCreationResult.Failure failure(PatternKind kind, CompoundTag tag) {
        return assertInstanceOf(PatternIdCreationResult.Failure.class,
                PatternIdFactory.create(kind, itemKey(tag)));
    }

    private static AEItemKey itemKey(CompoundTag tag) {
        AEItemKey key = mock(AEItemKey.class);
        when(key.toTagGeneric()).thenReturn(tag);
        return key;
    }

    /** FloatTag.valueOf intentionally folds both signed zeros into its public ZERO singleton. */
    private static FloatTag signedZeroFloat(boolean negative) {
        try {
            var constructor = FloatTag.class.getDeclaredConstructor(float.class);
            constructor.setAccessible(true);
            return constructor.newInstance(negative ? -0.0F : +0.0F);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to construct a signed-zero FloatTag fixture", exception);
        }
    }

    /** DoubleTag.valueOf intentionally folds both signed zeros into its public ZERO singleton. */
    private static DoubleTag signedZeroDouble(boolean negative) {
        try {
            var constructor = DoubleTag.class.getDeclaredConstructor(double.class);
            constructor.setAccessible(true);
            return constructor.newInstance(negative ? -0.0D : +0.0D);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to construct a signed-zero DoubleTag fixture", exception);
        }
    }

    private static CompoundTag single(String name, Tag value) {
        CompoundTag tag = new CompoundTag();
        tag.put(name, value);
        return tag;
    }

    private static CompoundTag allBuiltinTypes() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("byte", (byte) 1);
        tag.putShort("short", (short) 2);
        tag.putInt("int", 3);
        tag.putLong("long", 4L);
        tag.putFloat("float", 1.0F);
        tag.putDouble("double", 1.0D);
        tag.putByteArray("byte-array", new byte[] { 1, 2 });
        tag.putString("string", "value");
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(1));
        list.add(IntTag.valueOf(2));
        tag.put("list", list);
        CompoundTag nested = new CompoundTag();
        nested.putString("inner", "value");
        tag.put("compound", nested);
        tag.putIntArray("int-array", new int[] { 1, 2 });
        tag.putLongArray("long-array", new long[] { 1L, 2L });
        tag.put("empty-list", new ListTag());
        return tag;
    }

    private static CompoundTag genericItemTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:stone");
        CompoundTag itemTag = new CompoundTag();
        itemTag.putInt("Damage", 1);
        tag.put("tag", itemTag);
        CompoundTag caps = new CompoundTag();
        caps.putString("energy", "100");
        tag.put("caps", caps);
        tag.putString("#c", "ae2:i");
        tag.putString("author", "alice");
        return tag;
    }

    private static CompoundTag nestedTag(boolean reverseOrder) {
        CompoundTag root = new CompoundTag();
        CompoundTag nested = new CompoundTag();
        ListTag list = new ListTag();
        CompoundTag first = new CompoundTag();
        first.putIntArray("array", new int[] { 1, 2, 3 });
        CompoundTag second = new CompoundTag();
        second.putLongArray("array", new long[] { 4L, 5L });
        list.add(first);
        list.add(second);
        if (reverseOrder) {
            nested.putString("marker", "nested");
            nested.put("list", list);
        } else {
            nested.put("list", list);
            nested.putString("marker", "nested");
        }
        root.put("nested", nested);
        return root;
    }

    private static CompoundTag nestedCompounds(int nestedCount) {
        CompoundTag root = new CompoundTag();
        CompoundTag current = root;
        for (int index = 0; index < nestedCount; index++) {
            CompoundTag child = new CompoundTag();
            current.put("n" + index, child);
            current = child;
        }
        return root;
    }

    private static ListTag intList(int size) {
        ListTag list = new ListTag();
        for (int index = 0; index < size; index++) {
            list.add(IntTag.valueOf(index));
        }
        return list;
    }
}
