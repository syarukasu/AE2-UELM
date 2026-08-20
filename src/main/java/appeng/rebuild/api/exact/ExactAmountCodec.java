package appeng.rebuild.api.exact;

import java.math.BigInteger;
import java.util.Objects;

import net.minecraft.network.FriendlyByteBuf;

import appeng.rebuild.planner.PlannerLimits;
import appeng.rebuild.quantity.AEAmount;

/** Bounded unsigned wire codec for exact amounts. */
public final class ExactAmountCodec {
    private static final int MAX_BYTES = (PlannerLimits.MAX_CRAFT_QUANTITY_BITS + 7) / 8;

    private ExactAmountCodec() {
    }

    /** Writes a canonical unsigned amount without narrowing it to a long. */
    public static void write(FriendlyByteBuf buffer, AEAmount amount) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(amount, "amount");
        if (amount.toBigInteger().bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
            throw new IllegalArgumentException("Exact amount exceeds wire bound");
        }
        byte[] bytes = amount.toBigInteger().signum() == 0 ? new byte[0] : amount.toBigInteger().toByteArray();
        if (bytes.length > 0 && bytes[0] == 0) {
            byte[] canonical = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, canonical, 0, canonical.length);
            bytes = canonical;
        }
        buffer.writeVarInt(bytes.length);
        buffer.writeBytes(bytes);
    }

    /** Reads one bounded, canonical unsigned amount. */
    public static DecodeResult read(FriendlyByteBuf buffer) {
        Objects.requireNonNull(buffer, "buffer");
        try {
            int length = buffer.readVarInt();
            if (length < 0 || length > MAX_BYTES || length > buffer.readableBytes()) {
                return new Failure(FailureReason.LENGTH);
            }
            byte[] bytes = new byte[length];
            buffer.readBytes(bytes);
            if (length > 0 && bytes[0] == 0) {
                return new Failure(FailureReason.NON_CANONICAL);
            }
            BigInteger value = length == 0 ? BigInteger.ZERO : new BigInteger(1, bytes);
            if (value.bitLength() > PlannerLimits.MAX_CRAFT_QUANTITY_BITS) {
                return new Failure(FailureReason.LIMIT);
            }
            return new Success(AEAmount.of(value));
        } catch (RuntimeException exception) {
            return new Failure(FailureReason.MALFORMED);
        }
    }

    public sealed interface DecodeResult permits Success, Failure {
    }

    public record Success(AEAmount amount) implements DecodeResult {
        public Success {
            Objects.requireNonNull(amount, "amount");
        }
    }

    public record Failure(FailureReason reason) implements DecodeResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum FailureReason {
        LENGTH,
        NON_CANONICAL,
        LIMIT,
        MALFORMED
    }
}
