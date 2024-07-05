package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwapLock;
import java.math.BigInteger;

/** Contains helper functions that are useful for tests involving liquidity swap contracts. */
public final class LiquiditySwapTestingUtility {
  private static final BigInteger ZERO = BigInteger.ZERO;

  /**
   * Gets the actual liquidity balance of a swap contract, which is the balance disregarding locks.
   *
   * @param state of the swap contract to get balance from.
   * @param address of the swap contract to get balance from.
   * @return the actual balance, without accounting for locks.
   */
  public LiquiditySwapLock.TokenBalance getActualContractBalance(
      LiquiditySwapLock.LiquiditySwapContractState state, BlockchainAddress address) {
    return state.tokenBalances().balances().get(address);
  }

  /**
   * Calculates the virtual contract balance of a swap contract, by summing existing locks, and
   * adding them to the actual balance.
   *
   * @param state of the swap contract.
   * @param address of the swap contract.
   * @return the virtual balance state of the swap contract.
   */
  public LiquiditySwapLock.TokenBalance getVirtualContractBalance(
      LiquiditySwapLock.LiquiditySwapContractState state, BlockchainAddress address) {
    LiquiditySwapLock.TokenBalance virtualBalance = getActualContractBalance(state, address);

    for (LiquiditySwapLock.LiquidityLock lock : state.virtualState().locks().values()) {
      if (lock.tokensInOut().equals(tokenAinBout())) {
        virtualBalance =
            new LiquiditySwapLock.TokenBalance(
                virtualBalance.aTokens().add(lock.amountIn()),
                virtualBalance.bTokens().subtract(lock.amountOut()),
                ZERO);
      } else {
        virtualBalance =
            new LiquiditySwapLock.TokenBalance(
                virtualBalance.aTokens().subtract(lock.amountOut()),
                virtualBalance.bTokens().add(lock.amountIn()),
                ZERO);
      }
    }
    return virtualBalance;
  }

  LiquiditySwapLock.TokensInOut tokenAinBout() {
    return new LiquiditySwapLock.TokensInOut(
        new LiquiditySwapLock.DepositToken.TokenA(), new LiquiditySwapLock.DepositToken.TokenB());
  }

  LiquiditySwapLock.TokensInOut tokenBinAout() {
    return new LiquiditySwapLock.TokensInOut(
        new LiquiditySwapLock.DepositToken.TokenB(), new LiquiditySwapLock.DepositToken.TokenA());
  }
}
