package appeng.rebuild.persistence;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Shape/size guard used before handing generic NBT to addon-controlled AEKey loaders. */
final class StrictNbt {
    private StrictNbt() {
    }

    static boolean valid(Tag tag) {
        return valid(tag, 0, new Counter());
    }

    /** Linear incremental aggregate guard for builders which must reject before appending another large subtree. */
    static Budget budget() {
        return new Budget();
    }

    private static boolean valid(Tag tag, int depth, Counter counter) {
        if (tag == null || depth > PersistenceLimits.MAX_NBT_DEPTH || ++counter.nodes > PersistenceLimits.MAX_NBT_NODES
                || !charge(counter, 1)) {
            return false;
        }
        int id = Byte.toUnsignedInt(tag.getId());
        if (id <= Tag.TAG_END || id > Tag.TAG_LONG_ARRAY) {
            return false;
        }
        if (tag instanceof StringTag string) {
            int length = utf8Length(string.getAsString());
            return length >= 0 && charge(counter, length + Integer.BYTES);
        }
        if (tag instanceof ByteArrayTag bytes) {
            return bytes.size() <= PersistenceLimits.MAX_ARRAY_ELEMENTS
                    && charge(counter, bytes.size() + Integer.BYTES);
        }
        if (tag instanceof IntArrayTag integers) {
            return integers.size() <= PersistenceLimits.MAX_ARRAY_ELEMENTS
                    && charge(counter, (long) integers.size() * Integer.BYTES + Integer.BYTES);
        }
        if (tag instanceof LongArrayTag longs) {
            return longs.size() <= PersistenceLimits.MAX_ARRAY_ELEMENTS
                    && charge(counter, (long) longs.size() * Long.BYTES + Integer.BYTES);
        }
        if (tag instanceof ListTag list) {
            if (list.size() > PersistenceLimits.MAX_LIST_ENTRIES || !charge(counter, Integer.BYTES + 1))
                return false;
            int expected = Byte.toUnsignedInt(list.getElementType());
            if (list.size() > 0 && expected == Tag.TAG_END)
                return false;
            for (int i = 0; i < list.size(); i++) {
                if (Byte.toUnsignedInt(list.get(i).getId()) != expected || !valid(list.get(i), depth + 1, counter))
                    return false;
            }
        }
        if (tag instanceof CompoundTag compound) {
            if (compound.size() > PersistenceLimits.MAX_COMPOUND_ENTRIES)
                return false;
            for (String key : compound.getAllKeys()) {
                int length = utf8Length(key);
                if (length < 0 || !charge(counter, length + Integer.BYTES)
                        || !valid(compound.get(key), depth + 1, counter))
                    return false;
            }
        }
        return charge(counter, Long.BYTES);
    }

    static int utf8Length(String value) {
        if (value == null || value.length() > PersistenceLimits.MAX_UTF8_BYTES)
            return -1;
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer bytes = encoder.encode(CharBuffer.wrap(value));
            return bytes.remaining() <= PersistenceLimits.MAX_UTF8_BYTES ? bytes.remaining() : -1;
        } catch (CharacterCodingException failure) {
            return -1;
        }
    }

    private static boolean charge(Counter counter, long amount) {
        if (amount < 0 || amount > PersistenceLimits.MAX_ENCODED_BYTES - counter.bytes)
            return false;
        counter.bytes += amount;
        return true;
    }

    private static final class Counter {
        int nodes;
        long bytes;

        private Counter copy() {
            Counter copy = new Counter();
            copy.nodes = nodes;
            copy.bytes = bytes;
            return copy;
        }
    }

    static final class Budget {
        private Counter counter = new Counter();

        boolean reserve(long bytes, int nodes) {
            Counter proposed = counter.copy();
            if (nodes < 0 || proposed.nodes > PersistenceLimits.MAX_NBT_NODES - nodes || !charge(proposed, bytes))
                return false;
            proposed.nodes += nodes;
            counter = proposed;
            return true;
        }

        boolean add(Tag tag) {
            Counter proposed = counter.copy();
            if (!valid(tag, 0, proposed))
                return false;
            counter = proposed;
            return true;
        }
    }
}
