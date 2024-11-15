package defi.util;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.junit.TestBlockchain;
import com.partisiablockchain.serialization.LargeByteArray;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.function.IntConsumer;

/** System for measuring gas usage of invocation sequences. */
public final class GasBenchmark {

  private GasBenchmark() {}

  /**
   * Single test case for the gas benchmark.
   *
   * @param name Name of the test case.
   * @param sender Sender of the test invocation.
   * @param contractAddress Contract address to send to.
   * @param rpc RPC to send.
   */
  public record TestCase(
      String name,
      BlockchainAddress sender,
      BlockchainAddress contractAddress,
      LargeByteArray rpc) {

    /** Create new {@link TestCase} instance. */
    public TestCase {
      assertNotNull(name);
      assertNotNull(sender);
      assertNotNull(contractAddress);
      assertNotNull(rpc);
    }

    /**
     * Constructor for {@link TestCase}.
     *
     * @param name Name of the test case.
     * @param sender Sender of the test invocation.
     * @param contractAddress Contract address to send to.
     * @param rpc RPC to send.
     */
    public TestCase(
        String name, BlockchainAddress sender, BlockchainAddress contractAddress, byte[] rpc) {
      this(name, sender, contractAddress, new LargeByteArray(rpc));
    }
  }

  /**
   * Runs the gas measurement system, and generates a CSV-like output.
   *
   * <p>For each combination of state tiers and test cases, it runs a binary search for the minimum
   * amount of gas to send.
   *
   * @param blockchain Blockchain to run tests on.
   * @param printStream Output stream to write resulting report to.
   * @param stateSizeTiers State sizes.
   * @param stateTierUpgrader Function to upgrade the tier.
   * @param testCases Test cases to run.
   */
  public static void gasCostCsv(
      TestBlockchain blockchain,
      PrintStream printStream,
      List<Integer> stateSizeTiers,
      IntConsumer stateTierUpgrader,
      List<TestCase> testCases) {
    // Setup tsv
    printStream.println("Min gas cost");
    printStream.print("#accounts\tstate (bytes)");
    for (final TestCase testCase : testCases) {
      printStream.print("\t" + testCase.name() + ", minimum gas");
      printStream.print("\t" + testCase.name() + ", duration (nanos)");
    }
    printStream.println("");

    // Run tests
    for (final int stateSize : stateSizeTiers) {
      stateTierUpgrader.accept(stateSize);

      final int stateSizeInBytes =
          blockchain.getContractState(testCases.get(0).contractAddress()).length;
      printStream.print("%s\t%s".formatted(stateSize, stateSizeInBytes));

      for (final TestCase testCase : testCases) {
        final BenchmarkInfo benchmarkInfo =
            determineMinimumGas(
                blockchain,
                testCase.name(),
                testCase.sender(),
                testCase.contractAddress(),
                testCase.rpc().getData());

        printStream.print("\t" + benchmarkInfo.minimumGas());
        printStream.print("\t" + benchmarkInfo.duration().toNanos());
      }
      printStream.println("");
    }
  }

  /**
   * Determines whether the given {@link RuntimeException} indicates a problem with sending too
   * little gas.
   *
   * @param e Exception that occured.
   * @return True if the exception indicated a gas problem.
   */
  private static boolean wasGasFailure(RuntimeException e) {
    final String msg = e.getMessage();
    if (msg.contains("Cost (")
        && msg.contains(") exceeded maximum (")
        && msg.contains(") set for transaction.")) {
      return true;
    }
    return msg.contains("is less than the network fee")
        || msg.contains("Ran out of gas")
        || msg.contains("Out of instruction gas!")
        || msg.contains("Out of instruction cycles!")
        || msg.contains("Cannot allocate gas for events");
  }

  /**
   * Benchmark result for a single combination of state tiers and test cases.
   *
   * @param minimumGas The minimum amount of gas.
   * @param duration Duration that benchmarking tool.
   */
  private record BenchmarkInfo(long minimumGas, Duration duration) {

    private BenchmarkInfo {
      assertNotNull(minimumGas);
      assertNotNull(duration);
    }
  }

  /**
   * Determines the minimum of gas to send a specific transaction with, by performing binary search
   * over the transaction gas.
   *
   * @param blockchain Blockchain to run tests on.
   * @param name Name of test.
   * @param sender Sender of transaction.
   * @param contract Contract to send transaction.
   * @param rpc RPC to send.
   * @return The minimum gas amount and the duration it took to run that minimum.
   */
  private static BenchmarkInfo determineMinimumGas(
      TestBlockchain blockchain,
      String name,
      BlockchainAddress sender,
      BlockchainAddress contract,
      byte[] rpc) {
    long gasMinimum = 0L;
    long gasMaximum = 100L;
    boolean successYet = false;
    final int expectedStateSize = blockchain.getContractState(contract).length;
    Duration durationOfLastSuccessful = null;

    while (!successYet || gasMinimum < gasMaximum - 1) {
      final long gasAmount = (gasMinimum + gasMaximum) / 2;
      final long startTime = System.nanoTime();

      boolean success = true;
      try {
        blockchain.sendAction(sender, contract, rpc, gasAmount);
      } catch (RuntimeException e) {
        success = false;
        if (!wasGasFailure(e)) {
          throw e;
        }
      }
      final long endTime = System.nanoTime();

      if (success) {
        gasMaximum = gasAmount;
        successYet = true;
        durationOfLastSuccessful = Duration.ofNanos(endTime - startTime);
      } else if (!successYet) {
        gasMaximum = gasMaximum * 2;
      } else {
        gasMinimum = gasAmount;
      }

      final int currentStateSize = blockchain.getContractState(contract).length;

      if (expectedStateSize != currentStateSize) {
        throw new RuntimeException(
            String.format(
                "Contract state must not significantly change during gas tests in test %s", name));
      }
    }

    return new BenchmarkInfo(gasMaximum, durationOfLastSuccessful);
  }
}
