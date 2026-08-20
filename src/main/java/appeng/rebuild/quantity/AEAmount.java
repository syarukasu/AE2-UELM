package appeng.rebuild.quantity;

import java.math.BigInteger;
import java.util.Objects;

/**
 * An immutable, non-negative exact resource quantity.
 *
 * <p>
 * Values that fit in a signed {@code long} avoid allocating a {@link BigInteger}. Values above that range are
 * represented exactly by {@code BigInteger}; negative values are never valid resource quantities.
 */
public final class AEAmount implements Comparable<AEAmount> {
    public static final AEAmount ZERO = new AEAmount(0L);
    public static final AEAmount ONE = new AEAmount(1L);

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final long small;
    private final BigInteger large;

    private AEAmount(long small) {
        this.small = small;
        this.large = null;
    }

    private AEAmount(BigInteger large) {
        this.small = 0L;
        this.large = large;
    }

    public static AEAmount of(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Resource quantities must be non-negative: " + value);
        }
        if (value == 0L) {
            return ZERO;
        }
        if (value == 1L) {
            return ONE;
        }
        return new AEAmount(value);
    }

    public static AEAmount of(BigInteger value) {
        Objects.requireNonNull(value, "value");
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Resource quantities must be non-negative: " + value);
        }
        if (value.compareTo(LONG_MAX) <= 0) {
            return of(value.longValueExact());
        }
        return new AEAmount(value);
    }

    public AEAmount add(AEAmount rhs) {
        Objects.requireNonNull(rhs, "rhs");
        if (this.large == null && rhs.large == null) {
            try {
                return of(Math.addExact(this.small, rhs.small));
            } catch (ArithmeticException ignored) {
                // Fall through to exact promotion.
            }
        }
        return of(this.toBigInteger().add(rhs.toBigInteger()));
    }

    public AEAmount subtractExact(AEAmount rhs) {
        Objects.requireNonNull(rhs, "rhs");
        if (this.compareTo(rhs) < 0) {
            throw new ArithmeticException("Resource quantity would become negative");
        }
        if (this.large == null && rhs.large == null) {
            return of(this.small - rhs.small);
        }
        return of(this.toBigInteger().subtract(rhs.toBigInteger()));
    }

    public AEAmount multiply(AEAmount rhs) {
        Objects.requireNonNull(rhs, "rhs");
        if (this.isZero() || rhs.isZero()) {
            return ZERO;
        }
        if (this.large == null && rhs.large == null) {
            try {
                return of(Math.multiplyExact(this.small, rhs.small));
            } catch (ArithmeticException ignored) {
                // Fall through to exact promotion.
            }
        }
        return of(this.toBigInteger().multiply(rhs.toBigInteger()));
    }

    public AEAmount divide(AEAmount rhs) {
        Objects.requireNonNull(rhs, "rhs");
        if (rhs.isZero()) {
            throw new ArithmeticException("Division by zero");
        }
        if (this.large == null && rhs.large == null) {
            return of(this.small / rhs.small);
        }
        return of(this.toBigInteger().divide(rhs.toBigInteger()));
    }

    public AEAmount ceilDiv(AEAmount rhs) {
        Objects.requireNonNull(rhs, "rhs");
        if (rhs.isZero()) {
            throw new ArithmeticException("Division by zero");
        }
        if (this.isZero()) {
            return ZERO;
        }
        if (this.large == null && rhs.large == null) {
            return of(1L + (this.small - 1L) / rhs.small);
        }
        var quotientAndRemainder = this.toBigInteger().divideAndRemainder(rhs.toBigInteger());
        return of(quotientAndRemainder[1].signum() == 0
                ? quotientAndRemainder[0]
                : quotientAndRemainder[0].add(BigInteger.ONE));
    }

    public AEAmount min(AEAmount rhs) {
        Objects.requireNonNull(rhs, "rhs");
        return compareTo(rhs) <= 0 ? this : rhs;
    }

    public AEAmount max(AEAmount rhs) {
        Objects.requireNonNull(rhs, "rhs");
        return compareTo(rhs) >= 0 ? this : rhs;
    }

    public int intValueExact() {
        return large == null ? Math.toIntExact(small) : large.intValueExact();
    }

    public long longValueExact() {
        return large == null ? small : large.longValueExact();
    }

    public BigInteger toBigInteger() {
        return large == null ? BigInteger.valueOf(small) : large;
    }

    @Override
    public int compareTo(AEAmount rhs) {
        Objects.requireNonNull(rhs, "rhs");
        if (this.large == null && rhs.large == null) {
            return Long.compare(this.small, rhs.small);
        }
        return this.toBigInteger().compareTo(rhs.toBigInteger());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AEAmount other)) {
            return false;
        }
        if (this.large == null && other.large == null) {
            return this.small == other.small;
        }
        return this.toBigInteger().equals(other.toBigInteger());
    }

    @Override
    public int hashCode() {
        return large == null ? Long.hashCode(small) : large.hashCode();
    }

    @Override
    public String toString() {
        return large == null ? Long.toString(small) : large.toString();
    }

    boolean isSmall() {
        return large == null;
    }

    long smallValue() {
        if (large != null) {
            throw new IllegalStateException("Amount is represented as BigInteger");
        }
        return small;
    }

    private boolean isZero() {
        return large == null && small == 0L;
    }
}
