package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwapLock;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.JunitContractTest;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;

/** Utility for {@link LiquiditySwapLock} testing. */
public abstract class LiquiditySwapLockBaseTest extends JunitContractTest {
  abstract BlockchainAddress swapLockContractAddressAtoB();

  abstract BlockchainAddress swapLockContractAddressBtoC();

  abstract BlockchainAddress contractTokenA();

  abstract BlockchainAddress contractTokenB();

  abstract BlockchainAddress contractTokenC();

  abstract BlockchainAddress contractTokenD();

  static Set<LiquiditySwapLock.LiquidityLockId> lockTrackerAB;
  static Set<LiquiditySwapLock.LiquidityLockId> lockTrackerBC;
  static Set<LiquiditySwapLock.LiquidityLockId> lockTrackerCD;

  static final BigInteger ZERO = BigInteger.ZERO;

  static final short FEE = 3;

  static final LiquiditySwapTestingUtility swapUtil = new LiquiditySwapTestingUtility();

  LiquiditySwapLock.LiquiditySwapContractState getSwapState(BlockchainAddress swapAddress) {
    return LiquiditySwapLock.LiquiditySwapContractState.deserialize(
        blockchain.getContractState(swapAddress));
  }

  LiquiditySwapLock.LiquiditySwapContractState getSwapState() {
    return getSwapState(swapLockContractAddressAtoB());
  }

  Token.TokenState getTokenState(BlockchainAddress tokenAddress) {
    return Token.TokenState.deserialize(blockchain.getContractState(tokenAddress));
  }

  LiquiditySwapLock.VirtualState getVirtualSwapState(BlockchainAddress swapAddress) {
    return getSwapState(swapAddress).virtualState();
  }

  void instantSwap(
      BlockchainAddress swapper,
      BlockchainAddress swapContract,
      BlockchainAddress token,
      BigInteger amount,
      BigInteger minimumOut) {
    blockchain.sendAction(
        swapper, swapContract, LiquiditySwapLock.instantSwap(token, amount, minimumOut));
    assertLiquidityInvariant(swapContract);
  }

  void instantSwap(
      BlockchainAddress swapper,
      BlockchainAddress token,
      BigInteger amount,
      BigInteger minimumOut) {
    instantSwap(swapper, swapLockContractAddressAtoB(), token, amount, minimumOut);
  }

  BigInteger calculateReceivingAmount(
      BlockchainAddress swapContract, BlockchainAddress fromToken, BigInteger amount) {
    BigInteger a = calculateReceivingAmountNoLock(swapContract, fromToken, amount);
    BigInteger b = calculateReceivingAmountLocked(swapContract, fromToken, amount);
    return a.min(b);
  }

  BigInteger calculateReceivingAmount(BlockchainAddress fromToken, BigInteger amount) {
    return calculateReceivingAmount(swapLockContractAddressAtoB(), fromToken, amount);
  }

  BigInteger calculateReceivingAmountNoLock(
      BlockchainAddress swapContract, BlockchainAddress fromToken, BigInteger amount) {
    BigInteger oldFromAmount = getPoolAmountForToken(swapContract, fromToken);
    BigInteger oldToAmount =
        getPoolAmountForToken(swapContract, getMatchingToken(swapContract, fromToken));

    return deltaCalculation(amount, oldFromAmount, oldToAmount);
  }

  BigInteger calculateReceivingAmountLocked(
      BlockchainAddress swapContract, BlockchainAddress fromToken, BigInteger amount) {
    BigInteger oldFromAmount = getVirtualPoolAmountForToken(swapContract, fromToken);
    BigInteger oldToAmount =
        getVirtualPoolAmountForToken(swapContract, getMatchingToken(swapContract, fromToken));

    return deltaCalculation(amount, oldFromAmount, oldToAmount);
  }

  BigInteger calculateReceivingAmountLocked(BlockchainAddress fromToken, BigInteger amount) {
    return calculateReceivingAmountLocked(swapLockContractAddressAtoB(), fromToken, amount);
  }

  static BigInteger inverseDelta(BigInteger deltaOut, BigInteger fromAmount, BigInteger toAmount) {
    BigInteger noFee = BigInteger.valueOf(1000);
    BigInteger oppositeFee = noFee.subtract(BigInteger.valueOf(FEE));

    BigInteger num = noFee.multiply(deltaOut.multiply(fromAmount));
    BigInteger denom = deltaOut.multiply(oppositeFee).subtract(toAmount.multiply(oppositeFee));

    return num.divide(denom).negate();
  }

