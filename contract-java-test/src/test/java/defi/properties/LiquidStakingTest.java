package defi.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.errorprone.annotations.CheckReturnValue;
import com.partisiablockchain.language.abicodegen.LiquidStaking;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import java.math.BigInteger;
import java.util.Objects;

/** Testing for the {@link LiquidStaking} smart contract. */
@CheckReturnValue
public abstract class LiquidStakingTest extends LiquidStakingBaseTest {

  /** Definition of the {@link LiquidStaking} contract under test. */
  private final ContractBytes contractBytesLiquidStaking;

  /** Definition of the {@link Token} contract under test. */
  private final ContractBytes contractBytesToken;

  /**
   * Define {@link LiquidStakingTest} with associated contracts.
   *
   * @param contractBytesLiquidStaking Contract bytes to initialize the {@link LiquidStaking}
   *     contract.
   * @param contractBytesToken Contract bytes to initialize the {@link Token} contract.
   */
  public LiquidStakingTest(
      ContractBytes contractBytesLiquidStaking, ContractBytes contractBytesToken) {
    this.contractBytesLiquidStaking = Objects.requireNonNull(contractBytesLiquidStaking);
    this.contractBytesToken = Objects.requireNonNull(contractBytesToken);
  }

  /**
   * Setup for other tests. Instantiates the accounts of all the contract owners, deploys the
   * contracts, and transfers and approves tokens for the users and the liquid staking contract
   */
  @ContractTest
  void setup() {
    initializeUsersAndDeployTokenAndLiquidStakingContract(
        contractBytesLiquidStaking, contractBytesToken);

    assertInitialLiquidStakingState();
    assertTokenState(user1, USER_1_FUNDS, USER_1_FUNDS);
    assertTokenState(user2, USER_2_FUNDS, USER_2_FUNDS);
    assertTokenState(user3, USER_3_FUNDS, 0);
  }

  /**
   * A user submitting tokens for liquid staking before any rewards has been accrued happens at
   * exchange rate 1:1.
   */
  @ContractTest(previous = "setup")
  void initialSubmit() {
    assertInitialLiquidStakingState();

    submit(user1, 100);

    assertPoolAmounts(100, 100);
    assertThat(getLiquidBalance(user1)).isEqualTo(100);
    assertTokenState(user1, USER_1_FUNDS - 100, USER_1_FUNDS - 100);
    assertLiquidStakingStateInvariant();
  }

  /** A user cannot submit zero tokens for liquid staking. */
  @ContractTest(previous = "setup")
  void cannotSubmitZeroTokens() {
    initialSetupWithAsserts(100, 0, 0, 0);

    assertThatThrownBy(() -> submit(user1, 0))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot submit zero tokens for liquid staking");

    assertThat(totalPoolStakeToken()).isEqualTo(100);
    assertThat(totalPoolLiquidToken()).isEqualTo(100);
    assertThat(getLiquidBalance(user1)).isEqualTo(100);
    assertLiquidStakingStateInvariant();
  }

  /** A user can increase the amount of liquid staking by submitting tokens multiple times. */
  @ContractTest(previous = "setup")
  void userSubmitsSeveralTimes() {
    assertInitialLiquidStakingState();

    submit(user1, 50);
    submit(user1, 40);

    assertPoolAmounts(90, 90);
    assertThat(getLiquidBalance(user1)).isEqualTo(90);
    assertTokenState(user1, USER_1_FUNDS - 90, USER_1_FUNDS - 90);
    assertLiquidStakingStateInvariant();
  }

  /** A user can submit tokens to a shared liquid staking pool. */
  @ContractTest(previous = "setup")
  void usersSubmitToSharedPool() {
    assertInitialLiquidStakingState();

    submit(user1, 25);
    submit(user2, 30);

    assertPoolAmounts(55, 55);
    assertLiquidStakingStateInvariant();
  }

  /**
   * A user submitting tokens for liquid staking get his own individual liquid token balance. The
   * actions of one user cannot influence another user's balance.
   */
  @ContractTest(previous = "setup")
  void userSubmitsAreIndependent() {
    assertInitialLiquidStakingState();

    submit(user1, 50);
    submit(user2, 30);

    assertThat(getLiquidBalance(user1)).isEqualTo(50);
    assertThat(getLiquidBalance(user2)).isEqualTo(30);

    assertTokenState(user1, USER_1_FUNDS - 50, USER_1_FUNDS - 50);
    assertTokenState(user2, USER_2_FUNDS - 30, USER_2_FUNDS - 30);
    assertLiquidStakingStateInvariant();
  }

  /** A user cannot submit more tokens than she owns. */
  @ContractTest(previous = "setup")
  void cannotSubmitMoreThanUserOwns() {
    assertInitialLiquidStakingState();

    assertThatThrownBy(() -> submit(user1, USER_1_FUNDS + 1))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient Test Coin allowance for transfer_from! Allowed 500, but trying to"
                + " transfer 501 (in minimal units)");

