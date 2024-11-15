package defi.properties;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwap;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.Previous;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import defi.util.Arbitrary;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

/**
 * Testing a common interface for depositing and withdrawing of funds to a contract.
 *
 * <p>The interface used expects deposits and withdraws to explicit specify which token to operate
 * on.
 *
 * <p>Tests following properties:
 *
 * <ul>
 *   <li>Users can deposit funds to a contract when the contract has been approved as spender, and
 *       the user calls the deposit action.
 *   <li>Deposited funds are not available for transfering.
 *   <li>Deposits are accumulative.
 *   <li>Attempting to transfer after depositing all their funds will result in an error.
 *   <li>Attempting to deposit more funds than have been approved will result in an error.
 *   <li>Attempting to more funds than the user owns will result in an error.
 *   <li>Users can withdraw funds from the contract when they own some deposits.
 *   <li>Withdrawing funds that a user does not possess will result in an error.
 *   <li>Funds can be partially withdraw.
 *   <li>Deposited funds are tracked separately for each user. The actions of one user cannot
 *       influence another user's state.
 *   <li>Funds cannot be deposited from or withdraw to an unrelated contract.
 * </ul>
 *
 * <p>This test does not cover locked funds, etc. as these vary wildly from contract to contract.
 */
public abstract class DepositWithdrawTest extends JunitContractTest {

  private static final BigInteger TOTAL_SUPPLY = BigInteger.valueOf(99_000L);
  private static final BigInteger MAX_ALLOWED_VALUE =
      BigInteger.valueOf(1).shiftLeft(127).subtract(BigInteger.ONE);

  public BlockchainAddress creatorAddress;
  public BlockchainAddress contractTokenA;
  public BlockchainAddress contractTokenB;
  public BlockchainAddress contractUnderTestAddress;
  public List<BlockchainAddress> users;

  private final ContractBytes contractBytesToken;
  private final ContractBytes contractBytesDeposit;

  /**
   * Initialize the test class.
   *
   * @param contractBytesToken Contract bytes to initialize the {@link Token} contract.
   * @param contractBytesDeposit Contract bytes to initialize the {@link LiquiditySwap} contract.
   */
  public DepositWithdrawTest(
      final ContractBytes contractBytesToken, final ContractBytes contractBytesDeposit) {
    this.contractBytesToken = contractBytesToken;
    this.contractBytesDeposit = contractBytesDeposit;
  }

  /**
   * Creates initialization RPC for the kind of deposit contract that this subclass of {@link
   * DepositWithdrawTest} is testing.
   *
   * @param token1 The first token contract. Never null.
   * @param token2 The second token contract. Never null. Not needed if the contract only supports
   *     deposits from one contract.
   * @return RPC for initializing the contract. Must not be null.
   */
  protected abstract byte[] initContractUnderTestRpc(
      BlockchainAddress token1, BlockchainAddress token2);

  /**
   * Initializes the accounts of the users.
   *
   * <p>Some contracts have a complicated setup for initializing accounts, and use this callback to
   * create those accounts.
   *
   * <p>Defaults to doing nothing.
   */
  protected void callbackForAccountCreation() {}

  /** Intermediate test step for initializing all the required contracts under test. */
  @Test
  void initializeContracts() {
    creatorAddress = blockchain.newAccount(1);
    users = List.of(creatorAddress, blockchain.newAccount(2));

    // Setup tokens
    final byte[] initTokenA = Token.initialize("Token A", "AAA", (byte) 0, TOTAL_SUPPLY);
    contractTokenA = blockchain.deployContract(creatorAddress, contractBytesToken, initTokenA);

    final byte[] initTokenB = Token.initialize("Token B", "BBB", (byte) 0, BigInteger.ZERO);
    contractTokenB = blockchain.deployContract(creatorAddress, contractBytesToken, initTokenB);

    // Setup swap
    final byte[] initRpcDeposit = initContractUnderTestRpc(contractTokenA, contractTokenB);
    if (contractBytesDeposit.codeFormat() == ContractBytes.CodeFormat.ZKWA) {
      contractUnderTestAddress =
          blockchain.deployZkContract(creatorAddress, contractBytesDeposit, initRpcDeposit);
    } else {
      contractUnderTestAddress =
          blockchain.deployContract(creatorAddress, contractBytesDeposit, initRpcDeposit);
    }
  }