  BigInteger deltaCalculation(
      BigInteger deltaInAmount, BigInteger fromAmount, BigInteger toAmount) {
    BigInteger noFee = BigInteger.valueOf(1000);
    BigInteger oppositeFee = noFee.subtract(BigInteger.valueOf(FEE));

    BigInteger numerator = oppositeFee.multiply(deltaInAmount).multiply(toAmount);
    BigInteger denominator = noFee.multiply(fromAmount).add(oppositeFee.multiply(deltaInAmount));

    return numerator.divide(denominator);
  }

  private BigInteger getPoolAmountForToken(
      BlockchainAddress swapContract, BlockchainAddress token) {
    LiquiditySwapLock.TokenBalance b =
        swapUtil.getActualContractBalance(getSwapState(swapContract), swapContract);
    return deduceLeftOrRightToken(swapContract, token) ? b.aTokens() : b.bTokens();
  }

  private BigInteger getVirtualPoolAmountForToken(
      BlockchainAddress swapContract, BlockchainAddress token) {
    LiquiditySwapLock.TokenBalance b =
        swapUtil.getVirtualContractBalance(getSwapState(swapContract), swapContract);
    return deduceLeftOrRightToken(swapContract, token) ? b.aTokens() : b.bTokens();
  }

  private boolean deduceLeftOrRightToken(BlockchainAddress swapContract, BlockchainAddress token) {
    if (swapContract.equals(swapLockContractAddressAtoB())) {
      return token.equals(contractTokenA());
    } else if (swapContract.equals(swapLockContractAddressBtoC())) {
      return token.equals(contractTokenB());
    } else {
      return token.equals(contractTokenC());
    }
  }

  private BlockchainAddress getMatchingToken(
      BlockchainAddress swapContract, BlockchainAddress token) {
    if (swapContract.equals(swapLockContractAddressAtoB())) {
      return token.equals(contractTokenA()) ? contractTokenB() : contractTokenA();
    } else if (swapContract.equals(swapLockContractAddressBtoC())) {
      return token.equals(contractTokenB()) ? contractTokenC() : contractTokenB();
    } else {
      return token.equals(contractTokenC()) ? contractTokenD() : contractTokenC();
    }
  }

  void assertLiquidityInvariant(BlockchainAddress swapAddress) {
    LiquiditySwapLock.LiquiditySwapContractState swapState = getSwapState(swapAddress);

    LiquiditySwapLock.LockLiquidity lockLiquidityState = swapState.virtualState().lockLiquidity();

    BigInteger virtualLiquidityFromLocksA = ZERO;
    BigInteger virtualLiquidityFromLocksB = ZERO;
    for (LiquiditySwapLock.LiquidityLock lock : swapState.virtualState().locks().values()) {
      if (lock.tokensInOut().tokenIn().equals(new LiquiditySwapLock.Token.TokenA())) {
        virtualLiquidityFromLocksA = virtualLiquidityFromLocksA.add(lock.amountIn());
        virtualLiquidityFromLocksB = virtualLiquidityFromLocksB.subtract(lock.amountOut());
      } else if (lock.tokensInOut().tokenIn().equals(new LiquiditySwapLock.Token.TokenB())) {
        virtualLiquidityFromLocksA = virtualLiquidityFromLocksA.subtract(lock.amountOut());
        virtualLiquidityFromLocksB = virtualLiquidityFromLocksB.add(lock.amountIn());
      } else {
        assert false;
      }
    }

    LiquiditySwapLock.LockLiquidity lockLiquidityLocks =
        new LiquiditySwapLock.LockLiquidity(virtualLiquidityFromLocksA, virtualLiquidityFromLocksB);

    Assertions.assertThat(lockLiquidityState).isEqualTo(lockLiquidityLocks);
  }

  void depositIntoSwap(
      BlockchainAddress swapContract,
      BlockchainAddress sender,
      BlockchainAddress contractToken,
      BigInteger amount) {
    blockchain.sendAction(sender, contractToken, Token.approve(swapContract, amount));
    blockchain.sendAction(sender, swapContract, LiquiditySwapLock.deposit(contractToken, amount));
    assertLiquidityInvariant(swapContract);
  }

  void depositIntoSwap(
      BlockchainAddress sender, BlockchainAddress contractToken, BigInteger amount) {
    depositIntoSwap(swapLockContractAddressAtoB(), sender, contractToken, amount);
  }

  LiquiditySwapLock.LiquidityLockId lock(
      BlockchainAddress lockContract,
      BlockchainAddress locker,
      BlockchainAddress token,
      BigInteger inAmount,
      BigInteger minimumOut) {
    // Get the lock.
    blockchain.sendAction(
        locker, lockContract, LiquiditySwapLock.acquireSwapLock(token, inAmount, minimumOut));
    assertLiquidityInvariant(lockContract);

    // Get the id of the lock we just acquired, by checking for changes in state.
    Set<LiquiditySwapLock.LiquidityLockId> lockTracker = getCorrespondingLockTracker(lockContract);
    Set<LiquiditySwapLock.LiquidityLockId> newLocks =
        new HashSet<>(getVirtualSwapState(lockContract).locks().keySet());
    newLocks.removeAll(lockTracker);
    LiquiditySwapLock.LiquidityLockId lock = newLocks.iterator().next();
    lockTracker.add(lock);
    return lock;
  }

