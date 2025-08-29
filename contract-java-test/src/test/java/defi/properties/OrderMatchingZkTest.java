package defi.properties;

import static org.assertj.core.api.Assertions.assertThat;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abiclient.rpc.RpcContractBuilder;
import com.partisiablockchain.language.abiclient.zk.ZkInputBuilder;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.abicodegen.ZkOrderMatching;
import com.partisiablockchain.language.abimodel.model.FileAbi;
import com.partisiablockchain.language.abimodel.parser.AbiParser;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * {@link ZkOrderMatching} testing.
 *
 * <p>Properties tested:
 *
 * <ul>
 *   <li>Users can queue their order into the system.
 *   <li>Users with matching orders and a large enough deposit will have their order matched, and
 *       their tokens swapped.
 *   <li>Orders matched when both parties gain the expected number of tokens or more.
 *   <li>Orders does not match when one or both users would gain less than expected.
 *   <li>Orders where users does not possess the promised amount of tokens are ignored.
 * </ul>
 */
public abstract class OrderMatchingZkTest extends JunitContractTest {

  private static final BigInteger TOTAL_SUPPLY_A = BigInteger.ONE.shiftLeft(60);
  private static final BigInteger TOTAL_SUPPLY_B = BigInteger.ONE.shiftLeft(62);
  private static final BigInteger AMOUNT_FOR_INITIAL_LIQUIDITY = BigInteger.ONE.shiftLeft(50);
  private static final BigInteger AMOUNT_FOR_INITIAL_DEPOSIT = BigInteger.valueOf(1000);

  public BlockchainAddress creatorAddress;
  public BlockchainAddress brokeUser;
  public List<BlockchainAddress> users;
  public BlockchainAddress contractOrderMatch;
  private ZkOrderMatching orderMatchContract;

  public BlockchainAddress contractTokenA;
  public BlockchainAddress contractTokenB;

  private final ContractBytes contractBytesToken;
  private final ContractBytes contractBytesOrderMatch;
  private final FileAbi contractAbiOrderMatch;

  /**
   * Constructor for {@link OrderMatchingZkTest}.
   *
   * @param contractBytesToken Contract definition for token. Not nullable.
   * @param contractBytesOrderMatch Contract definition for order matching contract. Not nullable.
   */
  protected OrderMatchingZkTest(
      final ContractBytes contractBytesToken, final ContractBytes contractBytesOrderMatch) {
    this.contractBytesToken = Objects.requireNonNull(contractBytesToken);
    this.contractBytesOrderMatch = Objects.requireNonNull(contractBytesOrderMatch);
    this.contractAbiOrderMatch = new AbiParser(contractBytesOrderMatch.abi()).parseAbi();
  }

  /** Contract can be initialized. */
  @ContractTest
  void contractInit() {
    creatorAddress = blockchain.newAccount(1);
    brokeUser = blockchain.newAccount(999);
    users = IntStream.range(0, 10).map(x -> x + 1000).mapToObj(blockchain::newAccount).toList();

    // Setup tokens
    final byte[] initRpcA = Token.initialize("Token A", "A", (byte) 8, TOTAL_SUPPLY_A);
    contractTokenA = blockchain.deployContract(creatorAddress, contractBytesToken, initRpcA);

    final byte[] initRpcB = Token.initialize("Token B", "B", (byte) 8, TOTAL_SUPPLY_B);
    contractTokenB = blockchain.deployContract(creatorAddress, contractBytesToken, initRpcB);

    // Setup swap
    final byte[] initRpc = ZkOrderMatching.initialize(contractTokenA, contractTokenB);
    contractOrderMatch =
        blockchain.deployZkContract(creatorAddress, contractBytesOrderMatch, initRpc);
    orderMatchContract = new ZkOrderMatching(getStateClient(), contractOrderMatch);
  }

