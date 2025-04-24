package defi.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.Previous;
import com.partisiablockchain.language.junit.TestBlockchain;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import defi.util.Arbitrary;
import defi.util.Mpc20LikeState;
import defi.util.Mpc20Utility;
import java.math.BigInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

/**
 * Test that a token contract fulfils the <a
 * href="http://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-20-token-contract.html">MPC20
 * standard</a>.
 */
public abstract class Mpc20StandardTest extends JunitContractTest {

  public static final BigInteger INITIAL_SUPPLY = BigInteger.valueOf(123123);
  public static final BigInteger MAX_ALLOWED_VALUE =
      BigInteger.valueOf(1).shiftLeft(127).subtract(BigInteger.ONE);

  public final ContractBytes contractBytes;
  public BlockchainAddress tokenContract;
  public Mpc20Utility mpc20Utility;

  public BlockchainAddress account1;
  public BlockchainAddress account2;
  public BlockchainAddress account3;

  /**
   * Initialize the test class.
   *
   * @param contractBytes Contract bytes to initialize the contract.
   */
  public Mpc20StandardTest(ContractBytes contractBytes) {
    this.contractBytes = contractBytes;
  }

  /** Setup for all the other tests. Deploys token contract and instantiates accounts. */
  @ContractTest
  void setup() {
    account1 = blockchain.newAccount(2);
    account2 = blockchain.newAccount(3);
    account3 = blockchain.newAccount(4);

    tokenContract =
        deployAndInitializeTokenContract(
            blockchain,
            account1,
            "My Cool Token",
            "COOL",
            (byte) 8,
            account1,
            INITIAL_SUPPLY,
            contractBytes);

    mpc20Utility = new Mpc20Utility(tokenContract, INITIAL_SUPPLY);

    final Mpc20LikeState state = getContractState(tokenContract);
    assertThat(state.name()).isEqualTo("My Cool Token");
    assertThat(state.symbol()).isEqualTo("COOL");

    final BigInteger transferAmount = BigInteger.ONE;
    mpc20Utility.transfer(blockchain, account1, account2, transferAmount);

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(balance(account1)).isEqualTo(INITIAL_SUPPLY.subtract(transferAmount));
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
    mpc20Utility.transfer(blockchain, account1, account3, amountTransfer);

    // Check that state was updated correctly.
    assertThat(balance(account1)).isEqualTo(balanceBeforeTransfer.subtract(amountTransfer));
    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(balance(account3)).isEqualTo(amountTransfer);
  }

  /** When an account balance becomes zero the account is removed from balances. */
  @Test
  @Previous("setup")
  void removeAccountWhenBalanceIsZero() {
    assertThat(balance(account3)).isZero();

    mpc20Utility.transfer(blockchain, account2, account3, BigInteger.ONE);

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(balance(account3)).isEqualTo(BigInteger.ONE);
    assertThat(balance(account2)).isZero();
  }

  /** A user cannot transfer more tokens than they own. */
  @RepeatedTest(100)
  @Previous("setup")
  void underflowTransferInsufficientFunds(RepetitionInfo repetitionInfo) {
    final BigInteger amountAttemptTransfer =
        Arbitrary.bigInteger(repetitionInfo, BigInteger.valueOf(2), MAX_ALLOWED_VALUE);

    mpc20Utility.transfer(blockchain, account1, account3, BigInteger.ONE);

    final BigInteger account1Balance = balance(account1);

    assertThatThrownBy(
            () -> mpc20Utility.transfer(blockchain, account3, account1, amountAttemptTransfer))
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

    mpc20Utility.transfer(blockchain, account1, account3, BigInteger.ZERO);

    assertThat(balance(account1)).isEqualTo(balanceBeforeAttempt);
    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
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

    mpc20Utility.transfer(blockchain, account1, account1, amountAttemptTransfer);

    assertThat(balance(account1)).isEqualTo(balanceBeforeAttempt);
    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
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

    mpc20Utility.approve(blockchain, account1, account2, BigInteger.valueOf(100));

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));
  }

  /** A user can overwrite the number of approved tokens for another user. */
  @ContractTest(previous = "approveTokens")
  void overwriteApproveTokens() {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));

    mpc20Utility.approve(blockchain, account1, account2, BigInteger.valueOf(300));

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(300));
  }

  /**
   * A user that is approved to transfer from another user can perform a transfer, and the allowance
   * amount is reduced accordingly.
   */
  @Test
  @Previous("approveTokens")
  void transferApprovedTokens() {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));

    mpc20Utility.transferFrom(blockchain, account2, account1, account3, BigInteger.TEN);

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
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

    assertThatThrownBy(
            () -> mpc20Utility.transferFrom(blockchain, account2, account1, account3, amount))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient COOL allowance for transfer_from! Allowed 100, but trying to transfer "
                + amount);

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
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

    assertThatThrownBy(
            () -> mpc20Utility.transferFrom(blockchain, account3, account1, account2, amount))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient COOL allowance for transfer_from! Allowed 0, but trying to transfer "
                + amount);

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(allowance(account1, account2)).isNull();
  }

  /** A user cannot transferring approved tokens, when the approving account has zero tokens. */
  @Test
  @Previous("setup")
  void underflowTransferBalanceNonExistent() {
    assertThat(balance(account3)).isZero();
    BigInteger account1Balance = balance(account1);

    assertThatThrownBy(() -> mpc20Utility.transfer(blockchain, account3, account1, BigInteger.ONE))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient COOL tokens for transfer! Have 0, but trying to transfer 1 (in minimal"
                + " units)");

    assertThat(balance(account1)).isEqualTo(account1Balance);
  }

  /**
   * Deploy a Token contract with the given argument.
   *
   * @param blockchain the blockchain to deploy to.
   * @param creator the creator of the contract.
   * @param tokenName the token name.
   * @param tokenSymbol the token symbol.
   * @param decimals the amount of decimals a token can have.
   * @param initialTokenHolder the account that owns the initial supply of tokens.
   * @param initialTokenSupply the initial supply of the token.
   * @return the address for the deployed token contract
   */
  protected abstract BlockchainAddress deployAndInitializeTokenContract(
      TestBlockchain blockchain,
      BlockchainAddress creator,
      String tokenName,
      String tokenSymbol,
      byte decimals,
      BlockchainAddress initialTokenHolder,
      BigInteger initialTokenSupply,
      ContractBytes contractBytes);

  /**
   * Gets the state of the token contract with the given address.
   *
   * @param contractAddress Address of the contract. Not nullable.
   * @return MPC-20 like state. Not nullable.
   */
  protected abstract Mpc20LikeState getContractState(BlockchainAddress contractAddress);

  /**
   * Helper function to retrieve the allowance of a given owner and spender.
   *
   * @param owner The account that owns the tokens.
   * @param spender The account that has permission to transfer the tokens.
   * @return The amount of tokens the spender is allowed to transfer on behalf of the owner.
   */
  private BigInteger allowance(final BlockchainAddress owner, final BlockchainAddress spender) {
    return getContractState(tokenContract).allowance(owner, spender);
  }

  /**
   * Helper function to retrieve the balance of a given owner.
   *
   * @param owner The account that owns the tokens.
   * @return The amount of tokens the owner has in his balance or null if the account has no tokens.
   */
  private BigInteger balance(final BlockchainAddress owner) {
    final BigInteger balance = getContractState(tokenContract).balances().get(owner);
    return balance == null ? BigInteger.ZERO : balance;
  }
}
