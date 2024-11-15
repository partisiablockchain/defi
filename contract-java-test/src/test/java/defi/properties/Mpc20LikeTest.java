package defi.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.Previous;
import com.partisiablockchain.language.junit.TestBlockchain;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import defi.util.Arbitrary;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Test the {@link Token} contract. */
public abstract class Mpc20LikeTest extends JunitContractTest {

  private static final BigInteger TOTAL_SUPPLY = BigInteger.valueOf(123123);
  private static final BigInteger MAX_ALLOWED_VALUE =
      BigInteger.valueOf(1).shiftLeft(127).subtract(BigInteger.ONE);

  private static final int approvalAmount = 100;

  public BlockchainAddress account1;
  public BlockchainAddress account2;
  public BlockchainAddress account3;
  public BlockchainAddress account4;
  public BlockchainAddress tokenContract;

  protected final ContractBytes contractBytesToken;

  /**
   * Initialize the test class.
   *
   * @param contractBytesToken Contract bytes to initialize the {@link Token} contract.
   */
  public Mpc20LikeTest(final ContractBytes contractBytesToken) {
    this.contractBytesToken = contractBytesToken;
  }

  /** Setup for all the other tests. Deploys token contract and instantiates accounts. */
  @ContractTest
  void setup() {
    account1 = blockchain.newAccount(2);
    account2 = blockchain.newAccount(3);
    account3 = blockchain.newAccount(4);
    account4 = blockchain.newAccount(5);

    tokenContract =
        deployTokenContract(blockchain, account1, "My Cool Token", "COOL", (byte) 8, TOTAL_SUPPLY);

    final BigInteger transferAmount = BigInteger.ONE;

    transfer(account1, account2, transferAmount);

    assertStateInvariants();
    assertThat(balance(account1)).isEqualTo(TOTAL_SUPPLY.subtract(transferAmount));
    assertThat(balance(account2)).isEqualTo(transferAmount);
  }

  // Feature: Transfer

  /** A user can transfer tokens to another user, which updates the balances. */
  @RepeatedTest(100)
  @Previous("setup")
  void transferTokens(RepetitionInfo repetitionInfo) {
    // Determine initial state
    final BigInteger balanceBeforeTransfer = balance(account1);

    // Transfer some amount
    final BigInteger amountTransfer =
        Arbitrary.bigInteger(repetitionInfo, BigInteger.valueOf(0), balanceBeforeTransfer);
    transfer(account1, account3, amountTransfer);

    // Check that state was updated correctly.
    assertThat(balance(account1)).isEqualTo(balanceBeforeTransfer.subtract(amountTransfer));
    assertStateInvariants();
    assertThat(balance(account3)).isEqualTo(amountTransfer);
  }

  /** When an account balance becomes zero the account is removed from balances. */
  @Test
  @Previous("setup")
  void removeAccountWhenBalanceIsZero() {
    assertThat(balance(account3)).isZero();

    transfer(account2, account3, BigInteger.ONE);

    assertStateInvariants();
    assertThat(balance(account3)).isEqualTo(BigInteger.ONE);
    assertThat(balance(account2)).isZero();
  }

  /** A user cannot transfer more tokens than they own. */
  @RepeatedTest(100)
  @Previous("setup")
  void underflowTransferInsufficientFunds(RepetitionInfo repetitionInfo) {
    final BigInteger amountAttemptTransfer =
        Arbitrary.bigInteger(repetitionInfo, BigInteger.valueOf(2), MAX_ALLOWED_VALUE);

    blockchain.sendAction(account1, tokenContract, Token.transfer(account3, BigInteger.ONE));

    final BigInteger account1Balance = balance(account1);

    assertThatThrownBy(
            () ->
                blockchain.sendAction(
                    account3, tokenContract, Token.transfer(account1, amountAttemptTransfer)))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient COOL tokens for transfer! Have 1, but trying to transfer "
                + amountAttemptTransfer);

