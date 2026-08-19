package appeng.rebuild.quantity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Differential and boundary specification for the exact, non-negative quantity type.
 *
 * <p>The expected values in these tests are deliberately calculated with {@link BigInteger}. The
 * implementation may use a primitive fast path, but the representation is not part of the contract
 * and must not change any of these observable results.
 */
class AEAmountTest {

  private static final BigInteger TWO = BigInteger.valueOf(2);
  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
  private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);

  static Stream<Arguments> representativeValues() {
    return Stream.of(
            BigInteger.ZERO,
            BigInteger.ONE,
            INT_MAX.subtract(BigInteger.ONE),
            INT_MAX,
            INT_MAX.add(BigInteger.ONE),
            LONG_MAX.subtract(BigInteger.ONE),
            LONG_MAX,
            LONG_MAX.add(BigInteger.ONE),
            TWO.pow(64),
            TWO.pow(128),
            BigInteger.TEN.pow(1000))
        .map(Arguments::of);
  }

  @ParameterizedTest(name = "constructs exact value {0}")
  @MethodSource("representativeValues")
  void constructionAndRoundTripAreExact(BigInteger expected) {
    AEAmount actual = AEAmount.of(expected);

    assertEquals(expected, actual.toBigInteger());
    assertEquals(expected.toString(), actual.toString());
    assertEquals(actual, AEAmount.of(expected));
    assertEquals(actual.hashCode(), AEAmount.of(expected).hashCode());

    if (expected.compareTo(LONG_MAX) <= 0) {
      assertEquals(actual, AEAmount.of(expected.longValueExact()));
    }
  }

  @Test
  void zeroAndOneConstantsHaveTheirSpecifiedValues() {
    assertEquals(BigInteger.ZERO, AEAmount.ZERO.toBigInteger());
    assertEquals(BigInteger.ONE, AEAmount.ONE.toBigInteger());
    assertEquals(AEAmount.ZERO, AEAmount.of(0L));
    assertEquals(AEAmount.ONE, AEAmount.of(1L));
  }

  @Test
  void negativeResourceQuantitiesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> AEAmount.of(-1L));
    assertThrows(IllegalArgumentException.class, () -> AEAmount.of(BigInteger.valueOf(-1)));
    assertThrows(IllegalArgumentException.class, () -> AEAmount.of(LONG_MAX.negate()));
  }

  @Test
  void additionPromotesWithoutOverflowAndSubtractionReturnsExactValue() {
    AEAmount promoted = AEAmount.of(Long.MAX_VALUE).add(AEAmount.ONE);
    assertEquals(LONG_MAX.add(BigInteger.ONE), promoted.toBigInteger());

    AEAmount demotedByArithmetic = promoted.subtractExact(AEAmount.ONE);
    assertEquals(AEAmount.of(Long.MAX_VALUE), demotedByArithmetic);
    assertEquals(LONG_MAX, demotedByArithmetic.toBigInteger());
    assertEquals(Long.MAX_VALUE, demotedByArithmetic.longValueExact());
  }

  @Test
  void divisionCanReturnSmallCanonicalValuesAfterLargeValues() {
    AEAmount huge = AEAmount.of(TWO.pow(128));
    AEAmount quotient = huge.divide(huge);
    AEAmount zero = huge.divide(huge.add(AEAmount.ONE));

    assertEquals(AEAmount.ONE, quotient);
    assertEquals(AEAmount.ZERO, zero);
    assertEquals(1L, quotient.longValueExact());
    assertEquals(0L, zero.longValueExact());
  }

  @Test
  void arithmeticMatchesBigIntegerForRepresentativeValues() {
    AEAmount left = AEAmount.of(TWO.pow(64).add(BigInteger.valueOf(3)));
    AEAmount right = AEAmount.of(BigInteger.valueOf(7));

    assertEquals(left.toBigInteger().add(right.toBigInteger()), left.add(right).toBigInteger());
    assertEquals(
        left.toBigInteger().subtract(right.toBigInteger()),
        left.subtractExact(right).toBigInteger());
    assertEquals(
        left.toBigInteger().multiply(right.toBigInteger()), left.multiply(right).toBigInteger());
    assertEquals(
        left.toBigInteger().divide(right.toBigInteger()), left.divide(right).toBigInteger());

    BigInteger[] quotientAndRemainder =
        left.toBigInteger().divideAndRemainder(right.toBigInteger());
    BigInteger expectedCeil =
        quotientAndRemainder[0].add(
            quotientAndRemainder[1].signum() == 0 ? BigInteger.ZERO : BigInteger.ONE);
    assertEquals(expectedCeil, left.ceilDiv(right).toBigInteger());
    assertEquals(right.toBigInteger(), left.min(right).toBigInteger());
    assertEquals(left.toBigInteger(), left.max(right).toBigInteger());
    assertTrue(left.compareTo(right) > 0);
    assertTrue(right.compareTo(left) < 0);
    assertEquals(0, left.compareTo(AEAmount.of(left.toBigInteger())));
  }

  @Test
  void zeroAndExactDivisibilityHaveCorrectCeilingDivision() {
    AEAmount six = AEAmount.of(6L);
    AEAmount three = AEAmount.of(3L);
    AEAmount four = AEAmount.of(4L);

    assertEquals(AEAmount.ZERO, AEAmount.ZERO.divide(three));
    assertEquals(AEAmount.ZERO, AEAmount.ZERO.ceilDiv(three));
    assertEquals(AEAmount.of(2L), six.divide(three));
    assertEquals(AEAmount.of(2L), six.ceilDiv(three));
    assertEquals(AEAmount.of(2L), six.ceilDiv(four));
  }

  @Test
  void invalidArithmeticIsRejected() {
    assertThrows(ArithmeticException.class, () -> AEAmount.of(1L).subtractExact(AEAmount.of(2L)));
    assertThrows(ArithmeticException.class, () -> AEAmount.of(1L).divide(AEAmount.ZERO));
    assertThrows(ArithmeticException.class, () -> AEAmount.of(1L).ceilDiv(AEAmount.ZERO));
  }

  @Test
  void narrowingOnlySucceedsWhenTheValueFits() {
    assertEquals(Integer.MAX_VALUE, AEAmount.of(Integer.MAX_VALUE).intValueExact());
    assertEquals(Long.MAX_VALUE, AEAmount.of(Long.MAX_VALUE).longValueExact());
    assertEquals(0, AEAmount.ZERO.intValueExact());

    assertThrows(
        ArithmeticException.class, () -> AEAmount.of(INT_MAX.add(BigInteger.ONE)).intValueExact());
    assertThrows(
        ArithmeticException.class,
        () -> AEAmount.of(LONG_MAX.add(BigInteger.ONE)).longValueExact());
    assertThrows(ArithmeticException.class, () -> AEAmount.of(TWO.pow(64)).longValueExact());
  }

  @Test
  void equalityHashCodeAndStringAreValueBased() {
    AEAmount primitive = AEAmount.of(Long.MAX_VALUE);
    AEAmount sameValue = AEAmount.of(LONG_MAX);
    AEAmount different = AEAmount.of(LONG_MAX.add(BigInteger.ONE));

    assertEquals(primitive, sameValue);
    assertEquals(primitive.hashCode(), sameValue.hashCode());
    assertEquals(Long.toString(Long.MAX_VALUE), primitive.toString());
    assertNotEquals(primitive, different);
    assertFalse(primitive.equals(null));
    assertFalse(primitive.equals("9223372036854775807"));
  }

  @Test
  void randomizedDifferentialTestAgainstBigIntegerIsDeterministic() {
    Random random = new Random(0xAE2_5EED_1L);

    for (int iteration = 0; iteration < 512; iteration++) {
      BigInteger left = randomNonNegative(random);
      BigInteger right = randomNonNegative(random);
      AEAmount actualLeft = AEAmount.of(left);
      AEAmount actualRight = AEAmount.of(right);

      assertEquals(
          left.add(right), actualLeft.add(actualRight).toBigInteger(), "add at " + iteration);
      assertEquals(
          left.multiply(right),
          actualLeft.multiply(actualRight).toBigInteger(),
          "multiply at " + iteration);
      assertEquals(
          Integer.signum(left.compareTo(right)),
          Integer.signum(actualLeft.compareTo(actualRight)),
          "compare at " + iteration);
      assertEquals(
          left.min(right), actualLeft.min(actualRight).toBigInteger(), "min at " + iteration);
      assertEquals(
          left.max(right), actualLeft.max(actualRight).toBigInteger(), "max at " + iteration);

      if (left.compareTo(right) >= 0) {
        assertEquals(
            left.subtract(right),
            actualLeft.subtractExact(actualRight).toBigInteger(),
            "subtract at " + iteration);
      }

      if (right.signum() > 0) {
        BigInteger[] quotientAndRemainder = left.divideAndRemainder(right);
        BigInteger expectedCeil =
            quotientAndRemainder[0].add(
                quotientAndRemainder[1].signum() == 0 ? BigInteger.ZERO : BigInteger.ONE);
        assertEquals(
            left.divide(right),
            actualLeft.divide(actualRight).toBigInteger(),
            "divide at " + iteration);
        assertEquals(
            expectedCeil,
            actualLeft.ceilDiv(actualRight).toBigInteger(),
            "ceilDiv at " + iteration);
      }
    }
  }

  private static BigInteger randomNonNegative(Random random) {
    int selector = random.nextInt(12);
    int bitLength =
        switch (selector) {
          case 0 -> 0;
          case 1 -> 1;
          case 2 -> 31;
          case 3 -> 32;
          case 4 -> 63;
          case 5 -> 64;
          case 6 -> 128;
          case 7 -> 1000;
          default -> random.nextInt(1025);
        };
    if (bitLength == 0) {
      return BigInteger.ZERO;
    }
    return new BigInteger(bitLength, random);
  }
}
