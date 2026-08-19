package appeng.rebuild.quantity;

import java.math.BigInteger;
import java.util.Objects;

/** A signed exact difference between two resource quantities. */
public final class AmountDelta {
    public static final AmountDelta ZERO = new AmountDelta(BigInteger.ZERO);

    private final BigInteger value;

    private AmountDelta(BigInteger value) {
        this.value = value;
    }

    public static AmountDelta of(long value) {
        return of(BigInteger.valueOf(value));
    }

    public static AmountDelta of(BigInteger value) {
        Objects.requireNonNull(value, "value");
        return value.signum() == 0 ? ZERO : new AmountDelta(value);
    }

    public AmountDelta add(AmountDelta rhs) {
        Objects.requireNonNull(rhs, "rhs");
        return of(value.add(rhs.value));
    }

    public AmountDelta subtract(AmountDelta rhs) {
        Objects.requireNonNull(rhs, "rhs");
        return of(value.subtract(rhs.value));
    }

    public AmountDelta negate() {
        return of(value.negate());
    }

    public int signum() {
        return value.signum();
    }

    public BigInteger toBigInteger() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof AmountDelta other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