    assertThat(balance(account1)).isEqualTo(account1Balance);
    assertThat(balance(account3)).isEqualTo(BigInteger.ONE);
  }

  /** A user transferring zero tokens has no effect. */
  @Test
  @Previous("setup")
  void transferZeroTokens() {
    final BigInteger balanceBeforeAttempt = balance(account1);

    transfer(account1, account3, BigInteger.ZERO);

    assertThat(balance(account1)).isEqualTo(balanceBeforeAttempt);
    assertStateInvariants();
  }

  /** A user transferring to themselves has no effect. */
  @RepeatedTest(100)
  @Previous("setup")
  void transferToSelfTokens(RepetitionInfo repetitionInfo) {
    // Determine initial state
    final BigInteger balanceBeforeAttempt = balance(account1);

    //
    final BigInteger amountAttemptTransfer =
        Arbitrary.bigInteger(repetitionInfo, BigInteger.ZERO, balanceBeforeAttempt);

    transfer(account1, account1, amountAttemptTransfer);

    assertThat(balance(account1)).isEqualTo(balanceBeforeAttempt);
    assertStateInvariants();
  }

  // Feature: Approve and Transfer From.

  /**
   * A user can approve another user to transfer a number of tokens from her account, which updates
   * the allowances.
   */
  @Test
  @Previous("setup")
  void approveTokens() {

    assertThat(allowance(account1, account2)).isNull();

    approve(account1, account2, BigInteger.valueOf(100));

    assertStateInvariants();
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));
  }

  /** A user can overwrite the number of approved tokens for another user. */
  @ContractTest(previous = "approveTokens")
  void overwriteApproveTokens() {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));

    approve(account1, account2, BigInteger.valueOf(300));

    assertStateInvariants();
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(300));
  }

  /**
   * A user can approve a positive delta, relative to an already approved amount, increasing the
   * total approved amount.
   */
  @Previous("approveTokens")
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 12, 100, approvalAmount, approvalAmount * 123})
  void approveRelativeAdditionalTokens(int delta) {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));

    approveRelative(account1, account2, BigInteger.valueOf(delta));

    assertStateInvariants();
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100 + delta));
  }

  /**
   * A user can approve a negative delta, relative to an already approved amount, decreasing the
   * total approved amount.
   */
  @Previous("approveTokens")
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 12, approvalAmount - 1})
  void approveRelativeFewerTokens(int delta) {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(approvalAmount));

    approveRelative(account1, account2, BigInteger.valueOf(-delta));

    assertStateInvariants();
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(approvalAmount - delta));
  }

  /**
   * If a user relatively approves a negative delta equal to the already approved amount, the
   * allowance disappears.
   */
  @Previous("setup")
  @ParameterizedTest()
  @ValueSource(ints = {0, 1, 12, 50, 100, 921956719})
  void approveRelativeZeroTokens(int delta) {

    approve(account1, account2, BigInteger.valueOf(delta));

    approveRelative(account1, account2, BigInteger.valueOf(-delta));

    assertStateInvariants();
    assertThat(allowance(account1, account2)).isNull();
  }

  /**
   * If a user relatively approves with a negative delta, such that the resulting allowance would
   * become negative, the call fails, and the allowance is unchanged.
   */
  @Previous("approveTokens")
  @ParameterizedTest
  @ValueSource(ints = {-(approvalAmount + 1), -(approvalAmount + 1000)})
  void approveRelativeNegative(int delta) {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(approvalAmount));

    assertThatCode(() -> approveRelative(account1, account2, BigInteger.valueOf(delta)))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Allowance would become negative.");

    assertStateInvariants();
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(approvalAmount));
  }

  /**
   * A user that is approved to transfer from another user can perform a transfer, and the allowance
   * amount is reduced accordingly.
   */
  @Test
  @Previous("approveTokens")
  void transferApprovedTokens() {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));

    transferFrom(account2, account1, account3, BigInteger.TEN);

    assertStateInvariants();
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(90));
    assertThat(balance(account3)).isEqualTo(BigInteger.TEN);
  }

  /**
   * A user that is approved to transfer from another user cannot transfer more tokens than the
   * amount approved.
   */
  @RepeatedTest(100)
  @Previous("approveTokens")
  void transferMoreThanApprovedAmount(RepetitionInfo repetitionInfo) {
    final BigInteger amount =
        Arbitrary.bigInteger(repetitionInfo, BigInteger.valueOf(101), MAX_ALLOWED_VALUE);

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));

    assertThatThrownBy(() -> transferFrom(account2, account1, account3, amount))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient COOL allowance for transfer_from! Allowed 100, but trying to transfer "
                + amount);

    assertStateInvariants();
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));
    assertThat(balance(account3)).isZero();
  }

  /**
   * A user that is not approved to transfer from another user cannot transfer tokens from that
   * user.
   */
  @RepeatedTest(100)
  @Previous("setup")
  void transferFromNonApprovedTokens(RepetitionInfo repetitionInfo) {
    final BigInteger amount =
        Arbitrary.bigInteger(repetitionInfo, BigInteger.valueOf(1), MAX_ALLOWED_VALUE);

    assertThatThrownBy(() -> transferFrom(account3, account1, account2, amount))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient COOL allowance for transfer_from! Allowed 0, but trying to transfer "
                + amount);

    assertStateInvariants();
    assertThat(allowance(account1, account2)).isNull();
  }

  /** Transferring approved tokens, where the approving account has zero tokens fails. */
  @Test
  @Previous("setup")
  void underflowTransferBalanceNonExistent() {
    assertThat(balance(account3)).isZero();
    BigInteger account1Balance = balance(account1);

    byte[] transfer = Token.transfer(account1, BigInteger.ONE);
    assertThatThrownBy(() -> blockchain.sendAction(account3, tokenContract, transfer))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient COOL tokens for transfer! Have 0, but trying to transfer 1 (in minimal"
                + " units)");

    assertThat(balance(account1)).isEqualTo(account1Balance);
  }

  // Feature: Bulk Transfer

  /** A user can send tokens to different account, by sending a bulk transfer. */
  @Test
  @Previous("setup")
  void bulkTransferTokens() {
    assertThat(balance(account3)).isZero();
    byte[] transfer = Token.transfer(account2, BigInteger.TWO);
    blockchain.sendAction(account1, tokenContract, transfer);

    List<Token.Transfer> transfers =
        List.of(
            new Token.Transfer(account3, BigInteger.ONE),
            new Token.Transfer(account4, BigInteger.ONE));

    byte[] bulkTransfer = Token.bulkTransfer(transfers);
    blockchain.sendAction(account2, tokenContract, bulkTransfer);
    assertThat(balance(account3)).isEqualTo(BigInteger.ONE);
    assertThat(balance(account4)).isEqualTo(BigInteger.ONE);
    assertThat(balance(account2)).isEqualTo(BigInteger.ONE);
  }

  /** A user can bulk transfer approved tokens for an account to two other accounts. */
  @Test
  @Previous("setup")
  void bulkTransferFrom() {
    assertThat(balance(account3)).isZero();

    byte[] approve = Token.approve(account4, BigInteger.valueOf(2));
    blockchain.sendAction(account1, tokenContract, approve);

    List<Token.Transfer> transfers =
        List.of(
            new Token.Transfer(account2, BigInteger.ONE),
            new Token.Transfer(account3, BigInteger.ONE));

    byte[] bulkTransfer = Token.bulkTransferFrom(account1, transfers);
    blockchain.sendAction(account4, tokenContract, bulkTransfer);

    assertThat(balance(account2)).isEqualTo(BigInteger.TWO);
    assertThat(balance(account3)).isEqualTo(BigInteger.ONE);
  }

  /**
   * A user bulk transferring to two accounts, where the user does not have enough tokens for both
   * transfers makes both transfers fail.
   */
  @Test
  @Previous("setup")
  void bulkTransferTokensOneFails() {
    assertThat(balance(account3)).isZero();

    assertThat(balance(account2)).isEqualTo(BigInteger.ONE);

    List<Token.Transfer> transfers =
        List.of(
            new Token.Transfer(account3, BigInteger.ONE),
            new Token.Transfer(account4, BigInteger.ONE));

    byte[] bulkTransfer = Token.bulkTransfer(transfers);

    assertThatThrownBy(() -> blockchain.sendAction(account2, tokenContract, bulkTransfer))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient COOL tokens for transfer! Have 0, but trying to transfer 1 (in minimal"
                + " units)");

    assertStateInvariants();
    assertThat(balance(account2)).isEqualTo(BigInteger.ONE);
    assertThat(balance(account3)).isZero();
    assertThat(balance(account4)).isZero();
  }

  /** Bulk transfer must be given a non-empty list of transfers to perform. */
  @Test
  @Previous("setup")
  void bulkTransferNoTransfers() {
    assertThat(balance(account2)).isEqualTo(BigInteger.ONE);

    List<Token.Transfer> transfers = List.of();

    byte[] bulkTransfer = Token.bulkTransfer(transfers);
    blockchain.sendAction(account2, tokenContract, bulkTransfer);

    assertStateInvariants();
    assertThat(balance(account2)).isEqualTo(BigInteger.ONE);
  }

  /** Helper function for making transfer RPC and invoking the transfer action. */
  private void transfer(BlockchainAddress from, BlockchainAddress to, BigInteger amount) {
    final byte[] rpc = Token.transfer(to, amount);
    blockchain.sendAction(from, tokenContract, rpc);
  }

  /** Helper function for making approve RPC and invoking the approve action. */
  private void approve(BlockchainAddress approver, BlockchainAddress approvee, BigInteger amount) {
    final byte[] rpc = Token.approve(approvee, amount);
    blockchain.sendAction(approver, tokenContract, rpc);
  }

  /** Helper function for making approve RPC and invoking the approve_relative action. */
  private void approveRelative(
      BlockchainAddress approver, BlockchainAddress approvee, BigInteger delta) {
    final byte[] rpc = Token.approveRelative(approvee, delta);
    blockchain.sendAction(approver, tokenContract, rpc);
  }

  /** Helper function for making transferFrom RPC and invoking the transferFrom action. */
  private void transferFrom(
      BlockchainAddress approvee, BlockchainAddress from, BlockchainAddress to, BigInteger amount) {
    final byte[] rpc = Token.transferFrom(from, to, amount);
    blockchain.sendAction(approvee, tokenContract, rpc);
  }

  /**
   * Deploy a Token contract with the given argument.
   *
   * @param blockchain the blockchain to deploy to.
   * @param creator the creator of the contract.
   * @param tokenName the token name.
   * @param tokenSymbol the token symbol.
   * @param decimals the amount of decimals a token can have.
   * @param totalSupply the total supply of a token.
   * @return the address for the deployed token contract
   */
  public BlockchainAddress deployTokenContract(
      TestBlockchain blockchain,
      BlockchainAddress creator,
      String tokenName,
      String tokenSymbol,
      byte decimals,
      BigInteger totalSupply) {
    final byte[] initRpc = Token.initialize(tokenName, tokenSymbol, decimals, totalSupply);
    final BlockchainAddress address =
        blockchain.deployContract(creator, contractBytesToken, initRpc);

    final Mpc20LikeState state = getContractState(address);

    assertThat(state.name()).isEqualTo(tokenName);
    assertThat(state.symbol()).isEqualTo(tokenSymbol);
    assertThat(state.totalSupply()).isEqualTo(totalSupply);
    return address;
  }

  private BigInteger allowance(final BlockchainAddress owner, final BlockchainAddress spender) {
    final Mpc20LikeState state = getContractState(tokenContract);
    return state.allowance(owner, spender);
  }

  private BigInteger balance(final BlockchainAddress owner) {
    final Mpc20LikeState state = getContractState(tokenContract);
    final BigInteger balance = state.balances().get(owner);
    return balance == null ? BigInteger.ZERO : balance;
  }

  /**
   * Helper function for asserting the invariants that the balance map of the contract must not
   * contain zero-entries and that the sum of all the saved balances should equal the total supply.
   */
  private void assertStateInvariants() {
    final Mpc20LikeState state = getContractState(tokenContract);
    BigInteger allAssignedBalances = BigInteger.ZERO;

    for (final var balance : state.balances().values()) {
      assertThat(balance).as("State must not contain balances with zero tokens").isNotZero();
      allAssignedBalances = allAssignedBalances.add(balance);
    }

    assertThat(allAssignedBalances)
        .as("Number of assigned tokens must be identical to total supply")
        .isEqualTo(state.totalSupply())
        .isEqualTo(TOTAL_SUPPLY);
  }

  /** Common state among Mpc20 like contracts. */
  protected interface Mpc20LikeState {
    String name();

    byte decimals();

    String symbol();

    BlockchainAddress owner();

    BigInteger totalSupply();

    Map<BlockchainAddress, BigInteger> balances();

    BigInteger allowance(BlockchainAddress owner, BlockchainAddress spender);
  }

  /**
   * Gets the state of the token contract with the given address.
   *
   * @param contractAddress Address of the contract. Not nullable.
   * @return MPC-20 like state. Not nullable.
   */
  protected abstract Mpc20LikeState getContractState(BlockchainAddress contractAddress);
}