  @Test
  @Previous("initializeContracts")
  void userStartWithZeroAfterInitialize() {
    assertDepositAmount(users.get(0), BigInteger.ZERO);
  }

  /** Intermediate test step to create all the required accounts for testing the contract. */
  @Test
  @Previous("initializeContracts")
  void accountCreation() {
    callbackForAccountCreation();
  }

  @Test
  @Previous("accountCreation")
  void userStartWithZeroAfterAccountCreation() {
    assertDepositAmount(users.get(0), BigInteger.ZERO);
  }

  /**
   * Users can deposit funds to a contract when the contract has been approved as spender, and the
   * user calls the deposit action.
   */
  @Test
  @Previous("accountCreation")
  void depositInitial() {
    final BigInteger amount = BigInteger.valueOf(1000);

    // Approve contract
    approveDeposit(users.get(0), amount);

    // Send deposit
    deposit(users.get(0), amount);

    assertTokenAaaBalance(users.get(0), 98_000L);
    assertTokenAaaBalance(contractUnderTestAddress, 1_000L);

    assertDepositAmount(users.get(0), 1_000L);
  }

  /** Attempting to deposit more funds than have been approved will result in an error. */
  @Test
  @Previous("accountCreation")
  void cannotDepositMoreThanTheApprovalAmount() {
    final BigInteger amountApprove = BigInteger.valueOf(1000);
    final BigInteger amountDeposit = BigInteger.valueOf(1001);

    // Approve contract
    approveDeposit(users.get(0), amountApprove);

    // Send deposit
    Assertions.assertThatCode(() -> deposit(users.get(0), amountDeposit))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient AAA allowance for transfer_from! Allowed 1000, but trying to transfer"
                + " 1001");

    assertDepositAmount(users.get(0), 0L);

