package appeng.rebuild.quantity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigInteger;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Specification for signed quantity differences kept separate from {@link AEAmount}. */
class AmountDeltaTest {

  private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

  static Stream<Arguments> representativeSignedValues() {
    return Stream.of(
            BigInteger.ZERO,
            BigInteger.ONE,
            BigInteger.ONE.negate(),
            LONG_MIN,
            LONG_MAX,
            LONG_MIN.subtract(BigInteger.ONE),
            LONG_MAX.add(BigInteger.ONE),
            BigInteger.valueOf(2).pow(64).negate(),
            BigInteger.valueOf(2).pow(128),
            BigInteger.TEN.pow(1000).negate())
        .map(Arguments::of);
  }

  @ParameterizedTest(name = "constructs exact signed value {0}")
  @MethodSource("representativeSignedValues")
  void constructionAndRoundTripAreExact(BigInteger expected) {
    AmountDelta actual = AmountDelta.of(expected);

    assertEquals(expected, actual.toBigInteger());
    assertEquals(expected.toString(), actual.toString());
    assertEquals(actual, AmountDelta.of(expected));
    assertEquals(actual.hashCode(), AmountDelta.of(expected).hashCode());

    if (expected.bitLength() <= 63 || expected.equals(LONG_MIN)) {
      assertEquals(actual, AmountDelta.of(expected.longValueExact()));
    }
  }

  @Test
  void zeroConstantAndSignumAreCorrect() {
    assertEquals(BigInteger.ZERO, AmountDelta.ZERO.toBigInteger());
    assertEquals(AmountDelta.ZERO, AmountDelta.of(0L));
    assertEquals(0, AmountDelta.ZERO.signum());
    assertEquals(1, AmountDelta.of(1L).signum());
    assertEquals(-1, AmountDelta.of(-1L).signum());
  }

  @Test
  void additionSubtractionAndNegationMatchBigIntegerAtBoundaries() {
    AmountDelta min = AmountDelta.of(Long.MIN_VALUE);
    AmountDelta max = AmountDelta.of(Long.MAX_VALUE);
    AmountDelta one = AmountDelta.of(1L);

    assertEquals(LONG_MIN.subtract(BigInteger.ONE), min.subtract(one).toBigInteger());
    assertEquals(LONG_MAX.add(BigInteger.ONE), max.add(one).toBigInteger());
    assertEquals(LONG_MIN.negate(), min.negate().toBigInteger());
    assertEquals(LONG_MAX.negate(), max.negate().toBigInteger());
    assertEquals(BigInteger.valueOf(-1L), min.add(max).toBigInteger());
  }

  @Test
  void equalityHashCodeAndStringAreValueBased() {
    AmountDelta primitive = AmountDelta.of(Long.MIN_VALUE);
    AmountDelta sameValue = AmountDelta.of(LONG_MIN);
    AmountDelta different = AmountDelta.of(LONG_MIN.subtract(BigInteger.ONE));

    assertEquals(primitive, sameValue);
    assertEquals(primitive.hashCode(), sameValue.hashCode());
    assertEquals(Long.toString(Long.MIN_VALUE), primitive.toString());
    assertNotEquals(primitive, different);
    assertFalse(primitive.equals(null));
    assertFalse(primitive.equals("-9223372036854775808"));
  }

  @Test
  void randomizedDifferentialTestAgainstBigIntegerIsDeterministic() {
    Random random = new Random(0xD_E17_A2L);

    for (int iteration = 0; iteration < 512; iteration++) {
      BigInteger left = randomSigned(random);
      BigInteger right = randomSigned(random);
      AmountDelta actualLeft = AmountDelta.of(left);
      AmountDelta actualRight = AmountDelta.of(right);

      assertEquals(
          left.add(right), actualLeft.add(actualRight).toBigInteger(), "add at " + iteration);
      assertEquals(
          left.subtract(right),
          actualLeft.subtract(actualRight).toBigInteger(),
          "subtract at " + iteration);
      assertEquals(left.negate(), actualLeft.negate().toBigInteger(), "negate at " + iteration);
      assertEquals(left.signum(), actualLeft.signum(), "signum at " + iteration);
    }
  }

  private static BigInteger randomSigned(Random random) {
    int bitLength =
        switch (random.nextInt(10)) {
          case 0 -> 0;
          case 1 -> 1;
          case 2 -> 31;
          case 3 -> 63;
          case 4 -> 64;
          case 5 -> 128;
          case 6 -> 1000;
          default -> random.nextInt(1025);
        };
    BigInteger value = bitLength == 0 ? BigInteger.ZERO : new BigInteger(bitLength, random);
    return random.nextBoolean() ? value : value.negate();
  }
}
