package defi.properties;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwapLock;
import com.partisiablockchain.language.abicodegen.SwapRouter;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.Previous;
import com.partisiablockchain.language.testenvironment.TxExecution;
import defi.LiquiditySwapLockPermissionTest;
import defi.LiquiditySwapTestingUtility;
import defi.util.ExecutionUtil;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.assertj.core.api.Assertions;
import org.assertj.core.data.Percentage;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

/**
 * Testing for the {@link SwapRouter} smart contract. The setup that the router is working with is
 * the following graph:
 *
 * <pre>
 *     2^21   2^20
 *      +----A----+
 *      |    |0   |
 * 2^19 |   0|    | 2^23
 *      B    F    C
 *      |   0|    |
 * 2^18 |    |0   | 2^22
 *      +----D----+
 *     2^17    2^24
 *
 *           E
 * </pre>
 *
 * <p>Nodes (letters) represent token contracts, edges represent swap contracts, and the liquidity
 * at each swap contract is indicated by the edge numbers. Note that edges are undirected, as swap
 * contracts swap in both directions.
 */
public abstract class RoutingTest extends JunitContractTest {
  private static final int MAX_ROUTE_LENGTH = 5;
  private static final BigInteger ZERO = BigInteger.ZERO;

  private static final BigInteger TOTAL_SUPPLY_A = BigInteger.ONE.shiftLeft(30);
  private static final BigInteger TOTAL_SUPPLY_B = BigInteger.ONE.shiftLeft(29);
  private static final BigInteger TOTAL_SUPPLY_C = BigInteger.ONE.shiftLeft(28);
  private static final BigInteger TOTAL_SUPPLY_D = BigInteger.ONE.shiftLeft(27);
  private static final BigInteger TOTAL_SUPPLY_E = BigInteger.ONE.shiftLeft(35);
  private static final BigInteger TOTAL_SUPPLY_F = BigInteger.ONE.shiftLeft(37);

  private static final BigInteger A_LIQUIDITY_A_B = BigInteger.ONE.shiftLeft(21);
  private static final BigInteger A_LIQUIDITY_A_C = BigInteger.ONE.shiftLeft(21);
  private static final BigInteger B_LIQUIDITY_A_B = BigInteger.ONE.shiftLeft(19);
  private static final BigInteger B_LIQUIDITY_B_D = BigInteger.ONE.shiftLeft(18);
  private static final BigInteger C_LIQUIDITY_A_C = BigInteger.ONE.shiftLeft(23);
  private static final BigInteger C_LIQUIDITY_C_D = BigInteger.ONE.shiftLeft(22);
  private static final BigInteger D_LIQUIDITY_B_D = BigInteger.ONE.shiftLeft(17);
  private static final BigInteger D_LIQUIDITY_C_D = BigInteger.ONE.shiftLeft(24);

  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_A = BigInteger.ONE.shiftLeft(9);
  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_C = BigInteger.ONE.shiftLeft(10);

  public BlockchainAddress contractOwnerAddress;
  public BlockchainAddress nonOwnerAddress1;
  public BlockchainAddress nonOwnerAddress2;
  private final int numberOfExtraNonOwners;
  public List<BlockchainAddress> extraNonOwnerAddresses;
  public BlockchainAddress routerContract;

  public BlockchainAddress swapLockContractAddressAandB;
  public BlockchainAddress swapLockContractAddressAandC;
  public BlockchainAddress swapLockContractAddressBandD;
  public BlockchainAddress swapLockContractAddressCandD;
  public BlockchainAddress swapLockContractUnknown;
  public BlockchainAddress swapLockContractAddressAandF;
  public BlockchainAddress swapLockContractAddressDandF;
  static final short FEE_AB = 0;
  static final short FEE_AC = 0;
  static final short FEE_BD = 0;
  static final short FEE_CD = 0;
  static final short FEE_AF = 0;
  static final short FEE_DF = 0;
  public BlockchainAddress contractTokenA;
  public BlockchainAddress contractTokenB;
  public BlockchainAddress contractTokenC;
  public BlockchainAddress contractTokenD;
  public BlockchainAddress contractTokenE;
  public BlockchainAddress contractTokenF;
  public List<BlockchainAddress> listOfTokensForRandom;
  public Map<BlockchainAddress, List<BlockchainAddress>> tokensToSwapForRandom;
  public Map<BlockchainAddress, Short> swapToFee;
  static final LiquiditySwapTestingUtility swapUtil = new LiquiditySwapTestingUtility();
  protected final ContractBytes contractBytesToken;
  protected final ContractBytes contractBytesSwap;
  protected final ContractBytes contractBytesSwapRouter;

  private final long swapRouteGasAmount;

  /**
   * Initialize the test class.
   *
   * @param contractBytesToken Contract bytes to initialize the {@link Token} contract.
   * @param contractBytesSwap Contract bytes to initialize the {@link LiquiditySwapLock} contract.
   * @param contractBytesSwapRouter Contract bytes to initialize the {@link SwapRouter} contract.
   */
  public RoutingTest(
      final ContractBytes contractBytesToken,
      final ContractBytes contractBytesSwap,
      final ContractBytes contractBytesSwapRouter,
      final long swapRouteGasAmount,
      final int numberOfExtraNonOwners) {
    this.contractBytesToken = contractBytesToken;
    this.contractBytesSwap = contractBytesSwap;
    this.contractBytesSwapRouter = contractBytesSwapRouter;
    this.swapRouteGasAmount = swapRouteGasAmount;
    this.numberOfExtraNonOwners = numberOfExtraNonOwners;
  }

  /** Router contract can be correctly deployed. */
  @ContractTest
  void contractInit() {
    contractOwnerAddress = blockchain.newAccount(3);

    deployTokenContracts();

    List<SwapRouter.SwapContractInfo> swapContractInfoList = deploySwapContracts();

    provideInitialLiquidity();

    initializeNonOwners();

    // Deploy the router
    byte[] initRpcRouter = SwapRouter.initialize(swapContractInfoList);
    routerContract =
        blockchain.deployContract(contractOwnerAddress, contractBytesSwapRouter, initRpcRouter);

    // Check state has been correctly initialized.
    SwapRouter.RouterState state = getRouterState();
    Assertions.assertThat(state).isNotNull();
    Assertions.assertThat(state.swapContracts()).isEqualTo(swapContractInfoList);
    Assertions.assertThat(state.routeTracker()).isNotNull();
    Assertions.assertThat(state.routeTracker().nextRouteId()).isZero();
    Assertions.assertThat(state.routeTracker().activeRoutes()).isEmpty();
  }

  /**
   * A user can provide the router with a valid swap route, which results in the router performing
   * swaps along the route, and the user ending up with the desired output tokens.
   */
  @RepeatedTest(10)
  @Previous("contractInit")
  void swapRoute(RepetitionInfo repetitionInfo) {
    // Calculate receiving amounts for later assertions.
    final BigInteger receivingC =
        calculateReceivingAmount(
            swapLockContractAddressAandC, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, FEE_AC);
    final BigInteger receivingD =
        calculateReceivingAmount(swapLockContractAddressCandD, contractTokenC, receivingC, FEE_CD);

    // Approve the router at the original token.
    blockchain.sendAction(
        nonOwnerAddress1, contractTokenA, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_A));

    // Route swap A -> C -> D.
    List<BlockchainAddress> swapRoute =
        List.of(swapLockContractAddressAandC, swapLockContractAddressCandD);

