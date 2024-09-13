package defi.properties;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwap;
import com.partisiablockchain.language.abicodegen.LiquiditySwapLock;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import java.math.BigInteger;
import java.util.List;
import org.assertj.core.api.Assertions;

/** Testing of {@link LiquiditySwap} using a relatively realistic ETH-USDC swap pair. */
public abstract class LiquiditySwapEthUsdcTest extends LiquiditySwapBaseTest {

  private static final int DECIMALS_ETH = 18;
  private static final int DECIMALS_USDC = 3;

  private static final BigInteger BASE_UNIT_ETH = BigInteger.TEN.pow(DECIMALS_ETH);
  private static final BigInteger BASE_UNIT_USDC = BigInteger.TEN.pow(DECIMALS_USDC);

  private static final BigInteger TOTAL_SUPPLY_ETH =
      BigInteger.valueOf(120_000_000L).multiply(BASE_UNIT_ETH);
  private static final BigInteger TOTAL_SUPPLY_USDC =
      BigInteger.valueOf(21_200_000_000_000L).multiply(BASE_UNIT_USDC);

  private static final BigInteger INITIAL_NUM_ETH = BigInteger.valueOf(100);
  private static final BigInteger INITIAL_RATE_ETH_USDC = BigInteger.valueOf(1846);

  private static final BigInteger INITIAL_LIQUIDITY_ETH = INITIAL_NUM_ETH.multiply(BASE_UNIT_ETH);
  private static final BigInteger INITIAL_LIQUIDITY_USDC =
      INITIAL_NUM_ETH.multiply(INITIAL_RATE_ETH_USDC).multiply(BASE_UNIT_USDC);

  private static final short SWAP_FEE_PER_MILLE = (short) 0;

  public BlockchainAddress account2;
  public BlockchainAddress contractEth;
  public BlockchainAddress contractUsdCoin;

  protected final ContractBytes contractBytesToken;
  protected final ContractBytes contractBytesSwap;

  /**
   * Initialize the test class.
   *
   * @param contractBytesToken Contract bytes to initialize the {@link Token} contract.
   * @param contractBytesSwap Contract bytes to initialize the {@link LiquiditySwap} contract.
   */
  public LiquiditySwapEthUsdcTest(
      final ContractBytes contractBytesToken, final ContractBytes contractBytesSwap) {
    this.contractBytesToken = contractBytesToken;
    this.contractBytesSwap = contractBytesSwap;
  }

  @ContractTest
  void contractInit() {
    creatorAddress = blockchain.newAccount(1);
    account2 = blockchain.newAccount(2);

    // Setup tokens
    initializeTokenContracts();

    // Setup swap
    final byte[] initRpcSwap =
        LiquiditySwap.initialize(contractUsdCoin, contractEth, SWAP_FEE_PER_MILLE);
    swapContractAddress = blockchain.deployContract(creatorAddress, contractBytesSwap, initRpcSwap);

    // Deposit setup
    depositAmount(List.of(creatorAddress), contractEth, INITIAL_LIQUIDITY_ETH);
    depositAmount(List.of(creatorAddress), contractUsdCoin, INITIAL_LIQUIDITY_USDC);

    // Provide initial liquidity
    blockchain.sendAction(
        creatorAddress,
        swapContractAddress,
        LiquiditySwap.provideInitialLiquidity(INITIAL_LIQUIDITY_USDC, INITIAL_LIQUIDITY_ETH));

    // Validate
    validateBalance(creatorAddress, contractEth, BigInteger.ZERO);
    validateBalance(creatorAddress, contractUsdCoin, BigInteger.ZERO);
    validateBalance(swapContractAddress, contractEth, INITIAL_LIQUIDITY_ETH);
    validateBalance(swapContractAddress, contractUsdCoin, INITIAL_LIQUIDITY_USDC);
    validateExchangeRate(DECIMALS_ETH - DECIMALS_USDC, INITIAL_RATE_ETH_USDC);
  }

