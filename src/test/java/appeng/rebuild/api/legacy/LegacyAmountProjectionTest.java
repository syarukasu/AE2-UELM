package appeng.rebuild.api.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import appeng.rebuild.quantity.AEAmount;

/** Boundary tests for the exact-to-legacy compatibility projection. */
class LegacyAmountProjectionTest {

    @Test
    void projectsBoundariesWithDocumentedSaturation() {
        BigInteger longMaxPlusOne = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        AEAmount zero = AEAmount.ZERO;
        AEAmount longMax = AEAmount.of(Long.MAX_VALUE);
        AEAmount aboveLong = AEAmount.of(longMaxPlusOne);
        AEAmount twoTo128 = AEAmount.of(BigInteger.ONE.shiftLeft(128));

        assertEquals(0L, LegacyAmountProjection.saturatingLong(zero));
        assertEquals(Long.MAX_VALUE, LegacyAmountProjection.saturatingLong(longMax));
        assertEquals(Long.MAX_VALUE, LegacyAmountProjection.saturatingLong(aboveLong));
        assertEquals(Long.MAX_VALUE, LegacyAmountProjection.saturatingLong(twoTo128));
        assertEquals(longMaxPlusOne, aboveLong.toBigInteger());
        assertEquals(BigInteger.ONE.shiftLeft(128), twoTo128.toBigInteger());
    }

    @Test
    void rejectsNullWithoutChangingAnyExactInput() {
        assertThrows(
                NullPointerException.class,
                () -> LegacyAmountProjection.saturatingLong(null));

        AEAmount exact = AEAmount.of(BigInteger.TEN.pow(1000));
        LegacyAmountProjection.saturatingLong(exact);
        assertEquals(BigInteger.TEN.pow(1000), exact.toBigInteger());
    }
}
