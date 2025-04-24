package defi.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.errorprone.annotations.CheckReturnValue;
import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquidStaking;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Testing for the {@link LiquidStaking} smart contract. */
@CheckReturnValue
public abstract class LiquidStakingTest extends JunitContractTest {

  private static final int STAKE_TOKEN_SUPPLY = 100000000;
  private static final long LENGTH_OF_COOLDOWN_PERIOD = 100;
  private static final long LENGTH_OF_REDEEM_PERIOD = 100;
  private static final int USER_1_FUNDS = 500;
  private static final int USER_2_FUNDS = 1000;
  private static final int USER_3_FUNDS = 200;

  public BlockchainAddress stakeTokenOwner;
  public BlockchainAddress stakeTokenAddress;
  public BlockchainAddress liquidStakingOwner;
  public BlockchainAddress liquidStakingAddress;
  public BlockchainAddress user1;
  public BlockchainAddress user2;
  public BlockchainAddress user3;
  public BlockchainAddress stakingResponsible;
  public BlockchainAddress admin;

  private LiquidStaking liquidStakingContract;

  /** Definition of the Liquid Staking contract under test. */
  protected final ContractBytes contractBytesLiquidStaking;

  /** Definition of the token contract. */
  private final ContractBytes contractBytesToken;

  /**
   * Initialize the test class.
   *
   * @param contractBytesLiquidStaking Contract bytes to initialize the {@link LiquidStaking}
   *     contract.
   * @param contractBytesToken Contract bytes to initialize the token contract.
   */
  public LiquidStakingTest(
      ContractBytes contractBytesLiquidStaking, ContractBytes contractBytesToken) {
    this.contractBytesLiquidStaking = contractBytesLiquidStaking;
    this.contractBytesToken = contractBytesToken;
  }

  /**
   * Setup for other tests. Instantiates the accounts of all the contract owners, deploys the
   * contracts, and transfers and approves tokens for the users and the liquid staking contract
   */
  @ContractTest
  void setup() {
    stakeTokenOwner = blockchain.newAccount(2);
    liquidStakingOwner = blockchain.newAccount(3);
    user1 = blockchain.newAccount(4);
    user2 = blockchain.newAccount(5);
    user3 = blockchain.newAccount(6);
    stakingResponsible = blockchain.newAccount(7);
    admin = blockchain.newAccount(8);

    stakeTokenAddress = deployStakeTokenContract();

    provideFundsToAccount(user1, USER_1_FUNDS);
    provideFundsToAccount(user2, USER_2_FUNDS);
    provideFundsToAccount(user3, USER_3_FUNDS);

    liquidStakingAddress = deployLiquidStakingContract();
    liquidStakingContract = new LiquidStaking(getStateClient(), liquidStakingAddress);
    assertInitialLiquidStakingState();

    approveTransferToLiquidStakingContract(user1, USER_1_FUNDS);
    approveTransferToLiquidStakingContract(user2, USER_2_FUNDS);

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
    initialSetup(100, 0, 0, 0);

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
    initialSetup(50, 0, 0, 0);

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
    initialSetup(100, 0, 0, 0);

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
    initialSetup(100, 0, 10, 0);

    assertThatThrownBy(() -> withdraw(stakingResponsible, 91))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "The staking responsible tried to withdraw more tokens than available on the contract");