    assertTokenAaaBalance(users.get(0), TOTAL_SUPPLY);
    assertTokenAaaBalance(contractUnderTestAddress, 0L);
  }

  /** Users can withdraw funds from the contract when they own some deposits. */
  @RepeatedTest(100)
  @Previous("depositInitial")
  void canWithdrawDepositedAmount(RepetitionInfo repetitionInfo) {
    final BigInteger amount =
        Arbitrary.bigInteger(repetitionInfo, BigInteger.valueOf(0), BigInteger.valueOf(1000));

    // Withdraw
    withdraw(users.get(0), amount);

    assertDepositAmount(users.get(0), BigInteger.valueOf(1000L).subtract(amount));

    assertTokenAaaBalance(
        users.get(0), TOTAL_SUPPLY.subtract(BigInteger.valueOf(1000L)).add(amount));
    assertTokenAaaBalance(contractUnderTestAddress, BigInteger.valueOf(1000L).subtract(amount));
  }

  /** Withdrawing funds that a user does not possess will result in an error. */
  @RepeatedTest(100)
  @Previous("depositInitial")
  void cannotWithdrawMoreThanTheDepositedAmount(RepetitionInfo repetitionInfo) {
    final BigInteger amount =
        Arbitrary.bigInteger(repetitionInfo, BigInteger.valueOf(1001L), MAX_ALLOWED_VALUE);

    // Withdraw
    Assertions.assertThatCode(() -> withdraw(users.get(0), amount))
        .as("amount = " + amount)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Insufficient")
        .hasMessageContaining("" + amount);

    assertDepositAmount(users.get(0), 1_000L);

    assertTokenAaaBalance(users.get(0), 98_000L);
    assertTokenAaaBalance(contractUnderTestAddress, 1_000L);
  }

  /** Deposits are accumulative. */
  @RepeatedTest(100)
  @Previous("depositInitial")
  void depositExtra(RepetitionInfo repetitionInfo) {
    final BigInteger amount =
        Arbitrary.bigInteger(repetitionInfo, BigInteger.valueOf(1000), BigInteger.valueOf(98000L));

    // Approve contract
    approveDeposit(users.get(0), amount);

    // Send deposit
    deposit(users.get(0), amount);

    assertDepositAmount(users.get(0), BigInteger.valueOf(1000).add(amount));

    assertTokenAaaBalance(users.get(0), BigInteger.valueOf(98_000).subtract(amount));
    assertTokenAaaBalance(contractUnderTestAddress, BigInteger.valueOf(1000).add(amount));
  }

  /** Attempting to transfer after depositing all their funds will result in an error. */
  @RepeatedTest(100)
  @Previous("depositInitial")
  void cannotDepositMoreThanUserOwns(RepetitionInfo repetitionInfo) {
    final BigInteger amount =
        Arbitrary.bigInteger(repetitionInfo, BigInteger.valueOf(98001L), MAX_ALLOWED_VALUE);

    // Approve contract
    approveDeposit(users.get(0), amount);

    assertDepositAmount(users.get(0), 1_000L);

    assertTokenAaaBalance(users.get(0), 98_000L);
    assertTokenAaaBalance(contractUnderTestAddress, 1_000L);

    // Send deposit
    Assertions.assertThatCode(() -> deposit(users.get(0), amount))
        .as("amount = " + amount)
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient AAA tokens for transfer! Have 98000, but trying to transfer " + amount);

    assertDepositAmount(users.get(0), 1_000L);

    assertTokenAaaBalance(users.get(0), 98_000L);
    assertTokenAaaBalance(contractUnderTestAddress, 1_000L);
  }

  /**
   * Deposited funds are tracked separately for each user. The actions of one user cannot *
   * influence another user's deposit.
   */
  @Test
  @Previous("accountCreation")
  void userDepositsAreIndependent() {

    // Transfer to user 1
    blockchain.sendAction(
        users.get(0), contractTokenA, Token.transfer(users.get(1), BigInteger.valueOf(1_000L)));

    assertDepositAmount(users.get(0), 0L);
    assertDepositAmount(users.get(1), 0L);
    assertTokenAaaBalance(users.get(1), 1000L);
    assertTokenAaaBalance(users.get(0), 98000L);
    assertTokenAaaBalance(contractUnderTestAddress, 0L);

    // Deposit 1
    approveDeposit(users.get(1), BigInteger.valueOf(500));
    deposit(users.get(1), BigInteger.valueOf(500));

    assertDepositAmount(users.get(0), 0L);
    assertDepositAmount(users.get(1), 500L);
    assertTokenAaaBalance(users.get(1), 500L);
    assertTokenAaaBalance(users.get(0), 98000L);
    assertTokenAaaBalance(contractUnderTestAddress, 500L);

    // Deposit 2
    approveDeposit(users.get(0), BigInteger.valueOf(5000));
    deposit(users.get(0), BigInteger.valueOf(5000));

    assertDepositAmount(users.get(0), 5000L);
    assertDepositAmount(users.get(1), 500L);
    assertTokenAaaBalance(users.get(1), 500L);
    assertTokenAaaBalance(users.get(0), 93000L);
    assertTokenAaaBalance(contractUnderTestAddress, 5500L);

    // Withdraw 1
    withdraw(users.get(1), BigInteger.valueOf(500));

    assertDepositAmount(users.get(0), 5000L);
    assertDepositAmount(users.get(1), 0L);
    assertTokenAaaBalance(users.get(1), 1000L);
    assertTokenAaaBalance(users.get(0), 93000L);
    assertTokenAaaBalance(contractUnderTestAddress, 5000L);

    // Withdraw 2
    withdraw(users.get(0), BigInteger.valueOf(5000));

    assertDepositAmount(users.get(0), 0L);
    assertDepositAmount(users.get(1), 0L);
    assertTokenAaaBalance(users.get(1), 1000L);
    assertTokenAaaBalance(users.get(0), 98000L);
    assertTokenAaaBalance(contractUnderTestAddress, 0L);
  }

  /**
   * It must be possible to execute a long chain of deposits and withdraws without the contract
   * crashing.
   */
  @Test
  @Previous("accountCreation")
  void enduranceTestingDepositWithdrawNoFailing() {
    final List<byte[]> depositHistory = generateDepositWithdrawHistory(contractTokenA, 100);

    // Approve everything
    approveDeposit(users.get(0), BigInteger.valueOf(Long.MAX_VALUE));

    for (final byte[] movementRpc : depositHistory) {
      sendActionToCut(users.get(0), movementRpc);
    }
  }

  /** Users cannot deposit from a completely unrelated token contract. */
  @Test
  @Previous("depositInitial")
  void cannotDepositUnrelatedToken() {
    final BlockchainAddress unrelatedContractAddress = users.get(0);
    Assertions.assertThatCode(
            () ->
                sendActionToCut(
                    users.get(0),
                    LiquiditySwap.deposit(unrelatedContractAddress, BigInteger.valueOf(42))))
        .hasMessageContaining(
            "Unknown token "
                + unrelatedContractAddress.writeAsString().toUpperCase(Locale.ROOT)
                + ". Contract only supports ")
        .hasMessageContaining(contractTokenA.writeAsString().toUpperCase(Locale.ROOT));
  }

  /** Users cannot withdraw to a completely unrelated token contract. */
  @Test
  @Previous("depositInitial")
  void cannotWithdrawUnrelatedToken() {
    final BlockchainAddress unrelatedContractAddress = users.get(0);
    Assertions.assertThatCode(
            () ->
                sendActionToCut(
                    users.get(0),
                    LiquiditySwap.withdraw(unrelatedContractAddress, BigInteger.valueOf(42), true)))
        .hasMessageContaining(
            "Unknown token "
                + unrelatedContractAddress.writeAsString().toUpperCase(Locale.ROOT)
                + ". Contract only supports ")
        .hasMessageContaining(contractTokenA.writeAsString().toUpperCase(Locale.ROOT));
  }

  /**
   * Generates a trace of deposit/withdraw transactions with the given length for the given
   * contract.
   *
   * @param contractToken Token address to deposit/withdraw to/from.
   * @param historyLength Length of the generated history trace.
   * @return list of generated RPC.
   */
  private static List<byte[]> generateDepositWithdrawHistory(
      BlockchainAddress contractToken, int historyLength) {
    final Random rand = new Random(0x12312321L);
    final BigInteger ownedInTotal = TOTAL_SUPPLY;
    BigInteger currentDeposit = BigInteger.ZERO;
    final List<byte[]> history = new ArrayList<>();
    for (int idx = 0; idx < historyLength; idx++) {
      final BigInteger nextDeposit = Arbitrary.nextRandomBigInteger(rand, ownedInTotal);
      final BigInteger movement = nextDeposit.subtract(currentDeposit);

      final byte[] rpc;
      if (movement.compareTo(BigInteger.ZERO) < 0) {
        rpc = LiquiditySwap.withdraw(contractToken, movement.negate(), true);
      } else {
        rpc = LiquiditySwap.deposit(contractToken, movement);
      }
      history.add(rpc);

      currentDeposit = nextDeposit;
    }
    return List.copyOf(history);
  }

  private void approveDeposit(BlockchainAddress owner, BigInteger amount) {
    blockchain.sendAction(owner, contractTokenA, Token.approve(contractUnderTestAddress, amount));
  }

  private void deposit(BlockchainAddress owner, BigInteger amount) {
    sendActionToCut(owner, LiquiditySwap.deposit(contractTokenA, amount));
  }

  private void withdraw(BlockchainAddress owner, BigInteger amount) {
    sendActionToCut(owner, LiquiditySwap.withdraw(contractTokenA, amount, false));
  }

  private void sendActionToCut(final BlockchainAddress sender, byte[] rpc) {
    if (contractBytesDeposit.codeFormat() == ContractBytes.CodeFormat.ZKWA) {
      final byte[] newRpc = new byte[rpc.length + 1];
      System.arraycopy(rpc, 0, newRpc, 1, rpc.length);
      newRpc[0] = 0x09;
      rpc = newRpc;
    }
    blockchain.sendAction(sender, contractUnderTestAddress, rpc);
  }

  private void assertTokenAaaBalance(BlockchainAddress owner, long amount) {
    assertTokenAaaBalance(owner, BigInteger.valueOf(amount));
  }

  private void assertTokenAaaBalance(BlockchainAddress owner, BigInteger amount) {
    final Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(contractTokenA));
    final BigInteger balance = state.balances().getOrDefault(owner, BigInteger.ZERO);
    Assertions.assertThat(balance).isEqualTo(amount);
  }

  private void assertDepositAmount(BlockchainAddress owner, long amount) {
    assertDepositAmount(owner, BigInteger.valueOf(amount));
  }

  private void assertDepositAmount(BlockchainAddress owner, BigInteger amount) {
    Assertions.assertThat(getDepositAmount(owner))
        .as("Deposit amount for " + owner)
        .isEqualTo(amount);
  }

  /**
   * Determines how much the given address has deposited into the contract. Different contracts
   * store this information differently, and must thus be specialized for each test. For example, as
   * least one contract uses secret-shares for this functionality.
   *
   * <p>Should return zero if the user does not have an account with the contract.
   *
   * @param owner Owner of the deposit balance. Never null.
   * @return the deposit balance. Must never be null.
   */
  protected abstract BigInteger getDepositAmount(BlockchainAddress owner);
}
