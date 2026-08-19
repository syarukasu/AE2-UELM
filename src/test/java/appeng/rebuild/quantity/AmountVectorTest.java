package appeng.rebuild.quantity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Behavioural specification for the dense exact quantity vector.
 *
 * <p>
 * These tests intentionally do not inspect the vector's representation. A slot may move between a primitive and a wide
 * representation, but it must always expose one exact value.
 */
class AmountVectorTest {

    private static final BigInteger TWO = BigInteger.valueOf(2);

    @Test
    void newVectorContainsOnlyZeros() {
        AmountVector vector = new AmountVector(4);

        assertEquals(4, vector.size());
        assertEquals(0, vector.nonZeroSize());
        for (int index = 0; index < vector.size(); index++) {
            assertEquals(AEAmount.ZERO, vector.get(index));
        }
    }

    @Test
    void zeroLengthVectorIsValidAndClearIsIdempotent() {
        AmountVector vector = new AmountVector(0);

        assertEquals(0, vector.size());
        assertEquals(0, vector.nonZeroSize());
        vector.clear();
        assertEquals(0, vector.nonZeroSize());
    }

    @Test
    void allIndexedOperationsRejectOutOfBoundsIndices() {
        AmountVector vector = new AmountVector(2);
        int[] invalidIndices = { -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE };

        for (int index : invalidIndices) {
            assertThrows(IndexOutOfBoundsException.class, () -> vector.get(index), "get " + index);
            assertThrows(
                    IndexOutOfBoundsException.class, () -> vector.set(index, AEAmount.ONE), "set " + index);
            assertThrows(
                    IndexOutOfBoundsException.class, () -> vector.add(index, AEAmount.ONE), "add " + index);
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> vector.subtractExact(index, AEAmount.ONE),
                    "subtract " + index);
        }
    }

    @Test
    void setAddAndSubtractMaintainExactValuesAndNonZeroCount() {
        AmountVector vector = new AmountVector(3);
        BigInteger wide = TWO.pow(128).add(BigInteger.valueOf(9));

        vector.set(0, AEAmount.of(Long.MAX_VALUE));
        vector.add(0, AEAmount.ONE);
        assertEquals(
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), vector.get(0).toBigInteger());

        vector.set(1, AEAmount.of(wide));
        vector.add(1, AEAmount.of(2L));
        assertEquals(wide.add(BigInteger.TWO), vector.get(1).toBigInteger());
        vector.subtractExact(1, AEAmount.of(2L));
        assertEquals(wide, vector.get(1).toBigInteger());

        vector.set(2, AEAmount.of(7L));
        assertEquals(3, vector.nonZeroSize());
        vector.set(2, AEAmount.ZERO);
        assertEquals(2, vector.nonZeroSize());
        assertEquals(AEAmount.ZERO, vector.get(2));
    }

    @Test
    void promotionAndDemotionAreObservableOnlyAsExactBehaviour() {
        AmountVector vector = new AmountVector(1);
        BigInteger wide = TWO.pow(64);

        vector.set(0, AEAmount.of(Long.MAX_VALUE));
        vector.add(0, AEAmount.ONE);
        assertEquals(LONG_MAX_PLUS_ONE(), vector.get(0).toBigInteger());
        vector.subtractExact(0, AEAmount.ONE);
        assertEquals(Long.MAX_VALUE, vector.get(0).longValueExact());

        vector.set(0, AEAmount.of(wide));
        vector.subtractExact(0, AEAmount.of(wide.subtract(BigInteger.valueOf(7))));
        assertEquals(AEAmount.of(7L), vector.get(0));
        assertEquals(1, vector.nonZeroSize());
    }

    @Test
    void subtractBelowZeroThrowsAndDoesNotPartiallyMutateTheSlot() {
        AmountVector vector = new AmountVector(1);
        vector.set(0, AEAmount.of(3L));

        assertThrows(ArithmeticException.class, () -> vector.subtractExact(0, AEAmount.of(4L)));
        assertEquals(AEAmount.of(3L), vector.get(0));
        assertEquals(1, vector.nonZeroSize());
    }

    @Test
    void clearResetsEverySlotAndNonZeroCount() {
        AmountVector vector = new AmountVector(5);
        vector.set(0, AEAmount.ONE);
        vector.set(2, AEAmount.of(TWO.pow(128)));
        vector.set(4, AEAmount.of(99L));
        assertEquals(3, vector.nonZeroSize());

        vector.clear();

        assertEquals(0, vector.nonZeroSize());
        for (int index = 0; index < vector.size(); index++) {
            assertEquals(AEAmount.ZERO, vector.get(index));
        }
    }

    @Test
    void copyIsIndependentInBothDirections() {
        AmountVector original = new AmountVector(3);
        original.set(0, AEAmount.of(11L));
        original.set(1, AEAmount.of(TWO.pow(128)));

        AmountVector copy = original.copy();
        assertEquals(original.size(), copy.size());
        for (int index = 0; index < original.size(); index++) {
            assertEquals(original.get(index), copy.get(index));
        }

        copy.add(0, AEAmount.ONE);
        copy.set(1, AEAmount.ZERO);
        original.set(2, AEAmount.of(17L));

        assertEquals(AEAmount.of(11L), original.get(0));
        assertEquals(TWO.pow(128), original.get(1).toBigInteger());
        assertEquals(AEAmount.of(12L), copy.get(0));
        assertEquals(AEAmount.ZERO, copy.get(1));
        assertEquals(AEAmount.ZERO, copy.get(2));
        assertEquals(AEAmount.of(17L), original.get(2));
    }

    @Test
    void randomizedDifferentialTestAgainstBigIntegerIsDeterministic() {
        Random random = new Random(0xA0B0C0D2L);
        AmountVector actual = new AmountVector(9);
        BigInteger[] expected = new BigInteger[actual.size()];
        Arrays.fill(expected, BigInteger.ZERO);

        for (int iteration = 0; iteration < 2048; iteration++) {
            int index = random.nextInt(actual.size());
            int operation = random.nextInt(3);
            BigInteger operand = randomNonNegative(random);

            switch (operation) {
                case 0 -> {
                    actual.set(index, AEAmount.of(operand));
                    expected[index] = operand;
                }
                case 1 -> {
                    actual.add(index, AEAmount.of(operand));
                    expected[index] = expected[index].add(operand);
                }
                case 2 -> {
                    BigInteger safeOperand = operand.min(expected[index]);
                    actual.subtractExact(index, AEAmount.of(safeOperand));
                    expected[index] = expected[index].subtract(safeOperand);
                }
                default -> throw new AssertionError("unexpected operation");
            }

            int nonZero = 0;
            for (int slot = 0; slot < expected.length; slot++) {
                assertEquals(
                        expected[slot], actual.get(slot).toBigInteger(), "slot " + slot + " at " + iteration);
                if (expected[slot].signum() != 0) {
                    nonZero++;
                }
            }
            assertEquals(nonZero, actual.nonZeroSize(), "non-zero count at " + iteration);
        }
    }

    private static BigInteger randomNonNegative(Random random) {
        int bitLength = switch (random.nextInt(9)) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 31;
            case 3 -> 63;
            case 4 -> 64;
            case 5 -> 128;
            case 6 -> 1000;
            default -> random.nextInt(1025);
        };
        return bitLength == 0 ? BigInteger.ZERO : new BigInteger(bitLength, random);
    }

    private static BigInteger LONG_MAX_PLUS_ONE() {
        return BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
    }
}
