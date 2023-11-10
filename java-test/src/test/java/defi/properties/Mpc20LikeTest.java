package defi.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.TestBlockchain;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import java.math.BigInteger;
import java.util.List;

/** Test the {@link Token} contract. */
public abstract class Mpc20LikeTest extends JunitContractTest {

  private static final BigInteger TOTAL_SUPPLY = BigInteger.valueOf(123123);

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

    final Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));

    assertStateInvariants(state);
    assertThat(state.totalSupply()).isEqualTo(TOTAL_SUPPLY);
    assertThat(state.balances().get(account1)).isEqualTo(TOTAL_SUPPLY.subtract(transferAmount));
    assertThat(state.balances().get(account2)).isEqualTo(transferAmount);
  }

  // Feature: Transfer

  /** A user can transfer tokens to another user, which updates the balances. */
  @ContractTest(previous = "setup")
  void transferTenTokens() {

    Token.TokenState tokenState =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    BigInteger balanceBeforeTransfer = tokenState.balances().get(account1);

    transfer(account1, account3, BigInteger.TEN);

    tokenState = Token.TokenState.deserialize(blockchain.getContractState(tokenContract));

    assertThat(tokenState.balances().get(account1))
        .isEqualTo(balanceBeforeTransfer.subtract(BigInteger.TEN));
    assertStateInvariants(tokenState);
    assertThat(tokenState.balances().get(account3)).isEqualTo(BigInteger.TEN);
  }

  /** When an account balance becomes zero the account is removed from balances. */
  @ContractTest(previous = "setup")
  void removeAccountWhenBalanceIsZero() {
    Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    assertThat(state.balances().get(account3)).isNull();

    transfer(account2, account3, BigInteger.ONE);
    state = Token.TokenState.deserialize(blockchain.getContractState(tokenContract));

    assertStateInvariants(state);
    assertThat(state.balances().get(account3)).isEqualTo(BigInteger.ONE);
    assertThat(state.balances().get(account2)).isNull();
  }

  /** A user cannot transfer more tokens than they own. */
  @ContractTest(previous = "setup")
  void underflowTransferInsufficientFunds() {
    byte[] transferToAccount3 = Token.transfer(account3, BigInteger.ONE);
    blockchain.sendAction(account1, tokenContract, transferToAccount3);

    Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    BigInteger account1Balance = state.balances().get(account1);

    byte[] transfer = Token.transfer(account1, BigInteger.TWO);
    assertThatThrownBy(() -> blockchain.sendAction(account3, tokenContract, transfer))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Insufficient funds for transfer: 1/2");

    state = Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    assertThat(state.balances().get(account1)).isEqualTo(account1Balance);
    assertThat(state.balances().get(account3)).isEqualTo(BigInteger.ONE);
  }

  /** A user transferring zero tokens has no effect. */
  @ContractTest(previous = "setup")
  void transferZeroTokens() {

    Token.TokenState tokenState =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    BigInteger balanceBeforeAttempt = tokenState.balances().get(account1);

    transfer(account1, account3, BigInteger.ZERO);

    tokenState = Token.TokenState.deserialize(blockchain.getContractState(tokenContract));

    assertThat(tokenState.balances().get(account1)).isEqualTo(balanceBeforeAttempt);
    assertStateInvariants(tokenState);
  }

  // Feature: Approve and Transfer From.

  /**
   * A user can approve another user to transfer a number of tokens from her account, which updates
   * the allowances.
   */
  @ContractTest(previous = "setup")
  void approveTokens() {

    assertThat(allowance(account1, account2)).isNull();

    approve(account1, account2, BigInteger.valueOf(100));
    Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));

    assertStateInvariants(state);
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));
  }

  /**
   * A user that is approved to transfer from another user can perform a transfer, and the allowance
   * amount is reduced accordingly.
   */
  @ContractTest(previous = "approveTokens")
  void transferApprovedTokens() {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));

    transferFrom(account2, account1, account3, BigInteger.TEN);

    Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));

    assertStateInvariants(state);
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(90));
    assertThat(state.balances().get(account3)).isEqualTo(BigInteger.TEN);
  }

  /**
   * A user that is approved to transfer from another user cannot transfer more tokens than the
   * amount approved.
   */
  @ContractTest(previous = "approveTokens")
  void transferMoreThanApprovedAmount() {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));

    assertThatThrownBy(() -> transferFrom(account2, account1, account3, BigInteger.valueOf(110)))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Insufficient allowance for transfer_from: 100/110");

    Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));

    assertStateInvariants(state);
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));
    assertThat(state.balances().get(account3)).isNull();
  }

  /**
   * A user that is not approved to transfer from another user cannot transfer tokens from that
   * user.
   */
  @ContractTest(previous = "setup")
  void transferFromNonApprovedTokens() {

    Token.TokenState state;

    assertThatThrownBy(() -> transferFrom(account3, account1, account2, BigInteger.TEN))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Insufficient allowance for transfer_from: 0/10");

    state = Token.TokenState.deserialize(blockchain.getContractState(tokenContract));

    assertStateInvariants(state);
    assertThat(allowance(account1, account2)).isNull();
  }

  /** Transferring approved tokens, where the approving account has zero tokens fails. */
  @ContractTest(previous = "setup")
  void underflowTransferBalanceNonExistent() {
    Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    assertThat(state.balances().get(account3)).isNull();
    BigInteger account1Balance = state.balances().get(account1);

    byte[] transfer = Token.transfer(account1, BigInteger.ONE);
    assertThatThrownBy(() -> blockchain.sendAction(account3, tokenContract, transfer))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Insufficient funds for transfer: 0/1");

    state = Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    assertThat(state.balances().get(account1)).isEqualTo(account1Balance);
  }

  // Feature: Bulk Transfer

  /** A user can send tokens to different account, by sending a bulk transfer. */
  @ContractTest(previous = "setup")
  void bulkTransferTokens() {
    Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    assertThat(state.balances().get(account3)).isNull();
    byte[] transfer = Token.transfer(account2, BigInteger.TWO);
    blockchain.sendAction(account1, tokenContract, transfer);

    List<Token.Transfer> transfers =
        List.of(
            new Token.Transfer(account3, BigInteger.ONE),
            new Token.Transfer(account4, BigInteger.ONE));

    byte[] bulkTransfer = Token.bulkTransfer(transfers);
    blockchain.sendAction(account2, tokenContract, bulkTransfer);
    state = Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    assertThat(state.balances().get(account3)).isEqualTo(BigInteger.ONE);
    assertThat(state.balances().get(account4)).isEqualTo(BigInteger.ONE);
    assertThat(state.balances().get(account2)).isEqualTo(BigInteger.ONE);
  }

  /** A user can bulk transfer approved tokens for an account to two other accounts. */
  @ContractTest(previous = "setup")
  void bulkTransferFrom() {
    Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    assertThat(state.balances().get(account3)).isNull();

    byte[] approve = Token.approve(account4, BigInteger.valueOf(2));
    blockchain.sendAction(account1, tokenContract, approve);

    List<Token.Transfer> transfers =
        List.of(
            new Token.Transfer(account2, BigInteger.ONE),
            new Token.Transfer(account3, BigInteger.ONE));

    byte[] bulkTransfer = Token.bulkTransferFrom(account1, transfers);
    blockchain.sendAction(account4, tokenContract, bulkTransfer);

    state = Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    assertThat(state.balances().get(account2)).isEqualTo(BigInteger.TWO);
    assertThat(state.balances().get(account3)).isEqualTo(BigInteger.ONE);
  }

  /**
   * A user bulk transferring to two accounts, where the user does not have enough tokens for both
   * transfers makes both transfers fail.
   */
  @ContractTest(previous = "setup")
  void bulkTransferTokensOneFails() {
    Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    assertThat(state.balances().get(account3)).isNull();

    assertThat(state.balances().get(account2)).isEqualTo(BigInteger.ONE);

    List<Token.Transfer> transfers =
        List.of(
            new Token.Transfer(account3, BigInteger.ONE),
            new Token.Transfer(account4, BigInteger.ONE));

    byte[] bulkTransfer = Token.bulkTransfer(transfers);

    assertThatThrownBy(() -> blockchain.sendAction(account2, tokenContract, bulkTransfer))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Insufficient funds for transfer: 0/1");

    state = Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    assertStateInvariants(state);
    assertThat(state.balances().get(account2)).isEqualTo(BigInteger.ONE);
    assertThat(state.balances().get(account3)).isNull();
    assertThat(state.balances().get(account4)).isNull();
  }

  /** Bulk transfer must be given a non-empty list of transfers to perform. */
  @ContractTest(previous = "setup")
  void bulkTransferNoTransfers() {
    Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));

    assertThat(state.balances().get(account2)).isEqualTo(BigInteger.ONE);

    List<Token.Transfer> transfers = List.of();

    byte[] bulkTransfer = Token.bulkTransfer(transfers);
    blockchain.sendAction(account2, tokenContract, bulkTransfer);

    state = Token.TokenState.deserialize(blockchain.getContractState(tokenContract));

    assertStateInvariants(state);
    assertThat(state.balances().get(account2)).isEqualTo(BigInteger.ONE);
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

    final Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(address));

    assertThat(state.name()).isEqualTo(tokenName);
    assertThat(state.symbol()).isEqualTo(tokenSymbol);
    assertThat(state.totalSupply()).isEqualTo(totalSupply);
    return address;
  }

  private BigInteger allowance(final BlockchainAddress owner, final BlockchainAddress spender) {
    final Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
    final var ownerAllowances = state.allowed().get(owner);
    return ownerAllowances == null ? null : ownerAllowances.get(spender);
  }

  /**
   * Helper function for asserting the invariants that the balance map of the contract must not
   * contain zero-entries and that the sum of all the saved balances should equal the total supply.
   */
  private static void assertStateInvariants(Token.TokenState state) {
    BigInteger allAssignedBalances = BigInteger.ZERO;

    for (final var balance : state.balances().values()) {
      assertThat(balance).as("State must not contain balances with zero tokens").isNotZero();
      allAssignedBalances = allAssignedBalances.add(balance);
    }

    assertThat(allAssignedBalances)
        .as("Number of assigned tokens must be identical to total supply")
        .isEqualTo(state.totalSupply());
  }
}
