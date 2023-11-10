package defi.properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwap;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import java.io.PrintStream;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Gas benchmarking of {@link LiquiditySwap}.
 *
 * <p>Benchmarking is accomplished by repeatedly trying to call invocations with a varying amount of
 * gas in order to determine the precise amount of gas required for the given contract state sizes.
 *
 * <p>Following {@link LiquiditySwap} invocations are tested:
 *
 * <ul>
 *   <li>Swap.
 *   <li>Deposit.
 *   <li>Withdraw.
 *   <li>Provide liquidity.
 * </ul>
 */
public abstract class LiquiditySwapGasBenchmark extends LiquiditySwapBaseTest {

  private static final BigInteger TOTAL_SUPPLY_A = BigInteger.ONE.shiftLeft(60);
  private static final BigInteger TOTAL_SUPPLY_B = BigInteger.ONE.shiftLeft(62);
  private static final BigInteger AMOUNT_FOR_TOKEN_ACCOUNT = BigInteger.ONE.shiftLeft(35);
  private static final BigInteger AMOUNT_FOR_INITIAL_LIQUIDITY = BigInteger.ONE.shiftLeft(30);

  public BlockchainAddress contractTokenA;
  public BlockchainAddress contractTokenB;
  public List<BlockchainAddress> accounts;

  protected final ContractBytes contractBytesToken;
  protected final ContractBytes contractBytesSwap;

  /**
   * Initialize the benchmarking test class.
   *
   * @param contractBytesToken Contract bytes to initialize the {@link Token} contract.
   * @param contractBytesSwap Contract bytes to initialize the {@link LiquiditySwap} contract.
   */
  public LiquiditySwapGasBenchmark(
      final ContractBytes contractBytesToken, final ContractBytes contractBytesSwap) {
    this.contractBytesToken = contractBytesToken;
    this.contractBytesSwap = contractBytesSwap;
  }

  @ContractTest
  void contractInit() {
    creatorAddress = blockchain.newAccount(1);
    // Setup tokens
    final byte[] initRpcA = Token.initialize("Token A", "A", (byte) 8, TOTAL_SUPPLY_A);
    contractTokenA = blockchain.deployContract(creatorAddress, contractBytesToken, initRpcA);

    final byte[] initRpcB = Token.initialize("Token B", "B", (byte) 8, TOTAL_SUPPLY_B);
    contractTokenB = blockchain.deployContract(creatorAddress, contractBytesToken, initRpcB);

    // Setup swap
    final byte[] initRpcSwap = LiquiditySwap.initialize(contractTokenA, contractTokenB, (short) 0);
    swapContractAddress = blockchain.deployContract(creatorAddress, contractBytesSwap, initRpcSwap);

    // Deposit setup
    depositAmount(List.of(creatorAddress), contractTokenA, AMOUNT_FOR_INITIAL_LIQUIDITY);
    depositAmount(List.of(creatorAddress), contractTokenB, AMOUNT_FOR_INITIAL_LIQUIDITY);

    blockchain.sendAction(
        creatorAddress,
        swapContractAddress,
        LiquiditySwap.provideInitialLiquidity(
            AMOUNT_FOR_INITIAL_LIQUIDITY, AMOUNT_FOR_INITIAL_LIQUIDITY));
  }

  void initializeAdditionalAccounts(final int numAccounts, final boolean needsTokenB) {
    final var accountIdxBasis = (accounts == null ? 0 : accounts.size()) + 2;
    final List<BlockchainAddress> newAccounts =
        IntStream.range(accountIdxBasis, accountIdxBasis + numAccounts)
            .mapToObj(blockchain::newAccount)
            .toList();
    assert newAccounts.size() == numAccounts;
    if (accounts == null) {
      accounts = new ArrayList<>(newAccounts);
    } else {
      accounts.addAll(newAccounts);
    }

    // Ensure accounts have some money
    final var transfers =
        newAccounts.stream().map(s -> new Token.Transfer(s, AMOUNT_FOR_TOKEN_ACCOUNT)).toList();
    blockchain.sendAction(creatorAddress, contractTokenA, Token.bulkTransfer(transfers));
    blockchain.sendAction(creatorAddress, contractTokenB, Token.bulkTransfer(transfers));
    for (final BlockchainAddress sender : newAccounts) {
      blockchain.sendAction(
          sender, contractTokenA, Token.approve(swapContractAddress, AMOUNT_FOR_TOKEN_ACCOUNT));
      blockchain.sendAction(
          sender,
          swapContractAddress,
          LiquiditySwap.deposit(contractTokenA, AMOUNT_FOR_INITIAL_LIQUIDITY));
      if (needsTokenB) {
        blockchain.sendAction(
            sender, contractTokenB, Token.approve(swapContractAddress, AMOUNT_FOR_TOKEN_ACCOUNT));
        blockchain.sendAction(
            sender,
            swapContractAddress,
            LiquiditySwap.deposit(contractTokenB, AMOUNT_FOR_INITIAL_LIQUIDITY));
      }
    }
  }

