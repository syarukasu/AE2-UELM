package appeng.rebuild.quantity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

/** Regression coverage for growth of the exact quantity vector. */
class AmountVectorGrowthTest {

    @Test
    void negativeCapacityIsRejected() {
        AmountVector vector = new AmountVector(2);

        assertThrows(IllegalArgumentException.class, () -> vector.ensureCapacity(-1));
        assertEquals(2, vector.size());
    }

    @Test
    void equalOrSmallerCapacityIsANoOp() {
        AmountVector vector = new AmountVector(5);
        vector.set(0, AEAmount.ONE);
        vector.set(4, AEAmount.of(7));

        vector.ensureCapacity(5);
        assertEquals(5, vector.size());
        assertEquals(AEAmount.ONE, vector.get(0));
        assertEquals(AEAmount.of(7), vector.get(4));

        vector.ensureCapacity(3);
        assertEquals(5, vector.size());
        assertEquals(AEAmount.ONE, vector.get(0));
        assertEquals(AEAmount.of(7), vector.get(4));
    }

    @Test
    void growthPreservesZeroSmallAndWideExactValues() {
        AmountVector vector = new AmountVector(8);
        BigInteger longMaxPlusOne = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        BigInteger twoTo64 = BigInteger.ONE.shiftLeft(64);
        BigInteger twoTo128 = BigInteger.ONE.shiftLeft(128);

        vector.set(0, AEAmount.ZERO);
        vector.set(1, AEAmount.ONE);
        vector.set(2, AEAmount.of(Long.MAX_VALUE));
        vector.set(3, AEAmount.of(longMaxPlusOne));
        vector.set(4, AEAmount.of(twoTo64));
        vector.set(5, AEAmount.of(twoTo128));

        vector.ensureCapacity(64);

        assertTrue(vector.size() >= 64);
        assertEquals(BigInteger.ZERO, vector.get(0).toBigInteger());
        assertEquals(BigInteger.ONE, vector.get(1).toBigInteger());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), vector.get(2).toBigInteger());
        assertEquals(longMaxPlusOne, vector.get(3).toBigInteger());
        assertEquals(twoTo64, vector.get(4).toBigInteger());
        assertEquals(twoTo128, vector.get(5).toBigInteger());
        assertEquals(5, vector.nonZeroSize());

        for (int index = 6; index < vector.size(); index++) {
            assertEquals(AEAmount.ZERO, vector.get(index), "new slot " + index);
        }
    }

    @Test
    void copyAndGrowthRemainIndependent() {
        AmountVector original = new AmountVector(2);
        original.set(0, AEAmount.of(BigInteger.ONE.shiftLeft(128)));
        AmountVector copy = original.copy();

        original.ensureCapacity(8);
        original.set(7, AEAmount.of(17));
        assertTrue(original.size() >= 8);
        assertEquals(2, copy.size());
        assertEquals(BigInteger.ONE.shiftLeft(128), copy.get(0).toBigInteger());
        assertEquals(1, copy.nonZeroSize());

        copy.ensureCapacity(8);
        copy.set(0, AEAmount.ONE);
        copy.set(7, AEAmount.of(19));
        assertEquals(BigInteger.ONE.shiftLeft(128), original.get(0).toBigInteger());
        assertEquals(AEAmount.ONE, copy.get(0));
        assertEquals(AEAmount.of(17), original.get(7));
        assertEquals(AEAmount.of(19), copy.get(7));
        assertEquals(2, original.nonZeroSize());
        assertEquals(2, copy.nonZeroSize());
    }
}
