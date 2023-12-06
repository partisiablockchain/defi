package defi.properties;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.RepetitionInfo;

final class Arbitrary {

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
    System.out.println("sample : %s <= %s < %s".formatted(min, sample, max));
    return sample;
  }

  public static BigInteger nextRandomBigInteger(Random rand, BigInteger n) {
    BigInteger result = new BigInteger(n.bitLength(), rand);
    while (result.compareTo(n) >= 0) {
      result = new BigInteger(n.bitLength(), rand);
    }
    return result;
  }
}
