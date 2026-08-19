package appeng.rebuild.quantity;

import java.math.BigInteger;
import java.util.Objects;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/** Dense mutable storage for exact non-negative quantities keyed by a bounded integer id. */
public final class AmountVector {
    private final long[] small;
    private final Int2ObjectOpenHashMap<BigInteger> large;
    private int nonZeroSize;

    public AmountVector(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative: " + size);
        }
        this.small = new long[size];
        this.large = new Int2ObjectOpenHashMap<>();
    }

    public int size() {
        return small.length;
    }

    public AEAmount get(int index) {
        checkIndex(index);
        BigInteger largeValue = large.get(index);
        return largeValue == null ? AEAmount.of(small[index]) : AEAmount.of(largeValue);
    }

    public void set(int index, AEAmount amount) {
        checkIndex(index);
        Objects.requireNonNull(amount, "amount");
        boolean wasNonZero = isNonZero(index);
        if (amount.isSmall()) {
            small[index] = amount.smallValue();
            large.remove(index);
        } else {
            small[index] = 0L;
            large.put(index, amount.toBigInteger());
        }
        updateNonZeroSize(wasNonZero, !amount.equals(AEAmount.ZERO));
    }

    public void add(int index, AEAmount amount) {
        checkIndex(index);
        Objects.requireNonNull(amount, "amount");
        if (amount.equals(AEAmount.ZERO)) {
            return;
        }

        BigInteger currentLarge = large.get(index);
        if (currentLarge != null) {
            set(index, AEAmount.of(currentLarge.add(amount.toBigInteger())));
        } else if (amount.isSmall()) {
            try {
                set(index, AEAmount.of(Math.addExact(small[index], amount.smallValue())));
            } catch (ArithmeticException ignored) {
                set(index, AEAmount.of(BigInteger.valueOf(small[index]).add(amount.toBigInteger())));
            }
        } else {
            set(index, AEAmount.of(BigInteger.valueOf(small[index]).add(amount.toBigInteger())));
        }
    }

    public void subtractExact(int index, AEAmount amount) {
        checkIndex(index);
        Objects.requireNonNull(amount, "amount");
        if (amount.equals(AEAmount.ZERO)) {
            return;
        }

        BigInteger currentLarge = large.get(index);
        if (currentLarge != null) {
            set(index, AEAmount.of(currentLarge).subtractExact(amount));
        } else if (amount.isSmall()) {
            long delta = amount.smallValue();
            if (small[index] < delta) {
                throw new ArithmeticException("Resource quantity would become negative");
            }
            set(index, AEAmount.of(small[index] - delta));
        } else {
            throw new ArithmeticException("Resource quantity would become negative");
        }
    }

    public void clear() {
        java.util.Arrays.fill(small, 0L);
        large.clear();
        nonZeroSize = 0;
    }

    public AmountVector copy() {
        AmountVector copy = new AmountVector(small.length);
        System.arraycopy(small, 0, copy.small, 0, small.length);
        copy.large.putAll(large);
        copy.nonZeroSize = nonZeroSize;
        return copy;
    }

    public int nonZeroSize() {
        return nonZeroSize;
    }

    private boolean isNonZero(int index) {
        return small[index] != 0L || large.containsKey(index);
    }

    private void updateNonZeroSize(boolean wasNonZero, boolean isNonZero) {
        if (wasNonZero != isNonZero) {
            nonZeroSize += isNonZero ? 1 : -1;
        }
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= small.length) {
            throw new IndexOutOfBoundsException("index " + index + " outside [0, " + small.length + ')');
        }
    }
}