  /** Creator can deposit some tokens. */
  @ContractTest(previous = "contractInit")
  void depositInitialAmounts() {
    // Deposit setup
    depositAmount(List.of(creatorAddress), contractTokenA, AMOUNT_FOR_INITIAL_LIQUIDITY);
    depositAmount(List.of(creatorAddress), contractTokenB, AMOUNT_FOR_INITIAL_LIQUIDITY);

    //
    assertDepositBalances(
        creatorAddress, AMOUNT_FOR_INITIAL_LIQUIDITY, AMOUNT_FOR_INITIAL_LIQUIDITY);
    assertDepositBalances(contractOrderMatch, 0, 0);
  }

  /** Users can deposit tokens into order matching. */
  @ContractTest(previous = "depositInitialAmounts")
  void initUsers() {
    depositAmount(users, contractTokenA, AMOUNT_FOR_INITIAL_DEPOSIT);
    depositAmount(users, contractTokenB, AMOUNT_FOR_INITIAL_DEPOSIT);

    // Check state
    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    for (final BlockchainAddress user : users) {
      assertDepositBalances(user, AMOUNT_FOR_INITIAL_DEPOSIT, AMOUNT_FOR_INITIAL_DEPOSIT);
    }
    assertThat(state.ordersStored()).isEqualTo(0);
  }

  /**
   * User can place the first order, which simply queues the order, and does not trigger any
   * computations.
   */
  @ContractTest(previous = "initUsers")
  void firstOrder() {
    placeOrder(users.get(1), contractTokenA, 20, 10);

    //
    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(state.ordersStored()).isEqualTo(1);
  }

  /** User can place another order which matches with the first, resulting in swapped tokens. */
  @ContractTest(previous = "firstOrder")
  void matchingOrder() {
    placeOrder(users.get(2), contractTokenB, 10, 20);

    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();

    assertThat(state.ordersStored()).isEqualTo(0);
    assertDepositBalances(users.get(1), 1020, 990);
    assertDepositBalances(users.get(2), 980, 1010);
  }

  /**
   * Orders will match when one of the orders get precisely as expected, and the other gets a better
   * deal.
   */
  @ContractTest(previous = "initUsers")
  void matchingOrderOff1() {
    placeOrder(users.get(1), contractTokenA, 10, 22);
    placeOrder(users.get(2), contractTokenB, 20, 10);

    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(state.ordersStored()).isEqualTo(0);

    assertThat(state.ordersStored()).isEqualTo(0);
    assertDepositBalances(users.get(1), 1010, 978);
    assertDepositBalances(users.get(2), 990, 1022);
  }

  /** Orders will match when both orders gets a better deal than expected. */
  @ContractTest(previous = "initUsers")
  void matchingOrderOff2() {
    placeOrder(users.get(1), contractTokenA, 10, 23);
    placeOrder(users.get(2), contractTokenB, 20, 11);

    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(state.ordersStored()).isEqualTo(0);

    assertThat(state.ordersStored()).isEqualTo(0);
    assertDepositBalances(users.get(1), 1011, 977);
    assertDepositBalances(users.get(2), 989, 1023);
  }

  /** Orders will not match when these doesn't satisfy each other. */
  @ContractTest(previous = "initUsers")
  void nonMatching1() {
    placeOrder(users.get(1), contractTokenA, 10, 20 - 1);
    placeOrder(users.get(2), contractTokenB, 20, 10 - 1);

    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();

    assertThat(state.ordersStored()).isEqualTo(2);
    assertDepositBalances(users.get(1), 1000, 1000);
    assertDepositBalances(users.get(2), 1000, 1000);
  }

  /** Orders will not match when one of them doesn't satisfy the other. */
  @ContractTest(previous = "initUsers")
  void nonMatching2() {
    placeOrder(users.get(1), contractTokenA, 10, 20);
    placeOrder(users.get(2), contractTokenB, 20, 10 - 1);

    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(state.ordersStored()).isEqualTo(2);
    assertDepositBalances(users.get(1), 1000, 1000);
    assertDepositBalances(users.get(2), 1000, 1000);
  }

  /** Orders will not match when one of them doesn't satisfy the other. */
  @ContractTest(previous = "initUsers")
  void nonMatching3() {
    placeOrder(users.get(1), contractTokenA, 10, 20 - 1);
    placeOrder(users.get(2), contractTokenB, 20, 10);

    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(state.ordersStored()).isEqualTo(2);
    assertDepositBalances(users.get(1), 1000, 1000);
    assertDepositBalances(users.get(2), 1000, 1000);
  }