    assertTokenStateForLiquidStakingContract(90);
    assertLiquidStakingStateInvariant();
  }

  /** The staking responsible cannot withdraw zero tokens from the contract. */
  @ContractTest(previous = "setup")
  void stakingResponsibleCannotWithdrawZeroTokens() {
    initialSetup(100, 0, 0, 0);

    assertThatThrownBy(() -> withdraw(stakingResponsible, 0))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot withdraw zero tokens");

    assertTokenStateForLiquidStakingContract(100);
    assertLiquidStakingStateInvariant();
  }

  /**
   * The staking responsible cannot withdraw if action does not contain enough gas to execute the
   * events.
   */
  @ContractTest(previous = "setup")
  void withdrawalNeedsEnoughGasForEvents() {
    initialSetup(100, 0, 0, 0);

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
    initialSetup(100, 0, 0, 0);

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
    initialSetup(100, 0, 0, 0);

    assertThatThrownBy(() -> withdraw(admin, 10))
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
    initialSetup(50, 0, 50, 0);

    accrueRewards(stakingResponsible, 25);

    assertPoolAmounts(75, 50);
    assertThat(getLiquidBalance(user1)).isEqualTo(50);
    assertTokenStateForLiquidStakingContract(0);
    assertLiquidStakingStateInvariant();
  }

  /** The staking responsible cannot accrue a reward of zero tokens to the contract. */
  @ContractTest(previous = "setup")
  void stakingResponsibleCannotAccrueRewardOfZeroTokens() {
    initialSetup(50, 0, 50, 0);

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
    initialSetup(50, 0, 50, 0);

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
    initialSetup(50, 0, 50, 0);

    assertThatThrownBy(() -> accrueRewards(admin, 10))
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
    initialSetup(100, 25, 100, 0);

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
    initialSetup(100, 0, 100, 0);

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
    initialSetup(100, 0, 100, 0);

    requestUnlock(user1, 100);

    assertThat(getPendingUnlocks(user1)).isNotNull();
    assertThat(getPendingUnlocks(user1)).hasSize(1);
    assertThat(getPendingUnlocks(user1).get(0).liquidAmount()).isEqualTo(100);
    assertLiquidStakingStateInvariant();
  }

  /** A user can request to unlock all his liquid tokens over several requests. */
  @ContractTest(previous = "setup")
  void userCanRequestUnlockAllHisLiquidTokensOverSeveralRequests() {
    initialSetup(100, 0, 100, 0);

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
    initialSetup(100, 0, 100, 0);

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
    initialSetup(100, 0, 0, 0);

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
    initialSetup(50, 0, 50, 0);

    assertThatThrownBy(() -> requestUnlock(user1, 0))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot unlock zero tokens");

    assertThat(getPendingUnlocks(user1)).isNull();
    assertLiquidStakingStateInvariant();
  }

  /** A user cannot request to unlock if his liquid balance is empty. */
  @ContractTest(previous = "setup")
  void userCannotRequestUnlockWithEmptyBalance() {
    initialSetup(10, 0, 0, 0);

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
    initialSetup(10, 0, 10, 0);

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
    initialSetup(50, 0, 50, 0);

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
    initialSetup(100, 0, 100, 0);

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
    initialSetup(100, 0, 50, 0);

    assertThatThrownBy(() -> deposit(stakingResponsible, 0))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot deposit zero tokens");

    assertTokenStateForLiquidStakingContract(50);
    assertLiquidStakingStateInvariant();
  }

  /** The staking responsible cannot deposit more tokens than it owns. */
  @ContractTest(previous = "setup")
  void cannotDepositMoreThanOwns() {
    initialSetup(100, 0, 100, 0);
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
    initialSetup(100, 0, 100, 0);

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
    initialSetup(100, 0, 50, 0);

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
    initialSetup(100, 0, 50, 0);

    assertThatThrownBy(() -> deposit(admin, 10))
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
    initialSetup(100, 0, 100, 0);

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
    initialSetup(482, 0, 482, 0);

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
    initialSetup(250, 0, 250, 0);

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
    initialSetup(80, 0, 80, 0);

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
    initialSetup(100, 0, 100, 0);

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
    initialSetup(359, 0, 359, 0);

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
    initialSetup(40, 0, 0, 0);

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
    initialSetup(100, 0, 100, 0);

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
    initialSetup(100, 0, 100, 0);

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

    changeBuyIn(admin, 10);

    assertBuyInPercentage(true, 10);
    assertLiquidStakingStateInvariant();
  }

  /**
   * A user cannot change the buy in percentage. Only the administrator has access to changing the
   * buy in percentage.
   */
  @ContractTest(previous = "setup")
  void userCannotChangeBuyIn() {
    initialSetup(0, 0, 0, 10);

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
    initialSetup(0, 0, 0, 5);

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
    initialSetup(100, 25, 0, 10);

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

    changeBuyIn(admin, 10);
    submit(user1, 100);

    assertBuyInPercentage(true, 10);
    assertThat(getLiquidBalance(user1)).isEqualTo(90);
    assertThat(getBuyInTokens(user1)).isEqualTo(10);
    assertPoolAmounts(100, 90);
    assertTokenStateForLiquidStakingContract(100);
    assertLiquidStakingStateInvariant();

    disableBuyIn(admin);

    assertBuyInPercentage(false, 0);
    assertThat(getLiquidBalance(user1)).isEqualTo(100);
    assertThat(getBuyInTokens(user1)).isEqualTo(0);
    assertPoolAmounts(100, 100);
    assertTokenStateForLiquidStakingContract(100);
    assertLiquidStakingStateInvariant();

    assertLiquidStakingStateInvariant();
  }

  /**
   * A user cannot disable the buy in. Only the administrator has access to disabling the buy in and
   * releasing the locked buy in tokens.
   */
  @ContractTest(previous = "setup")
  void userCannotDisableBuyIn() {
    initialSetup(0, 0, 0, 10);

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
    initialSetup(0, 0, 0, 5);

    assertThatThrownBy(() -> disableBuyIn(stakingResponsible))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Cannot disable buy-in. Only the registered administrator (at address:"
                + " 00B4D7BC4690C2FC52A27BA8734E8633B5613DD0ED) can disable buy-in.");

    assertBuyInPercentage(true, 5);
    assertLiquidStakingStateInvariant();
  }

  /** The admin cannot disable buy-in, when it is already disabled. */
  @ContractTest(previous = "setup")
  void adminCannotDisableBuyInWhenDisabled() {
    initialSetup(0, 0, 0, 5);

    disableBuyIn(admin);
    assertBuyInPercentage(false, 0);

    assertThatThrownBy(() -> disableBuyIn(admin))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot disable buy-in, when it is already disabled.");

    assertBuyInPercentage(false, 0);
    assertLiquidStakingStateInvariant();
  }

  /** The admin can enable the buy in by changing the buy in percentage. */
  @ContractTest(previous = "setup")
  void adminEnablesBuyIn() {
    assertInitialLiquidStakingState();

    disableBuyIn(admin);
    assertBuyInPercentage(false, 0);
    assertLiquidStakingStateInvariant();

    changeBuyIn(admin, 13);

    assertBuyInPercentage(true, 13);
    assertLiquidStakingStateInvariant();
  }

  /** The administrator can clean up pending unlocks. */
  @ContractTest(previous = "setup")
  void adminCanCleanUpPendingUnlocks() {
    initialSetup(60, 0, 0, 0);

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

    cleanUpPendingUnlocks(admin);

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
    initialSetup(500, 0, 0, 0);

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
    initialSetup(60, 0, 0, 0);

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

  /** The administrator can upgrade the contract. */
  @ContractTest(previous = "setup")
  void adminCanUpgrade() {
    initialSetup(20, 0, 0, 0);

    assertThat(getLiquidBalance(user1)).isEqualTo(20);
    assertLiquidStakingStateInvariant();

    blockchain.upgradeContract(
        admin, liquidStakingAddress, contractBytesLiquidStaking, new byte[0]);

    assertThat(getLiquidBalance(user1)).isEqualTo(20);
    assertLiquidStakingStateInvariant();
  }

  /** The staking responsible cannot upgrade the contract. */
  @ContractTest(previous = "setup")
  void stakingResponsibleCannotUpgrade() {
    initialSetup(20, 0, 0, 0);

    assertThat(getLiquidBalance(user1)).isEqualTo(20);
    assertLiquidStakingStateInvariant();

    assertThatThrownBy(
            () ->
                blockchain.upgradeContract(
                    stakingResponsible,
                    liquidStakingAddress,
                    contractBytesLiquidStaking,
                    new byte[0]))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Contract did not allow this upgrade");

    assertLiquidStakingStateInvariant();
  }

  /** A user cannot upgrade the contract. */
  @ContractTest(previous = "setup")
  void userCannotUpgrade() {
    initialSetup(20, 0, 0, 0);

    assertThat(getLiquidBalance(user1)).isEqualTo(20);
    assertLiquidStakingStateInvariant();

    assertThatThrownBy(
            () ->
                blockchain.upgradeContract(
                    user1, liquidStakingAddress, contractBytesLiquidStaking, new byte[0]))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Contract did not allow this upgrade");

    assertLiquidStakingStateInvariant();
  }

  /**
   * Helper function to wait for the redeem period to start. Redeem period: from
   * pendingUnlock.cooldown_ends_at() to pendingUnlocks.expires_at()
   */
  private void waitForRedeemPeriod() {
    blockchain.waitForBlockProductionTime(
        blockchain.getBlockProductionTime() + LENGTH_OF_COOLDOWN_PERIOD + 5);
  }

  /**
   * Helper function to wait for the redeem period to end. Expired: after
   * pendingUnlocks.expires_at()
   */
  private void waitForRedeemPeriodToExpire() {
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
  private void submit(BlockchainAddress account, int amount) {
    byte[] rpc = LiquidStaking.submit(BigInteger.valueOf(amount));
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making withdraw RPC and invoking the withdrawal action.
   *
   * @param account The account that invokes the action.
   * @param amount The amount of stake tokens to withdraw.
   */
  private void withdraw(BlockchainAddress account, int amount) {
    byte[] rpc = LiquidStaking.withdraw(BigInteger.valueOf(amount));
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making deposit RPC and invoking the deposit action.
   *
   * @param account The account that invokes the action.
   * @param amount The amount of stake tokens to deposit.
   */
  private void deposit(BlockchainAddress account, int amount) {
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
  private void accrueRewards(BlockchainAddress account, int amount) {
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
  private void requestUnlock(BlockchainAddress account, int amount) {
    byte[] rpc = LiquidStaking.requestUnlock(BigInteger.valueOf(amount));
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making redeem RPC and invoking the redeem action.
   *
   * @param account The account that invokes the action.
   */
  private void redeem(BlockchainAddress account) {
    byte[] rpc = LiquidStaking.redeem();
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making change buy in RPC and invoking the change buy in action.
   *
   * @param account The account that invokes the action.
   * @param buyInPercentage The percentage the buy in should be changed to.
   */
  private void changeBuyIn(BlockchainAddress account, int buyInPercentage) {
    byte[] rpc = LiquidStaking.changeBuyIn(BigInteger.valueOf(buyInPercentage));
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making disable buy in RPC and invoking the disable buy in action.
   *
   * @param account The account that invokes the action.
   */
  private void disableBuyIn(BlockchainAddress account) {
    byte[] rpc = LiquidStaking.disableBuyIn();
    blockchain.sendAction(account, liquidStakingAddress, rpc);
  }

  /**
   * Helper function for making cleanup of pending unlocks RPC and invoking the cleanup pending
   * unlocks in action.
   *
   * @param account The account that invokes the action.
   */
  private void cleanUpPendingUnlocks(BlockchainAddress account) {
    byte[] redeemRpc = LiquidStaking.cleanUpPendingUnlocks();
    blockchain.sendAction(account, liquidStakingAddress, redeemRpc);
  }

  /**
   * Retrieve the liquid token balance for a user.
   *
   * @param user A user of the liquid staking contract.
   * @return The liquid token balance for the given user.
   */
  private BigInteger getLiquidBalance(final BlockchainAddress user) {
    LiquidStaking.LiquidStakingState state = liquidStakingContract.getState();
    return state.liquidTokenState().balances().get(user);
  }

  /**
   * Calculate the sum of all balances in the liquid balance map.
   *
   * @return The sum of all liquid token balances.
   */
  private BigInteger getLiquidBalanceSum() {
    LiquidStaking.LiquidStakingState state = liquidStakingContract.getState();
    return state.liquidTokenState().balances().getNextN(null, 100).stream()
        .map(Map.Entry::getValue)
        .reduce(BigInteger.ZERO, BigInteger::add);
  }

  /**
   * Retrieve the buy in tokens for a user.
   *
   * @param user A user of the liquid staking contract.
   * @return The amount of tokens locked by the buy in for the given user.
   */
  private BigInteger getBuyInTokens(final BlockchainAddress user) {
    LiquidStaking.LiquidStakingState state = liquidStakingContract.getState();
    return state.buyInTokens().get(user);
  }

  /**
   * Calculate the total sum of all locked buy in tokens.
   *
   * @return The sum of all tokens locked by the buy in.
   */
  private BigInteger getBuyInTokensSum() {
    LiquidStaking.LiquidStakingState state = liquidStakingContract.getState();
    return state.buyInTokens().getNextN(null, 100).stream()
        .map(Map.Entry::getValue)
        .reduce(BigInteger.ZERO, BigInteger::add);
  }

  /**
   * Retrieve the pending unlocks for a user.
   *
   * @param user A user of the liquid staking contract.
   * @return The list of all pending unlocks associated the user.
   */
  private List<LiquidStaking.PendingUnlock> getPendingUnlocks(final BlockchainAddress user) {
    LiquidStaking.LiquidStakingState state = liquidStakingContract.getState();
    return state.pendingUnlocks().get(user);
  }

  /**
   * Retrieve the amount of tokens in the stake token pool.
   *
   * @return The amount of stake tokens in the pool.
   */
  private BigInteger totalPoolStakeToken() {
    LiquidStaking.LiquidStakingState liquidStakingState = liquidStakingContract.getState();
    return liquidStakingState.totalPoolStakeToken();
  }

  /**
   * Retrieve the amount of tokens in the liquid token pool.
   *
   * @return The amount of liquid tokens in the pool.
   */
  private BigInteger totalPoolLiquidToken() {
    LiquidStaking.LiquidStakingState liquidStakingState = liquidStakingContract.getState();
    return liquidStakingState.totalPoolLiquid();
  }

  /** Assert that the contract is in an initial state before testing. */
  private void assertInitialLiquidStakingState() {
    LiquidStaking.LiquidStakingState initialState = liquidStakingContract.getState();
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
  private void assertLiquidStakingStateInvariant() {
    LiquidStaking.LiquidStakingState liquidStakingState = liquidStakingContract.getState();

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
  private void assertTokenStateForLiquidStakingContract(int balance) {
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
  private void assertTokenState(BlockchainAddress user, int balance, int allowedAmount) {
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
  private void assertPoolAmounts(int stakeTokenPoolAmount, int liquidPoolAmount) {
    assertThat(totalPoolStakeToken()).isEqualTo(stakeTokenPoolAmount);
    assertThat(totalPoolLiquidToken()).isEqualTo(liquidPoolAmount);
  }

  /**
   * Assert that the buy in percentage is as expected.
   *
   * @param buyInEnabled A flag indicating whether we expect the buy in to be enabled or disabled.
   * @param buyInPercentage The expected buy in percentage.
   */
  private void assertBuyInPercentage(boolean buyInEnabled, int buyInPercentage) {
    LiquidStaking.LiquidStakingState liquidStakingState = liquidStakingContract.getState();
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
  private void initialSetup(
      int initialSubmit,
      int initialReward,
      int initialWithdraw,
      int buyInPercentageAfterInitialSubmit) {
    assertInitialLiquidStakingState();

    if (initialSubmit != 0) {
      submit(user1, initialSubmit);
      assertThat(getLiquidBalance(user1)).isEqualTo(initialSubmit);
    }
    if (initialReward != 0) {
      accrueRewards(stakingResponsible, initialReward);
    }
    if (initialWithdraw != 0) {
      withdraw(stakingResponsible, initialWithdraw);
      approveTransferToLiquidStakingContract(stakingResponsible, initialWithdraw);
    }

    if (buyInPercentageAfterInitialSubmit != 0) {
      changeBuyIn(admin, buyInPercentageAfterInitialSubmit);
    }

    assertBuyInPercentage(true, buyInPercentageAfterInitialSubmit);
    assertThat(totalPoolStakeToken()).isEqualTo(initialSubmit + initialReward);
    assertThat(totalPoolLiquidToken()).isEqualTo(initialSubmit);
    assertLiquidStakingStateInvariant();
  }

  /**
   * Retrieve the state of the Token.
   *
   * @return The state of the token contract.
   */
  private Token.TokenState getTokenContractState() {
    return Token.TokenState.deserialize(blockchain.getContractState(stakeTokenAddress));
  }

  /**
   * Deploy a token contract.
   *
   * @return The blockchain address of the token contract.
   */
  private BlockchainAddress deployStakeTokenContract() {
    String tokenName = "Coin";
    String tokenSymbol = "Test Coin";

    byte[] tokenInitRpc =
        Token.initialize(tokenName, tokenSymbol, (byte) 18, BigInteger.valueOf(STAKE_TOKEN_SUPPLY));
    BlockchainAddress address =
        blockchain.deployContract(stakeTokenOwner, contractBytesToken, tokenInitRpc);

    final Token.TokenState state =
        Token.TokenState.deserialize(blockchain.getContractState(address));

    assertThat(state.name()).isEqualTo(tokenName);
    assertThat(state.symbol()).isEqualTo(tokenSymbol);
    assertThat(state.totalSupply()).isEqualTo(STAKE_TOKEN_SUPPLY);
    return address;
  }

  /**
   * Deploy the liquid staking contract.
   *
   * @return The blockchain address of the liquid staking contract.
   */
  private BlockchainAddress deployLiquidStakingContract() {
    byte[] initRpc =
        LiquidStaking.initialize(
            stakeTokenAddress,
            stakingResponsible,
            admin,
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
  private void provideFundsToAccount(BlockchainAddress account, int amount) {
    byte[] transferRpc = Token.transfer(account, BigInteger.valueOf(amount));
    blockchain.sendAction(stakeTokenOwner, stakeTokenAddress, transferRpc);
  }

  /**
   * Allow the liquid staking contract to transfer tokens on behalf of the token owner.
   *
   * @param account Owner of the tokens that approves that another user can transfer tokens.
   * @param amount The amount of tokens to be approved.
   */
  private void approveTransferToLiquidStakingContract(BlockchainAddress account, int amount) {
    byte[] approveRpc = Token.approve(liquidStakingAddress, BigInteger.valueOf(amount));
    blockchain.sendAction(account, stakeTokenAddress, approveRpc);
  }
}
