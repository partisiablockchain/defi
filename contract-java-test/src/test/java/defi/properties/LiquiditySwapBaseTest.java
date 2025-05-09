package defi.properties;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwap;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.JunitContractTest;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;

/** {@link LiquiditySwap} testing. */
public abstract class LiquiditySwapBaseTest extends JunitContractTest {

  public BlockchainAddress creatorAddress;
  public BlockchainAddress swapContractAddress;

  /** State accessor for token balances. */
  protected static LiquiditySwap.TokenBalance getSwapDepositBalances(
      LiquiditySwap.LiquiditySwapContractState state, BlockchainAddress owner) {
    return state.tokenBalances().balances().get(owner);
  }

  /**
   * State accessor for deposits on liquidity swap contracts.
   *
   * @param state the state to assert.
   * @param tokenBalance the balance to from.
   * @param tokenAddress the token to get balance for.
   * @return the balance for the given token.
   */
  public static BigInteger swapDepositBalance(
      LiquiditySwap.LiquiditySwapContractState state,
      LiquiditySwap.TokenBalance tokenBalance,
      BlockchainAddress tokenAddress) {
    if (tokenBalance == null) {
      return BigInteger.ZERO;
    }
    if (tokenAddress.equals(state.liquidityPoolAddress())) {
      return tokenBalance.liquidityTokens();
    }
    if (tokenAddress.equals(state.tokenBalances().tokenAAddress())) {
      return tokenBalance.aTokens();
    }
    if (tokenAddress.equals(state.tokenBalances().tokenBAddress())) {
      return tokenBalance.bTokens();
    }
    return null;
  }

  /**
   * State accessor for deposits on liquidity swap contracts.
   *
   * <p>Token addresses are determined from the contract state. Liquidity tokens can be queried with
   * the liquidity swap contract's address.
   *
   * @param state Liquidity swap state.
   * @param owner Owner of the deposit
   * @param tokenAddress Addess of the token to determine balance for.
   * @return Token balance.
   */
  public static BigInteger swapDepositBalance(
      final LiquiditySwap.LiquiditySwapContractState state,
      final BlockchainAddress owner,
      final BlockchainAddress tokenAddress) {
    return swapDepositBalance(state, state.tokenBalances().balances().get(owner), tokenAddress);
  }

  /** State accessor for token balances. */
  protected final BigInteger swapDepositBalance(
      BlockchainAddress owner, BlockchainAddress tokenAddr) {
    final LiquiditySwap.LiquiditySwapContractState state =
        new LiquiditySwap(getStateClient(), swapContractAddress).getState();
    return swapDepositBalance(state, owner, tokenAddr);
  }

