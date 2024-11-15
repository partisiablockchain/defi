package defi;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwapLock;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.codegenlib.AvlTreeMap;
import com.partisiablockchain.language.junit.JunitContractTest;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;

/** Utility for {@link LiquiditySwapLock} testing. */
@CheckReturnValue
public abstract class LiquiditySwapLockBaseTest extends JunitContractTest {
  abstract BlockchainAddress swapLockContractAddressAtoB();

  abstract BlockchainAddress swapLockContractAddressBtoC();

  abstract BlockchainAddress contractTokenA();

  abstract BlockchainAddress contractTokenB();

  abstract BlockchainAddress contractTokenC();

  abstract BlockchainAddress contractTokenD();

  static final BigInteger ZERO = BigInteger.ZERO;

  static final short FEE = 3;

  static final LiquiditySwapTestingUtility swapUtil = new LiquiditySwapTestingUtility();

  LiquiditySwapLock.LiquiditySwapContractState getSwapState(BlockchainAddress swapAddress) {
    return new LiquiditySwapLock(getStateClient(), swapAddress).getState();
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
    final BigInteger liquidity1 = getContractLiquidityConstantAfterLocksExecuted(swapContract);

    blockchain.sendAction(
        swapper, swapContract, LiquiditySwapLock.instantSwap(token, amount, minimumOut));

    final BigInteger liquidity2 = getContractLiquidityConstantAfterLocksExecuted(swapContract);

    Assertions.assertThat(liquidity2)
        .as("Liquidity constant must never decrease due to an instant swap.")
        .isGreaterThanOrEqualTo(liquidity1);
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

    LiquiditySwapLock.LockSums lockSumsAsTrackedInState = swapState.virtualState().lockSums();

    BigInteger lockSumDerivedFromLockA = ZERO;
    BigInteger lockSumDerivedFromLockB = ZERO;
    for (LiquiditySwapLock.LiquidityLock lock : getLocks(swapState)) {
      if (lock.tokensInOut().tokenIn().equals(new LiquiditySwapLock.DepositTokenTokenA())) {
        lockSumDerivedFromLockA = lockSumDerivedFromLockA.add(lock.amountIn());
      } else if (lock.tokensInOut().tokenIn().equals(new LiquiditySwapLock.DepositTokenTokenB())) {
        lockSumDerivedFromLockB = lockSumDerivedFromLockB.add(lock.amountIn());
      } else {
        assert false;
      }
    }

    LiquiditySwapLock.LockSums lockSumsDerivedFromLocks =
        new LiquiditySwapLock.LockSums(lockSumDerivedFromLockA, lockSumDerivedFromLockB);

    Assertions.assertThat(lockSumsAsTrackedInState)
        .as(
            "Invariant: Sum locks derived from state must be equal to sum of locks maintained in"
                + " the state")
        .isEqualTo(lockSumsDerivedFromLocks);
  }

  List<LiquiditySwapLock.LiquidityLock> getLocks(BlockchainAddress swapAddress) {
    return getLocks(getSwapState(swapAddress));
  }

  List<LiquiditySwapLock.LiquidityLock> getLocks(
      LiquiditySwapLock.LiquiditySwapContractState swapState) {
    return swapState.virtualState().locks().getNextN(null, 1000).stream()
        .map(Map.Entry::getValue)
        .toList();
  }