    assertInitialLiquidStakingState();
  }

  /** A user cannot submit tokens that are not approved. */
  @ContractTest(previous = "setup")
  void cannotSubmitWithoutApprove() {
    assertInitialLiquidStakingState();

    assertThatThrownBy(() -> submit(user3, 10))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient Test Coin allowance for transfer_from! Allowed 0, but trying to transfer"
                + " 10 (in minimal units)");

    assertInitialLiquidStakingState();
  }

  /** A user cannot submit if action does not contain enough gas to execute the events. */
  @ContractTest(previous = "setup")
  void submitNeedsEnoughGasForEvents() {
    initialSetupWithAsserts(50, 0, 0, 0);

    byte[] rpc = LiquidStaking.submit(BigInteger.valueOf(20));
    assertThatThrownBy(() -> blockchain.sendAction(user1, liquidStakingAddress, rpc, 16988))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot allocate gas for events");

    assertLiquidStakingStateInvariant();
  }

  /**
   * The staking responsible can withdraw tokens from the liquid staking contract. Withdrawal does
   * not change the pools or the user balances.
   */
  @ContractTest(previous = "setup")
  void stakingResponsibleCanWithdrawTokens() {
    initialSetupWithAsserts(100, 0, 0, 0);

    withdraw(stakingResponsible, 10);

    assertThat(totalPoolStakeToken()).isEqualTo(100);
    assertThat(totalPoolLiquidToken()).isEqualTo(100);
    assertThat(getLiquidBalance(user1)).isEqualTo(100);
    assertTokenStateForLiquidStakingContract(90);
    assertLiquidStakingStateInvariant();
  }

  /** The staking responsible cannot withdraw more tokens than what is stored on the contract. */
  @ContractTest(previous = "setup")
  void stakingResponsibleCannotWithdrawMoreTokenThanStoredOnContract() {
    initialSetupWithAsserts(100, 0, 10, 0);

    assertThatThrownBy(() -> withdraw(stakingResponsible, 91))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "The staking responsible tried to withdraw 91 tokens, more than available on the"
                + " contract (90).");

    assertTokenStateForLiquidStakingContract(90);
    assertLiquidStakingStateInvariant();
  }

  /** The staking responsible cannot withdraw zero tokens from the contract. */
  @ContractTest(previous = "setup")
  void stakingResponsibleCannotWithdrawZeroTokens() {
    initialSetupWithAsserts(100, 0, 0, 0);

    assertThatThrownBy(() -> withdraw(stakingResponsible, 0))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot withdraw 0 tokens");

    assertTokenStateForLiquidStakingContract(100);
    assertLiquidStakingStateInvariant();
  }

  /**
   * The staking responsible cannot withdraw if action does not contain enough gas to execute the
   * events.
   */
  @ContractTest(previous = "setup")
  void withdrawalNeedsEnoughGasForEvents() {
    initialSetupWithAsserts(100, 0, 0, 0);

    byte[] rpc = LiquidStaking.withdraw(BigInteger.valueOf(20));
    assertThatThrownBy(
            () -> blockchain.sendAction(stakingResponsible, liquidStakingAddress, rpc, 16385))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot allocate gas for events");

    assertLiquidStakingStateInvariant();
  }

  /**
   * A user cannot withdraw tokens from the contract. Only the staking responsible has access to
   * withdraw tokens from the contract.
   */
  @ContractTest(previous = "setup")
  void userCannotWithdrawTokens() {
    initialSetupWithAsserts(100, 0, 0, 0);

    assertThatThrownBy(() -> withdraw(user1, 10))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Unauthorized to withdraw tokens. Only the registered staking responsible (at address:"
                + " 00F96DB08DBEB7B777E48D993CAF474A410A9B629B) can withdraw tokens.");

    assertTokenStateForLiquidStakingContract(100);
    assertLiquidStakingStateInvariant();
  }

  /**
   * The administrator cannot withdraw tokens from the contract. Only the staking responsible has
   * access to withdraw tokens from the contract.
   */
  @ContractTest(previous = "setup")
  void adminCannotWithdrawTokens() {
    initialSetupWithAsserts(100, 0, 0, 0);

    assertThatThrownBy(() -> withdraw(liquidStakingAdministrator, 10))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Unauthorized to withdraw tokens. Only the registered staking responsible (at address:"
                + " 00F96DB08DBEB7B777E48D993CAF474A410A9B629B) can withdraw tokens.");

    assertTokenStateForLiquidStakingContract(100);
    assertLiquidStakingStateInvariant();
  }

  /**
   * The staking responsible can accrue rewards of the stake tokens, which will increase the stake
   * token pool on the liquid staking contract.
   */
  @ContractTest(previous = "setup")
  void stakingResponsibleCanAccrueRewards() {
    initialSetupWithAsserts(50, 0, 50, 0);

    accrueRewards(stakingResponsible, 25);

    assertPoolAmounts(75, 50);
    assertThat(getLiquidBalance(user1)).isEqualTo(50);
    assertTokenStateForLiquidStakingContract(0);
    assertLiquidStakingStateInvariant();
  }

  /** The staking responsible cannot accrue a reward of zero tokens to the contract. */
  @ContractTest(previous = "setup")
  void stakingResponsibleCannotAccrueRewardOfZeroTokens() {
    initialSetupWithAsserts(50, 0, 50, 0);

    assertThatThrownBy(() -> accrueRewards(stakingResponsible, 0))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot accrue rewards of zero tokens");

    assertPoolAmounts(50, 50);
    assertTokenStateForLiquidStakingContract(0);
    assertLiquidStakingStateInvariant();
  }

  /**
   * A user cannot accrue rewards to the contract. Only the staking responsible has access to accrue
   * rewards.
   */
  @ContractTest(previous = "setup")
  void userCannotAccrueRewards() {
    initialSetupWithAsserts(50, 0, 50, 0);

    assertThatThrownBy(() -> accrueRewards(user1, 10))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Unauthorized to accrue rewards. Only the registered staking responsible (at address:"
                + " 00F96DB08DBEB7B777E48D993CAF474A410A9B629B) can accrue rewards.");

    assertPoolAmounts(50, 50);
    assertTokenStateForLiquidStakingContract(0);
    assertLiquidStakingStateInvariant();
  }

  /**
   * The administrator cannot accrue rewards to the contract. Only the staking responsible has
   * access to accrue rewards.
   */
  @ContractTest(previous = "setup")
  void adminCannotAccrueRewards() {
    initialSetupWithAsserts(50, 0, 50, 0);

    assertThatThrownBy(() -> accrueRewards(liquidStakingAdministrator, 10))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Unauthorized to accrue rewards. Only the registered staking responsible (at address:"
                + " 00F96DB08DBEB7B777E48D993CAF474A410A9B629B) can accrue rewards.");

    assertPoolAmounts(50, 50);
    assertTokenStateForLiquidStakingContract(0);
    assertLiquidStakingStateInvariant();
  }

  /** A user submits to another exchange rate after rewards has been accrued. */
  @ContractTest(previous = "setup")
  void accrueRewardsChangesExchangeRate() {
    assertInitialLiquidStakingState();

    // submitting to exchange rate 1 (100 stake tokens -> 100 liquid tokens)
    submit(user1, 100);
    assertPoolAmounts(100, 100);
    assertThat(getLiquidBalance(user1)).isEqualTo(100);

    accrueRewards(stakingResponsible, 25);

    assertPoolAmounts(125, 100);
    assertThat(getLiquidBalance(user1)).isEqualTo(100);

    // submitting to exchange rate 0.8 (10 stake tokens -> 8 liquid tokens)
    submit(user1, 10);
    assertPoolAmounts(135, 108);
    assertThat(getLiquidBalance(user1)).isEqualTo(108);
    assertLiquidStakingStateInvariant();
  }

  /** A user submits to the same exchange rate within a reward period. */
  @ContractTest(previous = "setup")
  void exchangeRateRemainsFixedWithinRewardPeriod() {
    initialSetupWithAsserts(100, 25, 100, 0);

    // submitting to exchange rate 0.8 (10 stake tokens -> 8 liquid tokens)
    submit(user1, 10);
    assertPoolAmounts(135, 108);
    assertThat(getLiquidBalance(user1)).isEqualTo(108);

    // submitting to exchange rate 0.8 (10 stake tokens -> 8 liquid tokens)
    submit(user1, 10);
    assertPoolAmounts(145, 116);
    assertThat(getLiquidBalance(user1)).isEqualTo(116);
    assertLiquidStakingStateInvariant();
  }

  /** A user can request to unlock liquid tokens. */
  @ContractTest(previous = "setup")
  void userCanRequestUnlock() {
    initialSetupWithAsserts(100, 0, 100, 0);

    requestUnlock(user1, 10);
    requestUnlock(user1, 30);

    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(2);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user1).get(1).liquidAmount()).isEqualTo(30);
    assertLiquidStakingStateInvariant();
  }

  /** A user can request to unlock all his liquid tokens. */
  @ContractTest(previous = "setup")
  void userCanRequestUnlockAllHisLiquidTokens() {
    initialSetupWithAsserts(100, 0, 100, 0);

    requestUnlock(user1, 100);

    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(100);
    assertLiquidStakingStateInvariant();
  }

  /** A user can request to unlock all his liquid tokens over several requests. */
  @ContractTest(previous = "setup")
  void userCanRequestUnlockAllHisLiquidTokensOverSeveralRequests() {
    initialSetupWithAsserts(100, 0, 100, 0);

    requestUnlock(user1, 15);
    requestUnlock(user1, 50);
    requestUnlock(user1, 35);

    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(3);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(15);
    assertThat(getPendingUnlocks(user1).get(1).liquidAmount()).isEqualTo(50);
    assertThat(getPendingUnlocks(user1).get(2).liquidAmount()).isEqualTo(35);
    assertThat(getPendingUnlocks(user1).get(0).stakeTokenAmount()).isEqualTo(15);
    assertThat(getPendingUnlocks(user1).get(1).stakeTokenAmount()).isEqualTo(50);
    assertThat(getPendingUnlocks(user1).get(2).stakeTokenAmount()).isEqualTo(35);
    assertLiquidStakingStateInvariant();
  }

  /** A user can request to unlock liquid tokens to a new exchange rate after a reward. */
  @ContractTest(previous = "setup")
  void userCanRequestUnlockToNewExchangeRate() {
    initialSetupWithAsserts(100, 0, 100, 0);

    requestUnlock(user1, 10);

    accrueRewards(stakingResponsible, 25);

    requestUnlock(user1, 10);

    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(2);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user1).get(1).liquidAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user1).get(0).stakeTokenAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user1).get(1).stakeTokenAmount()).isEqualTo(12);
    assertLiquidStakingStateInvariant();
  }

  /**
   * Request unlock does not count expired pending unlocks, when determining total amount of
   * unlockable tokens.
   */
  @ContractTest(previous = "setup")
  void requestUnlockDoesNotCountExpiredUnlocks() {
    initialSetupWithAsserts(100, 0, 0, 0);

    requestUnlock(user1, 10);

    waitForRedeemPeriodToExpire();

    requestUnlock(user1, 100);

    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(2);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user1).get(1).liquidAmount()).isEqualTo(100);
    assertThat(getPendingUnlocks(user1).get(0).stakeTokenAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user1).get(1).stakeTokenAmount()).isEqualTo(100);
    assertLiquidStakingStateInvariant();
  }

  /** A user cannot request to unlock zero liquid tokens. */
  @ContractTest(previous = "setup")
  void userCannotRequestUnlockOfZeroTokens() {
    initialSetupWithAsserts(50, 0, 50, 0);

    assertThatThrownBy(() -> requestUnlock(user1, 0))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot unlock zero tokens");

    assertThat(getPendingUnlocks(user1)).isNull();
    assertLiquidStakingStateInvariant();
  }

  /** A user cannot request to unlock if his liquid balance is empty. */
  @ContractTest(previous = "setup")
  void userCannotRequestUnlockWithEmptyBalance() {
    initialSetupWithAsserts(10, 0, 0, 0);

    assertThatThrownBy(() -> requestUnlock(user2, 10))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Unlock amount too large. Requested 10 liquid tokens, which is larger than users"
                + " balance (0) minus existing (non-expired) pending unlocks (0)");

    assertThat(getPendingUnlocks(user2)).isNull();
    assertLiquidStakingStateInvariant();
  }

  /** A user cannot request to unlock more liquid tokens than what is in his liquid balance. */
  @ContractTest(previous = "setup")
  void userCannotRequestUnlockOfMoreThanBalanceAmount() {
    initialSetupWithAsserts(10, 0, 10, 0);

    submit(user2, 50);

    assertThatThrownBy(() -> requestUnlock(user2, 51))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Unlock amount too large. Requested 51 liquid tokens, which is larger than users"
                + " balance (50) minus existing (non-expired) pending unlocks (0)");

    assertThat(getPendingUnlocks(user2)).isNull();
    assertLiquidStakingStateInvariant();
  }

  /**
   * A user cannot request to unlock several times to get more than what is in his liquid balance.
   */
  @ContractTest(previous = "setup")
  void userCannotRequestUnlockSeveralTimeToGetMoreThanBalanceAmount() {
    initialSetupWithAsserts(50, 0, 50, 0);

    requestUnlock(user1, 10);

    waitForRedeemPeriodToExpire();

    requestUnlock(user1, 10);
    requestUnlock(user1, 30);

    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(3);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user1).get(1).liquidAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user1).get(2).liquidAmount()).isEqualTo(30);

    assertThatThrownBy(() -> requestUnlock(user1, 11))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Unlock amount too large. Requested 11 liquid tokens, which is larger than users"
                + " balance (50) minus existing (non-expired) pending unlocks (40)");

    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(3);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user1).get(1).liquidAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user1).get(2).liquidAmount()).isEqualTo(30);
    assertLiquidStakingStateInvariant();
  }

  /**
   * The staking responsible can deposit tokens to the liquid staking contract. Deposit does not
   * change the pools or the user balances.
   */
  @ContractTest(previous = "setup")
  void stakingResponsibleCanDepositTokens() {
    initialSetupWithAsserts(100, 0, 100, 0);

    deposit(stakingResponsible, 10);

    assertThat(totalPoolStakeToken()).isEqualTo(100);
    assertThat(totalPoolLiquidToken()).isEqualTo(100);
    assertThat(getLiquidBalance(user1)).isEqualTo(100);
    assertTokenStateForLiquidStakingContract(10);
    assertLiquidStakingStateInvariant();
  }

  /** The staking responsible cannot deposit zero tokens to the contract. */
  @ContractTest(previous = "setup")
  void stakingResponsibleCannotDepositZeroTokens() {
    initialSetupWithAsserts(100, 0, 50, 0);

    assertThatThrownBy(() -> deposit(stakingResponsible, 0))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot deposit zero tokens");

    assertTokenStateForLiquidStakingContract(50);
    assertLiquidStakingStateInvariant();
  }

  /** The staking responsible cannot deposit more tokens than it owns. */
  @ContractTest(previous = "setup")
  void cannotDepositMoreThanOwns() {
    initialSetupWithAsserts(100, 0, 100, 0);
    assertTokenStateForLiquidStakingContract(0);

    assertThatThrownBy(() -> deposit(stakingResponsible, 101))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient Test Coin allowance for transfer_from! Allowed 100, but trying to"
                + " transfer 101 (in minimal units)");

    assertTokenStateForLiquidStakingContract(0);
    assertLiquidStakingStateInvariant();
  }

  /**
   * The staking responsible cannot deposit if action does not contain enough gas to execute the
   * events.
   */
  @ContractTest(previous = "setup")
  void depositNeedsEnoughGasForEvents() {
    initialSetupWithAsserts(100, 0, 100, 0);

    byte[] rpc = LiquidStaking.deposit(BigInteger.valueOf(20));
    assertThatThrownBy(
            () -> blockchain.sendAction(stakingResponsible, liquidStakingAddress, rpc, 16988))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot allocate gas for events");

    assertLiquidStakingStateInvariant();
  }

  /**
   * A user cannot deposit tokens to the contract. Only the staking responsible has access to
   * deposit tokens to the contract.
   */
  @ContractTest(previous = "setup")
  void userCannotDepositTokens() {
    initialSetupWithAsserts(100, 0, 50, 0);

    assertThatThrownBy(() -> deposit(user1, 10))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Unauthorized to deposit tokens. Only the registered staking responsible (at address:"
                + " 00F96DB08DBEB7B777E48D993CAF474A410A9B629B) can deposit tokens.");

    assertTokenStateForLiquidStakingContract(50);
    assertLiquidStakingStateInvariant();
  }

  /**
   * The administrator cannot deposit tokens to the contract. Only the staking responsible has
   * access to deposit tokens to the contract.
   */
  @ContractTest(previous = "setup")
  void adminCannotDepositTokens() {
    initialSetupWithAsserts(100, 0, 50, 0);

    assertThatThrownBy(() -> deposit(liquidStakingAdministrator, 10))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Unauthorized to deposit tokens. Only the registered staking responsible (at address:"
                + " 00F96DB08DBEB7B777E48D993CAF474A410A9B629B) can deposit tokens.");

    assertTokenStateForLiquidStakingContract(50);
    assertLiquidStakingStateInvariant();
  }

  /** A user can redeem unlocked liquid tokens. */
  @ContractTest(previous = "setup")
  void userCanRedeemUnlockedTokens() {
    initialSetupWithAsserts(100, 0, 100, 0);

    requestUnlock(user1, 10);
    requestUnlock(user1, 23);

    assertThat(getLiquidBalance(user1)).isEqualTo(100);
    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(2);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user1).get(1).liquidAmount()).isEqualTo(23);
    assertTokenState(user1, USER_1_FUNDS - 100, USER_1_FUNDS - 100);
    assertLiquidStakingStateInvariant();

    deposit(stakingResponsible, 10);
    deposit(stakingResponsible, 23);
    assertTokenStateForLiquidStakingContract(33);

    waitForRedeemPeriod();
    redeem(user1);

    assertThat(getLiquidBalance(user1)).isEqualTo(67);
    assertPoolAmounts(67, 67);
    assertThat(getPendingUnlocks(user1)).isNull();
    assertTokenState(user1, USER_1_FUNDS - 100 + 33, USER_1_FUNDS - 100);
    assertTokenStateForLiquidStakingContract(0);
    assertLiquidStakingStateInvariant();
  }

  /** A user can unlock and redeem all his liquid tokens. */
  @ContractTest(previous = "setup")
  void userCanUnlockAndRedeemAllTokens() {
    initialSetupWithAsserts(482, 0, 482, 0);

    requestUnlock(user1, 482);

    assertThat(getLiquidBalance(user1)).isEqualTo(482);
    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(482);
    assertTokenState(user1, USER_1_FUNDS - 482, USER_1_FUNDS - 482);
    assertLiquidStakingStateInvariant();

    deposit(stakingResponsible, 482);
    assertTokenStateForLiquidStakingContract(482);

    waitForRedeemPeriod();
    redeem(user1);

    assertThat(getLiquidBalance(user1)).isNull();
    assertPoolAmounts(0, 0);
    assertThat(getPendingUnlocks(user1)).isNull();
    assertTokenState(user1, USER_1_FUNDS, USER_1_FUNDS - 482);
    assertTokenStateForLiquidStakingContract(0);
    assertLiquidStakingStateInvariant();
  }

  /** A user cannot redeem liquid tokens from pending unlocks that are still in cooldown period. */
  @ContractTest(previous = "setup")
  void userCannotRedeemPendingUnlocksInCooldownPeriod() {
    initialSetupWithAsserts(250, 0, 250, 0);

    requestUnlock(user1, 200);

    assertThat(getLiquidBalance(user1)).isEqualTo(250);
    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(200);
    assertTokenState(user1, USER_1_FUNDS - 250, USER_1_FUNDS - 250);

    assertThatThrownBy(() -> redeem(user1))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("User has no pending unlocks that are ready to be redeemed");

    assertThat(getLiquidBalance(user1)).isEqualTo(250);
    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(200);
    assertTokenState(user1, USER_1_FUNDS - 250, USER_1_FUNDS - 250);
    assertTokenStateForLiquidStakingContract(0);
    assertLiquidStakingStateInvariant();
  }

  /** A user cannot redeem liquid tokens from pending unlocks that are expired. */
  @ContractTest(previous = "setup")
  void userCannotRedeemExpiredPendingUnlocks() {
    initialSetupWithAsserts(80, 0, 80, 0);

    requestUnlock(user1, 30);
    deposit(stakingResponsible, 30);

    assertThat(getLiquidBalance(user1)).isEqualTo(80);
    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(30);
    assertTokenState(user1, USER_1_FUNDS - 80, USER_1_FUNDS - 80);

    waitForRedeemPeriodToExpire();

    assertThatThrownBy(() -> redeem(user1))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("User has no pending unlocks that are ready to be redeemed");

    assertThat(getLiquidBalance(user1)).isEqualTo(80);
    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(30);
    assertTokenState(user1, USER_1_FUNDS - 80, USER_1_FUNDS - 80);
    assertTokenStateForLiquidStakingContract(30);
    assertLiquidStakingStateInvariant();
  }

  /** A user cannot redeem liquid tokens if he has no pending unlocks. */
  @ContractTest(previous = "setup")
  void userCannotRedeemIfNoPendingUnlocks() {
    initialSetupWithAsserts(100, 0, 100, 0);

    assertThat(getLiquidBalance(user1)).isEqualTo(100);
    assertThat(getPendingUnlocks(user1)).isNull();

    assertThatThrownBy(() -> redeem(user1))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("User has no pending unlocks");

    assertThat(getLiquidBalance(user1)).isEqualTo(100);
    assertThat(getPendingUnlocks(user1)).isNull();
    assertLiquidStakingStateInvariant();
  }

  /**
   * A user cannot redeem if the stake token amount exceeds the balance of the stake token on the
   * contract.
   */
  @ContractTest(previous = "setup")
  void userCannotRedeemIfContractDoesNotHaveEnoughTokens() {
    initialSetupWithAsserts(359, 0, 359, 0);

    requestUnlock(user1, 42);
    waitForRedeemPeriod();

    assertThatThrownBy(() -> redeem(user1))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Tried to redeem more tokens than available on the contract. Token balance: 0, but"
                + " tried to redeem 42");

    assertThat(getLiquidBalance(user1)).isEqualTo(359);
    assertPoolAmounts(359, 359);
    assertThat(getLiquidBalance(user1)).isEqualTo(359);
    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(42);
    assertTokenState(user1, USER_1_FUNDS - 359, USER_1_FUNDS - 359);
    assertTokenStateForLiquidStakingContract(0);
    assertLiquidStakingStateInvariant();
  }

  /** A user cannot redeem if action does not contain enough gas to execute the events. */
  @ContractTest(previous = "setup")
  void redeemNeedsEnoughGasForEvents() {
    initialSetupWithAsserts(40, 0, 0, 0);

    requestUnlock(user1, 40);
    waitForRedeemPeriod();

    byte[] rpc = LiquidStaking.redeem();
    assertThatThrownBy(() -> blockchain.sendAction(user1, liquidStakingAddress, rpc, 16733))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot allocate gas for events");

    assertLiquidStakingStateInvariant();
  }

  /** A user can redeem only the liquid tokens that are within the redeem period. */
  @ContractTest(previous = "setup")
  void userCanOnlyRedeemTokensWithinRedeemPeriod() {
    initialSetupWithAsserts(100, 0, 100, 0);

    // Will expire before redeeming
    requestUnlock(user1, 11);
    deposit(stakingResponsible, 11);

    waitForRedeemPeriodToExpire();

    // Will be redeemable
    requestUnlock(user1, 24);
    deposit(stakingResponsible, 24);

    waitForRedeemPeriod();

    // Still in cooldown period
    requestUnlock(user1, 37);

    redeem(user1);

    assertThat(getLiquidBalance(user1)).isEqualTo(76);
    assertPoolAmounts(76, 76);
    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(2);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(11);
    assertThat(getPendingUnlocks(user1).get(1).liquidAmount()).isEqualTo(37);
    assertTokenState(user1, USER_1_FUNDS - 100 + 24, USER_1_FUNDS - 100);
    assertTokenStateForLiquidStakingContract(11);
    assertLiquidStakingStateInvariant();
  }

  /**
   * A user redeems to the exchange rate at the time of the unlock request. If an accrue reward
   * happens between the unlock request and the redeem, it will not change the exchange rate.
   */
  @ContractTest(previous = "setup")
  void userRedeemsToExchangeRateAtTimeOfUnlockRequest() {
    initialSetupWithAsserts(100, 0, 100, 0);

    requestUnlock(user1, 10);
    deposit(stakingResponsible, 10);
    accrueRewards(stakingResponsible, 25);

    assertThat(getLiquidBalance(user1)).isEqualTo(100);
    assertPoolAmounts(125, 100);
    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(10);
    assertTokenState(user1, USER_1_FUNDS - 100, USER_1_FUNDS - 100);
    assertTokenStateForLiquidStakingContract(10);
    assertLiquidStakingStateInvariant();

    waitForRedeemPeriod();
    redeem(user1);

    assertThat(getLiquidBalance(user1)).isEqualTo(90);
    assertPoolAmounts(115, 90);
    assertThat(getPendingUnlocks(user1)).isNull();
    assertTokenState(user1, USER_1_FUNDS - 100 + 10, USER_1_FUNDS - 100);
    assertTokenStateForLiquidStakingContract(0);
    assertLiquidStakingStateInvariant();
  }

  /** The administrator can change the buy in percentage. */
  @ContractTest(previous = "setup")
  void adminCanChangeBuyInPercentage() {
    assertInitialLiquidStakingState();

    changeBuyIn(liquidStakingAdministrator, 10);

    assertBuyInPercentage(true, 10);
    assertLiquidStakingStateInvariant();
  }

  /**
   * A user cannot change the buy in percentage. Only the administrator has access to changing the
   * buy in percentage.
   */
  @ContractTest(previous = "setup")
  void userCannotChangeBuyIn() {
    initialSetupWithAsserts(0, 0, 0, 10);

    assertThatThrownBy(() -> changeBuyIn(user1, 0))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Cannot change the buy-in percentage. Only the registered administrator (at address:"
                + " 00B4D7BC4690C2FC52A27BA8734E8633B5613DD0ED) can change the buy-in percentage.");

    assertBuyInPercentage(true, 10);
    assertLiquidStakingStateInvariant();
  }

  /**
   * The staking responsible cannot change the buy in percentage. Only the administrator has access
   * to changing the buy in percentage.
   */
  @ContractTest(previous = "setup")
  void stakingResponsibleCannotChangeBuyIn() {
    initialSetupWithAsserts(0, 0, 0, 5);

    assertThatThrownBy(() -> changeBuyIn(stakingResponsible, 13))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Cannot change the buy-in percentage. Only the registered administrator (at address:"
                + " 00B4D7BC4690C2FC52A27BA8734E8633B5613DD0ED) can change the buy-in percentage.");

    assertBuyInPercentage(true, 5);
    assertLiquidStakingStateInvariant();
  }

  /** A user submitting tokens with a non-zero buy in percentage has some of his tokens locked. */
  @ContractTest(previous = "setup")
  void userSubmitWithNonZeroBuyIn() {
    initialSetupWithAsserts(100, 25, 0, 10);

    // submitting to buy in percentage 10 (100 stake tokens -> 10 locked buy in tokens)
    // submitting to exchange rate 0.8 (90 stake tokens after buy in -> 72 liquid tokens)
    submit(user1, 100);

    assertThat(getLiquidBalance(user1)).isEqualTo(172);
    assertThat(getBuyInTokens(user1)).isEqualTo(10);
    assertPoolAmounts(225, 172);
    assertTokenStateForLiquidStakingContract(200);
    assertLiquidStakingStateInvariant();

    // submitting to buy in percentage 10 (39 stake tokens -> 3 locked buy in tokens)
    // submitting to exchange rate 0.8 (36 stake tokens after buy in -> 28 liquid tokens)
    submit(user2, 39);

    assertThat(getLiquidBalance(user2)).isEqualTo(28);
    assertThat(getBuyInTokens(user2)).isEqualTo(3);
    assertPoolAmounts(264, 200);
    assertTokenStateForLiquidStakingContract(239);
    assertLiquidStakingStateInvariant();

    // submitting to buy in percentage 10 (52 stake tokens -> 5 locked buy in tokens)
    // submitting to exchange rate 0.8 (47 stake tokens after buy in -> 37 liquid tokens)
    submit(user2, 52);

    assertThat(getLiquidBalance(user2)).isEqualTo(65);
    assertThat(getBuyInTokens(user2)).isEqualTo(8);
    assertPoolAmounts(316, 237);
    assertTokenStateForLiquidStakingContract(291);
    assertLiquidStakingStateInvariant();
  }

  /** The administrator can disable the buy in and release all locked buy in tokens. */
  @ContractTest(previous = "setup")
  void adminCanDisableBuyIn() {
    assertInitialLiquidStakingState();

    changeBuyIn(liquidStakingAdministrator, 10);
    submit(user1, 100);

    assertBuyInPercentage(true, 10);
    assertThat(getLiquidBalance(user1)).isEqualTo(90);
    assertThat(getBuyInTokens(user1)).isEqualTo(10);
    assertPoolAmounts(100, 90);
    assertTokenStateForLiquidStakingContract(100);
    assertLiquidStakingStateInvariant();

    disableBuyIn(liquidStakingAdministrator);

    assertBuyInPercentage(false, 0);
    assertThat(getLiquidBalance(user1)).isEqualTo(100);
    assertThat(getBuyInTokens(user1)).isEqualTo(0);
    assertPoolAmounts(100, 100);
    assertTokenStateForLiquidStakingContract(100);
    assertLiquidStakingStateInvariant();
  }

  /**
   * A user cannot disable the buy in. Only the administrator has access to disabling the buy in and
   * releasing the locked buy in tokens.
   */
  @ContractTest(previous = "setup")
  void userCannotDisableBuyIn() {
    initialSetupWithAsserts(0, 0, 0, 10);

    assertThatThrownBy(() -> disableBuyIn(user1))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Cannot disable buy-in. Only the registered administrator (at address:"
                + " 00B4D7BC4690C2FC52A27BA8734E8633B5613DD0ED) can disable buy-in.");

    assertBuyInPercentage(true, 10);
    assertLiquidStakingStateInvariant();
  }

  /**
   * The staking responsible cannot disable the buy in. Only the administrator has access to
   * disabling the buy in and releasing the locked buy in tokens.
   */
  @ContractTest(previous = "setup")
  void stakingResponsibleCannotDisableBuyIn() {
    initialSetupWithAsserts(0, 0, 0, 5);

    assertThatThrownBy(() -> disableBuyIn(stakingResponsible))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Cannot disable buy-in. Only the registered administrator (at address:"
                + " 00B4D7BC4690C2FC52A27BA8734E8633B5613DD0ED) can disable buy-in.");

    assertBuyInPercentage(true, 5);
    assertLiquidStakingStateInvariant();
  }

  /** The liquidStakingAdministrator cannot disable buy-in, when it is already disabled. */
  @ContractTest(previous = "setup")
  void adminCannotDisableBuyInWhenDisabled() {
    initialSetupWithAsserts(0, 0, 0, 5);

    disableBuyIn(liquidStakingAdministrator);
    assertBuyInPercentage(false, 0);

    assertThatThrownBy(() -> disableBuyIn(liquidStakingAdministrator))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot disable buy-in, when it is already disabled.");

    assertBuyInPercentage(false, 0);
    assertLiquidStakingStateInvariant();
  }

  /** The liquidStakingAdministrator can enable the buy in by changing the buy in percentage. */
  @ContractTest(previous = "setup")
  void adminEnablesBuyIn() {
    assertInitialLiquidStakingState();

    disableBuyIn(liquidStakingAdministrator);
    assertBuyInPercentage(false, 0);
    assertLiquidStakingStateInvariant();

    changeBuyIn(liquidStakingAdministrator, 13);

    assertBuyInPercentage(true, 13);
    assertLiquidStakingStateInvariant();
  }

  /** The user can cancel their own pending not-yet-expired unlock. */
  @ContractTest(previous = "setup")
  void userCanCancelTheirOwnPendingUnlocks() {
    initialSetupWithAsserts(60, 0, 0, 0);

    requestUnlock(user1, 50);

    assertThat(getPendingUnlocks(user1)).isNotNull();

    cancelPendingUnlock(user1, getPendingUnlocks(user1).get(0).id());

    assertThat(getPendingUnlocks(user1)).isNull();
  }

  /** The user can cancel their own pending expired unlocks. */
  @ContractTest(previous = "setup")
  void userCanCancelTheirOwnExpiredPendingUnlocks() {
    initialSetupWithAsserts(60, 0, 0, 0);

    requestUnlock(user1, 50);

    waitForRedeemPeriodToExpire();

    assertThat(getPendingUnlocks(user1)).isNotNull();

    cancelPendingUnlock(user1, getPendingUnlocks(user1).get(0).id());

    assertThat(getPendingUnlocks(user1)).isNull();
  }

  /** The user cannot cancel another users pending unlock. */
  @ContractTest(previous = "setup")
  void userCannotCancelOtherUsersPendingUnlocks() {
    initialSetupWithAsserts(60, 0, 0, 0);

    submit(user2, 60);

    requestUnlock(user1, 10);
    requestUnlock(user2, 50);

    assertThat(getPendingUnlocks(user2)).isNotNull();

    var otherUsersId = getPendingUnlocks(user2).get(0).id();
    assertThatThrownBy(() -> cancelPendingUnlock(user1, otherUsersId))
        .hasMessageContaining("User does not possess pending unlock with id: 2");

    assertThat(getPendingUnlocks(user2)).isNotNull();
  }

  /** User cannot cancel when they don't have any pending unlocks. */
  @ContractTest(previous = "setup")
  void userCannotCancelWhenTheyDontHaveAnyPendingUnlocks() {
    initialSetupWithAsserts(60, 0, 0, 0);

    submit(user2, 60);

    assertThat(getPendingUnlocks(user2)).isNull();

    assertThatThrownBy(() -> cancelPendingUnlock(user1, 2))
        .hasMessageContaining("User does not possess any pending unlocks");

    assertThat(getPendingUnlocks(user2)).isNull();
  }

  /** The administrator can clean up pending unlocks. */
  @ContractTest(previous = "setup")
  void adminCanCleanUpPendingUnlocks() {
    initialSetupWithAsserts(60, 0, 0, 0);

    submit(user2, 40);

    requestUnlock(user1, 50);
    requestUnlock(user2, 10);

    waitForRedeemPeriodToExpire();

    requestUnlock(user2, 20);

    waitForRedeemPeriod();

    requestUnlock(user2, 5);

    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(50);

    assertThat(getPendingUnlocks(user2)).isNotNull();
    assertThat(getPendingUnlocks(user2)).hasSize(3);
    assertThat(getPendingUnlocks(user2).get(0).liquidAmount()).isEqualTo(10);
    assertThat(getPendingUnlocks(user2).get(1).liquidAmount()).isEqualTo(20);
    assertThat(getPendingUnlocks(user2).get(2).liquidAmount()).isEqualTo(5);

    assertLiquidStakingStateInvariant();

    cleanUpPendingUnlocks(liquidStakingAdministrator);

    assertThat(getPendingUnlocks(user1)).isNull();

    assertThat(getPendingUnlocks(user2)).isNotNull();
    assertThat(getPendingUnlocks(user2)).hasSize(2);
    assertThat(getPendingUnlocks(user2).get(0).liquidAmount()).isEqualTo(20);
    assertThat(getPendingUnlocks(user2).get(1).liquidAmount()).isEqualTo(5);

    assertLiquidStakingStateInvariant();
  }

  /** The staking responsible can clean up pending unlocks. */
  @ContractTest(previous = "setup")
  void stakingResponsibleCanCleanUpPendingUnlocks() {
    initialSetupWithAsserts(500, 0, 0, 0);

    submit(user2, 400);

    requestUnlock(user1, 500);
    requestUnlock(user2, 100);

    waitForRedeemPeriodToExpire();

    requestUnlock(user2, 200);

    waitForRedeemPeriod();

    requestUnlock(user2, 50);

    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(500);

    assertThat(getPendingUnlocks(user2)).isNotNull();
    assertThat(getPendingUnlocks(user2)).hasSize(3);
    assertThat(getPendingUnlocks(user2).get(0).liquidAmount()).isEqualTo(100);
    assertThat(getPendingUnlocks(user2).get(1).liquidAmount()).isEqualTo(200);
    assertThat(getPendingUnlocks(user2).get(2).liquidAmount()).isEqualTo(50);

    assertLiquidStakingStateInvariant();

    cleanUpPendingUnlocks(stakingResponsible);

    assertThat(getPendingUnlocks(user1)).isNull();

    assertThat(getPendingUnlocks(user2)).isNotNull();
    assertThat(getPendingUnlocks(user2)).hasSize(2);
    assertThat(getPendingUnlocks(user2).get(0).liquidAmount()).isEqualTo(200);
    assertThat(getPendingUnlocks(user2).get(1).liquidAmount()).isEqualTo(50);

    assertLiquidStakingStateInvariant();
  }

  /**
   * A user cannot clean up pending unlocks. Only the administrator has access to clean up pending
   * unlocks.
   */
  @ContractTest(previous = "setup")
  void userCannotCleanUpPendingUnlocks() {
    initialSetupWithAsserts(60, 0, 0, 0);

    requestUnlock(user1, 50);
    waitForRedeemPeriodToExpire();

    assertThatThrownBy(() -> cleanUpPendingUnlocks(user1))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Cannot clean up pending unlocks. Only the registered administrator (at address:"
                + " 00B4D7BC4690C2FC52A27BA8734E8633B5613DD0ED) or staking responsible (at address:"
                + " 00F96DB08DBEB7B777E48D993CAF474A410A9B629B) can clean up pending unlocks.");

    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(50);
    assertLiquidStakingStateInvariant();
  }
}