  /**
   * State validation.
   *
   * @param state the state to validate.
   */
  protected final void validateStateInvariants(
      final LiquiditySwap.LiquiditySwapContractState state) {
    // Check that no accounts are empty
    for (final var entry : state.tokenBalances().balances().getNextN(null, 1000)) {
      LiquiditySwap.TokenBalance tokenBalance = entry.getValue();
      final List<BigInteger> hasAnyTokens =
          List.of(tokenBalance.aTokens(), tokenBalance.bTokens(), tokenBalance.liquidityTokens());
      Assertions.assertThat(hasAnyTokens)
          .as("TokenBalance must contain at least one non-zero field")
          .anyMatch(n -> !BigInteger.ZERO.equals(n));
    }

    // Check that liquidity tokens are correctly tracked.
    final BigInteger allLiquidityTokensIncludingBuiltInSum =
        state.tokenBalances().balances().getNextN(null, 1000).stream()
            .map(Map.Entry::getValue)
            .map(LiquiditySwap.TokenBalance::liquidityTokens)
            .collect(Collectors.reducing(BigInteger.ZERO, BigInteger::add));
    final BigInteger liquidityTokenBuiltInSum =
        swapDepositBalance(state, state.liquidityPoolAddress(), state.liquidityPoolAddress());

    Assertions.assertThat(liquidityTokenBuiltInSum)
        .as("Liquidity-token built-in sum must be equal to all other liquidity token balances")
        .isEqualTo(allLiquidityTokensIncludingBuiltInSum.subtract(liquidityTokenBuiltInSum));

    // Check that initialized pools are consistent.
    final LiquiditySwap.TokenBalance tokenBalance =
        getSwapDepositBalances(state, state.liquidityPoolAddress());
    final List<BigInteger> hasAnyTokens =
        List.of(tokenBalance.aTokens(), tokenBalance.bTokens(), tokenBalance.liquidityTokens());
    final boolean expectedZeroes = hasAnyTokens.contains(BigInteger.ZERO);
    if (expectedZeroes) {
      Assertions.assertThat(hasAnyTokens)
          .containsExactly(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
    } else {
      Assertions.assertThat(hasAnyTokens).doesNotContain(BigInteger.ZERO);
    }
  }

  /** State validation. */
  protected final void validateStateInvariants() {
    final LiquiditySwap.LiquiditySwapContractState state =
        new LiquiditySwap(getStateClient(), swapContractAddress).getState();
    validateStateInvariants(state);
  }

  /**
   * Get the exchange rate.
   *
   * @param precision the precision the exchange have.
   * @return the exchange rate.
   */
  protected final BigInteger exchangeRate(int precision) {
    final LiquiditySwap.LiquiditySwapContractState state =
        new LiquiditySwap(getStateClient(), swapContractAddress).getState();
    final var aTokens =
        swapDepositBalance(
            state, state.liquidityPoolAddress(), state.tokenBalances().tokenAAddress());
    final var bTokens =
        swapDepositBalance(
            state, state.liquidityPoolAddress(), state.tokenBalances().tokenBAddress());
    return BigInteger.TEN.pow(precision).multiply(aTokens).divide(bTokens);
  }

  /**
   * State modifier for depositing, with automatic transfer from the token {@link creatorAddress}.
   */
  protected final void depositAmount(
      List<BlockchainAddress> senders, BlockchainAddress contractToken, BigInteger amount) {
    final var transfers = senders.stream().map(s -> new Token.Transfer(s, amount)).toList();
    blockchain.sendAction(creatorAddress, contractToken, Token.bulkTransfer(transfers));

    for (final BlockchainAddress sender : senders) {
      blockchain.sendAction(sender, contractToken, Token.approve(swapContractAddress, amount));
      blockchain.sendAction(
          sender, swapContractAddress, LiquiditySwap.deposit(contractToken, amount));
    }
  }

  /**
   * Swap inputToken for the outputToken, with a minimal amount of expected output.
   *
   * @param swapper Account of user that is swapping.
   * @param tokenInput Input token.
   * @param amountInput Amount of input token to deposit.
   */
  protected final void swap(
      BlockchainAddress swapper, BlockchainAddress tokenInput, BigInteger amountInput) {
    swap(swapper, tokenInput, amountInput, BigInteger.ONE);
  }

  /**
   * Swap inputToken for the outputToken, with a given amount of expected output.
   *
   * @param swapper Account of user that is swapping.
   * @param tokenInput Input token.
   * @param amountInput Amount of input token to deposit.
   * @param amountOutputMinimum Amount of output token that must be output before allowing the swap.
   */
  protected final void swap(
      BlockchainAddress swapper,
      BlockchainAddress tokenInput,
      BigInteger amountInput,
      BigInteger amountOutputMinimum) {
    final byte[] rpc = LiquiditySwap.swap(tokenInput, amountInput, amountOutputMinimum);
    blockchain.sendAction(swapper, swapContractAddress, rpc);
  }

  /**
   * Assert that the {@link exchangeRate} is currently some amount.
   *
   * @param precision Amount of precision in the exchange rate.
   * @param expectedExchangeRate The expected exchange rate.
   */
  protected final void validateExchangeRate(int precision, BigInteger expectedExchangeRate) {
    Assertions.assertThat(exchangeRate(precision))
        .as("Exchange rate")
        .isEqualTo(expectedExchangeRate);
  }

  /**
   * Assert that the given user has a specific amount of balance deposited into the swap contract.
   *
   * @param swapper Account of the user.
   * @param token Token to check valance for.
   * @param amount The expected amount of token balance.
   */
  protected final void validateBalance(
      BlockchainAddress swapper, BlockchainAddress token, BigInteger amount) {
    Assertions.assertThat(swapDepositBalance(swapper, token))
        .as("Balance of user: " + swapper)
        .isEqualTo(amount);
  }
}
