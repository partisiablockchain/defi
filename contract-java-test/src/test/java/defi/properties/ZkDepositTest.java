package defi.properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.abicodegen.ZkDeposit;
import com.partisiablockchain.language.codegenlib.BlockchainStateClient;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.Previous;
import com.partisiablockchain.language.testenvironment.zk.node.RealV1FakeNodes;
import com.secata.stream.BitInput;
import com.secata.stream.BitOutput;
import com.secata.stream.CompactBitArray;
import com.secata.stream.SafeDataOutputStream;
import java.math.BigInteger;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/** Test the {@link ZkDeposit} contract. */
public abstract class ZkDepositTest extends JunitContractTest {

  private static final BigInteger TOTAL_SUPPLY = BigInteger.valueOf(123123);

  public static final byte SHORTNAME_REQUEST_TRANSFER = 0x4A;
  public static final byte SHORTNAME_APPROVE_TRANSFER = 0x4B;
  public static final byte SHORTNAME_CREATE_ACCOUNT = 0x49;

  public static final BigInteger RECIPIENT_KEY_SENDER = BigInteger.valueOf(1);
  public static final BigInteger RECIPIENT_KEY_RECIPIENT = BigInteger.valueOf(2);
  public static final BigInteger RECIPIENT_KEY_UNUSED = BigInteger.valueOf(9999);

  public static final int TOKEN_BIT_SIZE = 128;
  public static final int RECIPIENT_KEY_BIT_SIZE = 128;

  public BlockchainAddress accountCreator;
  public BlockchainAddress accountSender;
  public BlockchainAddress accountApprover;
  public BlockchainAddress accountRecipient;
  public BlockchainAddress accountNoAccount;
  public BlockchainAddress contractToken;
  public BlockchainAddress contractDeposit;

  private static final int NUM_EXTRANIOUS_ACCOUNTS = 10;

  private final ContractBytes contractBytesToken;
  private final ContractBytes contractBytesDeposit;

  /**
   * Initialize the test class.
   *
   * @param contractBytesToken Contract bytes to initialize the {@link Token} contract.
   * @param contractBytesDeposit Contract bytes to initialize the {@link ZkDeposit} contract. The
   *     contract under test.
   */
  public ZkDepositTest(
      final ContractBytes contractBytesToken, final ContractBytes contractBytesDeposit) {
    this.contractBytesToken = contractBytesToken;
    this.contractBytesDeposit = contractBytesDeposit;
  }

  ////////////////////////////////////////////////////////////////////////////////
  // Basic cases

  /** Setup for all the other tests. Deploys token contract and instantiates accounts. */
  @Test
  void initializeContracts() {
    accountCreator = blockchain.newAccount(1);
    accountApprover = blockchain.newAccount(2);
    accountSender = blockchain.newAccount(3);
    accountRecipient = blockchain.newAccount(4);
    accountNoAccount = blockchain.newAccount(5);

    // Setup tokens
    final byte[] initToken = Token.initialize("Token A", "AAA", (byte) 0, TOTAL_SUPPLY);
    contractToken = blockchain.deployContract(accountCreator, contractBytesToken, initToken);

    // Setup swap
    final byte[] initRpcDeposit = ZkDeposit.initialize(accountApprover, contractToken);
    if (contractBytesDeposit.codeFormat() == ContractBytes.CodeFormat.ZKWA) {
      contractDeposit =
          blockchain.deployZkContract(accountCreator, contractBytesDeposit, initRpcDeposit);
    } else {
      contractDeposit =
          blockchain.deployContract(accountCreator, contractBytesDeposit, initRpcDeposit);
    }
  }

  /** Users must be able to instantiate accounts in the zk-deposit contract. */
  @Test
  @Previous("initializeContracts")
  void createUserAccounts() {
    Assertions.assertThat(getQueueSize()).isEqualTo(0);

    createAccount(accountSender, RECIPIENT_KEY_SENDER);
    createAccount(accountRecipient, RECIPIENT_KEY_RECIPIENT);

    // Create additional accounts to just fill the state.
    java.util.stream.IntStream.range(1_000, 1000 + NUM_EXTRANIOUS_ACCOUNTS)
        .forEach(key -> createAccount(blockchain.newAccount(key), BigInteger.valueOf(key)));

    Assertions.assertThat(getQueueSize()).isEqualTo(0);

    // Setup
    blockchain.sendAction(
        accountCreator, contractToken, Token.transfer(accountSender, BigInteger.valueOf(3_000L)));
    assertInvariantsAtIdle();
  }