  @ContractTest(previous = "contractInit")
  void allGasCosts() {
    initializeAdditionalAccounts(1, true);
    blockchain.sendAction(
        creatorAddress,
        contractTokenA,
        Token.approve(swapContractAddress, AMOUNT_FOR_INITIAL_LIQUIDITY));
    blockchain.sendAction(
        creatorAddress,
        contractTokenB,
        Token.approve(swapContractAddress, AMOUNT_FOR_INITIAL_LIQUIDITY));

    final List<Integer> numAccountTiers = List.of(1, 2, 4, 8, 16, 32, 64, 128, 256);

    gasCostCsv(
        System.out,
        numAccountTiers,
        List.of(
            new TestCase(
                "swap",
                accounts.get(0),
                swapContractAddress,
                LiquiditySwap.swap(contractTokenA, BigInteger.ONE, BigInteger.ZERO)),
            new TestCase(
                "deposit",
                accounts.get(0),
                swapContractAddress,
                LiquiditySwap.deposit(contractTokenA, BigInteger.ONE)),
            new TestCase(
                "withdraw",
                accounts.get(0),
                swapContractAddress,
                LiquiditySwap.withdraw(contractTokenA, BigInteger.ONE)),
            new TestCase(
                "provide liquidity",
                accounts.get(0),
                swapContractAddress,
                LiquiditySwap.provideLiquidity(contractTokenA, BigInteger.TEN))));
  }

  private record TestCase(
      String name, BlockchainAddress sender, BlockchainAddress contractAddress, byte[] rpc) {

    private TestCase {
      assertNotNull(name);
      assertNotNull(sender);
      assertNotNull(contractAddress);
      assertNotNull(rpc);
    }
  }

  private void gasCostCsv(
      PrintStream printStream, List<Integer> numAccountTiers, List<TestCase> testCases) {
    // Setup tsv
    printStream.println("Min gas cost");
    printStream.print("#accounts\tstate (bytes)");
    for (final TestCase testCase : testCases) {
      printStream.print("\t" + testCase.name() + ", minimum gas");
      printStream.print("\t" + testCase.name() + ", duration (nanos)");
    }
    printStream.println("");

    // Run tests
    for (final int numAccounts : numAccountTiers) {
      initializeAdditionalAccounts(numAccounts - accounts.size(), false);

      final int stateSize =
          blockchain.getContractState(testCases.get(0).contractAddress()).state().length;
      printStream.print("%s\t%s".formatted(numAccounts, stateSize));

      for (final TestCase testCase : testCases) {
        final BenchmarkInfo benchmarkInfo =
            determineMinimumGas(
                testCase.name(), testCase.sender(), testCase.contractAddress(), testCase.rpc());

        printStream.print("\t" + benchmarkInfo.minimumGas());
        printStream.print("\t" + benchmarkInfo.duration().toNanos());
      }
      printStream.println("");
    }
  }

  private static boolean wasGasFailure(RuntimeException e) {
    final String msg = e.getMessage();
    if (msg.contains("Cost (")
        && msg.contains(") exceeded maximum (")
        && msg.contains(") set for transaction.")) {
      return true;
    }
    return msg.contains("is less than the network fee")
        || msg.contains("Ran out of gas")
        || msg.contains("Out of instruction gas!");
  }

  private record BenchmarkInfo(long minimumGas, Duration duration) {

    private BenchmarkInfo {
      assertNotNull(minimumGas);
      assertNotNull(duration);
    }
  }

  private BenchmarkInfo determineMinimumGas(
      String name, BlockchainAddress sender, BlockchainAddress contract, byte[] rpc) {
    long gasMinimum = 0L;
    long gasMaximum = 100L;
    boolean successYet = false;
    final int expectedStateSize = blockchain.getContractState(contract).state().length;
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

      final int currentStateSize = blockchain.getContractState(contract).state().length;

      if (expectedStateSize != currentStateSize) {
        throw new RuntimeException(
            String.format(
                "Contract state must not significantly change during gas tests in test %s", name));
      }
    }

    return new BenchmarkInfo(gasMaximum, durationOfLastSuccessful);
  }
}
