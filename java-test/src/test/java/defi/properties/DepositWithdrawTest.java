package defi.properties;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwap;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.assertj.core.api.Assertions;

/**
 * Testing a common interface for depositing and withdrawing of funds to a contract.
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
 * </ul>
 *
 * <p>This test does not cover locked funds, etc. as these vary wildly from contract to contract.
 */
public abstract class DepositWithdrawTest extends JunitContractTest {

  private static final BigInteger TOTAL_SUPPLY = BigInteger.valueOf(99_000L);

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

  protected abstract byte[] initContractUnderTestRpc(
      BlockchainAddress token1, BlockchainAddress token2);

  @ContractTest
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

  /**
   * Users can deposit funds to a contract when the contract has been approved as spender, and the
   * user calls the deposit action.
   */
  @ContractTest(previous = "initializeContracts")
  void depositInitial() {
    final BigInteger amount = BigInteger.valueOf(1000);

    // Approve contract
    approveDeposit(users.get(0), amount);

    // Send deposit
    deposit(users.get(0), amount);

    assertTokenAaaBalance(users.get(0), 98_000L);
    assertTokenAaaBalance(contractUnderTestAddress, 1_000L);
  }

  /** Attempting to deposit more funds than have been approved will result in an error. */
  @ContractTest(previous = "initializeContracts")
  void cannotDepositMoreThanTheApprovalAmount() {
    final BigInteger amountApprove = BigInteger.valueOf(1000);
    final BigInteger amountDeposit = BigInteger.valueOf(1001);

    // Approve contract
    approveDeposit(users.get(0), amountApprove);

    // Send deposit
    Assertions.assertThatCode(() -> deposit(users.get(0), amountDeposit))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Insufficient allowance for transfer_from: 1000/1001");

    assertTokenAaaBalance(users.get(0), TOTAL_SUPPLY);
    assertTokenAaaBalance(contractUnderTestAddress, 0L);
  }

  /** Users can withdraw funds from the contract when they own some deposits. */
  @ContractTest(previous = "depositInitial")
  void canWithdrawDepositedAmount() {
    final BigInteger amount = BigInteger.valueOf(1000);

    // Withdraw
    withdraw(users.get(0), amount);

    assertTokenAaaBalance(users.get(0), TOTAL_SUPPLY);
    assertTokenAaaBalance(contractUnderTestAddress, 0L);
  }

  /** Withdrawing funds that a user does not possess will result in an error. */
  @ContractTest(previous = "depositInitial")
  void cannotWithdrawMoreThanTheDepositedAmount() {
    final BigInteger amount = BigInteger.valueOf(1001);

    // Withdraw
    Assertions.assertThatCode(() -> withdraw(users.get(0), amount))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Insufficient TokenA deposit");

    assertTokenAaaBalance(users.get(0), 98_000L);
    assertTokenAaaBalance(contractUnderTestAddress, 1_000L);
  }

  /** Deposits are accumulative. */
  @ContractTest(previous = "depositInitial")
  void depositExtra() {
    final BigInteger amount = BigInteger.valueOf(1000);

    // Approve contract
    approveDeposit(users.get(0), amount);

    // Send deposit
    deposit(users.get(0), amount);

    assertTokenAaaBalance(users.get(0), 97_000L);
    assertTokenAaaBalance(contractUnderTestAddress, 2_000L);
  }

  @ContractTest(previous = "depositExtra")
  void withdrawEverything() {
    final BigInteger amount = BigInteger.valueOf(2000);

    // Approve contract
    approveDeposit(users.get(0), amount);

    // Send deposit
    withdraw(users.get(0), amount);

    assertTokenAaaBalance(users.get(0), 99_000L);
    assertTokenAaaBalance(contractUnderTestAddress, 0L);
  }

  /** Attempting to transfer after depositing all their funds will result in an error. */
  @ContractTest(previous = "depositInitial")
  void cannotDepositMoreThanUserOwns() {
    final BigInteger amount = TOTAL_SUPPLY;

    // Approve contract
    approveDeposit(users.get(0), amount);

    // Send deposit
    Assertions.assertThatCode(() -> deposit(users.get(0), amount))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Insufficient funds for transfer: 98000/99000");

    assertTokenAaaBalance(users.get(0), 98_000L);
    assertTokenAaaBalance(contractUnderTestAddress, 1_000L);
  }

