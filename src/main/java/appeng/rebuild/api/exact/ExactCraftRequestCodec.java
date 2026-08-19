package appeng.rebuild.api.exact;

import java.util.Objects;

import net.minecraft.network.FriendlyByteBuf;

import appeng.api.stacks.AEKey;
import appeng.rebuild.key.KeyId;
import appeng.rebuild.key.KeyRegistry;
import appeng.rebuild.planner.ExactCraftRequest;
import appeng.rebuild.quantity.AEAmount;

/**
 * Bounded wire codec for an exact craft request.
 *
 * <p>
 * The key is resolved with {@link KeyRegistry#lookup(AEKey)} only. Decoding a client request never interns a key or
 * changes registry state; an unknown key is rejected before a request reaches the exact CPU path.
 */
public final class ExactCraftRequestCodec {
    private ExactCraftRequestCodec() {
    }

    /** Writes a registry-scoped request as a transport-safe AE key and an exact unsigned amount. */
    public static void write(FriendlyByteBuf buffer, ExactCraftRequest request, KeyRegistry registry) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(registry, "registry");
        AEKey.writeKey(buffer, registry.resolve(request.output()));
        ExactAmountCodec.write(buffer, request.amount());
    }

    /** Reads and validates a client request against the current registry without mutating it. */
    public static DecodeResult read(FriendlyByteBuf buffer, KeyRegistry registry) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(registry, "registry");
        AEKey key;
        try {
            key = AEKey.readKey(buffer);
        } catch (RuntimeException exception) {
            return new Failure(FailureReason.MALFORMED_KEY);
        }
        if (key == null) {
            return new Failure(FailureReason.MALFORMED_KEY);
        }
        ExactAmountCodec.DecodeResult decodedAmount = ExactAmountCodec.read(buffer);
        if (decodedAmount instanceof ExactAmountCodec.Failure) {
            return new Failure(FailureReason.MALFORMED_AMOUNT);
        }
        AEAmount amount = ((ExactAmountCodec.Success) decodedAmount).amount();
        if (amount.equals(AEAmount.ZERO)) {
            return new Failure(FailureReason.ZERO_AMOUNT);
        }
        KeyId keyId = registry.lookup(key);
        if (keyId == null) {
            return new Failure(FailureReason.UNKNOWN_KEY);
        }
        return new Success(new ExactCraftRequest(keyId, amount));
    }

    public sealed interface DecodeResult permits Success, Failure {
    }

    public record Success(ExactCraftRequest request) implements DecodeResult {
        public Success {
            Objects.requireNonNull(request, "request");
        }
    }

    public record Failure(FailureReason reason) implements DecodeResult {
        public Failure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public enum FailureReason {
        MALFORMED_KEY,
        MALFORMED_AMOUNT,
        ZERO_AMOUNT,
        UNKNOWN_KEY
    }
}
