package defi.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.TestBlockchain;
import java.math.BigInteger;

/** Provides easy access to MPC20 standard actions and state invariant assertion. */
public final class Mpc20Utility {

  private final BlockchainAddress tokenContract;
  private final BigInteger initialSupply;

  /**
   * Initialize the utility class.
   *
   * @param tokenContract The address of the contract under test
   * @param initialSupply The initial supply of tokens
   */
  public Mpc20Utility(BlockchainAddress tokenContract, BigInteger initialSupply) {
    this.tokenContract = tokenContract;
    this.initialSupply = initialSupply;
  }

  /**
   * Helper function for making transfer RPC and invoking the transfer action.
   *
   * @param blockchain The test blockchain
   * @param from The account that invokes the action, and the owner of the tokens to be transferred.
   * @param to The account that receives the tokens.
   * @param amount The amount of tokens to be transferred.
   */
  public void transfer(
      TestBlockchain blockchain, BlockchainAddress from, BlockchainAddress to, BigInteger amount) {
    final byte[] rpc = Token.transfer(to, amount);
    blockchain.sendAction(from, tokenContract, rpc);
  }

  /**
   * Helper function for making approve RPC and invoking the approve action.
   *
   * @param blockchain The test blockchain
   * @param approver The account that invokes the action, and the owner of the tokens to be
   *     approved.
   * @param approvee The account who will get permission to transfer tokens on behalf of the owner.
   * @param amount The amount of tokens to be approved.
   */
  public void approve(
      TestBlockchain blockchain,
      BlockchainAddress approver,
      BlockchainAddress approvee,
      BigInteger amount) {
    final byte[] rpc = Token.approve(approvee, amount);
    blockchain.sendAction(approver, tokenContract, rpc);
  }

  /**
   * Helper function for making transferFrom RPC and invoking the transferFrom action.
   *
   * @param blockchain The test blockchain
   * @param approvee The account that invokes the action. This account has permission to transfer
   *     tokens on behalf of the owner.
   * @param from The owner of the tokens to be transferred.
   * @param to The account that receives the tokens.
   * @param amount The amount of tokens to be transferred.
   */
  public void transferFrom(
      TestBlockchain blockchain,
      BlockchainAddress approvee,
      BlockchainAddress from,
      BlockchainAddress to,
      BigInteger amount) {
    final byte[] rpc = Token.transferFrom(from, to, amount);
    blockchain.sendAction(approvee, tokenContract, rpc);
  }

  /**
   * Helper function for asserting the invariants.
   *
   * <ul>
   *   <li>State must not contain balances with zero tokens.
   *   <li>The sum of all the saved balances should equal the current total supply.
   *   <li>Transferring tokens does not change the total amount of tokens on the contract.
   * </ul>
   */
  public void assertStateInvariants(Mpc20LikeState state) {
    BigInteger allAssignedBalances = BigInteger.ZERO;

    for (final var balance : state.balances().values()) {
      assertThat(balance).as("State must not contain balances with zero tokens").isNotZero();
      allAssignedBalances = allAssignedBalances.add(balance);
    }

    assertThat(allAssignedBalances)
        .as("The sum of all the saved balances should equal the current total supply")
        .isEqualTo(state.currentTotalSupply());

    assertThat(state.currentTotalSupply())
        .as("Transferring tokens does not change the total amount of tokens on the contract")
        .isEqualTo(initialSupply);
  }
}
