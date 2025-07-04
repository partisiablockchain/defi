package defi.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.Previous;
import com.partisiablockchain.language.junit.TestBlockchain;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import defi.util.Mpc20LikeState;
import defi.util.Mpc20Utility;
import java.math.BigInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Test that a token contract fulfils the MPC20 extension: Approve Relative. When approving
 * relative, the owner of the tokens can update the allowance for the spender by adding or
 * subtracting a difference.
 */
public abstract class Mpc20ExtensionApproveRelativeTest extends JunitContractTest {

  public static final BigInteger INITIAL_SUPPLY = BigInteger.valueOf(123123);
  private static final int approvalAmount = 100;

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
  public Mpc20ExtensionApproveRelativeTest(ContractBytes contractBytes) {
    this.contractBytes = contractBytes;
  }

  /** Setup for all the other tests. Deploys token contract and instantiates accounts. */
  @ContractTest
  void setup() {
    account1 = blockchain.newAccount(10);
    account2 = blockchain.newAccount(11);
    account3 = blockchain.newAccount(12);

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
    assertThat(allowance(account1, account2)).isNull();

    final BigInteger transferAmount = BigInteger.ONE;
    mpc20Utility.transfer(blockchain, account1, account2, transferAmount);

    mpc20Utility.approve(blockchain, account1, account2, BigInteger.valueOf(100));

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));
    assertThat(balance(account1)).isEqualTo(INITIAL_SUPPLY.subtract(transferAmount));
    assertThat(balance(account2)).isEqualTo(transferAmount);
  }

  /**
   * A user can approve a positive delta, relative to an already approved amount, increasing the
   * total approved amount.
   */
  @Previous("setup")
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 12, 100, approvalAmount, approvalAmount * 123})
  void approveRelativeAdditionalTokens(int delta) {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100));

    approveRelative(account1, account2, BigInteger.valueOf(delta));

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(100 + delta));
  }

  /**
   * A user can approve a negative delta, relative to an already approved amount, decreasing the
   * total approved amount.
   */
  @Previous("setup")
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 12, approvalAmount - 1})
  void approveRelativeFewerTokens(int delta) {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(approvalAmount));

    approveRelative(account1, account2, BigInteger.valueOf(-delta));

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(approvalAmount - delta));
  }

  /**
   * When a user relatively approves a negative delta equal to the already approved amount, the
   * allowance disappears.
   */
  @Previous("setup")
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 12, 50, 100, 921956719})
  void approveRelativeZeroTokens(int delta) {

    assertThat(allowance(account1, account3)).isNull();

    mpc20Utility.approve(blockchain, account1, account3, BigInteger.valueOf(delta));

    approveRelative(account1, account3, BigInteger.valueOf(-delta));

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(allowance(account1, account3)).isNull();
  }

  /**
   * When a user relatively approves with a negative delta, such that the resulting allowance would
   * become negative, the call fails, and the allowance is unchanged.
   */
  @Previous("setup")
  @ParameterizedTest
  @ValueSource(ints = {-(approvalAmount + 1), -(approvalAmount + 1000)})
  void approveRelativeNegative(int delta) {

    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(approvalAmount));

    assertThatCode(() -> approveRelative(account1, account2, BigInteger.valueOf(delta)))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Allowance would become negative.");

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(allowance(account1, account2)).isEqualTo(BigInteger.valueOf(approvalAmount));
  }

  /** Helper function for making approve RPC and invoking the approve_relative action. */
  protected void approveRelative(
      BlockchainAddress approver, BlockchainAddress approvee, BigInteger delta) {
    final byte[] rpc = Token.approveRelative(approvee, delta);
    blockchain.sendAction(approver, tokenContract, rpc);
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
   * Helper function to retrieve the balance of a given owner.
   *
   * @param owner The account that owns the tokens.
   * @return The amount of tokens the owner has in his balance.
   */
  private BigInteger balance(final BlockchainAddress owner) {
    final BigInteger balance = getContractState(tokenContract).balances().get(owner);
    return balance == null ? BigInteger.ZERO : balance;
  }

  /**
   * Helper function to retrieve the allowance of a given owner and spender.
   *
   * @param owner The account that owns the tokens.
   * @param spender The account that has permission to transfer the tokens.
   * @return The amount of tokens the spender is allowed to transfer on behalf of the owner.
   */
  private BigInteger allowance(final BlockchainAddress owner, final BlockchainAddress spender) {
    Mpc20LikeState state = getContractState(tokenContract);
    return state.allowance(owner, spender);
  }
}
