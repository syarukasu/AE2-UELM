package appeng.rebuild.api.legacy;

import java.math.BigInteger;
import java.util.Objects;

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
        Objects.requireNonNull(amount, "amount");
        return amount.toBigInteger().compareTo(LONG_MAX) > 0 ? Long.MAX_VALUE : amount.longValueExact();
    }
}
