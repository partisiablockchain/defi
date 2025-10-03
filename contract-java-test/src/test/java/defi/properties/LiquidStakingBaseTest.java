package defi.properties;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.errorprone.annotations.CheckReturnValue;
import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquidStaking;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.JunitContractTest;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Provides utility for {@link LiquidStaking} smart contract. */
@CheckReturnValue
public abstract class LiquidStakingBaseTest extends JunitContractTest {

  public static final int STAKE_TOKEN_SUPPLY = 100000000;
  public static final long LENGTH_OF_COOLDOWN_PERIOD = 100;
  public static final long LENGTH_OF_REDEEM_PERIOD = 100;

  protected static final int USER_1_FUNDS = 500;
  protected static final int USER_2_FUNDS = 1000;
  protected static final int USER_3_FUNDS = 200;

  public BlockchainAddress stakeTokenOwner;
  public BlockchainAddress stakeTokenAddress;
  public BlockchainAddress liquidStakingOwner;
  public BlockchainAddress liquidStakingAddress;
  public BlockchainAddress user1;
  public BlockchainAddress user2;
  public BlockchainAddress user3;
  public BlockchainAddress stakingResponsible;
  public BlockchainAddress liquidStakingAdministrator;

  /**
   * Helper function to wait for the redeem period to start. Redeem period: from
   * pendingUnlock.cooldown_ends_at() to pendingUnlocks.expires_at()
   */
  protected final void waitForRedeemPeriod() {
    blockchain.waitForBlockProductionTime(
        blockchain.getBlockProductionTime() + LENGTH_OF_COOLDOWN_PERIOD + 5);
  }

  /**
   * Helper function to wait for the redeem period to end. Expired: after
   * pendingUnlocks.expires_at()
   */
  protected final void waitForRedeemPeriodToExpire() {
    blockchain.waitForBlockProductionTime(
        blockchain.getBlockProductionTime()
            + LENGTH_OF_COOLDOWN_PERIOD
            + LENGTH_OF_REDEEM_PERIOD
            + 5);
  }