  Set<LiquiditySwapLock.LiquidityLockId> getLockIds(BlockchainAddress swapAddress) {
    return getVirtualSwapState(swapAddress).locks().getNextN(null, 1000).stream()
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
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

  @CanIgnoreReturnValue
  LiquiditySwapLock.LiquidityLockId acquireLock(
      BlockchainAddress lockContract,
      BlockchainAddress locker,
      BlockchainAddress token,
      BigInteger inAmount,
      BigInteger minimumOut) {

    final BigInteger liquidity1 = getContractLiquidityConstantAfterLocksExecuted(lockContract);

    final Set<LiquiditySwapLock.LiquidityLockId> existingLocks = getLockIds(lockContract);

    // Get the lock.
    blockchain.sendAction(
        locker, lockContract, LiquiditySwapLock.acquireSwapLock(token, inAmount, minimumOut));
    assertLiquidityInvariant(lockContract);

    final BigInteger liquidity2 = getContractLiquidityConstantAfterLocksExecuted(lockContract);

    Assertions.assertThat(liquidity2)
        .as("Liquidity constant must never decrease due to acquiring a lock.")
        .isGreaterThanOrEqualTo(liquidity1);

    // Get the id of the lock we just acquired, by checking for changes in state.
    final Set<LiquiditySwapLock.LiquidityLockId> newLocks = getLockIds(lockContract);
    newLocks.removeAll(existingLocks);

    Assertions.assertThat(newLocks)
        .as("The set of new locks in the state must be precisely one")
        .hasSize(1);

    return newLocks.iterator().next();
  }

  @CanIgnoreReturnValue
  LiquiditySwapLock.LiquidityLockId acquireLock(
      BlockchainAddress locker,
      BlockchainAddress token,
      BigInteger inAmount,
      BigInteger minimumOut) {
    return acquireLock(swapLockContractAddressAtoB(), locker, token, inAmount, minimumOut);
  }

  void cancelLock(
      BlockchainAddress lockContract,
      BlockchainAddress sender,
      LiquiditySwapLock.LiquidityLockId lockId) {
    blockchain.sendAction(sender, lockContract, LiquiditySwapLock.cancelLock(lockId));

    // Check other invariants
    assertLiquidityInvariant(lockContract);
  }

  void executeLockSwap(
      BlockchainAddress sender,
      BlockchainAddress swapContract,
      LiquiditySwapLock.LiquidityLockId lockId) {

    final BigInteger liquidity1 = getContractLiquidityConstantAfterLocksExecuted(swapContract);

    blockchain.sendAction(sender, swapContract, LiquiditySwapLock.executeLockSwap(lockId));

    final BigInteger liquidity2 = getContractLiquidityConstantAfterLocksExecuted(swapContract);

    Assertions.assertThat(liquidity2)
        .as("Liquidity constant must never decrease due to executing a swap lock.")
        .isGreaterThanOrEqualTo(liquidity1);

    assertLiquidityInvariant(swapContract);
  }

  void executeLockSwap(BlockchainAddress sender, LiquiditySwapLock.LiquidityLockId lockId) {
    executeLockSwap(sender, swapLockContractAddressAtoB(), lockId);
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
      AvlTreeMap<LiquiditySwapLock.LiquidityLockId, LiquiditySwapLock.LiquidityLock> locks,
      LiquiditySwapLock.LiquidityLockId lockId,
      LiquiditySwapLock.LiquidityLock lock) {

    LiquiditySwapLock.LiquidityLock actualLock = locks.get(lockId);
    Assertions.assertThat(actualLock).isNotNull();
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
            locks.getNextN(null, 1000), lock, actualLock);
    throw new AssertionError(assertionMsg);
  }

  BigInteger getContractLiquidityConstantAfterLocksExecuted(BlockchainAddress swapContract) {
    final var liq = getActualContractBalance(swapContract);
    if (liq == null) {
      return BigInteger.ZERO;
    }

    BigInteger a = liq.aTokens();
    BigInteger b = liq.bTokens();

    for (LiquiditySwapLock.LiquidityLock lock : getLocks(swapContract)) {
      if (lock.tokensInOut().tokenIn().equals(new LiquiditySwapLock.DepositTokenTokenA())) {
        a = a.add(lock.amountIn());
        b = b.subtract(lock.amountOut());
      } else if (lock.tokensInOut().tokenIn().equals(new LiquiditySwapLock.DepositTokenTokenB())) {
        b = b.add(lock.amountIn());
        a = a.subtract(lock.amountOut());
      } else {
        assert false;
      }
    }

    return a.multiply(b);
  }

  LiquiditySwapLock.TokenBalance getActualContractBalance(BlockchainAddress swapContract) {
    return swapUtil.getActualContractBalance(getSwapState(swapContract), swapContract);
  }

  LiquiditySwapLock.TokenBalance getVirtualContractBalance(BlockchainAddress swapContract) {
    return swapUtil.getVirtualContractBalance(getSwapState(swapContract), swapContract);
  }
}
