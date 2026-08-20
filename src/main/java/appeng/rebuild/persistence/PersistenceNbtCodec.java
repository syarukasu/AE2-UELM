package appeng.rebuild.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.rebuild.key.KeyId;

/** Strict bounded primitives shared by versioned persistence records. No helper supplies a fallback value. */
public final class PersistenceNbtCodec {
    private PersistenceNbtCodec() {
    }

    public static String encodeString(String value) {
        if (!bounded(value, PersistenceLimits.MAX_UTF8_BYTES))
            throw new IllegalArgumentException("String exceeds bound");
        return value;
    }

    public static PersistenceDecodeResult<String> decodeString(CompoundTag root, String field, int maxBytes) {
        if (root == null || field == null || maxBytes < 0 || !root.contains(field, Tag.TAG_STRING))
            return fail();
        String value = root.getString(field);
        return bounded(value, maxBytes) ? new PersistenceDecodeResult.Success<>(value) : limit();
    }

    public static PersistenceDecodeResult<UUID> decodeUuid(CompoundTag root, String field) {
        PersistenceDecodeResult<String> value = decodeString(root, field, 36);
        if (!(value instanceof PersistenceDecodeResult.Success<String> success))
            return propagate(value);
        try {
            return new PersistenceDecodeResult.Success<>(UUID.fromString(success.value()));
        } catch (IllegalArgumentException failure) {
            return fail();
        }
    }

    public static PersistenceDecodeResult<KeyId> decodeKeyId(CompoundTag root, String field) {
        if (root == null || field == null || !root.contains(field, Tag.TAG_INT))
            return fail();
        try {
            return new PersistenceDecodeResult.Success<>(new KeyId(root.getInt(field)));
        } catch (IllegalArgumentException failure) {
            return fail();
        }
    }

    public static <E extends Enum<E>> PersistenceDecodeResult<E> decodeEnum(CompoundTag root, String field,
            Class<E> type) {
        if (type == null)
            return fail();
        PersistenceDecodeResult<String> value = decodeString(root, field, 128);
        if (!(value instanceof PersistenceDecodeResult.Success<String> success))
            return propagate(value);
        try {
            return new PersistenceDecodeResult.Success<>(Enum.valueOf(type, success.value()));
        } catch (IllegalArgumentException failure) {
            return fail();
        }
    }

    public static <T> PersistenceDecodeResult<List<T>> decodeList(CompoundTag root, String field, int elementType,
            int maxEntries, Function<Tag, PersistenceDecodeResult<T>> decoder) {
        if (root == null || decoder == null || maxEntries < 0 || !root.contains(field, Tag.TAG_LIST))
            return fail();
        Tag raw = root.get(field);
        if (!(raw instanceof ListTag values)
                || values.size() > 0 && Byte.toUnsignedInt(values.getElementType()) != elementType)
            return fail();
        if (values.size() > maxEntries)
            return limit();
        ArrayList<T> result = new ArrayList<>(values.size());
        for (Tag value : values) {
            if (Byte.toUnsignedInt(value.getId()) != elementType)
                return fail();
            PersistenceDecodeResult<T> decoded;
            try {
                decoded = decoder.apply(value);
            } catch (RuntimeException failure) {
                return fail();
            }
            if (!(decoded instanceof PersistenceDecodeResult.Success<T> success))
                return propagate(decoded);
            result.add(success.value());
        }
        return new PersistenceDecodeResult.Success<>(List.copyOf(result));
    }

    public static <T> PersistenceDecodeResult<Map<String, T>> decodeMap(CompoundTag root, String field,
            int maxEntries, Function<Tag, PersistenceDecodeResult<T>> decoder) {
        if (root == null || decoder == null || maxEntries < 0 || !root.contains(field, Tag.TAG_COMPOUND))
            return fail();
        Tag raw = root.get(field);
        if (!(raw instanceof CompoundTag values))
            return fail();
        if (values.size() > maxEntries)
            return limit();
        TreeMap<String, T> result = new TreeMap<>();
        for (String key : values.getAllKeys()) {
            if (!bounded(key, PersistenceLimits.MAX_UTF8_BYTES))
                return limit();
            PersistenceDecodeResult<T> decoded;
            try {
                decoded = decoder.apply(values.get(key));
            } catch (RuntimeException failure) {
                return fail();
            }
            if (!(decoded instanceof PersistenceDecodeResult.Success<T> success)
                    || result.put(key, success.value()) != null)
                return decoded instanceof PersistenceDecodeResult.Failure<T> ? propagate(decoded) : fail();
        }
        return new PersistenceDecodeResult.Success<>(Map.copyOf(result));
    }

    private static boolean bounded(String value, int maxBytes) {
        int length = StrictNbt.utf8Length(value);
        return length >= 0 && length <= maxBytes;
    }

    private static <T> PersistenceDecodeResult<T> fail() {
        return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.MALFORMED);
    }

    private static <T> PersistenceDecodeResult<T> limit() {
        return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.LIMIT_EXCEEDED);
    }

    private static <T> PersistenceDecodeResult<T> propagate(PersistenceDecodeResult<?> source) {
        if (source instanceof PersistenceDecodeResult.Failure<?> failure)
            return new PersistenceDecodeResult.Failure<>(failure.reason());
        return fail();
    }
}