  /**
   * Orders should not match when one of the users doesn't have enough liquidity to cover the order.
   */
  @ContractTest(previous = "initUsers")
  void depositIsTooLow() {
    placeOrder(users.get(1), contractTokenA, 20, 1010);
    placeOrder(users.get(2), contractTokenB, 1000, 20);

    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(state.ordersStored()).isEqualTo(2);
    assertDepositBalances(users.get(1), 1000, 1000);
    assertDepositBalances(users.get(2), 1000, 1000);
  }

  /** Updated orders can be resolved. */
  @ContractTest(previous = "depositIsTooLow")
  void canRecoverFromLowDeposit() {
    placeOrder(users.get(1), contractTokenA, 20, 1000);

    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(state.ordersStored()).isEqualTo(1);
    assertDepositBalances(users.get(1), 1020, 0);
    assertDepositBalances(users.get(2), 980, 2000);
  }

  /** Several buy orders can be placed at once. */
  @ContractTest(previous = "initUsers")
  void buildQueueForBuying() {
    zkNodes.stop();

    for (final BlockchainAddress user : users) {
      placeOrder(user, contractTokenA, 20, 10);
    }

    zkNodes.finishTasks();

    //
    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(state.ordersStored()).isEqualTo(users.size());
  }

  /** Each order in the queue can be resolved one-by-one. */
  @ContractTest(previous = "buildQueueForBuying")
  void sellSlowly() {
    for (final BlockchainAddress user : users) {
      placeOrder(user, contractTokenB, 10, 20);
    }

    //
    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(state.ordersStored()).isEqualTo(0);
  }

  /** Several sell orders can be placed at once. */
  @ContractTest(previous = "buildQueueForBuying")
  void buildQueueForSelling() {
    zkNodes.stop();

    for (final BlockchainAddress user : users) {
      placeOrder(user, contractTokenB, 10, 20);
    }

    zkNodes.finishTasks();

    //
    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(state.ordersStored()).isEqualTo(0);
  }

  /** Sell and buy orders placed at once will automatically execute. */
  @ContractTest(previous = "initUsers")
  void buildTheBigQueue2() {
    zkNodes.stop();

    for (final BlockchainAddress user : users) {
      placeOrder(user, contractTokenA, 20, 10);
      placeOrder(user, contractTokenB, 10, 20);
    }

    zkNodes.finishTasks();

    //
    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(state.ordersStored()).isEqualTo(0);
  }

  private void placeOrder(
      BlockchainAddress orderPlacer,
      BlockchainAddress boughtToken,
      long inputAmount,
      long outputAmount) {
    placeOrder(
        orderPlacer,
        boughtToken,
        BigInteger.valueOf(inputAmount),
        BigInteger.valueOf(outputAmount));
  }

  private void placeOrder(
      BlockchainAddress orderPlacer,
      BlockchainAddress boughtToken,
      BigInteger inputAmount,
      BigInteger outputAmount) {
    final RpcContractBuilder builder =
        new RpcContractBuilder(contractAbiOrderMatch.contract(), "place_order");
    final ZkInputBuilder zkBuilder =
        ZkInputBuilder.createZkInputBuilder("place_order", contractAbiOrderMatch.contract());

    if (boughtToken.equals(contractTokenA)) {
      System.out.println("Placing order for A");
      zkBuilder.addStruct().addI128(inputAmount).addI128(outputAmount).addBool(true);
    } else {
      System.out.println("Placing order for B");
      zkBuilder.addStruct().addI128(outputAmount).addI128(inputAmount).addBool(false);
    }

    blockchain.sendSecretInput(
        contractOrderMatch, orderPlacer, zkBuilder.getBits(), builder.getBytes());
    System.out.println("    Order complete");
  }

  /** State accessor for token balances. */
  static ZkOrderMatching.TokenBalance getDepositBalances(
      ZkOrderMatching.ContractState state, BlockchainAddress owner) {
    return state.tokenBalances().balances().get(owner);
  }

