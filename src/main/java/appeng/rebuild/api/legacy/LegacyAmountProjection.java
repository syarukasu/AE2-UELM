package appeng.rebuild.api.legacy;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.rebuild.quantity.AEAmount;

/** Converts exact amounts to the bounded legacy long representation used only by compatibility consumers. */
public final class LegacyAmountProjection {
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private LegacyAmountProjection() {
    }

    /**
     * Projects an exact non-negative amount into a legacy long, explicitly saturating values above
     * {@link Long#MAX_VALUE}. The exact ledger is never modified or narrowed.
     */
    public static long saturatingLong(AEAmount amount) {
        return project(amount).amount();
    }

    /**
     * Projects one exact amount for a long-only consumer while retaining whether information was clipped.
     */
    public static ProjectedAmount project(AEAmount amount) {
        Objects.requireNonNull(amount, "amount");
        boolean saturated = amount.toBigInteger().compareTo(LONG_MAX) > 0;
        return new ProjectedAmount(saturated ? Long.MAX_VALUE : amount.longValueExact(), saturated);
    }

    /**
     * Produces a legacy {@link GenericStack} only as an explicitly marked, bounded projection.
     */
    public static ProjectedGenericStack projectGenericStack(AEKey key, AEAmount amount) {
        Objects.requireNonNull(key, "key");
        ProjectedAmount projected = project(amount);
        return new ProjectedGenericStack(new GenericStack(key, projected.amount()), projected.saturated());
    }

    /**
     * Produces a legacy {@link KeyCounter} from exact key amounts without mutating the source amounts.
     *
     * <p>
     * The returned marker is required because a {@code KeyCounter} cannot represent values above {@link Long#MAX_VALUE}
     * exactly.
     */
    public static ProjectedKeyCounter projectKeyCounter(Map<AEKey, AEAmount> amounts) {
        Objects.requireNonNull(amounts, "amounts");
        KeyCounter projected = new KeyCounter();
        boolean saturated = false;
        for (Map.Entry<AEKey, AEAmount> entry : amounts.entrySet()) {
            ProjectedAmount amount = project(Objects.requireNonNull(entry.getValue(), "amount"));
            projected.set(Objects.requireNonNull(entry.getKey(), "key"), amount.amount());
            saturated |= amount.saturated();
        }
        return new ProjectedKeyCounter(projected, saturated);
    }

    /** A bounded scalar projection for a legacy long field. */
    public record ProjectedAmount(long amount, boolean saturated) {
        public ProjectedAmount {
            if (amount < 0) {
                throw new IllegalArgumentException("Projected amount must be non-negative");
            }
        }
    }

    /** A bounded {@link GenericStack} projection and its loss marker. */
    public record ProjectedGenericStack(GenericStack stack, boolean saturated) {
        public ProjectedGenericStack {
            Objects.requireNonNull(stack, "stack");
        }
    }

    /** A bounded {@link KeyCounter} projection and its aggregate loss marker. */
    public record ProjectedKeyCounter(KeyCounter counter, boolean saturated) {
        public ProjectedKeyCounter {
            Objects.requireNonNull(counter, "counter");
        }
    }
}
