package defi.util;

import com.partisiablockchain.BlockchainAddress;
import java.math.BigInteger;
import java.util.Map;

/** Common state among Mpc20 like contracts. */
public interface Mpc20LikeState {
  /**
   * Balances for the accounts associated with the contract.
   *
   * @return A map of all balances.
   */
  Map<BlockchainAddress, BigInteger> balances();

  /**
   * The name of the token - e.g. "MyToken".
   *
   * @return The name of the token.
   */
  String name();

  /**
   * The symbol of the token. E.g. "HIX".
   *
   * @return The symbol of the token.
   */
  String symbol();

  /**
   * The number of decimals the token uses. E.g. 8, means to divide the token amount by `100000000`
   * to get its user representation.
   *
   * @return The number of decimals the token uses.
   */
  byte decimals();

  /**
   * Ledger for allowances, that allows users or contracts to transfer tokens on behalf of others.
   *
   * @return A map of all allowances.
   */
  BigInteger allowance(BlockchainAddress owner, BlockchainAddress spender);

  /**
   * Current amount of tokens for the TokenContract.
   *
   * @return The total amount of tokens on this contract.
   */
  BigInteger currentTotalSupply();
}