  /** State accessor for deposits on {@link ZkOrderMatching} contracts. */
  private static BigInteger depositBalance(
      ZkOrderMatching.ContractState state,
      ZkOrderMatching.TokenBalance tokenBalance,
      BlockchainAddress tokenAddress) {
    if (tokenBalance == null) {
      return BigInteger.ZERO;
    }
    if (tokenAddress.equals(state.tokenBalances().tokenAAddress())) {
      return tokenBalance.aTokens();
    }
    if (tokenAddress.equals(state.tokenBalances().tokenBAddress())) {
      return tokenBalance.bTokens();
    }
    return null;
  }

  /**
   * State accessor for deposits on {@link ZkOrderMatching} contracts.
   *
   * <p>Token addresses are determined from the contract state. Liquidity tokens can be queried with
   * the {@link ZkOrderMatching} contract's address.
   *
   * @param state {@link ZkOrderMatching} state.
   * @param owner Owner of the deposit
   * @param tokenAddress Addess of the token to determine balance for.
   * @return Token balance.
   */
  public static BigInteger depositBalance(
      final ZkOrderMatching.ContractState state,
      final BlockchainAddress owner,
      final BlockchainAddress tokenAddress) {
    return depositBalance(state, state.tokenBalances().balances().get(owner), tokenAddress);
  }

  /** State accessor for token balances. */
  BigInteger depositBalance(BlockchainAddress owner, BlockchainAddress tokenAddr) {
    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    return depositBalance(state, owner, tokenAddr);
  }

  /** State validation. */
  void validateStateInvariants(final ZkOrderMatching.ContractState state) {
    // Check that no accounts are empty
    for (final var entry : state.tokenBalances().balances().getNextN(null, 1000)) {
      ZkOrderMatching.TokenBalance tokenBalance = entry.getValue();
      final List<BigInteger> hasAnyTokens = List.of(tokenBalance.aTokens(), tokenBalance.bTokens());
      assertThat(hasAnyTokens)
          .as("TokenBalance must contain at least one non-zero field")
          .anyMatch(n -> !BigInteger.ZERO.equals(n));
    }

    // Check that initialized pools are consistent.
    final ZkOrderMatching.TokenBalance tokenBalance =
        getDepositBalances(state, state.depositAddress());
    final List<BigInteger> hasAnyTokens = List.of(tokenBalance.aTokens(), tokenBalance.bTokens());
    final boolean expectedZeroes = hasAnyTokens.contains(BigInteger.ZERO);
    if (expectedZeroes) {
      assertThat(hasAnyTokens).containsExactly(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
    } else {
      assertThat(hasAnyTokens).doesNotContain(BigInteger.ZERO);
    }
  }

  /** State validation. */
  void validateStateInvariants() {
    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    validateStateInvariants(state);
  }

  private void assertDepositBalances(BlockchainAddress user, long amountTokenA, long amountTokenB) {
    assertDepositBalances(user, BigInteger.valueOf(amountTokenA), BigInteger.valueOf(amountTokenB));
  }

  private void assertDepositBalances(
      BlockchainAddress user, BigInteger amountTokenA, BigInteger amountTokenB) {
    final ZkOrderMatching.ContractState state = orderMatchContract.getState().openState();
    assertThat(depositBalance(state, user, contractTokenA))
        .as("Token A Balance")
        .isEqualTo(amountTokenA);
    assertThat(depositBalance(state, user, contractTokenB))
        .as("Token A Balance")
        .isEqualTo(amountTokenB);
  }

  /**
   * State modifier for depositing, with automatic transfer from the token {@link creatorAddress}.
   */
  void depositAmount(
      List<BlockchainAddress> senders, BlockchainAddress contractToken, BigInteger amount) {
    final var transfers = senders.stream().map(s -> new Token.Transfer(s, amount)).toList();
    blockchain.sendAction(
        creatorAddress, contractToken, Token.bulkTransfer(transfers), 1_000_000_000L);

    for (final BlockchainAddress sender : senders) {
      blockchain.sendAction(sender, contractToken, Token.approve(contractOrderMatch, amount));
      blockchain.sendAction(
          sender, contractOrderMatch, ZkOrderMatching.deposit(contractToken, amount));
    }
  }
}