  /**
   * Helper function for making submit RPC and invoking the submit action.
   *
   * @param account The account that invokes the action.
   * @param amount The amount of stake tokens to submit.
   */
  protected final void submit(BlockchainAddress account, int amount) {
    byte[] rpc = LiquidStaking.submit(BigInteger.valueOf(amount));
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making withdraw RPC and invoking the withdrawal action.
   *
   * @param account The account that invokes the action.
   * @param amount The amount of stake tokens to withdraw.
   */
  protected final void withdraw(BlockchainAddress account, int amount) {
    byte[] rpc = LiquidStaking.withdraw(BigInteger.valueOf(amount));
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making deposit RPC and invoking the deposit action.
   *
   * @param account The account that invokes the action.
   * @param amount The amount of stake tokens to deposit.
   */
  protected final void deposit(BlockchainAddress account, int amount) {
    byte[] rpc = LiquidStaking.deposit(BigInteger.valueOf(amount));
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making accrue rewards RPC and invoking the accrue rewards action.
   *
   * @param account The account that invokes the action.
   * @param amount The amount of stake tokens of stake tokens in the reward that should be accrued
   *     to the contract.
   */
  protected final void accrueRewards(BlockchainAddress account, int amount) {
    provideFundsToAccount(stakingResponsible, amount);
    approveTransferToLiquidStakingContract(stakingResponsible, amount);

    byte[] rpc = LiquidStaking.accrueRewards(BigInteger.valueOf(amount));
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making request unlock RPC and invoking the request unlock action.
   *
   * @param account The account that invokes the action.
   * @param amount The amount of liquid tokens to unlock.
   */
  protected final void requestUnlock(BlockchainAddress account, int amount) {
    byte[] rpc = LiquidStaking.requestUnlock(BigInteger.valueOf(amount));
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making redeem RPC and invoking the redeem action.
   *
   * @param account The account that invokes the action.
   */
  protected final void redeem(BlockchainAddress account) {
    byte[] rpc = LiquidStaking.redeem();
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making change buy in RPC and invoking the change buy in action.
   *
   * @param account The account that invokes the action.
   * @param buyInPercentage The percentage the buy in should be changed to.
   */
  protected final void changeBuyIn(BlockchainAddress account, int buyInPercentage) {
    byte[] rpc = LiquidStaking.changeBuyIn(BigInteger.valueOf(buyInPercentage));
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making disable buy in RPC and invoking the disable buy in action.
   *
   * @param account The account that invokes the action.
   */
  protected final void disableBuyIn(BlockchainAddress account) {
    byte[] rpc = LiquidStaking.disableBuyIn();
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making cleanup of pending unlocks RPC and invoking the cleanup pending
   * unlocks in action.
   *
   * @param account The account that invokes the action.
   */
  protected final void cleanUpPendingUnlocks(BlockchainAddress account) {
    byte[] redeemRpc = LiquidStaking.cleanUpPendingUnlocks();
    blockchain.sendAction(account, liquidStakingAddress, redeemRpc);
  }

  /**
   * Helper function for making cancellation of pending unlocks.
   *
   * @param account The account that invokes the action.
   * @param pendingUnlockId The specific pending unlocks ID.
   */
  protected final void cancelPendingUnlock(BlockchainAddress account, int pendingUnlockId) {
    byte[] cancelRpc = LiquidStaking.cancelPendingUnlock(pendingUnlockId);
    blockchain.sendAction(account, liquidStakingAddress, cancelRpc);
  }

  /**
   * Retrieve the liquid token balance for a user.
   *
   * @param user A user of the liquid staking contract.
   * @return The liquid token balance for the given user.
   */
  protected final BigInteger getLiquidBalance(final BlockchainAddress user) {
    return getLiquidStakingState().liquidTokenState().balances().get(user);
  }

  /**
   * Get state of the {@link LiquidStaking} contract.
   *
   * @return state of the {@link LiquidStaking} contract. Not nullable.
   */
  protected final LiquidStaking.LiquidStakingState getLiquidStakingState() {
    return getLiquidStakingClient().getState();
  }

  /** Get client for {@link LiquidStaking} contract. */
  protected final LiquidStaking getLiquidStakingClient() {
    return new LiquidStaking(getStateClient(), liquidStakingAddress);
  }

  /**
   * Calculate the sum of all balances in the liquid balance map.
   *
   * @return The sum of all liquid token balances.
   */
  protected final BigInteger getLiquidBalanceSum() {
    return getLiquidStakingState().liquidTokenState().balances().getNextN(null, 100).stream()
        .map(Map.Entry::getValue)
        .reduce(BigInteger.ZERO, BigInteger::add);
  }

  /**
   * Retrieve the buy in tokens for a user.
   *
   * @param user A user of the liquid staking contract.
   * @return The amount of tokens locked by the buy in for the given user.
   */
  protected final BigInteger getBuyInTokens(final BlockchainAddress user) {
    return getLiquidStakingState().buyInTokens().get(user);
  }

  /**
   * Calculate the total sum of all locked buy in tokens.
   *
   * @return The sum of all tokens locked by the buy in.
   */
  protected final BigInteger getBuyInTokensSum() {
    return getLiquidStakingState().buyInTokens().getNextN(null, 100).stream()
        .map(Map.Entry::getValue)
        .reduce(BigInteger.ZERO, BigInteger::add);
  }

  /**
   * Retrieve the pending unlocks for a user.
   *
   * @param user A user of the liquid staking contract.
   * @return The list of all pending unlocks associated the user.
   */
  protected final List<LiquidStaking.PendingUnlock> getPendingUnlocks(
      final BlockchainAddress user) {
    return getLiquidStakingState().pendingUnlocks().get(user);
  }

  /**
   * Retrieve the amount of tokens in the stake token pool.
   *
   * @return The amount of stake tokens in the pool.
   */
  protected final BigInteger totalPoolStakeToken() {
    return getLiquidStakingState().totalPoolStakeToken();
  }

  /**
   * Retrieve the amount of tokens in the liquid token pool.
   *
   * @return The amount of liquid tokens in the pool.
   */
  protected final BigInteger totalPoolLiquidToken() {
    return getLiquidStakingState().totalPoolLiquid();
  }

  /** Assert that the contract is in an initial state before testing. */
  protected final void assertInitialLiquidStakingState() {
    LiquidStaking.LiquidStakingState initialState = getLiquidStakingState();
    assertThat(initialState.totalPoolStakeToken()).isZero();
    assertThat(initialState.totalPoolLiquid()).isZero();
  }

  /**
   * Assert the state invariants.
   *
   * <ul>
   *   <li>The sum of all balances in the liquid ledger is equal to 'total liquid token pool'.
   *   <li>The 'total stake token pool' is always greater than or equal to the 'total liquid token
   *       pool'.
   *   <li>The property 'stake token balance' is equal to the balance (for this contract) on the
   *       stake token.
   *   <li>If buy in is disabled, then there are no locked buy in tokens.
   * </ul>
   */
  protected final void assertLiquidStakingStateInvariant() {
    LiquidStaking.LiquidStakingState liquidStakingState = getLiquidStakingState();

    assertThat(getLiquidBalanceSum()).isEqualTo(totalPoolLiquidToken());
    assertThat(totalPoolStakeToken()).isGreaterThanOrEqualTo(totalPoolLiquidToken());
    assertTokenStateForLiquidStakingContract(
        liquidStakingState.stakeTokenBalance().intValueExact());

    if (!liquidStakingState.buyInEnabled()) {
      assertThat(getBuyInTokensSum()).isZero();
    }
  }

  /**
   * Assert that the contract has the expected amount of tokens on the stake token contract.
   *
   * @param balance The expected token balance.
   */
  protected final void assertTokenStateForLiquidStakingContract(int balance) {
    Token.TokenState tokenState = getTokenContractState();
    if (balance == 0) {
      assertThat(tokenState.balances().get(liquidStakingAddress)).isNull();
    } else {
      assertThat(tokenState.balances().get(liquidStakingAddress)).isEqualTo(balance);
    }
  }

  /**
   * Assert that the user has the expected amount of tokens on the stake token contract.
   *
   * @param user The address of the user whose balance we want to assert.
   * @param balance The expected token balance of the user.
   * @param allowedAmount The expected allowance the user has bestowed the liquid staking contract.
   */
  protected final void assertTokenState(BlockchainAddress user, int balance, int allowedAmount) {
    Token.TokenState tokenState = getTokenContractState();
    assertThat(tokenState.balances().get(user)).isEqualTo(balance);

    if (allowedAmount == 0) {
      assertThat(tokenState.allowed().containsKey(user)).isFalse();
    } else {
      assertThat(tokenState.allowed().get(user).get(liquidStakingAddress)).isEqualTo(allowedAmount);
    }
  }

  /**
   * Assert that the pools on the liquid staking contract has the correct amount of tokens.
   *
   * @param stakeTokenPoolAmount The expected amount of tokens in the Stake Token Pool.
   * @param liquidPoolAmount The expected amount of tokens in the Liquid Token Pool.
   */
  protected final void assertPoolAmounts(int stakeTokenPoolAmount, int liquidPoolAmount) {
    assertThat(totalPoolStakeToken()).isEqualTo(stakeTokenPoolAmount);
    assertThat(totalPoolLiquidToken()).isEqualTo(liquidPoolAmount);
  }

  /**
   * Assert that the buy in percentage is as expected.
   *
   * @param buyInEnabled A flag indicating whether we expect the buy in to be enabled or disabled.
   * @param buyInPercentage The expected buy in percentage.
   */
  protected final void assertBuyInPercentage(boolean buyInEnabled, int buyInPercentage) {
    LiquidStaking.LiquidStakingState liquidStakingState = getLiquidStakingState();
    assertThat(liquidStakingState.buyInPercentage()).isEqualTo(buyInPercentage);
    assertThat(liquidStakingState.buyInEnabled()).isEqualTo(buyInEnabled);
  }

  /**
   * Invokes the liquid staking contract to get an initial state.
   *
   * @param initialSubmit The amount of stake tokens initial submitted by user1 to the contract.
   * @param initialReward The amount of stake tokens that initially has been rewarded and accrued to
   *     the contract.
   * @param initialWithdraw The amount of stake tokens the staking responsible initially has
   *     withdrawn from the contract.
   * @param buyInPercentageAfterInitialSubmit The buy in percentage after the initial submit.
   */
  protected final void initialSetupWithAsserts(
      int initialSubmit,
      int initialReward,
      int initialWithdraw,
      int buyInPercentageAfterInitialSubmit) {
    initialSetupWithoutAsserts(
        initialSubmit, initialReward, initialWithdraw, buyInPercentageAfterInitialSubmit);
    assertBuyInPercentage(true, buyInPercentageAfterInitialSubmit);
    assertThat(totalPoolStakeToken()).isEqualTo(initialSubmit + initialReward);
    assertThat(totalPoolLiquidToken()).isEqualTo(initialSubmit);
    assertLiquidStakingStateInvariant();
  }

  /**
   * Invokes the liquid staking contract to get an initial state.
   *
   * @param initialSubmit The amount of stake tokens initial submitted by user1 to the contract.
   * @param initialReward The amount of stake tokens that initially has been rewarded and accrued to
   *     the contract.
   * @param initialWithdraw The amount of stake tokens the staking responsible initially has
   *     withdrawn from the contract.
   * @param buyInPercentageAfterInitialSubmit The buy in percentage after the initial submit.
   */
  protected final void initialSetupWithoutAsserts(
      int initialSubmit,
      int initialReward,
      int initialWithdraw,
      int buyInPercentageAfterInitialSubmit) {
    if (initialSubmit != 0) {
      submit(user1, initialSubmit);
    }
    if (initialReward != 0) {
      accrueRewards(stakingResponsible, initialReward);
    }
    if (initialWithdraw != 0) {
      withdraw(stakingResponsible, initialWithdraw);
      approveTransferToLiquidStakingContract(stakingResponsible, initialWithdraw);
    }

    if (buyInPercentageAfterInitialSubmit != 0) {
      changeBuyIn(liquidStakingAdministrator, buyInPercentageAfterInitialSubmit);
    }
  }

  /**
   * Retrieve the state of the {@link Token}.
   *
   * @return The state of the {@link Token} contract.
   */
  protected final Token.TokenState getTokenContractState() {
    return new Token(getStateClient(), stakeTokenAddress).getState();
  }

  /**
   * Deploy staking {@link Token} contract.
   *
   * @return The blockchain address of the token contract.
   */
  protected final BlockchainAddress deployStakeTokenContract(ContractBytes contractBytesToken) {
    String tokenName = "Coin";
    String tokenSymbol = "Test Coin";

    byte[] tokenInitRpc =
        Token.initialize(tokenName, tokenSymbol, (byte) 18, BigInteger.valueOf(STAKE_TOKEN_SUPPLY));
    this.stakeTokenAddress =
        blockchain.deployContract(stakeTokenOwner, contractBytesToken, tokenInitRpc);

    final Token.TokenState state = getTokenContractState();
    assertThat(state.name()).isEqualTo(tokenName);
    assertThat(state.symbol()).isEqualTo(tokenSymbol);
    assertThat(state.totalSupply()).isEqualTo(STAKE_TOKEN_SUPPLY);
    return this.stakeTokenAddress;
  }

  /**
   * Deploy the liquid staking contract.
   *
   * @return The blockchain address of the liquid staking contract.
   */
  protected final BlockchainAddress deployLiquidStakingContract(
      ContractBytes contractBytesLiquidStaking) {
    byte[] initRpc =
        LiquidStaking.initialize(
            stakeTokenAddress,
            stakingResponsible,
            liquidStakingAdministrator,
            LENGTH_OF_COOLDOWN_PERIOD,
            LENGTH_OF_REDEEM_PERIOD,
            BigInteger.ZERO,
            "Liquid Staking Token",
            "LST",
            (byte) 4);
    return blockchain.deployContract(liquidStakingOwner, contractBytesLiquidStaking, initRpc);
  }

  /**
   * Transfer funds to the user.
   *
   * @param account The user account that receives the tokens.
   * @param amount The amount of tokens the user receives.
   */
  protected final void provideFundsToAccount(BlockchainAddress account, int amount) {
    final byte[] rpc = Token.transfer(account, BigInteger.valueOf(amount));
    blockchain.sendAction(stakeTokenOwner, stakeTokenAddress, rpc);
  }

  /**
   * Allow the liquid staking contract to transfer tokens on behalf of the token owner.
   *
   * @param account Owner of the tokens that approves that another user can transfer tokens.
   * @param amount The amount of tokens to be approved.
   */
  protected final void approveTransferToLiquidStakingContract(
      BlockchainAddress account, int amount) {
    final byte[] rpc = Token.approve(liquidStakingAddress, BigInteger.valueOf(amount));
    blockchain.sendAction(account, stakeTokenAddress, rpc);
  }

  /**
   * Initialize users and deploy {@link Token} and {@link LiquidStaking} contracts.
   *
   * <p><b>Important</b>: Must not inspect the state of the contracts, so it can be used with
   * outdated contract versions.
   *
   * @param contractBytesLiquidStaking Contract bytes to initialize the {@link LiquidStaking}
   *     contract.
   * @param contractBytesToken Contract bytes to initialize the {@link Token} contract.
   */
  protected final void initializeUsersAndDeployTokenAndLiquidStakingContract(
      ContractBytes contractBytesLiquidStaking, ContractBytes contractBytesToken) {
    stakeTokenOwner = blockchain.newAccount(2);
    liquidStakingOwner = blockchain.newAccount(3);
    user1 = blockchain.newAccount(4);
    user2 = blockchain.newAccount(5);
    user3 = blockchain.newAccount(6);
    stakingResponsible = blockchain.newAccount(7);
    liquidStakingAdministrator = blockchain.newAccount(8);

    stakeTokenAddress = deployStakeTokenContract(contractBytesToken);

    provideFundsToAccount(user1, USER_1_FUNDS);
    provideFundsToAccount(user2, USER_2_FUNDS);
    provideFundsToAccount(user3, USER_3_FUNDS);

    liquidStakingAddress = deployLiquidStakingContract(contractBytesLiquidStaking);

    approveTransferToLiquidStakingContract(user1, USER_1_FUNDS);
    approveTransferToLiquidStakingContract(user2, USER_2_FUNDS);
  }
}