  /**
   * Deposited funds are tracked separately for each user. The actions of one user cannot *
   * influence another user's deposit.
   */
  @ContractTest(previous = "initializeContracts")
  void userDepositsAreIndependent() {

    // Transfer to user 1
    blockchain.sendAction(
        users.get(0), contractTokenA, Token.transfer(users.get(1), BigInteger.valueOf(1_000L)));

    assertTokenAaaBalance(users.get(1), 1000L);
    assertTokenAaaBalance(users.get(0), 98000L);
    assertTokenAaaBalance(contractUnderTestAddress, 0L);

    // Deposit
    approveDeposit(users.get(1), BigInteger.valueOf(500));
    deposit(users.get(1), BigInteger.valueOf(500));
    assertTokenAaaBalance(users.get(1), 500L);
    assertTokenAaaBalance(users.get(0), 98000L);
    assertTokenAaaBalance(contractUnderTestAddress, 500L);

    approveDeposit(users.get(0), BigInteger.valueOf(5000));
    deposit(users.get(0), BigInteger.valueOf(5000));
    assertTokenAaaBalance(users.get(1), 500L);
    assertTokenAaaBalance(users.get(0), 93000L);
    assertTokenAaaBalance(contractUnderTestAddress, 5500L);

    // Withdraw
    withdraw(users.get(1), BigInteger.valueOf(500));
    assertTokenAaaBalance(users.get(1), 1000L);
    assertTokenAaaBalance(users.get(0), 93000L);
    assertTokenAaaBalance(contractUnderTestAddress, 5000L);

    withdraw(users.get(0), BigInteger.valueOf(5000));
    assertTokenAaaBalance(users.get(1), 1000L);
    assertTokenAaaBalance(users.get(0), 98000L);
    assertTokenAaaBalance(contractUnderTestAddress, 0L);
  }

  @ContractTest(previous = "initializeContracts")
  void enduranceTestingDepositWithdrawNoFailing() {
    final List<byte[]> depositHistory = generateDepositWithdrawHistory(100);

    // Approve everything
    approveDeposit(users.get(0), BigInteger.valueOf(Long.MAX_VALUE));

    for (final byte[] movementRpc : depositHistory) {
      sendActionToCut(users.get(0), movementRpc);
    }
  }

  private List<byte[]> generateDepositWithdrawHistory(int historyLength) {
    final Random rand = new Random(0x12312321L);
    final BigInteger ownedInTotal = TOTAL_SUPPLY;
    BigInteger currentDeposit = BigInteger.ZERO;
    final List<byte[]> history = new ArrayList<>();
    for (int idx = 0; idx < historyLength; idx++) {
      final BigInteger nextDeposit = nextRandomBigInteger(rand, ownedInTotal);
      final BigInteger movement = nextDeposit.subtract(currentDeposit);

      final byte[] rpc;
      if (movement.compareTo(BigInteger.ZERO) < 0) {
        rpc = LiquiditySwap.withdraw(contractTokenA, movement.negate());
      } else {
        rpc = LiquiditySwap.deposit(contractTokenA, movement);
      }
      history.add(rpc);

      currentDeposit = nextDeposit;
    }
    return List.copyOf(history);
  }

  private static BigInteger nextRandomBigInteger(Random rand, BigInteger n) {
    BigInteger result = new BigInteger(n.bitLength(), rand);
    while (result.compareTo(n) >= 0) {
      result = new BigInteger(n.bitLength(), rand);
    }
    return result;
  }

  private void approveDeposit(BlockchainAddress owner, BigInteger amount) {
    blockchain.sendAction(owner, contractTokenA, Token.approve(contractUnderTestAddress, amount));
  }

  private void deposit(BlockchainAddress owner, BigInteger amount) {
    sendActionToCut(owner, LiquiditySwap.deposit(contractTokenA, amount));
  }

  private void withdraw(BlockchainAddress owner, BigInteger amount) {
    sendActionToCut(owner, LiquiditySwap.withdraw(contractTokenA, amount));
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
}
