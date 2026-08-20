package appeng.rebuild.persistence;

import java.math.BigInteger;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** Canonical unsigned AEAmount NBT codec; it never uses decimal or a narrowed long representation. */
public final class AEAmountNbtCodec {
    private static final String VERSION = "v";
    private static final String VALUE = "u";

    private AEAmountNbtCodec() {
    }

    public static CompoundTag encode(AEAmount amount) {
        if (amount == null || amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException("Amount exceeds persistence bound");
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt(VERSION, PersistenceLimits.FORMAT_VERSION);
        byte[] bytes = amount.toBigInteger().equals(BigInteger.ZERO) ? new byte[0]
                : amount.toBigInteger().toByteArray();
        if (bytes.length > 0 && bytes[0] == 0) {
            byte[] minimal = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, minimal, 0, minimal.length);
            bytes = minimal;
        }
        tag.putByteArray(VALUE, bytes);
        return tag;
    }

    public static PersistenceDecodeResult<AEAmount> decode(CompoundTag tag) {
        if (tag == null || tag.size() != 2 || !tag.contains(VERSION, Tag.TAG_INT)
                || !tag.contains(VALUE, Tag.TAG_BYTE_ARRAY)) {
            return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.MALFORMED);
        }
        if (tag.getInt(VERSION) != PersistenceLimits.FORMAT_VERSION)
            return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.UNSUPPORTED_VERSION);
        byte[] bytes = tag.getByteArray(VALUE);
        if (bytes.length > (PlannerLimits.MAX_CRAFT_QUANTITY_BITS + 7) / 8
                || (bytes.length > 0 && bytes[0] == 0)) {
            return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.LIMIT_EXCEEDED);
        }
        BigInteger value = bytes.length == 0 ? BigInteger.ZERO : new BigInteger(1, bytes);
        if (value.bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            return new PersistenceDecodeResult.Failure<>(PersistenceDecodeResult.Reason.LIMIT_EXCEEDED);
        }
        return new PersistenceDecodeResult.Success<>(AEAmount.of(value));
    }
}