  LiquiditySwapLock.LiquidityLockId lock(
      BlockchainAddress locker,
      BlockchainAddress token,
      BigInteger inAmount,
      BigInteger minimumOut) {
    return lock(swapLockContractAddressAtoB(), locker, token, inAmount, minimumOut);
  }

  void executeLockSwap(
      BlockchainAddress sender,
      BlockchainAddress swapContract,
      LiquiditySwapLock.LiquidityLockId lockId) {
    blockchain.sendAction(sender, swapContract, LiquiditySwapLock.executeLockSwap(lockId));
    assertLiquidityInvariant(swapContract);
  }

  void executeLockSwap(BlockchainAddress sender, LiquiditySwapLock.LiquidityLockId lockId) {
    executeLockSwap(sender, swapLockContractAddressAtoB(), lockId);
  }

  private Set<LiquiditySwapLock.LiquidityLockId> getCorrespondingLockTracker(
      BlockchainAddress swapContract) {
    if (swapContract.equals(swapLockContractAddressAtoB())) {
      return lockTrackerAB;
    } else if (swapContract.equals(swapLockContractAddressBtoC())) {
      return lockTrackerBC;
    } else {
      return lockTrackerCD;
    }
  }

  BigInteger calculateEquivalentLiquidity(
      BlockchainAddress swapContract, BlockchainAddress inToken, BigInteger inAmount) {
    BigInteger tokenInPoll = getPoolAmountForToken(swapContract, inToken);
    BigInteger tokenOutPool =
        getPoolAmountForToken(swapContract, getMatchingToken(swapContract, inToken));

    return inAmount.multiply(tokenOutPool).divide(tokenInPoll).add(BigInteger.ONE);
  }

  BigInteger calculateMintedLiquidity(
      BlockchainAddress swapContract, BlockchainAddress inToken, BigInteger inAmount) {
    BigInteger tokenInPoll = getPoolAmountForToken(swapContract, inToken);
    BigInteger totalMintedLiquidity =
        swapUtil
            .getActualContractBalance(getSwapState(swapContract), swapContract)
            .liquidityTokens();

    return inAmount.multiply(totalMintedLiquidity).divide(tokenInPoll);
  }

  LiquiditySwapLock.TokenBalance calculateReclaim(
      BlockchainAddress swapContract, BigInteger reclaimAmount) {
    LiquiditySwapLock.TokenBalance contractBalance =
        swapUtil.getActualContractBalance(getSwapState(swapContract), swapContract);

    BigInteger reclaimA =
        contractBalance.aTokens().multiply(reclaimAmount).divide(contractBalance.liquidityTokens());
    BigInteger reclaimB =
        contractBalance.bTokens().multiply(reclaimAmount).divide(contractBalance.liquidityTokens());

    return new LiquiditySwapLock.TokenBalance(reclaimA, reclaimB, ZERO);
  }

  void assertThatLocksContain(
      Map<LiquiditySwapLock.LiquidityLockId, LiquiditySwapLock.LiquidityLock> locks,
      LiquiditySwapLock.LiquidityLockId lockId,
      LiquiditySwapLock.LiquidityLock lock) {
    Assertions.assertThat(locks.keySet()).contains(lockId);

    LiquiditySwapLock.LiquidityLock actualLock = locks.get(lockId);
    // Test every field except timestamp
    if (actualLock.amountIn().equals(lock.amountIn())
        && actualLock.amountOut().equals(lock.amountOut())
        && actualLock.tokensInOut().equals(lock.tokensInOut())
        && actualLock.owner().equals(lock.owner())) {
      return;
    }

    String assertionMsg =
        String.format(
            """
                                Expecting map:
                                  %s
                                to contain lock:
                                  %s
                                but the lock had different values:
                                  %s
                                """,
            locks, lock, actualLock);
    throw new AssertionError(assertionMsg);
  }

  LiquiditySwapLock.TokenBalance getActualContractBalance(BlockchainAddress swapContract) {
    return swapUtil.getActualContractBalance(getSwapState(swapContract), swapContract);
  }

  LiquiditySwapLock.TokenBalance getVirtualContractBalance(BlockchainAddress swapContract) {
    return swapUtil.getVirtualContractBalance(getSwapState(swapContract), swapContract);
  }
}
