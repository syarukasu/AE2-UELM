package appeng.rebuild.api.exact;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.network.FriendlyByteBuf;

import appeng.rebuild.execution.ExactCpuLedgerState;
import appeng.rebuild.execution.ExactTransferBrokerState;
import appeng.rebuild.execution.ExactWorkOrderState;
import appeng.rebuild.quantity.AEAmount;

/** Versioned bounded wire codec for the immutable {@link ExactCpuStatus} observation. */
public final class ExactCpuStatusCodec {
    private static final int FORMAT_VERSION = 1;

    private ExactCpuStatusCodec() {
    }

    /** Writes an exact CPU observation without narrowing any retained material total. */
    public static void write(FriendlyByteBuf buffer, ExactCpuStatus status) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(status, "status");
        buffer.writeVarInt(FORMAT_VERSION);
        buffer.writeByte(status.ledgerState().ordinal());
        buffer.writeByte(status.brokerState().ordinal());
        buffer.writeBoolean(status.workOrderState().isPresent());
        status.workOrderState().ifPresent(state -> buffer.writeByte(state.ordinal()));
        ExactAmountCodec.write(buffer, status.reserved());
        ExactAmountCodec.write(buffer, status.escrowed());
        ExactAmountCodec.write(buffer, status.custody());
    }

    /** Reads one exact CPU observation, rejecting malformed enums and non-canonical quantity encodings. */
    public static DecodeResult read(FriendlyByteBuf buffer) {
        Objects.requireNonNull(buffer, "buffer");
        try {
            if (buffer.readVarInt() != FORMAT_VERSION) {
                return new Failure(FailureReason.VERSION);
            }
            ExactCpuLedgerState ledger = enumAt(ExactCpuLedgerState.values(), buffer.readUnsignedByte());
            ExactTransferBrokerState broker = enumAt(ExactTransferBrokerState.values(), buffer.readUnsignedByte());
            Optional<ExactWorkOrderState> workOrder = buffer.readBoolean()
                    ? Optional.of(enumAt(ExactWorkOrderState.values(), buffer.readUnsignedByte()))
                    : Optional.empty();
            AEAmount reserved = readAmount(buffer);
            AEAmount escrowed = readAmount(buffer);
            AEAmount custody = readAmount(buffer);
            return new Success(new ExactCpuStatus(ledger, broker, workOrder, reserved, escrowed, custody));
        } catch (IllegalArgumentException exception) {
            return new Failure(FailureReason.INVALID_ENUM);
        } catch (RuntimeException exception) {
            return new Failure(FailureReason.MALFORMED);
        }
    }

    private static <E extends Enum<E>> E enumAt(E[] values, int ordinal) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Enum ordinal outside codec range");
        }
        return values[ordinal];
    }

    private static AEAmount readAmount(FriendlyByteBuf buffer) {
        ExactAmountCodec.DecodeResult result = ExactAmountCodec.read(buffer);
        if (result instanceof ExactAmountCodec.Failure) {
            throw new IllegalArgumentException("Malformed exact status amount");
        }
        return ((ExactAmountCodec.Success) result).amount();
    }

    public sealed interface DecodeResult permits Success, Failure {
    }

    public record Success(ExactCpuStatus status) implements DecodeResult {
        public Success {
            Objects.requireNonNull(status, "status");
        }
    }

    public record Failure(FailureReason reason) implements DecodeResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum FailureReason {
        VERSION,
        INVALID_ENUM,
        MALFORMED
    }
}
