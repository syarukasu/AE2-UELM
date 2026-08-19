package appeng.rebuild.pattern;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import appeng.api.stacks.AEItemKey;

/** Creates restart-stable PatternIds from a pattern kind and its canonical generic item-key NBT. */
public final class PatternIdFactory {
    private static final byte[] DOMAIN = "ae2-rebuild-pattern-id-v1".getBytes(StandardCharsets.US_ASCII);
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PatternIdFactory() {
    }

    /**
     * Creates a SHA-256 identity from explicit length-delimited domain, kind, and canonical NBT fields. The input key
     * and its source tag are read only and never used as an object-identity fallback.
     */
    public static PatternIdCreationResult create(PatternKind kind, AEItemKey definition) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(definition, "definition");

        MessageDigest digest = newSha256();
        try {
            DigestSink sink = new DigestSink(digest);
            sink.writeLengthDelimited(DOMAIN);
            sink.writeLengthDelimited(kind.name().getBytes(StandardCharsets.US_ASCII));
            new CanonicalNbtEncoder(sink).encode(definition.toTagGeneric());
            return PatternIdCreationResult.success(new PatternId("sha256:" + toLowerHex(digest.digest())));
        } catch (CanonicalEncodingException exception) {
            return PatternIdCreationResult.failure(exception.reason, exception.context);
        } catch (RuntimeException exception) {
            return PatternIdCreationResult.failure(PatternIdCreationResult.FailureReason.MALFORMED_TAG, "nbt");
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String toLowerHex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = Byte.toUnsignedInt(bytes[index]);
            result[index * 2] = HEX[value >>> 4];
            result[index * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(result);
    }

    private static final class CanonicalNbtEncoder {
        private final DigestSink sink;
        private final CharsetEncoder utf8 = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        private final byte[] utf8Buffer = new byte[PatternLimits.MAX_PATTERN_ID_NBT_STRING_UTF8_BYTES];
        private int nodeCount;

        private CanonicalNbtEncoder(DigestSink sink) {
            this.sink = sink;
        }

        private void encode(CompoundTag root) throws CanonicalEncodingException {
            if (root == null) {
                throw malformed();
            }
            encodeValue(root, -1, 0, true);
        }

        private void encodeValue(Tag tag, int expectedId, int depth, boolean includeId)
                throws CanonicalEncodingException {
            if (depth > PatternLimits.MAX_PATTERN_ID_NBT_DEPTH) {
                throw limit();
            }
            if (tag == null) {
                throw malformed();
            }
            int id = Byte.toUnsignedInt(tag.getId());
            if (id == Tag.TAG_END) {
                throw malformed();
            }
            if (id > Tag.TAG_LONG_ARRAY) {
                throw unsupported();
            }
            if (expectedId >= 0 && id != expectedId) {
                throw malformed();
            }
            if (nodeCount == PatternLimits.MAX_PATTERN_ID_NBT_NODES) {
                throw limit();
            }
            nodeCount++;
            if (includeId) {
                sink.writeByte(id);
            }

            switch (id) {
                case Tag.TAG_BYTE -> writeByteTag(tag);
                case Tag.TAG_SHORT -> writeShortTag(tag);
                case Tag.TAG_INT -> writeIntTag(tag);
                case Tag.TAG_LONG -> writeLongTag(tag);
                case Tag.TAG_FLOAT -> writeFloatTag(tag);
                case Tag.TAG_DOUBLE -> writeDoubleTag(tag);
                case Tag.TAG_BYTE_ARRAY -> writeByteArray(tag);
                case Tag.TAG_STRING -> writeStringTag(tag);
                case Tag.TAG_LIST -> writeList(tag, depth);
                case Tag.TAG_COMPOUND -> writeCompound(tag, depth);
                case Tag.TAG_INT_ARRAY -> writeIntArray(tag);
                case Tag.TAG_LONG_ARRAY -> writeLongArray(tag);
                default -> throw unsupported();
            }
        }

        private void writeByteTag(Tag tag) throws CanonicalEncodingException {
            if (!(tag instanceof ByteTag value)) {
                throw malformed();
            }
            sink.writeByte(value.getAsByte());
        }

        private void writeShortTag(Tag tag) throws CanonicalEncodingException {
            if (!(tag instanceof ShortTag value)) {
                throw malformed();
            }
            sink.writeShort(value.getAsShort());
        }

        private void writeIntTag(Tag tag) throws CanonicalEncodingException {
            if (!(tag instanceof IntTag value)) {
                throw malformed();
            }
            sink.writeInt(value.getAsInt());
        }

        private void writeLongTag(Tag tag) throws CanonicalEncodingException {
            if (!(tag instanceof LongTag value)) {
                throw malformed();
            }
            sink.writeLong(value.getAsLong());
        }

        private void writeFloatTag(Tag tag) throws CanonicalEncodingException {
            if (!(tag instanceof FloatTag value)) {
                throw malformed();
            }
            sink.writeInt(Float.floatToIntBits(value.getAsFloat()));
        }

        private void writeDoubleTag(Tag tag) throws CanonicalEncodingException {
            if (!(tag instanceof DoubleTag value)) {
                throw malformed();
            }
            sink.writeLong(Double.doubleToLongBits(value.getAsDouble()));
        }

        private void writeByteArray(Tag tag) throws CanonicalEncodingException {
            if (!(tag instanceof ByteArrayTag value)) {
                throw malformed();
            }
            int size = value.size();
            checkArraySize(size);
            sink.ensureArrayPayload(size, Byte.BYTES);
            byte[] array = value.getAsByteArray();
            if (array.length != size) {
                throw malformed();
            }
            sink.writeInt(size);
            sink.writeBytes(array, 0, array.length);
        }

        private void writeStringTag(Tag tag) throws CanonicalEncodingException {
            if (!(tag instanceof StringTag value)) {
                throw malformed();
            }
            writeUtf8(value.getAsString());
        }

        private void writeList(Tag tag, int depth) throws CanonicalEncodingException {
            if (!(tag instanceof ListTag list)) {
                throw malformed();
            }
            int size = list.size();
            if (size < 0 || size > PatternLimits.MAX_PATTERN_ID_NBT_LIST_ENTRIES) {
                throw limit();
            }
            int elementId = Byte.toUnsignedInt(list.getElementType());
            if (elementId > Tag.TAG_LONG_ARRAY) {
                throw unsupported();
            }
            if (size > 0 && elementId == Tag.TAG_END) {
                throw malformed();
            }
            sink.writeByte(elementId);
            sink.writeInt(size);
            for (int index = 0; index < size; index++) {
                encodeValue(list.get(index), elementId, depth + 1, false);
            }
        }

        private void writeCompound(Tag tag, int depth) throws CanonicalEncodingException {
            if (!(tag instanceof CompoundTag compound)) {
                throw malformed();
            }
            if (compound.size() > PatternLimits.MAX_PATTERN_ID_NBT_COMPOUND_ENTRIES) {
                throw limit();
            }
            List<EncodedKey> keys = new ArrayList<>(compound.size());
            int bufferedKeyBytes = 0;
            for (String key : compound.getAllKeys()) {
                int length = encodeUtf8(key);
                if (length > PatternLimits.MAX_PATTERN_ID_NBT_ENCODED_BYTES - bufferedKeyBytes) {
                    throw limit();
                }
                byte[] bytes = new byte[length];
                System.arraycopy(utf8Buffer, 0, bytes, 0, length);
                keys.add(new EncodedKey(key, bytes));
                bufferedKeyBytes += length;
            }
            keys.sort(Comparator.comparing(EncodedKey::bytes, CanonicalNbtEncoder::compareUnsigned));
            sink.writeInt(keys.size());
            for (EncodedKey key : keys) {
                sink.writeLengthDelimited(key.bytes());
                encodeValue(compound.get(key.value()), -1, depth + 1, true);
            }
        }

        private void writeIntArray(Tag tag) throws CanonicalEncodingException {
            if (!(tag instanceof IntArrayTag value)) {
                throw malformed();
            }
            int size = value.size();
            checkArraySize(size);
            sink.ensureArrayPayload(size, Integer.BYTES);
            int[] array = value.getAsIntArray();
            if (array.length != size) {
                throw malformed();
            }
            sink.writeInt(size);
            for (int element : array) {
                sink.writeInt(element);
            }
        }

        private void writeLongArray(Tag tag) throws CanonicalEncodingException {
            if (!(tag instanceof LongArrayTag value)) {
                throw malformed();
            }
            int size = value.size();
            checkArraySize(size);
            sink.ensureArrayPayload(size, Long.BYTES);
            long[] array = value.getAsLongArray();
            if (array.length != size) {
                throw malformed();
            }
            sink.writeInt(size);
            for (long element : array) {
                sink.writeLong(element);
            }
        }

        private void writeUtf8(String value) throws CanonicalEncodingException {
            int length = encodeUtf8(value);
            sink.ensureAdditional(Integer.BYTES + length);
            sink.writeInt(length);
            sink.writeBytes(utf8Buffer, 0, length);
        }

        private int encodeUtf8(String value) throws CanonicalEncodingException {
            if (value == null) {
                throw malformed();
            }
            utf8.reset();
            ByteBuffer target = ByteBuffer.wrap(utf8Buffer);
            try {
                var result = utf8.encode(CharBuffer.wrap(value), target, true);
                if (result.isOverflow()) {
                    throw limit();
                }
                if (result.isError()) {
                    result.throwException();
                }
                result = utf8.flush(target);
                if (result.isOverflow()) {
                    throw limit();
                }
                if (result.isError()) {
                    result.throwException();
                }
                return target.position();
            } catch (CharacterCodingException exception) {
                throw malformed();
            }
        }

        private static int compareUnsigned(byte[] left, byte[] right) {
            int length = Math.min(left.length, right.length);
            for (int index = 0; index < length; index++) {
                int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return Integer.compare(left.length, right.length);
        }

        private static void checkArraySize(int size) throws CanonicalEncodingException {
            if (size < 0 || size > PatternLimits.MAX_PATTERN_ID_NBT_ARRAY_ELEMENTS) {
                throw limit();
            }
        }

        private static CanonicalEncodingException limit() {
            return new CanonicalEncodingException(PatternIdCreationResult.FailureReason.IDENTITY_LIMIT, "nbt-limit");
        }

        private static CanonicalEncodingException malformed() {
            return new CanonicalEncodingException(PatternIdCreationResult.FailureReason.MALFORMED_TAG, "nbt");
        }

        private static CanonicalEncodingException unsupported() {
            return new CanonicalEncodingException(PatternIdCreationResult.FailureReason.UNSUPPORTED_TAG, "nbt-type");
        }
    }

    private static final class DigestSink {
        private final MessageDigest digest;
        private int encodedBytes;

        private DigestSink(MessageDigest digest) {
            this.digest = digest;
        }

        private void writeLengthDelimited(byte[] bytes) throws CanonicalEncodingException {
            writeInt(bytes.length);
            writeBytes(bytes, 0, bytes.length);
        }

        private void writeByte(int value) throws CanonicalEncodingException {
            reserve(1);
            digest.update((byte) value);
            encodedBytes++;
        }

        private void writeShort(int value) throws CanonicalEncodingException {
            reserve(Short.BYTES);
            writeByte(value >>> 8);
            writeByte(value);
        }

        private void writeInt(int value) throws CanonicalEncodingException {
            reserve(Integer.BYTES);
            writeByte(value >>> 24);
            writeByte(value >>> 16);
            writeByte(value >>> 8);
            writeByte(value);
        }

        private void writeLong(long value) throws CanonicalEncodingException {
            reserve(Long.BYTES);
            for (int shift = Long.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
                writeByte((int) (value >>> shift));
            }
        }

        private void writeBytes(byte[] bytes, int offset, int length) throws CanonicalEncodingException {
            if (bytes == null || offset < 0 || length < 0 || offset > bytes.length - length) {
                throw CanonicalNbtEncoder.malformed();
            }
            reserve(length);
            digest.update(bytes, offset, length);
            encodedBytes += length;
        }

        private void ensureAdditional(int length) throws CanonicalEncodingException {
            reserve(length);
        }

        private void ensureArrayPayload(int elements, int elementWidth) throws CanonicalEncodingException {
            if (elements < 0 || elementWidth <= 0
                    || elements > (PatternLimits.MAX_PATTERN_ID_NBT_ENCODED_BYTES - encodedBytes - Integer.BYTES)
                            / elementWidth) {
                throw CanonicalNbtEncoder.limit();
            }
        }

        private void reserve(int length) throws CanonicalEncodingException {
            if (length < 0 || length > PatternLimits.MAX_PATTERN_ID_NBT_ENCODED_BYTES - encodedBytes) {
                throw CanonicalNbtEncoder.limit();
            }
        }
    }

    private record EncodedKey(String value, byte[] bytes) {
    }

    private static final class CanonicalEncodingException extends Exception {
        private final PatternIdCreationResult.FailureReason reason;
        private final String context;

        private CanonicalEncodingException(PatternIdCreationResult.FailureReason reason, String context) {
            this.reason = reason;
            this.context = context;
        }
    }
}
