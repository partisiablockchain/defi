package defi.properties;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwap;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import defi.util.GasBenchmark;
import java.math.BigInteger;
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

    GasBenchmark.gasCostCsv(
        blockchain,
        System.out,
        numAccountTiers,
        numWantedAccounts ->
            initializeAdditionalAccounts(numWantedAccounts - accounts.size(), false),
        List.of(
            new GasBenchmark.TestCase(
                "swap",
                accounts.get(0),
                swapContractAddress,
                LiquiditySwap.swap(contractTokenA, BigInteger.ONE, BigInteger.ZERO)),
            new GasBenchmark.TestCase(
                "deposit",
                accounts.get(0),
                swapContractAddress,
                LiquiditySwap.deposit(contractTokenA, BigInteger.ONE)),
            new GasBenchmark.TestCase(
                "withdraw",
                accounts.get(0),
                swapContractAddress,
                LiquiditySwap.withdraw(contractTokenA, BigInteger.ONE, false)),
            new GasBenchmark.TestCase(
                "provide liquidity",
                accounts.get(0),
                swapContractAddress,
                LiquiditySwap.provideLiquidity(contractTokenA, BigInteger.TEN))));
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
}