    final TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute, contractTokenA, contractTokenD, NON_OWNER_TOKEN_AMOUNT_A, ZERO),
            swapRouteGasAmount);
    executeTxExecutionInUnpredictableOrder(repetitionInfo, List.of(s1));

    Assertions.assertThat(getTokenBalance(contractTokenD, nonOwnerAddress1)).isEqualTo(receivingD);
  }

  /**
   * Two users can route the same swap route, at the same time, both resulting in successful
   * route-swaps.
   */
  @RepeatedTest(10)
  @Previous("contractInit")
  void swapRouteDoubleIdentical(RepetitionInfo repetitionInfo) {
    // Calculate receiving amounts for later assertions.
    final BigInteger receivingC =
        calculateReceivingAmount(
            swapLockContractAddressAandC, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, FEE_AC);
    final BigInteger receivingD =
        calculateReceivingAmount(swapLockContractAddressCandD, contractTokenC, receivingC, FEE_CD);

    // Approve the router at the original token.
    blockchain.sendAction(
        nonOwnerAddress1, contractTokenA, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_A));
    blockchain.sendAction(
        nonOwnerAddress2, contractTokenA, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_A));

    // Route swap A -> C -> D.
    List<BlockchainAddress> swapRoute =
        List.of(swapLockContractAddressAandC, swapLockContractAddressCandD);

    final TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute,
                contractTokenA,
                contractTokenD,
                NON_OWNER_TOKEN_AMOUNT_A,
                BigInteger.ONE),
            swapRouteGasAmount);
    final TxExecution s2 =
        blockchain.sendActionAsync(
            nonOwnerAddress2,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute,
                contractTokenA,
                contractTokenD,
                NON_OWNER_TOKEN_AMOUNT_A,
                BigInteger.ONE),
            swapRouteGasAmount);

    executeTxExecutionInUnpredictableOrder(repetitionInfo, List.of(s1, s2));

    Assertions.assertThat(s1.getContractInteraction().isRecursiveSuccess()).isTrue();
    Assertions.assertThat(s2.getContractInteraction().isRecursiveSuccess()).isTrue();

    assertTokenBalanceMarginEntry(contractTokenD, nonOwnerAddress1, receivingD, 1);
    assertTokenBalanceMarginEntry(contractTokenD, nonOwnerAddress2, receivingD, 1);
  }

  /**
   * Many users can route the same swap route, at the same time, all resulting in successful
   * route-swaps.
   */
  @RepeatedTest(10)
  @Previous("contractInit")
  void swapRouteManyIdentical(RepetitionInfo repetitionInfo) {
    // Calculate receiving amounts for later assertions.
    final BigInteger receivingC =
        calculateReceivingAmount(
            swapLockContractAddressAandC, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, FEE_AC);
    final BigInteger receivingD =
        calculateReceivingAmount(swapLockContractAddressCandD, contractTokenC, receivingC, FEE_CD);

    // Approve the router at the original token, for all nonOwners.
    approveExtraNonOwnersA();

    // Route swap A -> C -> D.
    List<BlockchainAddress> swapRoute =
        List.of(swapLockContractAddressAandC, swapLockContractAddressCandD);

    List<TxExecution> spawns = new ArrayList<>();
    final int numberOfNonOwnersToUse = 10;
    for (BlockchainAddress nonOwner : extraNonOwnerAddresses.subList(0, numberOfNonOwnersToUse)) {
      spawns.add(
          blockchain.sendActionAsync(
              nonOwner,
              routerContract,
              SwapRouter.routeSwap(
                  swapRoute,
                  contractTokenA,
                  contractTokenD,
                  NON_OWNER_TOKEN_AMOUNT_A,
                  BigInteger.ONE),
              swapRouteGasAmount));
    }
    executeTxExecutionInUnpredictableOrder(repetitionInfo, spawns);

    for (TxExecution initialSpawn : spawns) {
      Assertions.assertThat(initialSpawn.getContractInteraction().isRecursiveSuccess()).isTrue();
    }

    for (BlockchainAddress nonOwner : extraNonOwnerAddresses.subList(0, numberOfNonOwnersToUse)) {
      assertTokenBalanceMarginEntry(contractTokenD, nonOwner, receivingD, 2.6);
    }
  }

  /**
   * Many users can execute different swap routes at the same time, all resulting in successful
   * route-swaps.
   */
  @RepeatedTest(10)
  @Previous("contractInit")
  void swapRouteManyRandom(RepetitionInfo repetitionInfo) {
    initializeTestMappings();
    List<TxExecution> spawns = new ArrayList<>();
    List<BigInteger> expectedOutcomes = new ArrayList<>();

    final int amountOfRoutes = 10;

    List<RandomRouteInfo> randomRoutes = generateRandomRoutes(repetitionInfo, amountOfRoutes, 3);

    // Finish approving first
    for (int i = 0; i < amountOfRoutes; i++) {
      RandomRouteInfo info = randomRoutes.get(i);
      BlockchainAddress routeUser = extraNonOwnerAddresses.get(i);

      blockchain.sendAction(
          contractOwnerAddress, info.initialToken, Token.transfer(routeUser, info.initialAmount));

      blockchain.sendAction(
          routeUser, info.initialToken, Token.approve(routerContract, info.initialAmount));

      final BigInteger balance = getTokenBalance(info.finalToken, routeUser);
      if (balance != null) {
        expectedOutcomes.add(info.expectedOutput.add(balance));
      } else {
        expectedOutcomes.add(info.expectedOutput);
      }
    }
    // Then start async calls.
    for (int i = 0; i < amountOfRoutes; i++) {
      RandomRouteInfo info = randomRoutes.get(i);
      BlockchainAddress routeUser = extraNonOwnerAddresses.get(i);
      spawns.add(
          blockchain.sendActionAsync(
              routeUser,
              routerContract,
              SwapRouter.routeSwap(
                  info.swapRoute,
                  info.initialToken,
                  info.finalToken,
                  info.initialAmount,
                  BigInteger.ONE),
              swapRouteGasAmount));
    }

    executeTxExecutionInUnpredictableOrder(repetitionInfo, spawns);

    // All the interactions fully succeeded.
    for (TxExecution initialSpawn : spawns) {
      Assertions.assertThat(initialSpawn.getContractInteraction().isRecursiveSuccess()).isTrue();
    }

    for (int i = 0; i < amountOfRoutes; i++) {
      BlockchainAddress nonOwner = extraNonOwnerAddresses.get(i);
      RandomRouteInfo info = randomRoutes.get(i);
      // The tokens arrived at their intended location (within margin)
      assertTokenBalanceMarginEntry(info.finalToken, nonOwner, expectedOutcomes.get(i), 50);
      Assertions.assertThat(getTokenBalance(info.finalToken, nonOwner)).isGreaterThan(ZERO);
    }
  }

  /**
   * A huge amount of users can execute different swap routes at the same time, all resulting in
   * successful route-swaps.
   */
  @RepeatedTest(1)
  @Previous("contractInit")
  void swapHugeNumberOfRoutesAtTheSameTime(RepetitionInfo repetitionInfo) {
    initializeTestMappings();
    List<TxExecution> spawns = new ArrayList<>();

    final int amountOfRoutes = numberOfExtraNonOwners;
    final int numberOfSwaps = 4;
    List<RandomRouteInfo> randomRoutes =
        generateRandomRoutes(repetitionInfo, amountOfRoutes, numberOfSwaps);

    // Finish approving first
    for (int i = 0; i < amountOfRoutes; i++) {
      RandomRouteInfo info = randomRoutes.get(i);
      BlockchainAddress routeUser = extraNonOwnerAddresses.get(i);

      blockchain.sendAction(
          contractOwnerAddress, info.initialToken, Token.transfer(routeUser, info.initialAmount));

      blockchain.sendAction(
          routeUser, info.initialToken, Token.approve(routerContract, info.initialAmount));
    }
    final long guaranteedGasCost = 90_000;
    // Then start async calls.
    for (int i = 0; i < amountOfRoutes; i++) {
      RandomRouteInfo info = randomRoutes.get(i);
      BlockchainAddress routeUser = extraNonOwnerAddresses.get(i);
      spawns.add(
          blockchain.sendActionAsync(
              routeUser,
              routerContract,
              SwapRouter.routeSwap(
                  info.swapRoute,
                  info.initialToken,
                  info.finalToken,
                  info.initialAmount,
                  BigInteger.ONE),
              guaranteedGasCost));
    }

    executeTxExecutionInUnpredictableOrder(repetitionInfo, spawns);

    // All the interactions fully succeeded.
    for (TxExecution initialSpawn : spawns) {
      Assertions.assertThat(initialSpawn.getContractInteraction().isRecursiveSuccess()).isTrue();
    }

    for (int i = 0; i < amountOfRoutes; i++) {
      BlockchainAddress nonOwner = extraNonOwnerAddresses.get(i);
      RandomRouteInfo info = randomRoutes.get(i);
      // The tokens arrived at their intended location (within margin)
      Assertions.assertThat(getTokenBalance(info.finalToken, nonOwner)).isGreaterThan(ZERO);
    }
  }

  @ContractTest(previous = "contractInit")
  void gasTest() {
    // Approve the router at the original token.
    blockchain.sendAction(
        nonOwnerAddress1, contractTokenA, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_A));

    // Route swap A -> C -> D.
    List<BlockchainAddress> swapRoute =
        List.of(swapLockContractAddressAandC, swapLockContractAddressCandD);

    final TxExecution s1 =
        blockchain.sendAction(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute, contractTokenA, contractTokenD, NON_OWNER_TOKEN_AMOUNT_A, ZERO),
            swapRouteGasAmount);

    s1.printGasAccounting();
  }

  /**
   * Two users can execute reverse routes at the same time, both resulting in successful
   * route-swaps.
   */
  @RepeatedTest(10)
  @Previous("contractInit")
  void swapRouteIdenticalReverse(RepetitionInfo repetitionInfo) {
    // Calculate receiving amounts for later assertions.
    final BigInteger receivingAtoB =
        calculateReceivingAmount(
            swapLockContractAddressAandB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, FEE_AB);
    final BigInteger receivingBtoD =
        calculateReceivingAmount(
            swapLockContractAddressBandD, contractTokenB, receivingAtoB, FEE_BD);
    final BigInteger receivingDtoC =
        calculateReceivingAmount(
            swapLockContractAddressCandD, contractTokenD, receivingBtoD, FEE_CD);

    final BigInteger receivingCtoD =
        calculateReceivingAmount(
            swapLockContractAddressCandD, contractTokenC, NON_OWNER_TOKEN_AMOUNT_C, FEE_CD);
    final BigInteger receivingDtoB =
        calculateReceivingAmount(
            swapLockContractAddressBandD, contractTokenD, receivingCtoD, FEE_BD);
    final BigInteger receivingBtoA =
        calculateReceivingAmount(
            swapLockContractAddressAandB, contractTokenB, receivingDtoB, FEE_AB);

    // Give second user some C tokens
    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenC,
        Token.transfer(nonOwnerAddress2, NON_OWNER_TOKEN_AMOUNT_C));

    // Approve the router at the original tokens.
    blockchain.sendAction(
        nonOwnerAddress1, contractTokenA, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_A));
    blockchain.sendAction(
        nonOwnerAddress2, contractTokenC, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_C));

    // Route swap A -> B -> D -> C (nonOwner1).
    List<BlockchainAddress> swapRoute1 =
        List.of(
            swapLockContractAddressAandB,
            swapLockContractAddressBandD,
            swapLockContractAddressCandD);

    // Route swap A <- B <- D <- C (nonOwner2).
    List<BlockchainAddress> swapRoute2 =
        List.of(
            swapLockContractAddressCandD,
            swapLockContractAddressBandD,
            swapLockContractAddressAandB);

    final TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute1,
                contractTokenA,
                contractTokenC,
                NON_OWNER_TOKEN_AMOUNT_A,
                BigInteger.ONE),
            swapRouteGasAmount);
    final TxExecution s2 =
        blockchain.sendActionAsync(
            nonOwnerAddress2,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute2,
                contractTokenC,
                contractTokenA,
                NON_OWNER_TOKEN_AMOUNT_C,
                BigInteger.ONE),
            swapRouteGasAmount);

    executeTxExecutionInUnpredictableOrder(repetitionInfo, List.of(s1, s2));

    Assertions.assertThat(s1.getContractInteraction().isRecursiveSuccess()).isTrue();
    Assertions.assertThat(s2.getContractInteraction().isRecursiveSuccess()).isTrue();

    assertTokenBalanceMarginEntry(contractTokenC, nonOwnerAddress1, receivingDtoC, 14);
    assertTokenBalanceMarginEntry(contractTokenA, nonOwnerAddress2, receivingBtoA, 20);
  }

  /**
   * A user can provide a route with the same swap contract included multiple times, which still
   * results in a route-swap.
   */
  @ContractTest(previous = "contractInit")
  void swapRouteDuplicateSwapContract() {
    // Calculate receiving amounts for later assertions.
    final BigInteger receivingC =
        calculateReceivingAmount(
            swapLockContractAddressAandC, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, FEE_AC);
    final BigInteger receivingA =
        calculateReceivingAmount(swapLockContractAddressAandC, contractTokenC, receivingC, FEE_AC);
    final BigInteger receivingB =
        calculateReceivingAmount(swapLockContractAddressAandB, contractTokenA, receivingA, FEE_AB);

    // Approve the router at the original token.
    blockchain.sendAction(
        nonOwnerAddress1, contractTokenA, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_A));

    // Route swap A -> C -> A -> B.
    List<BlockchainAddress> swapRoute =
        List.of(
            swapLockContractAddressAandC,
            swapLockContractAddressAandC,
            swapLockContractAddressAandB);

    blockchain.sendAction(
        nonOwnerAddress1,
        routerContract,
        SwapRouter.routeSwap(
            swapRoute, contractTokenA, contractTokenB, NON_OWNER_TOKEN_AMOUNT_A, ZERO),
        swapRouteGasAmount);

    // User gets the desired tokens.
    Assertions.assertThat(getTokenBalance(contractTokenB, nonOwnerAddress1)).isEqualTo(receivingB);
  }

  private TxExecution executeEventAsync(TxExecution tx) {
    blockchain.executeEventAsync(tx);
    return tx;
  }

  /**
   * A user can request a route swap, which acquires locks along the route, and then executes them.
   * This test is the same test as {@link #swapRoute}, but with manual execution flow, allowing
   * assertions of the interactions happening along the route.
   */
  @ContractTest(previous = "contractInit")
  void swapRouteDetailed() {
    // Calculate receiving amounts for later assertions.
    final BigInteger receivingC =
        calculateReceivingAmount(
            swapLockContractAddressAandC, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, FEE_AC);
    final BigInteger receivingD =
        calculateReceivingAmount(swapLockContractAddressCandD, contractTokenC, receivingC, FEE_CD);

    // Approve the router at the original token.
    blockchain.sendAction(
        nonOwnerAddress1, contractTokenA, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_A));

    // Route swap A -> C -> D.
    List<BlockchainAddress> swapRoute =
        List.of(swapLockContractAddressAandC, swapLockContractAddressCandD);
    TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute, contractTokenA, contractTokenD, NON_OWNER_TOKEN_AMOUNT_A, ZERO),
            swapRouteGasAmount);

    // Execute user -> router interaction.
    TxExecution s2 = executeEventAsync(s1.getContractInteraction());

    // Execute router -> token transfer_from interaction.
    TxExecution s3 = executeEventAsync(s2.getContractInteraction());
    TxExecution s4 = executeEventAsync(s3.getSystemCallback());
    TxExecution s5 = executeEventAsync(s4.getContractCallback());
    // We took control of tokens.
    Assertions.assertThat(getTokenBalance(contractTokenA, routerContract))
        .isEqualTo(NON_OWNER_TOKEN_AMOUNT_A);

    // Acquire first lock at AC.
    TxExecution s6 = executeEventAsync(s5.getContractInteraction());
    Assertions.assertThat(getSwapState(swapLockContractAddressAandC).virtualState().locks())
        .hasSize(1);

    // Callback to acquire next lock.
    TxExecution s7 = executeEventAsync(s6.getSystemCallback());
    TxExecution s8 = executeEventAsync(s7.getContractCallback());

    // Acquire second lock at CD.
    TxExecution s9 = executeEventAsync(s8.getContractInteraction());
    Assertions.assertThat(getSwapState(swapLockContractAddressCandD).virtualState().locks())
        .hasSize(1);

    // Execute callbacks to acquire next lock.
    TxExecution s10 = executeEventAsync(s9.getSystemCallback());
    TxExecution s11 = executeEventAsync(s10.getContractCallback());

    // Executing the lock on A -> C:
    // Approve
    TxExecution s12 = executeEventAsync(s11.getContractInteraction());
    TxExecution s13 = executeEventAsync(s12.getSystemCallback());
    TxExecution s14 = executeEventAsync(s13.getContractCallback());
    // Deposit
    TxExecution s15 = executeEventAsync(s14.getContractInteraction());

    TxExecution s16 = executeEventAsync(s15.getContractInteraction());
    TxExecution s17 = executeEventAsync(s16.getSystemCallback());
    TxExecution s18 = executeEventAsync(s17.getContractCallback());

    Assertions.assertThat(getSwapState(swapLockContractAddressAandC).tokenBalances().balances())
        .containsEntry(
            routerContract,
            new LiquiditySwapLock.TokenBalance(NON_OWNER_TOKEN_AMOUNT_A, ZERO, ZERO));
    TxExecution s19 = executeEventAsync(s18.getSystemCallback());
    TxExecution s20 = executeEventAsync(s19.getContractCallback());
    // Execute
    TxExecution s21 = executeEventAsync(s20.getContractInteraction());
    Assertions.assertThat(getSwapState(swapLockContractAddressAandC).virtualState().locks())
        .hasSize(0);
    Assertions.assertThat(getSwapState(swapLockContractAddressAandC).tokenBalances().balances())
        .containsEntry(routerContract, new LiquiditySwapLock.TokenBalance(ZERO, receivingC, ZERO));

    // Handle callback to withdrawal
    TxExecution s22 = executeEventAsync(s21.getSystemCallback());
    TxExecution s23 = executeEventAsync(s22.getContractCallback());

    // Start the withdrawal of C tokens, to ready next execute.
    TxExecution s24 = executeEventAsync(s23.getContractInteraction());
    // Withdrawing triggers a transfer from the swap contract at the token.
    TxExecution s25 = executeEventAsync(s24.getContractInteraction());
    Assertions.assertThat(getTokenBalance(contractTokenC, routerContract)).isEqualTo(receivingC);
    // A callback is triggered to the swap contract, to ensure correct ordering.
    TxExecution s26 = executeEventAsync(s25.getSystemCallback());
    TxExecution s27 = executeEventAsync(s26.getContractCallback());
    // And then callback to our execute handler.
    TxExecution s28 = executeEventAsync(s27.getSystemCallback());
    TxExecution s29 = executeEventAsync(s28.getContractCallback());

    // Execute the lock on C -> D.
    // Approve
    TxExecution s30 = executeEventAsync(s29.getContractInteraction());
    TxExecution s31 = executeEventAsync(s30.getSystemCallback());
    TxExecution s32 = executeEventAsync(s31.getContractCallback());
    // Deposit
    TxExecution s33 = executeEventAsync(s32.getContractInteraction());

    TxExecution s34 = executeEventAsync(s33.getContractInteraction());
    TxExecution s35 = executeEventAsync(s34.getSystemCallback());
    TxExecution s36 = executeEventAsync(s35.getContractCallback());

    Assertions.assertThat(getSwapState(swapLockContractAddressCandD).tokenBalances().balances())
        .containsEntry(routerContract, new LiquiditySwapLock.TokenBalance(receivingC, ZERO, ZERO));
    TxExecution s37 = executeEventAsync(s36.getSystemCallback());
    TxExecution s38 = executeEventAsync(s37.getContractCallback());
    // Execute
    TxExecution s39 = executeEventAsync(s38.getContractInteraction());
    Assertions.assertThat(getSwapState(swapLockContractAddressCandD).virtualState().locks())
        .hasSize(0);
    Assertions.assertThat(getSwapState(swapLockContractAddressCandD).tokenBalances().balances())
        .containsEntry(routerContract, new LiquiditySwapLock.TokenBalance(ZERO, receivingD, ZERO));

    // Handle callback to withdrawal
    TxExecution s40 = executeEventAsync(s39.getSystemCallback());
    TxExecution s41 = executeEventAsync(s40.getContractCallback());

    // Start withdrawal of D tokens.
    TxExecution s42 = executeEventAsync(s41.getContractInteraction());
    TxExecution s43 = executeEventAsync(s42.getContractInteraction());
    Assertions.assertThat(getTokenBalance(contractTokenD, routerContract)).isEqualTo(receivingD);
    // And handle the callback to next lock execute.
    TxExecution s44 = executeEventAsync(s43.getSystemCallback());
    TxExecution s45 = executeEventAsync(s44.getContractCallback());
    TxExecution s46 = executeEventAsync(s45.getSystemCallback());
    TxExecution s47 = executeEventAsync(s46.getContractCallback());

    // No more locks to execute, transfer D tokens to user.
    blockchain.executeEvent(s47.getContractInteraction());

    Assertions.assertThat(getTokenBalance(contractTokenD, nonOwnerAddress1)).isEqualTo(receivingD);
  }

  /**
   * If a user provides a swap address which is unknown to the router, routing fails, and no locks
   * are acquired.
   */
  @ContractTest(previous = "contractInit")
  void routeUnknownSwapAddress() {
    // Route swap A -> C -> D -> E.
    List<BlockchainAddress> swapRoute =
        List.of(
            swapLockContractAddressAandC, swapLockContractAddressCandD, swapLockContractUnknown);

    TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute, contractTokenA, contractTokenE, NON_OWNER_TOKEN_AMOUNT_A, ZERO),
            swapRouteGasAmount);
    TxExecution s2 = executeEventAsync(s1.getContractInteraction());

    // We get the correct error message.
    Assertions.assertThat(s1.getContractInteraction().getFailureCause().getErrorMessage())
        .contains(
            "Unknown swap address: %s."
                .formatted(
                    LiquiditySwapLockPermissionTest.addressAsFormattedByteString(
                        swapLockContractUnknown)));

    // The interaction didn't spawn further events -> No calls to swap contracts -> no locks
    // acquired.
    Assertions.assertThat(s2.getSpawnedEvents()).isEmpty();

    // Check that no tokens have been taken from the end user.
    Assertions.assertThat(getTokenBalance(contractTokenA, nonOwnerAddress1))
        .isEqualTo(NON_OWNER_TOKEN_AMOUNT_A);
  }

  /**
   * If a user provides a route where tokens doesn't match, the route is rejected, and no locks are
   * acquired.
   */
  @ContractTest(previous = "contractInit")
  void invalidSwapAlongRoute() {
    // Try to route swap A -> C -> D -> B,
    // But use a wrong swap in the middle.
    List<BlockchainAddress> swapRoute =
        List.of(
            swapLockContractAddressAandC,
            swapLockContractAddressBandD,
            swapLockContractAddressBandD);

    TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute, contractTokenA, contractTokenB, NON_OWNER_TOKEN_AMOUNT_A, ZERO),
            swapRouteGasAmount);
    TxExecution s2 = executeEventAsync(s1.getContractInteraction());

    // We get the correct error message.
    Assertions.assertThat(s1.getContractInteraction().getFailureCause().getErrorMessage())
        .contains(
            "No tokens at swap contract %s matches token %s, at swap number %s."
                .formatted(
                    LiquiditySwapLockPermissionTest.addressAsFormattedByteString(
                        swapLockContractAddressBandD),
                    LiquiditySwapLockPermissionTest.addressAsFormattedByteString(contractTokenC),
                    2));

    // The interaction didn't spawn further events -> No calls to swap contracts -> no locks
    // acquired.
    Assertions.assertThat(s2.getSpawnedEvents()).isEmpty();

    // Check that no tokens have been taken from the end user.
    Assertions.assertThat(getTokenBalance(contractTokenA, nonOwnerAddress1))
        .isEqualTo(NON_OWNER_TOKEN_AMOUNT_A);
  }

  /**
   * If a user provides a valid route, but the final token doesn't match the intended output, the
   * route is rejected and no locks are acquired.
   */
  @ContractTest(previous = "contractInit")
  void outputTokensDoesntMatch() {
    // Try to route swap A -> C -> D,
    // But use a wrong output token.
    List<BlockchainAddress> swapRoute =
        List.of(swapLockContractAddressAandC, swapLockContractAddressCandD);

    TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute, contractTokenA, contractTokenE, NON_OWNER_TOKEN_AMOUNT_A, ZERO),
            swapRouteGasAmount);
    TxExecution s2 = executeEventAsync(s1.getContractInteraction());

    // We get the correct error message.
    Assertions.assertThat(s1.getContractInteraction().getFailureCause().getErrorMessage())
        .contains("The output token from the swap route doesn't match the intended output.");

    // The interaction didn't spawn further events -> No calls to swap contracts -> no locks
    // acquired.
    Assertions.assertThat(s2.getSpawnedEvents()).isEmpty();
  }

  /**
   * If the minimum output amount is higher than the locks can provide, the final lock acquisition
   * fails, and all acquired locks so far are cancelled.
   */
  @Previous("contractInit")
  @RepeatedTest(5)
  void routeMinimumOutputTooHigh(RepetitionInfo repetitionInfo) {
    // Calculate receiving amounts.
    final BigInteger receivingC =
        calculateReceivingAmount(
            swapLockContractAddressAandC, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, FEE_AC);
    final BigInteger receivingD =
        calculateReceivingAmount(swapLockContractAddressCandD, contractTokenC, receivingC, FEE_CD);

    // Route swap A -> C -> D,
    // But set amountOutMinimum too higher.
    List<BlockchainAddress> swapRoute =
        List.of(swapLockContractAddressAandC, swapLockContractAddressCandD);

    // Approve the router at the original token.
    blockchain.sendAction(
        nonOwnerAddress1, contractTokenA, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_A));

    TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute,
                contractTokenA,
                contractTokenD,
                NON_OWNER_TOKEN_AMOUNT_A,
                receivingD.add(BigInteger.ONE)),
            swapRouteGasAmount);

    ExecutionUtil.executeTxExecutionInUnpredictableOrder(this, repetitionInfo, List.of(s1))
        .assertFailures(
            swapLockContractAddressCandD,
            "Swap would produce %s output tokens, but minimum was set to %s."
                .formatted(receivingD, receivingD.add(BigInteger.ONE)),
            routerContract,
            "Could not acquire all locks in route.");

    // Check for no locks, and no tokens taken from the user
    Assertions.assertThat(getSwapState(swapLockContractAddressAandB).virtualState().locks())
        .hasSize(0);
    Assertions.assertThat(getSwapState(swapLockContractAddressBandD).virtualState().locks())
        .hasSize(0);
    Assertions.assertThat(getTokenBalance(contractTokenA, nonOwnerAddress1))
        .isEqualTo(NON_OWNER_TOKEN_AMOUNT_A);
  }

  /**
   * If a user provides a route which includes a swap contract without liquidity, lock acquisition
   * fails, and all acquired locks are cancelled.
   */
  @Previous("contractInit")
  @RepeatedTest(5)
  void swapNoLiquidity(RepetitionInfo repetitionInfo) {
    // Route swap A -> B -> D -> F.
    List<BlockchainAddress> swapRoute =
        List.of(
            swapLockContractAddressAandB,
            swapLockContractAddressBandD,
            swapLockContractAddressDandF);

    // Approve the router at the original token.
    blockchain.sendAction(
        nonOwnerAddress1, contractTokenA, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_A));

    final TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute, contractTokenA, contractTokenF, NON_OWNER_TOKEN_AMOUNT_A, ZERO),
            swapRouteGasAmount);

    ExecutionUtil.executeTxExecutionInUnpredictableOrder(this, repetitionInfo, List.of(s1))
        .assertFailures(
            swapLockContractAddressDandF,
            "Pools must have existing liquidity to acquire a lock",
            routerContract,
            "Could not acquire all locks in route.");

    // Check for no locks, and no tokens taken from the user
    Assertions.assertThat(getSwapState(swapLockContractAddressAandB).virtualState().locks())
        .hasSize(0);
    Assertions.assertThat(getSwapState(swapLockContractAddressBandD).virtualState().locks())
        .hasSize(0);
    Assertions.assertThat(getTokenBalance(contractTokenA, nonOwnerAddress1))
        .isEqualTo(NON_OWNER_TOKEN_AMOUNT_A);
  }

  /** If a user provides an empty swap route, the swap is rejected. */
  @ContractTest(previous = "contractInit")
  void emptySwapRouteIsRejected() {
    // Empty route
    List<BlockchainAddress> swapRoute = List.of();

    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    nonOwnerAddress1,
                    routerContract,
                    SwapRouter.routeSwap(
                        swapRoute, contractTokenA, contractTokenB, NON_OWNER_TOKEN_AMOUNT_A, ZERO),
                    swapRouteGasAmount))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("The given route is empty.");
  }

  /**
   * If a user doesn't approve the router before requesting a lock swap, routing fails and no locks
   * are acquired.
   */
  @ContractTest(previous = "contractInit")
  void userDoesntApproveRouter() {
    // Route swap A -> B -> D.
    List<BlockchainAddress> swapRoute =
        List.of(swapLockContractAddressAandB, swapLockContractAddressBandD);

    TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute, contractTokenA, contractTokenD, NON_OWNER_TOKEN_AMOUNT_A, ZERO),
            swapRouteGasAmount);

    // Execute user -> router interaction.
    TxExecution s2 = executeEventAsync(s1.getContractInteraction());

    // Execute router -> Token transfer_from interaction
    TxExecution s3 = executeEventAsync(s2.getContractInteraction());
    Assertions.assertThat(s2.getContractInteraction().getFailureCause().getErrorMessage())
        .contains(
            String.format(
                "Insufficient allowance for transfer_from: 0/%s", NON_OWNER_TOKEN_AMOUNT_A));

    // Handle callback to allow router to throw error.
    TxExecution s4 = executeEventAsync(s3.getSystemCallback());
    blockchain.executeEvent(s4.getContractCallback());
    Assertions.assertThat(s4.getContractCallback().getFailureCause().getErrorMessage())
        .contains("Could not take control of tokens.");
  }

  /**
   * If a user has fewer tokens than initial swap input amount, routing fails and no locks are
   * acquired.
   */
  @ContractTest(previous = "contractInit")
  void userDoesntHaveEnoughTokens() {
    // Route swap A -> B -> D.
    List<BlockchainAddress> swapRoute =
        List.of(swapLockContractAddressAandB, swapLockContractAddressBandD);

    // Approve the router at the original token.
    blockchain.sendAction(
        nonOwnerAddress1, contractTokenA, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_A));

    TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            routerContract,
            SwapRouter.routeSwap(
                swapRoute,
                contractTokenA,
                contractTokenD,
                NON_OWNER_TOKEN_AMOUNT_A.add(BigInteger.ONE), // 1 more token than owned.
                ZERO),
            swapRouteGasAmount);

    // Execute user -> router interaction.
    TxExecution s2 = executeEventAsync(s1.getContractInteraction());

    // Execute router -> Token transfer_from interaction
    TxExecution s3 = executeEventAsync(s2.getContractInteraction());
    Assertions.assertThat(s2.getContractInteraction().getFailureCause().getErrorMessage())
        .contains(
            String.format(
                "Insufficient allowance for transfer_from: %s/%s",
                NON_OWNER_TOKEN_AMOUNT_A, NON_OWNER_TOKEN_AMOUNT_A.add(BigInteger.ONE)));

    // Handle callback to allow router to throw error.
    TxExecution s4 = executeEventAsync(s3.getSystemCallback());
    blockchain.executeEvent(s4.getContractCallback());
    Assertions.assertThat(s4.getContractCallback().getFailureCause().getErrorMessage())
        .contains("Could not take control of tokens.");
  }

  /**
   * If a user provides too little gas to execute the whole swap-chain, routing exits early, without
   * acquiring locks.
   */
  @RepeatedTest(10)
  @Previous("contractInit")
  void tooLittleGas(RepetitionInfo repetitionInfo) {
    initializeTestMappings();
    BlockchainAddress routeUser = blockchain.newAccount(66);

    RandomRouteInfo randomRoute = generateRandomRoutes(repetitionInfo, 1, 4).get(0);
    final long tooLittleGas = 87250;

    blockchain.sendAction(
        contractOwnerAddress,
        randomRoute.initialToken,
        Token.transfer(routeUser, randomRoute.initialAmount));

    blockchain.sendAction(
        routeUser,
        randomRoute.initialToken,
        Token.approve(routerContract, randomRoute.initialAmount));

    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    routeUser,
                    routerContract,
                    SwapRouter.routeSwap(
                        randomRoute.swapRoute,
                        randomRoute.initialToken,
                        randomRoute.finalToken,
                        randomRoute.initialAmount,
                        BigInteger.ONE),
                    tooLittleGas))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Cannot allocate gas for events.");

    // No locks exist at the swap contracts.
    for (BlockchainAddress swapOnRoute : randomRoute.swapRoute) {
      Assertions.assertThat(getSwapState(swapOnRoute).virtualState().locks()).isEmpty();
    }

    // Tokens haven't moved.
    Assertions.assertThat(getTokenBalance(randomRoute.initialToken, routeUser))
        .isEqualTo(randomRoute.initialAmount);

    // Executing the route with a little more gas fully succeeds.
    blockchain.sendAction(
        routeUser,
        routerContract,
        SwapRouter.routeSwap(
            randomRoute.swapRoute,
            randomRoute.initialToken,
            randomRoute.finalToken,
            randomRoute.initialAmount,
            BigInteger.ONE),
        tooLittleGas + 250);

    assertTokenBalanceMarginEntry(randomRoute.finalToken, routeUser, randomRoute.expectedOutput, 1);
  }

  /** If a user provides a route exceeding the maximum supported length, no locks are acquired. */
  @RepeatedTest(10)
  @Previous("contractInit")
  void tooLongRoute(RepetitionInfo repetitionInfo) {
    initializeTestMappings();
    BlockchainAddress routeUser = blockchain.newAccount(77);
    RandomRouteInfo randomRoute =
        generateRandomRoutes(repetitionInfo, 1, MAX_ROUTE_LENGTH + 1).get(0);

    blockchain.sendAction(
        contractOwnerAddress,
        randomRoute.initialToken,
        Token.transfer(routeUser, randomRoute.initialAmount));

    blockchain.sendAction(
        routeUser,
        randomRoute.initialToken,
        Token.approve(routerContract, randomRoute.initialAmount));

    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    routeUser,
                    routerContract,
                    SwapRouter.routeSwap(
                        randomRoute.swapRoute,
                        randomRoute.initialToken,
                        randomRoute.finalToken,
                        randomRoute.initialAmount,
                        BigInteger.ONE),
                    swapRouteGasAmount))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Swap route length (%s) is greater than maximum allowed (%s).",
            randomRoute.swapRoute.size(), randomRoute.swapRoute.size() - 1);

    // No locks exist at the swap contracts.
    for (BlockchainAddress swapOnRoute : randomRoute.swapRoute) {
      Assertions.assertThat(getSwapState(swapOnRoute).virtualState().locks()).isEmpty();
    }

    // Tokens haven't moved.
    Assertions.assertThat(getTokenBalance(randomRoute.initialToken, routeUser))
        .isEqualTo(randomRoute.initialAmount);
  }

  /**
   * A user can provide the router with a valid swap route of maximum allowed length, which results
   * in the router performing swaps along the route, and the user ending up with the desired output
   * tokens.
   */
  @RepeatedTest(10)
  @Previous("contractInit")
  void swapRouteMaxLength(RepetitionInfo repetitionInfo) {
    initializeTestMappings();
    final BlockchainAddress routeUser = blockchain.newAccount(88);
    RandomRouteInfo randomRoute = generateRandomRoutes(repetitionInfo, 1, MAX_ROUTE_LENGTH).get(0);

    blockchain.sendAction(
        contractOwnerAddress,
        randomRoute.initialToken,
        Token.transfer(routeUser, randomRoute.initialAmount));

    blockchain.sendAction(
        routeUser,
        randomRoute.initialToken,
        Token.approve(routerContract, randomRoute.initialAmount));

    final TxExecution s1 =
        blockchain.sendActionAsync(
            routeUser,
            routerContract,
            SwapRouter.routeSwap(
                randomRoute.swapRoute,
                randomRoute.initialToken,
                randomRoute.finalToken,
                randomRoute.initialAmount,
                BigInteger.ONE),
            110_000L);
    executeTxExecutionInUnpredictableOrder(repetitionInfo, List.of(s1));

    assertTokenBalanceMarginEntry(randomRoute.finalToken, routeUser, randomRoute.expectedOutput, 1);
  }

  private void approveExtraNonOwnersA() {
    for (BlockchainAddress nonOwner : extraNonOwnerAddresses) {
      blockchain.sendAction(
          nonOwner, contractTokenA, Token.approve(routerContract, NON_OWNER_TOKEN_AMOUNT_A));
    }
  }

  private void initializeNonOwners() {
    nonOwnerAddress1 = blockchain.newAccount(5);
    nonOwnerAddress2 = blockchain.newAccount(99);

    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenA,
        Token.transfer(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_A));
    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenA,
        Token.transfer(nonOwnerAddress2, NON_OWNER_TOKEN_AMOUNT_A));

    long privateKey = 100;
    extraNonOwnerAddresses = new ArrayList<>(numberOfExtraNonOwners);
    for (int i = 0; i < numberOfExtraNonOwners; i++) {

      BlockchainAddress nonOwner = blockchain.newAccount(privateKey);
      blockchain.sendAction(
          contractOwnerAddress, contractTokenA, Token.transfer(nonOwner, NON_OWNER_TOKEN_AMOUNT_A));

      extraNonOwnerAddresses.add(nonOwner);

      privateKey++;
    }
  }

  private void provideInitialLiquidity() {
    depositIntoSwap(
        swapLockContractAddressAandB, contractOwnerAddress, contractTokenA, A_LIQUIDITY_A_B);
    depositIntoSwap(
        swapLockContractAddressAandB, contractOwnerAddress, contractTokenB, B_LIQUIDITY_A_B);
    blockchain.sendAction(
        contractOwnerAddress,
        swapLockContractAddressAandB,
        LiquiditySwapLock.provideInitialLiquidity(A_LIQUIDITY_A_B, B_LIQUIDITY_A_B));

    depositIntoSwap(
        swapLockContractAddressAandC, contractOwnerAddress, contractTokenA, A_LIQUIDITY_A_C);
    depositIntoSwap(
        swapLockContractAddressAandC, contractOwnerAddress, contractTokenC, C_LIQUIDITY_A_C);
    blockchain.sendAction(
        contractOwnerAddress,
        swapLockContractAddressAandC,
        LiquiditySwapLock.provideInitialLiquidity(A_LIQUIDITY_A_C, C_LIQUIDITY_A_C));

    depositIntoSwap(
        swapLockContractAddressBandD, contractOwnerAddress, contractTokenB, B_LIQUIDITY_B_D);
    depositIntoSwap(
        swapLockContractAddressBandD, contractOwnerAddress, contractTokenD, D_LIQUIDITY_B_D);
    blockchain.sendAction(
        contractOwnerAddress,
        swapLockContractAddressBandD,
        LiquiditySwapLock.provideInitialLiquidity(B_LIQUIDITY_B_D, D_LIQUIDITY_B_D));

    depositIntoSwap(
        swapLockContractAddressCandD, contractOwnerAddress, contractTokenC, C_LIQUIDITY_C_D);
    depositIntoSwap(
        swapLockContractAddressCandD, contractOwnerAddress, contractTokenD, D_LIQUIDITY_C_D);
    blockchain.sendAction(
        contractOwnerAddress,
        swapLockContractAddressCandD,
        LiquiditySwapLock.provideInitialLiquidity(C_LIQUIDITY_C_D, D_LIQUIDITY_C_D));
  }

  private void deployTokenContracts() {
    byte[] initRpcA = Token.initialize("Token A", "A", (byte) 8, TOTAL_SUPPLY_A);
    contractTokenA = blockchain.deployContract(contractOwnerAddress, contractBytesToken, initRpcA);

    byte[] initRpcB = Token.initialize("Token B", "B", (byte) 8, TOTAL_SUPPLY_B);
    contractTokenB = blockchain.deployContract(contractOwnerAddress, contractBytesToken, initRpcB);

    byte[] initRpcC = Token.initialize("Token C", "C", (byte) 8, TOTAL_SUPPLY_C);
    contractTokenC = blockchain.deployContract(contractOwnerAddress, contractBytesToken, initRpcC);

    byte[] initRpcD = Token.initialize("Token D", "D", (byte) 8, TOTAL_SUPPLY_D);
    contractTokenD = blockchain.deployContract(contractOwnerAddress, contractBytesToken, initRpcD);

    byte[] initRpcE = Token.initialize("Token E", "E", (byte) 8, TOTAL_SUPPLY_E);
    contractTokenE = blockchain.deployContract(contractOwnerAddress, contractBytesToken, initRpcE);

    byte[] initRpcF = Token.initialize("Token F", "F", (byte) 8, TOTAL_SUPPLY_F);
    contractTokenF = blockchain.deployContract(contractOwnerAddress, contractBytesToken, initRpcF);
  }

  private List<SwapRouter.SwapContractInfo> deploySwapContracts() {
    byte[] initRpcSwapAandB =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenA, contractTokenB, FEE_AB);
    swapLockContractAddressAandB =
        blockchain.deployContract(contractOwnerAddress, contractBytesSwap, initRpcSwapAandB);

    byte[] initRpcSwapAandC =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenA, contractTokenC, FEE_AC);
    swapLockContractAddressAandC =
        blockchain.deployContract(contractOwnerAddress, contractBytesSwap, initRpcSwapAandC);

    byte[] initRpcSwapBandD =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenB, contractTokenD, FEE_BD);
    swapLockContractAddressBandD =
        blockchain.deployContract(contractOwnerAddress, contractBytesSwap, initRpcSwapBandD);

    byte[] initRpcSwapCandD =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenC, contractTokenD, FEE_CD);
    swapLockContractAddressCandD =
        blockchain.deployContract(contractOwnerAddress, contractBytesSwap, initRpcSwapCandD);

    byte[] initRpcSwapDandE =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenD, contractTokenE, FEE_CD);
    swapLockContractUnknown =
        blockchain.deployContract(contractOwnerAddress, contractBytesSwap, initRpcSwapDandE);

    byte[] initRpcSwapAandF =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenA, contractTokenF, FEE_AF);
    swapLockContractAddressAandF =
        blockchain.deployContract(contractOwnerAddress, contractBytesSwap, initRpcSwapAandF);

    byte[] initRpcSwapDandF =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenD, contractTokenF, FEE_DF);
    swapLockContractAddressDandF =
        blockchain.deployContract(contractOwnerAddress, contractBytesSwap, initRpcSwapDandF);

    // Don't include D -> E, as it's supposed to be unknown to the router.
    return List.of(
        new SwapRouter.SwapContractInfo(
            swapLockContractAddressAandB, contractTokenA, contractTokenB),
        new SwapRouter.SwapContractInfo(
            swapLockContractAddressAandC, contractTokenA, contractTokenC),
        new SwapRouter.SwapContractInfo(
            swapLockContractAddressBandD, contractTokenB, contractTokenD),
        new SwapRouter.SwapContractInfo(
            swapLockContractAddressCandD, contractTokenC, contractTokenD),
        new SwapRouter.SwapContractInfo(
            swapLockContractAddressAandF, contractTokenA, contractTokenF),
        new SwapRouter.SwapContractInfo(
            swapLockContractAddressDandF, contractTokenD, contractTokenF));
  }

  private void depositIntoSwap(
      BlockchainAddress swapContract,
      BlockchainAddress sender,
      BlockchainAddress contractToken,
      BigInteger amount) {
    blockchain.sendAction(sender, contractToken, Token.approve(swapContract, amount));
    blockchain.sendAction(sender, swapContract, LiquiditySwapLock.deposit(contractToken, amount));
  }

  private SwapRouter.RouterState getRouterState() {
    return SwapRouter.RouterState.deserialize(blockchain.getContractState(routerContract));
  }

  private LiquiditySwapLock.LiquiditySwapContractState getSwapState(
      BlockchainAddress swapContract) {
    return LiquiditySwapLock.LiquiditySwapContractState.deserialize(
        blockchain.getContractState(swapContract));
  }

  protected abstract BigInteger getTokenBalance(
      BlockchainAddress tokenContract, BlockchainAddress key);

  private BigInteger calculateReceivingAmount(
      BlockchainAddress swapContract, BlockchainAddress fromToken, BigInteger amount, short fee) {
    BigInteger a = calculateReceivingAmountNoLock(swapContract, fromToken, amount, fee);
    BigInteger b = calculateReceivingAmountLocked(swapContract, fromToken, amount, fee);
    return a.min(b);
  }

  private BigInteger calculateReceivingAmountNoLock(
      BlockchainAddress swapContract, BlockchainAddress fromToken, BigInteger amount, short fee) {
    BigInteger oldFromAmount = getPoolAmountForToken(swapContract, fromToken);
    BigInteger oldToAmount =
        getPoolAmountForToken(swapContract, getMatchingToken(swapContract, fromToken));

    return deltaCalculation(amount, oldFromAmount, oldToAmount, fee);
  }

  private BigInteger calculateReceivingAmountLocked(
      BlockchainAddress swapContract, BlockchainAddress fromToken, BigInteger amount, short fee) {
    BigInteger oldFromAmount = getVirtualPoolAmountForToken(swapContract, fromToken);
    BigInteger oldToAmount =
        getVirtualPoolAmountForToken(swapContract, getMatchingToken(swapContract, fromToken));

    return deltaCalculation(amount, oldFromAmount, oldToAmount, fee);
  }

  private BigInteger deltaCalculation(
      BigInteger deltaInAmount, BigInteger fromAmount, BigInteger toAmount, short fee) {
    BigInteger noFee = BigInteger.valueOf(1000);
    BigInteger oppositeFee = noFee.subtract(BigInteger.valueOf(fee));

    BigInteger numerator = oppositeFee.multiply(deltaInAmount).multiply(toAmount);
    BigInteger denominator = noFee.multiply(fromAmount).add(oppositeFee.multiply(deltaInAmount));

    return numerator.divide(denominator);
  }

  private BigInteger getVirtualPoolAmountForToken(
      BlockchainAddress swapContract, BlockchainAddress token) {
    LiquiditySwapLock.TokenBalance b = getVirtualContractBalance(swapContract);
    return deduceLeftOrRightToken(swapContract, token) ? b.aTokens() : b.bTokens();
  }

  private BigInteger getPoolAmountForToken(
      BlockchainAddress swapContract, BlockchainAddress token) {
    LiquiditySwapLock.TokenBalance b = getActualContractBalance(swapContract);
    return deduceLeftOrRightToken(swapContract, token) ? b.aTokens() : b.bTokens();
  }

  private LiquiditySwapLock.TokenBalance getActualContractBalance(BlockchainAddress swapContract) {
    return swapUtil.getActualContractBalance(getSwapState(swapContract), swapContract);
  }

  private LiquiditySwapLock.TokenBalance getVirtualContractBalance(BlockchainAddress swapContract) {
    return swapUtil.getVirtualContractBalance(getSwapState(swapContract), swapContract);
  }

  private boolean deduceLeftOrRightToken(BlockchainAddress swapContract, BlockchainAddress token) {
    if (swapContract.equals(swapLockContractAddressAandB)
        || swapContract.equals(swapLockContractAddressAandC)
        || swapContract.equals(swapLockContractAddressAandF)) {
      return token.equals(contractTokenA);
    } else if (swapContract.equals(swapLockContractAddressBandD)) {
      return token.equals(contractTokenB);
    } else if (swapContract.equals(swapLockContractAddressCandD)) {
      return token.equals(contractTokenC);
    } else {
      return token.equals(contractTokenD);
    }
  }

  private BlockchainAddress getMatchingToken(
      BlockchainAddress swapContract, BlockchainAddress token) {
    if (swapContract.equals(swapLockContractAddressAandB)) {
      return token.equals(contractTokenA) ? contractTokenB : contractTokenA;
    } else if (swapContract.equals(swapLockContractAddressAandC)) {
      return token.equals(contractTokenA) ? contractTokenC : contractTokenA;
    } else if (swapContract.equals(swapLockContractAddressAandF)) {
      return token.equals(contractTokenA) ? contractTokenF : contractTokenA;
    } else if (swapContract.equals(swapLockContractAddressBandD)) {
      return token.equals(contractTokenB) ? contractTokenD : contractTokenB;
    } else if (swapContract.equals(swapLockContractAddressCandD)) {
      return token.equals(contractTokenC) ? contractTokenD : contractTokenC;
    } else {
      return token.equals(contractTokenD) ? contractTokenF : contractTokenD;
    }
  }

  /**
   * Executes the subtree TxExecution by the given TxExecution event in an arbitrary and
   * unpredictable order. This helps in finding invalid assumptions in the contract code about the
   * event execution order.
   *
   * @param repetitionInfo Contains seed information for the execution order.
   * @param initialTxExecution The initial TxExecution events.
   */
  private void executeTxExecutionInUnpredictableOrder(
      RepetitionInfo repetitionInfo, List<TxExecution> initialTxExecution) {
    ExecutionUtil.executeTxExecutionInUnpredictableOrder(this, repetitionInfo, initialTxExecution)
        .assertNoFailures();
  }

  private void assertTokenBalanceMarginEntry(
      BlockchainAddress tokenContract,
      BlockchainAddress owner,
      BigInteger amount,
      double percentage) {
    final BigInteger balance = getTokenBalance(tokenContract, owner);
    Assertions.assertThat(balance).isCloseTo(amount, Percentage.withPercentage(percentage));
  }

  private void initializeTestMappings() {
    tokensToSwapForRandom =
        Map.of(
            contractTokenA, List.of(swapLockContractAddressAandB, swapLockContractAddressAandC),
            contractTokenB, List.of(swapLockContractAddressAandB, swapLockContractAddressBandD),
            contractTokenC, List.of(swapLockContractAddressAandC, swapLockContractAddressCandD),
            contractTokenD, List.of(swapLockContractAddressBandD, swapLockContractAddressCandD));

    swapToFee =
        Map.of(
            swapLockContractAddressAandB, FEE_AB,
            swapLockContractAddressAandC, FEE_AC,
            swapLockContractAddressBandD, FEE_BD,
            swapLockContractAddressCandD, FEE_CD,
            swapLockContractAddressAandF, FEE_AF,
            swapLockContractAddressDandF, FEE_AF);

    listOfTokensForRandom = List.of(contractTokenA, contractTokenB, contractTokenC, contractTokenD);
  }

  private record RandomRouteInfo(
      List<BlockchainAddress> swapRoute,
      BlockchainAddress initialToken,
      BlockchainAddress finalToken,
      BigInteger initialAmount,
      BigInteger expectedOutput) {}

  private List<RandomRouteInfo> generateRandomRoutes(
      final RepetitionInfo repetitionInfo, int numberOfRoutes, int numberOfSwaps) {
    final Random random = new Random(repetitionInfo.getCurrentRepetition());
    List<RandomRouteInfo> randomRoutes = new ArrayList<>(numberOfRoutes);

    for (int i = 0; i < numberOfRoutes; i++) {
      int randIndex = random.nextInt(listOfTokensForRandom.size());
      BlockchainAddress initialToken = listOfTokensForRandom.get(randIndex);
      BlockchainAddress currentToken = initialToken;

      BigInteger initialAmount = BigInteger.valueOf(random.nextLong(512, 1024));
      BigInteger expectedOut = initialAmount;

      List<BlockchainAddress> swapRoute = new ArrayList<>();

      for (int j = 0; j < numberOfSwaps; j++) {
        List<BlockchainAddress> possibleSwaps = tokensToSwapForRandom.get(currentToken);

        int randSwapIndex = random.nextInt(possibleSwaps.size());
        BlockchainAddress randSwap = possibleSwaps.get(randSwapIndex);
        swapRoute.add(randSwap);

        expectedOut =
            calculateReceivingAmount(randSwap, currentToken, expectedOut, swapToFee.get(randSwap));
        currentToken = getMatchingToken(randSwap, currentToken);
      }

      randomRoutes.add(
          new RandomRouteInfo(swapRoute, initialToken, currentToken, initialAmount, expectedOut));
    }

    return randomRoutes;
  }
}