  /**
   * The contract cannot be deployed with a swap fee less than 0 per mille, or greater than 1000.
   */
  @ContractTest
  void deployFeeOutsideRange() {
    creatorAddress = blockchain.newAccount(3);

    // Deploy token contracts.
    initializeTokenContracts();

    byte[] initRpcSwapAtoBlow = LiquiditySwap.initialize(contractUsdCoin, contractEth, (short) -1);
    Assertions.assertThatCode(
            () -> blockchain.deployContract(creatorAddress, contractBytesSwap, initRpcSwapAtoBlow))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("per mille value was 65535‰, but must be between 0‰ and 1000‰");

    byte[] initRpcSwapAtoBhigh =
        LiquiditySwap.initialize(contractUsdCoin, contractEth, (short) 1001);
    Assertions.assertThatCode(
            () -> blockchain.deployContract(creatorAddress, contractBytesSwap, initRpcSwapAtoBhigh))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("per mille value was 1001‰, but must be between 0‰ and 1000‰");
  }

  /**
   * If too little liquidity is provided as the initial liquidity, invocation fails, and no
   * liquidity is provided.
   */
  @ContractTest
  void provideTooLittleInitialLiquidity() {
    creatorAddress = blockchain.newAccount(1);
    account2 = blockchain.newAccount(2);

    // Setup tokens
    initializeTokenContracts();

    // Setup swap
    final byte[] initRpcSwap =
        LiquiditySwap.initialize(contractUsdCoin, contractEth, SWAP_FEE_PER_MILLE);
    swapContractAddress = blockchain.deployContract(creatorAddress, contractBytesSwap, initRpcSwap);

    // Deposit setup
    depositAmount(List.of(creatorAddress), contractEth, INITIAL_LIQUIDITY_ETH);
    depositAmount(List.of(creatorAddress), contractUsdCoin, BigInteger.ZERO);

    // Try to provide initial liquidity
    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    creatorAddress,
                    swapContractAddress,
                    LiquiditySwap.provideInitialLiquidity(INITIAL_LIQUIDITY_USDC, BigInteger.ZERO)))
        .hasMessageContaining("The given input amount yielded 0 minted liquidity");

    // Validate no liquidity.
    LiquiditySwap.LiquiditySwapContractState state =
        new LiquiditySwap(getStateClient(), swapContractAddress).getState();
    LiquiditySwap.TokenBalance stateBalance =
        state.tokenBalances().balances().get(state.liquidityPoolAddress());

    Assertions.assertThat(stateBalance).isNull();
  }

  /** If too little liquidity is provided, invocation fails, and no liquidity is added. */
  @ContractTest(previous = "contractInit")
  void provideTooLittleLiquidity() {
    LiquiditySwap.LiquiditySwapContractState state =
        new LiquiditySwap(getStateClient(), swapContractAddress).getState();
    LiquiditySwap.TokenBalance stateBalance =
        state.tokenBalances().balances().get(state.liquidityPoolAddress());

    final BigInteger aBefore = stateBalance.aTokens();
    final BigInteger bBefore = stateBalance.bTokens();

    // Try to provide too little liquidity
    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    account2,
                    swapContractAddress,
                    LiquiditySwap.provideLiquidity(contractUsdCoin, BigInteger.ZERO)))
        .hasMessageContaining("The given input amount yielded 0 minted liquidity");

    // Validate no liquidity.
    state = new LiquiditySwap(getStateClient(), swapContractAddress).getState();
    stateBalance = state.tokenBalances().balances().get(state.liquidityPoolAddress());

    Assertions.assertThat(stateBalance.aTokens()).isEqualTo(aBefore);
    Assertions.assertThat(stateBalance.bTokens()).isEqualTo(bBefore);
  }

  /** If pools are non-empty, a user cannot provide initial liquidity. */
  @ContractTest(previous = "contractInit")
  void provideInitialLiquidityAgain() {
    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    creatorAddress,
                    swapContractAddress,
                    LiquiditySwap.provideInitialLiquidity(
                        INITIAL_LIQUIDITY_USDC, INITIAL_LIQUIDITY_ETH)))
        .hasMessageContaining("Can only initialize when both pools are empty");
  }

  @ContractTest(previous = "contractInit")
  void swapForOneEth() {
    final BigInteger usdcAmount =
        BigInteger.ONE.multiply(INITIAL_RATE_ETH_USDC).multiply(BASE_UNIT_USDC);

    depositAmount(List.of(account2), contractUsdCoin, usdcAmount);
    validateExchangeRate(DECIMALS_ETH - DECIMALS_USDC, INITIAL_RATE_ETH_USDC);
    validateBalance(account2, contractEth, BigInteger.ZERO);
    validateBalance(account2, contractUsdCoin, usdcAmount);
    swap(account2, contractUsdCoin, usdcAmount);
    validateBalance(account2, contractUsdCoin, BigInteger.ZERO);
    validateExchangeRate(DECIMALS_ETH - DECIMALS_USDC, BigInteger.valueOf(1883));
  }

  @ContractTest(previous = "swapForOneEth")
  void swapBack() {
    final BigInteger etcAmount = swapDepositBalance(account2, contractEth);

    // Lose one to odd exchange rate:
    final BigInteger expectedOutput =
        BigInteger.ONE
            .multiply(INITIAL_RATE_ETH_USDC)
            .multiply(BASE_UNIT_USDC)
            .subtract(BigInteger.ONE);

    swap(account2, contractEth, etcAmount);
    validateExchangeRate(DECIMALS_ETH - DECIMALS_USDC, INITIAL_RATE_ETH_USDC);
    validateBalance(account2, contractEth, BigInteger.ZERO);
    validateBalance(account2, contractUsdCoin, expectedOutput);
  }

  @ContractTest(previous = "swapForOneEth")
  void swapBackButExpectTooMuch() {
    final BigInteger etcAmount = swapDepositBalance(account2, contractEth);
    // Lose one to odd exchange rate, add one to miscalculated output.
    final BigInteger minimumOutput =
        BigInteger.ONE.multiply(INITIAL_RATE_ETH_USDC).multiply(BASE_UNIT_USDC);

    Assertions.assertThatCode(() -> swap(account2, contractEth, etcAmount, minimumOutput))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Swap produced 1845999 output tokens, but minimum was set to 1846000.");
  }

  /** A user cannot perform a swap when there is no liquidity. */
  @ContractTest(previous = "contractInit")
  void swapFailsWithNoLiquidity() {
    final BigInteger usdcAmount =
        BigInteger.ONE.multiply(INITIAL_RATE_ETH_USDC).multiply(BASE_UNIT_USDC);
    LiquiditySwap.LiquiditySwapContractState state =
        new LiquiditySwap(getStateClient(), swapContractAddress).getState();

    // Contract owner reclaims all liquidity
    blockchain.sendAction(
        creatorAddress,
        swapContractAddress,
        LiquiditySwapLock.reclaimLiquidity(
            state.tokenBalances().balances().get(creatorAddress).liquidityTokens()));

    // A user deposits into the swap, and tries to swap.
    depositAmount(List.of(account2), contractUsdCoin, usdcAmount);
    Assertions.assertThatCode(() -> swap(account2, contractUsdCoin, usdcAmount))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Pools must have existing liquidity to perform a swap");
  }

  private void initializeTokenContracts() {
    final byte[] initRpcEth =
        Token.initialize("Ethereum Ether", "ETH", (byte) DECIMALS_ETH, TOTAL_SUPPLY_ETH);
    contractEth = blockchain.deployContract(creatorAddress, contractBytesToken, initRpcEth);

    final byte[] initRpcUsdCoin =
        Token.initialize("USD Coin", "USDC", (byte) DECIMALS_USDC, TOTAL_SUPPLY_USDC);
    contractUsdCoin = blockchain.deployContract(creatorAddress, contractBytesToken, initRpcUsdCoin);
  }
}
