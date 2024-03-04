package defi.util;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.RepetitionInfo;

/** Utility class for generating arbitrary values for use in property-like tests. */
public final class Arbitrary {

  /**
   * Generate an arbitrary {@link BigInteger} between the given bounds, with a bias towards the
   * lower and upper bounds. Inspired by similar {@code Arbitrary} systems in property-based testing
   * frameworks.
   *
   * @param repetitionInfo Used for seeding.
   * @param min Minimum value, inclusive.
   * @param max Maximum value, inclusive.
   * @return generated value.
   */
  public static BigInteger bigInteger(
      RepetitionInfo repetitionInfo, BigInteger min, BigInteger max) {
    final int rep = repetitionInfo.getCurrentRepetition();

    final BigInteger sample;
    if (rep == 1) {
      sample = min;
    } else if (rep == 2) {
      sample = min.add(BigInteger.ONE);
    } else if (rep == 3) {
      sample = min.add(max).divide(BigInteger.valueOf(2));
    } else if (rep == 4) {
      sample = max.subtract(BigInteger.ONE);
    } else if (rep == 5) {
      sample = max;
    } else {
      final Random rand = new Random(rep);
      sample = nextRandomBigInteger(rand, max.subtract(min)).add(min);
    }
    return sample;
  }

  /**
   * Generate an {@link BigInteger} between {@code 0} (inclusive) and {@code n} (exclusive).
   *
   * @param rand Randomness to use.
   * @param n Maximum value that the generate.
   * @return generated value.
   */
  public static BigInteger nextRandomBigInteger(Random rand, BigInteger n) {
    BigInteger result = new BigInteger(n.bitLength(), rand);
    while (result.compareTo(n) >= 0) {
      result = new BigInteger(n.bitLength(), rand);
    }
    return result;
  }
}