  /**
   * Users can deposit amounts, which result in secret-shared balances, which can be read by the
   * owner.
   */
  @Test
  @Previous("createUserAccounts")
  void senderDepositToken() {
    assertTokenBalance(accountSender, 3_000);

    // Deposit
    approveDeposit(accountSender, BigInteger.valueOf(3_000));
    deposit(accountSender, BigInteger.valueOf(1_000));

    assertDepositBalance(accountSender, 1_000);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountSender, 2_000);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();
  }

  /**
   * Users can transfer tokens to other users by using their {@code recipientKey}. This requires an
   * approval process, whereby a third party, which gains temporary ownership of the transfer
   * variable must accept the transfer.
   */
  @Test
  @Previous("senderDepositToken")
  void requestTransferApproveAndExecute() {
    // Request transfer
    final int transferId =
        requestTransfer(accountSender, RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(400));

    assertDepositBalance(accountSender, 1_000);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();

    Assertions.assertThat(variableOwner(transferId))
        .as("Approver must own transfer while under review")
        .isEqualTo(accountApprover);

    // Approve transfer
    approveTransfer(accountApprover, transferId);

    assertDepositBalance(accountSender, 600);
    assertDepositBalance(accountRecipient, 400);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();
  }

  /** Users can withdraw tokens from their accounts if they have enough. */
  @Test
  @Previous("requestTransferApproveAndExecute")
  void userWithdraw() {
    withdraw(accountRecipient, BigInteger.valueOf(400));

    assertDepositBalance(accountSender, 600);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountRecipient, 400);
    assertInvariantsAtIdle();
  }

  ////////////////////////////////////////////////////////////////////////////////
  // Failing cases

  /** Users cannot create two accounts for the same blockchain address. */
  @Test
  @Previous("initializeContracts")
  void failToCreateTheSameUserAccountTwice() {
    createAccount(accountSender, RECIPIENT_KEY_SENDER);
    Assertions.assertThatCode(() -> createAccount(accountSender, RECIPIENT_KEY_SENDER))
        .hasStackTraceContaining("Cannot create new user when account already exists");

    assertInvariantsAtIdle();
  }

  /** Users cannot deposit if they do not have an account. */
  @Test
  @Previous("createUserAccounts")
  void failToDepositWithoutCreatingAccount() {
    blockchain.sendAction(
        accountCreator,
        contractToken,
        Token.transfer(accountNoAccount, BigInteger.valueOf(1_000L)));

    // Deposit
    approveDeposit(accountNoAccount, BigInteger.valueOf(1_000));
    Assertions.assertThatCode(() -> deposit(accountNoAccount, BigInteger.valueOf(1_000)))
        .hasStackTraceContaining(
            "User does not possess an account: 00C5DCB3BCF6F048B0A765184B55B3F8D89DEA7377");

    assertInvariantsAtIdle();
  }

  /** Transfers can only be approved once. */
  @Test
  @Previous("senderDepositToken")
  void failToApproveSameTransferTwice() {
    // Request transfer
    final int transferId =
        requestTransfer(accountSender, RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(400));

    // Approve transfer
    approveTransfer(accountApprover, transferId);
    assertDepositBalance(accountSender, 600);
    assertDepositBalance(accountRecipient, 400);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();

    // Attempt transfer again
    Assertions.assertThatCode(() -> approveTransfer(accountApprover, transferId))
        .hasStackTraceContaining("Could not find a pending request with id " + transferId);

    assertDepositBalance(accountSender, 600);
    assertDepositBalance(accountRecipient, 400);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();
  }

  /** Non-approvers cannot approve transfers. */
  @Test
  @Previous("senderDepositToken")
  void nonApproverCannotApproveTransfer() {
    // Request transfer
    final int transferId =
        requestTransfer(accountSender, RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(400));

    // Approve transfer
    Assertions.assertThatCode(() -> approveTransfer(accountSender, transferId))
        .hasStackTraceContaining("Approver is the only user that can approve transfers");

    assertDepositBalance(accountSender, 1_000);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();
  }

  /** Approvers cannot approve transfers that does not exist. */
  @Test
  @Previous("senderDepositToken")
  void failToApproveNonExistingTransfer() {
    // Attempt transfer again
    Assertions.assertThatCode(() -> approveTransfer(accountApprover, 999))
        .hasStackTraceContaining("Could not find a pending request with id 999");

    assertDepositBalance(accountSender, 1_000);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();
  }

  /**
   * Transfers require the user to own an account.
   *
   * <p>If they don't own an account, they have a known about of tokens: {@code 0}
   */
  @Test
  @Previous("senderDepositToken")
  void failToTransferFromUserWithoutAccount() {
    // Request transfer
    final int transferId =
        requestTransfer(accountNoAccount, RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(400));

    assertDepositBalance(accountSender, 1_000);
    assertDepositBalance(accountNoAccount, null);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();

    // Approve transfer
    Assertions.assertThatCode(() -> approveTransfer(accountApprover, transferId))
        .hasStackTraceContaining(
            "User does not possess an account: 00C5DCB3BCF6F048B0A765184B55B3F8D89DEA7377");

    assertDepositBalance(accountSender, 1_000);
    assertDepositBalance(accountNoAccount, null);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();
  }

  /**
   * Transfers must be done to known accounts, and will have no effect if done to an non-existing
   * account. Sender does not lose any tokens.
   */
  @Test
  @Previous("senderDepositToken")
  void failToTransferToUserWithoutAccount() {
    // Request transfer
    final int transferId =
        requestTransfer(accountSender, RECIPIENT_KEY_UNUSED, BigInteger.valueOf(400));

    assertDepositBalance(accountRecipient, 0);
    assertDepositBalance(accountNoAccount, null);
    assertDepositBalance(accountSender, 1_000);
    assertInvariantsAtIdle();

    // Approve transfer
    approveTransfer(accountApprover, transferId);

    assertDepositBalance(accountRecipient, 0);
    assertDepositBalance(accountNoAccount, null);
    assertDepositBalance(accountSender, 1_000);
    assertInvariantsAtIdle();
  }

  /** Users cannot transfer to the zero transfer key. */
  @Test
  @Previous("senderDepositToken")
  void failToTransferToZeroAccount() {
    // Setup
    final BlockchainAddress accountZero1 = blockchain.newAccount(10);
    final BlockchainAddress accountZero2 = blockchain.newAccount(11);
    final BlockchainAddress accountZero3 = blockchain.newAccount(12);
    final BigInteger transferKeyZero = BigInteger.valueOf(0);
    createAccount(accountZero1, transferKeyZero);
    createAccount(accountZero2, transferKeyZero);
    createAccount(accountZero3, transferKeyZero);

    // Request transfer
    final int transferId = requestTransfer(accountSender, transferKeyZero, BigInteger.valueOf(400));

    assertDepositBalance(accountZero1, 0);
    assertDepositBalance(accountZero2, 0);
    assertDepositBalance(accountZero3, 0);
    assertDepositBalance(accountSender, 1_000);
    assertInvariantsAtIdle();

    // Approve transfer
    sendActionToCut(accountApprover, ZkDeposit.approveTransfer(transferId), 10_000);

    assertDepositBalance(accountZero1, 0);
    assertDepositBalance(accountZero2, 0);
    assertDepositBalance(accountZero3, 0);
    assertDepositBalance(accountSender, 1_000);
    assertInvariantsAtIdle();
  }

  /**
   * Senders must have more or equal tokens to the sent amount; otherwise the transfer will fail,
   * and no tokens will be moved.
   */
  @Test
  @Previous("senderDepositToken")
  void failToTransferTooManyTokens() {
    failingTransferInSecret(RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(1001));
  }

  /**
   * Withdrawals require the user to own an account.
   *
   * <p>If they don't own an account, they have a known about of tokens: {@code 0}
   */
  @Test
  @Previous("createUserAccounts")
  void failToWithdrawForUserWithoutAccount() {
    Assertions.assertThatCode(() -> withdraw(accountNoAccount, BigInteger.valueOf(400)))
        .hasStackTraceContaining(
            "User does not possess an account: 00C5DCB3BCF6F048B0A765184B55B3F8D89DEA7377");

    assertDepositBalance(accountNoAccount, null);
    assertTokenBalance(accountNoAccount, 0);
    assertInvariantsAtIdle();
  }

  /** Transfers from the user to themselves has no effect, and will not result in an error. */
  @Test
  @Previous("senderDepositToken")
  void transferToOneselfSpecialCase() {
    failingTransferInSecret(RECIPIENT_KEY_SENDER, BigInteger.valueOf(400));
  }

  /** It is not possible to create a user with a duplicated {@code transfer_key}. */
  @Test
  @Previous("senderDepositToken")
  void failToCreateUserWithDuplicateTransferKey() {
    // Create new account with RECIPIENT_KEY_RECIPIENT
    createAccount(accountNoAccount, RECIPIENT_KEY_RECIPIENT);

    final var balance = getDepositBalance(accountNoAccount);
    Assertions.assertThat(balance.recipientKey()).isEqualTo(BigInteger.ZERO);
  }

  /** User cannot withdraw more tokens than they own. */
  @Test
  @Previous("requestTransferApproveAndExecute")
  void failToWithdrawHugeAmounts() {
    Assertions.assertThatCode(
            () -> withdraw(accountRecipient, new BigInteger("FFFFFFFFFFFFFFFF", 16)))
        .hasStackTraceContaining(
            "Insufficient deposit balance! Could not withdraw 18446744073709551615 tokens, as user"
                + " do not have that amount deposited");

    assertDepositBalance(accountRecipient, 400);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();
  }

  /** Fail to send some very large amounts that user does not own. */
  @Test
  @Previous("senderDepositToken")
  void failWhenSendingTooHugeTransfers() {
    failingTransferInSecret(RECIPIENT_KEY_RECIPIENT, new BigInteger("0FFFFFFFFFFFFFFF", 16));
    failingTransferInSecret(RECIPIENT_KEY_RECIPIENT, new BigInteger("1FFFFFFFFFFFFFFF", 16));
    failingTransferInSecret(RECIPIENT_KEY_RECIPIENT, new BigInteger("3FFFFFFFFFFFFFFF", 16));
    failingTransferInSecret(RECIPIENT_KEY_RECIPIENT, new BigInteger("7FFFFFFFFFFFFFFF", 16));
    failingTransferInSecret(RECIPIENT_KEY_RECIPIENT, new BigInteger("FFFFFFFFFFFFFFFF", 16));
  }

  void failingTransferInSecret(BigInteger recipientKey, BigInteger amount) {
    final int transferId = requestTransfer(accountSender, recipientKey, amount);

    assertDepositBalance(accountSender, 1_000);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();

    // Approve transfer
    approveTransfer(accountApprover, transferId);

    assertDepositBalance(accountSender, 1_000);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();
  }

  /** Users cannot call {@code continueQueue} directly. */
  @Test
  @Previous("senderDepositToken")
  void continueQueueCannotBeCalledDirectlyByUsers() {
    Assertions.assertThatCode(
            () -> sendActionToCut(accountSender, ZkDeposit.continueQueue(), 100_000))
        .hasStackTraceContaining(
            "This is an internal invocation. Must not be invoked by outside users.");
  }

  ////////////////////////////////////////////////////////////////////////////////
  // Queue tests

  /**
   * Users can create a large queue of deposits that is executed once the nodes get around to it.
   */
  @Test
  @Previous("createUserAccounts")
  void queueDeposits() {
    approveDeposit(accountSender, BigInteger.valueOf(1_000));

    assertDepositBalance(accountSender, 0);
    assertTokenBalance(accountSender, 3_000);

    // Deposit
    zkNodes.stop();

    Assertions.assertThat(getQueueSize()).isEqualTo(0);
    deposit(accountSender, BigInteger.valueOf(100));
    Assertions.assertThat(getQueueSize()).isEqualTo(0);
    deposit(accountSender, BigInteger.valueOf(100));
    deposit(accountSender, BigInteger.valueOf(100));
    deposit(accountSender, BigInteger.valueOf(100));
    deposit(accountSender, BigInteger.valueOf(100));
    deposit(accountSender, BigInteger.valueOf(100));
    deposit(accountSender, BigInteger.valueOf(100));
    deposit(accountSender, BigInteger.valueOf(100));
    deposit(accountSender, BigInteger.valueOf(100));
    deposit(accountSender, BigInteger.valueOf(100));
    Assertions.assertThat(getQueueSize()).isEqualTo(9);

    assertDepositBalance(accountSender, 0);
    assertTokenBalance(accountSender, 2_000);

    zkNodes.finishTasks();

    Assertions.assertThat(getQueueSize()).isEqualTo(0);

    assertDepositBalance(accountSender, 1_000);
    assertTokenBalance(accountSender, 2_000);
  }

  /**
   * Users can create a large queue of transfers that is executed once the nodes get around to it.
   */
  @Test
  @Previous("senderDepositToken") // also queueDeposits
  void queueTransfers() {

    // Request transfers
    final int transferId1 =
        requestTransfer(accountSender, RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(100));
    final int transferId2 =
        requestTransfer(accountSender, RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(100));
    final int transferId3 =
        requestTransfer(accountSender, RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(100));
    final int transferId4 =
        requestTransfer(accountSender, RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(100));

    assertDepositBalance(accountSender, 1_000);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();

    // Approve transfer
    zkNodes.stop();
    Assertions.assertThat(getQueueSize()).isEqualTo(0);
    approveTransfer(accountApprover, transferId1);
    Assertions.assertThat(getQueueSize()).isEqualTo(0);
    approveTransfer(accountApprover, transferId2);
    approveTransfer(accountApprover, transferId3);
    approveTransfer(accountApprover, transferId4);
    Assertions.assertThat(getQueueSize()).isEqualTo(3);

    assertDepositBalance(accountSender, 1_000);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountRecipient, 0);

    zkNodes.finishTasks();

    Assertions.assertThat(getQueueSize()).isEqualTo(0);

    assertDepositBalance(accountSender, 600);
    assertDepositBalance(accountRecipient, 400);
    assertTokenBalance(accountRecipient, 0);
    assertInvariantsAtIdle();
  }

  /**
   * Users can create a large queue of withdrawals that is executed once the nodes get around to it.
   */
  @Test
  @Previous("requestTransferApproveAndExecute") // also queueTransfers
  void queueWithdrawals() {
    zkNodes.stop();
    withdraw(accountRecipient, BigInteger.valueOf(100));
    withdraw(accountRecipient, BigInteger.valueOf(100));
    withdraw(accountRecipient, BigInteger.valueOf(100));
    withdraw(accountRecipient, BigInteger.valueOf(100));

    assertDepositBalance(accountSender, 600);
    assertDepositBalance(accountRecipient, 400);
    assertTokenBalance(accountRecipient, 0);

    zkNodes.finishTasks();

    assertDepositBalance(accountSender, 600);
    assertDepositBalance(accountRecipient, 0);
    assertTokenBalance(accountRecipient, 400);
    assertInvariantsAtIdle();
  }

  /**
   * Users can queue a mix of events after each other. These are executed once the nodes get around
   * to it.
   */
  @Test
  @Previous("requestTransferApproveAndExecute") // also queueTransfers
  void checkThatTransferCanBeQueuedAfterEveryTypeOfWorkItem() {
    final int transferId1 =
        requestTransfer(accountSender, RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(100));
    final int transferId2 =
        requestTransfer(accountSender, RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(100));
    final int transferId3 =
        requestTransfer(accountSender, RECIPIENT_KEY_RECIPIENT, BigInteger.valueOf(100));

    zkNodes.stop();

    withdraw(accountRecipient, BigInteger.valueOf(100));
    approveTransfer(accountApprover, transferId1);

    deposit(accountSender, BigInteger.valueOf(100));
    approveTransfer(accountApprover, transferId2);

    createAccount(accountNoAccount, RECIPIENT_KEY_UNUSED);
    approveTransfer(accountApprover, transferId3);

    zkNodes.finishTasks();

    // If the finish tasks doesn't crash, it is satisfiable.
  }

  ////////////////////////////////////////////////////////////////////////////////
  // Utility

  private void createAccount(BlockchainAddress user, BigInteger recipientKey) {
    final byte[] publicRpc =
        SafeDataOutputStream.serialize(s -> s.writeByte(SHORTNAME_CREATE_ACCOUNT));

    final CompactBitArray secretRpc =
        BitOutput.serializeBits(
            s -> s.writeUnsignedBigInteger(recipientKey, RECIPIENT_KEY_BIT_SIZE));

    blockchain.sendSecretInput(contractDeposit, user, secretRpc, publicRpc, 40_000);

    // Skip checking of compute complexity as it varies too much.
  }

  private void approveDeposit(BlockchainAddress owner, BigInteger amount) {
    blockchain.sendAction(owner, contractToken, Token.approve(contractDeposit, amount), 3_000);
  }

  private void deposit(BlockchainAddress owner, BigInteger amount) {
    sendActionToCut(owner, ZkDeposit.deposit(contractToken, amount), 40_000);

    // Check computation stats: Constant based on token amount bit size
    assertComputeComplexity(TOKEN_BIT_SIZE, TOKEN_BIT_SIZE);
  }

  private void withdraw(BlockchainAddress owner, BigInteger amount) {
    sendActionToCut(owner, ZkDeposit.withdraw(contractToken, amount, false), 40_000);

    // Check computation stats: Constant based on token amount bit size
    assertComputeComplexity(TOKEN_BIT_SIZE * 2 + 4, TOKEN_BIT_SIZE * 4 + 2);
  }

  private void approveTransfer(BlockchainAddress sender, int transferId) {
    sendActionToCut(sender, ZkDeposit.approveTransfer(transferId), 10_000);

    // Check computation stats
    assertComputeComplexity(446, 10661);
  }

  private int requestTransfer(
      BlockchainAddress sender, BigInteger recipientKey, BigInteger amount) {
    final byte[] publicRpc =
        SafeDataOutputStream.serialize(s -> s.writeByte(SHORTNAME_REQUEST_TRANSFER));

    final CompactBitArray secretRpc =
        BitOutput.serializeBits(
            s -> {
              s.writeUnsignedBigInteger(recipientKey, RECIPIENT_KEY_BIT_SIZE);
              s.writeUnsignedBigInteger(amount, TOKEN_BIT_SIZE);
            });

    zkNodes.stop();
    final var inputId = blockchain.sendSecretInput(contractDeposit, sender, secretRpc, publicRpc);
    zkNodes.finishTasks();

    Assertions.assertThat(inputId).isNotNull();

    return inputId.inputId();
  }

  private void assertComputeComplexity(int numberOfRounds, int multiplicationCount) {
    final var complexity = zkNodes.getComplexityOfLastComputation();
    if (complexity != null) {
      System.out.println("numberOfRounds: " + complexity.numberOfRounds());
      System.out.println("multiplicationCount: " + complexity.multiplicationCount());
      Assertions.assertThat(complexity.numberOfRounds())
          .as("transfer round complexity")
          .isEqualTo(numberOfRounds);
      Assertions.assertThat(complexity.multiplicationCount())
          .as("transfer multiplications")
          .isEqualTo(multiplicationCount);
    }
  }

  private void sendActionToCut(final BlockchainAddress sender, byte[] rpc, long gas) {
    blockchain.sendAction(sender, contractDeposit, rpc, gas);
  }

  private void assertTokenBalance(BlockchainAddress owner, long amount) {
    assertTokenBalance(owner, BigInteger.valueOf(amount));
  }

  private void assertTokenBalance(BlockchainAddress owner, BigInteger amount) {
    Assertions.assertThat(getTokenBalance(owner))
        .as("Token balance for " + owner)
        .isEqualTo(amount);
  }

  /**
   * Get the amount of tokens owned by the user in the token contract.
   *
   * @param owner User to check balance for.
   * @return Token balance.
   */
  protected BigInteger getTokenBalance(BlockchainAddress owner) {
    final Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(contractToken));
    return state.balances().getOrDefault(owner, BigInteger.ZERO);
  }

  private void assertDepositBalance(BlockchainAddress owner, long amount) {
    assertDepositBalance(owner, BigInteger.valueOf(amount));
  }

  private void assertDepositBalance(BlockchainAddress owner, BigInteger amount) {
    final DepositBalance depositBalance = getDepositBalance(owner);

    Assertions.assertThat(depositBalance.accountBalance())
        .as("Deposit amount for " + owner)
        .isEqualTo(amount);

    if (amount != null) {
      Assertions.assertThat(variableOwner(depositBalance.variableId()))
          .as("Deposit variable of " + owner)
          .isEqualTo(owner);
    }
  }

  /** Asserts invariants for the contract that are relevant at idle (and in general). */
  private void assertInvariantsAtIdle() {
    assertInvariants();

    // Idle specific
    Assertions.assertThat(getQueueSize()).isZero();
  }

  private int getQueueSize() {
    final var state = new ZkDeposit(getStateClient(), contractDeposit).getState().openState();
    return state.workQueue().size();
  }

  /**
   * Asserts invariants for the contract.
   *
   * <p>Invariants checked:
   *
   * <ul>
   *   <li>Sum of all deposits in deposit should be equal to deposit's balance in token ({@link
   *       assertTokenSupplyInvariants}.)
   *   <li>Contract should not own any secrets ({@link assertVariableInvariantsAndCountBalances}.)
   *   <li>All balance secrets must be directly referenced from the [`ContractState::balances`] map
   *   <li>Amount of [`VariableKind::DepositBalance`] secrets equal to size of
   *       [`ContractState::balances`].
   *   <li>[`ContractState::redundant_variables`] is empty.
   *   <li>That all deposit keys are unique, except for zero-keys. ({@link
   *       assertUniqueDepositKeyInvariant}.)
   * </ul>
   */
  private void assertInvariants() {
    // Check invariants on variables
    final int numBalanceVariables = assertVariableInvariantsAndCountBalances();

    // Sum of balances
    final Map<BlockchainAddress, DepositBalance> depositBalances =
        getDepositBalances(zkNodes, getStateClient(), contractDeposit);

    // Unique deposit keys (except for zero.)
    assertUniqueDepositKeyInvariant(depositBalances);

    // Sum of all deposits in deposit should be equal to deposit's balance in token
    assertTokenSupplyInvariants(depositBalances);

    // Number of balances should be equal to the amount in state
    Assertions.assertThat(depositBalances)
        .as("Invariant Broken: Number of balances should be equal to the amount in state")
        .hasSize(numBalanceVariables);
  }

  private static void assertUniqueDepositKeyInvariant(
      final Map<BlockchainAddress, DepositBalance> depositBalances) {
    final Set<BigInteger> seenRecipientKeys = new HashSet<>();
    for (DepositBalance bal : depositBalances.values()) {
      if (!bal.recipientKey().equals(BigInteger.ZERO)) {
        Assertions.assertThat(bal.recipientKey()).isNotIn(seenRecipientKeys);
      }
      seenRecipientKeys.add(bal.recipientKey());
    }
  }

  private void assertTokenSupplyInvariants(
      final Map<BlockchainAddress, DepositBalance> depositBalances) {
    BigInteger sumOfBalances =
        depositBalances.values().stream()
            .map(x -> x.accountBalance())
            .reduce(BigInteger.ZERO, BigInteger::add);

    // Sum of balances must be synchronized with amounts in token.
    final BigInteger depositContractBalanceInToken = getTokenBalance(contractDeposit);
    Assertions.assertThat(sumOfBalances)
        .as(
            "Invariant Broken: Sum of balances (gotten) must be equal to deposit's balance in token"
                + " (expected)")
        .isEqualTo(depositContractBalanceInToken);
  }

  private int assertVariableInvariantsAndCountBalances() {
    int numBalanceVariables = 0;
    final var variableJsons =
        blockchain.getContractStateJson(contractDeposit).getNode("/variables");
    for (final var variableJson : variableJsons) {
      final int variableId = variableJson.get("key").asInt();

      Assertions.assertThat(variableOwner(variableId))
          .as("Invariant Broken: Contract must not own any secret variables")
          .isNotEqualTo(contractDeposit);

      // Determine whether variable is a balance.
      final ZkDeposit.VariableKind metadata = variableMetadata(variableId);
      if (metadata instanceof ZkDeposit.VariableKindDepositBalance) {
        numBalanceVariables++;
      }
    }

    return numBalanceVariables;
  }

  /** Get a singular deposit address for the given account. */
  private DepositBalance getDepositBalance(BlockchainAddress owner) {
    return getDepositBalance(zkNodes, getStateClient(), contractDeposit, owner);
  }

  /** Get a singular deposit address for the given account. */
  public static DepositBalance getDepositBalance(
      RealV1FakeNodes zkNodes,
      BlockchainStateClient stateClient,
      BlockchainAddress contractDeposit,
      BlockchainAddress owner) {
    return getDepositBalances(zkNodes, stateClient, contractDeposit)
        .getOrDefault(owner, UNKNOWN_BALANCE);
  }

  private static final DepositBalance UNKNOWN_BALANCE = new DepositBalance(-1, null, null);

  /** Get the full mapping of deposit balances. */
  private static Map<BlockchainAddress, DepositBalance> getDepositBalances(
      RealV1FakeNodes zkNodes,
      BlockchainStateClient stateClient,
      BlockchainAddress contractDeposit) {
    final var state = new ZkDeposit(stateClient, contractDeposit).getState().openState();

    final var balances = state.balances().getNextN(null, 10000);

    final LinkedHashMap<BlockchainAddress, DepositBalance> depositBalances = new LinkedHashMap<>();
    for (final var entry : balances) {
      final var variableId = entry.getValue();
      depositBalances.put(
          entry.getKey(),
          getVariableDataAsDepositBalance(zkNodes, contractDeposit, variableId.rawId()));
    }

    return depositBalances;
  }

  /** Reads information for the the given account id. */
  public static DepositBalance getVariableDataAsDepositBalance(
      RealV1FakeNodes zkNodes, BlockchainAddress contractDeposit, int variableId) {
    final var secretVariable = zkNodes.getSecretVariable(contractDeposit, variableId);

    final byte[] result = secretVariable.data();
    Assertions.assertThat(result)
        .as("deposit balance byte length")
        .hasSize((RECIPIENT_KEY_BIT_SIZE + TOKEN_BIT_SIZE) / 8);

    final var bitInput = BitInput.create(result);
    final BigInteger accountBalance = bitInput.readUnsignedBigInteger(TOKEN_BIT_SIZE);
    final BigInteger recipientKey = bitInput.readUnsignedBigInteger(RECIPIENT_KEY_BIT_SIZE);
    return new DepositBalance(variableId, accountBalance, recipientKey);
  }

  /**
   * Utility struct for storing data for tests.
   *
   * @param variableId Identifier of the balance secret variable.
   * @param accountBalance The amount of tokens deposited in the given account.
   * @param recipientKey Key used to transfer to the given account.
   */
  public record DepositBalance(
      int variableId, BigInteger accountBalance, BigInteger recipientKey) {}

  /** Metadata of the variable with the given id. */
  private ZkDeposit.VariableKind variableMetadata(int variableId) {
    final var variableJson = variableWithId(variableId);
    final String metadataBase64 = variableJson.get("information").get("data").asText();
    final byte[] metadataBytes = Base64.getDecoder().decode(metadataBase64);
    return ZkDeposit.deserializeSpecialVariableKind(metadataBytes);
  }

  /** Owner of the account balance, eg who can read the secret-shares. */
  private BlockchainAddress variableOwner(int variableId) {
    return variableOwner(variableWithId(variableId));
  }

  /** Owner of the account balance, eg who can read the secret-shares. */
  private BlockchainAddress variableOwner(JsonNode secretVariableData) {
    if (secretVariableData == null) {
      return null;
    } else {
      return BlockchainAddress.fromString(secretVariableData.get("owner").asText());
    }
  }

  /** Get json for variable with the given id. */
  private JsonNode variableWithId(int variableId) {
    final var variableJsons =
        blockchain.getContractStateJson(contractDeposit).getNode("/variables");
    for (final var variableJson : variableJsons) {
      if (variableJson.get("key").asInt() == variableId) {
        return variableJson.get("value");
      }
    }
    return null;
  }
}
