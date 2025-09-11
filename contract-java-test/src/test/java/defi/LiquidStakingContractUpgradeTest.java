package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquidStaking;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.abicodegen.previous.LiquidStakingV1;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import java.math.BigInteger;
import java.nio.file.Path;
import org.assertj.core.api.Assertions;

/**
 * Tests for testing the successful upgrade process for {@link LiquidStakingContract}.
 *
 * @see LiquidStakingContractTest LiquidStakingContractTest for tests of the upgrade permission
 *     system error cases.
 */
public final class LiquidStakingContractUpgradeTest extends JunitContractTest {

  /** {@link ContractBytes} for the current {@link LiquidStakingContract} contract. */
  static final ContractBytes CONTRACT_BYTES_CURRENT = LiquidStakingContractTest.CONTRACT_BYTES;

  /** {@link ContractBytes} for the current {@link LiquidStakingContract} contract. */
  static final ContractBytes CONTRACT_BYTES_V1 =
      ContractBytes.fromPbcFile(
          Path.of("src/test/resources/defi/previous-versions/liquid_staking_v1.pbc"), null);

  private static final BigInteger USER_STAKE_AMOUNT = BigInteger.valueOf(1_000);
  private static final BigInteger USER_REQUEST_UNLOCK_AMOUNT = BigInteger.valueOf(100);

  BlockchainAddress administrator;
  BlockchainAddress user;
  BlockchainAddress liquidStakingContractAddress;
  BlockchainAddress tokenContractAddress;

  /** {@link #CONTRACT_BYTES_V1} can be deployed. */
  @ContractTest
  public void deployV1() {
    administrator = blockchain.newAccount(2);
    user = blockchain.newAccount(3);

    liquidStakingContractAddress =
        LiquidStakingContractTest.deployAndInitializeLiquidStakingContractWithUnderlyingToken(
            blockchain,
            administrator,
            "Test Token",
            "TEST",
            (byte) 0,
            administrator,
            BigInteger.valueOf(2_000_000),
            BigInteger.valueOf(1_000_000),
            CONTRACT_BYTES_V1);

    setupPendingUnlocks();
  }

  /**
   * {@link #CONTRACT_BYTES_V1} can be upgraded to {@link CONTRACT_BYTES_CURRENT} by administrator.
   */
  @ContractTest(previous = "deployV1")
  public void upgradeToV2() {
    blockchain.upgradeContract(
        administrator, liquidStakingContractAddress, CONTRACT_BYTES_CURRENT, new byte[0]);

    // Ensure that contract was correctly upgraded
    Assertions.assertThat(liquidStakingState().pendingUnlocks().get(administrator).get(0).id())
        .isEqualTo(1);
    Assertions.assertThat(liquidStakingState().pendingUnlocks().get(user).get(0).id()).isEqualTo(2);
    Assertions.assertThat(liquidStakingState().pendingUnlocks().get(user).get(1).id()).isEqualTo(3);
    Assertions.assertThat(liquidStakingState().pendingUnlockIdCounter()).isEqualTo(4);
  }

  /** Can cancel upgraded pending unlocks. */
  @ContractTest(previous = "upgradeToV2")
  public void canCancelUpgradedPendingUnlocks() {
    blockchain.sendAction(user, liquidStakingContractAddress, LiquidStaking.cancelPendingUnlock(2));

    Assertions.assertThat(liquidStakingState().pendingUnlocks().get(user)).hasSize(1);
    Assertions.assertThat(liquidStakingState().pendingUnlocks().get(user).get(0).id()).isEqualTo(3);
    Assertions.assertThat(liquidStakingState().pendingUnlocks().get(administrator)).hasSize(1);
  }

  private void setupPendingUnlocks() {
    tokenContractAddress = liquidStakingV1State().tokenForStaking();

    blockchain.sendAction(
        administrator, tokenContractAddress, Token.transfer(user, USER_STAKE_AMOUNT));
    blockchain.sendAction(
        user, tokenContractAddress, Token.approve(liquidStakingContractAddress, USER_STAKE_AMOUNT));
    blockchain.sendAction(
        user, liquidStakingContractAddress, LiquidStakingV1.submit(USER_STAKE_AMOUNT));

    blockchain.sendAction(
        user,
        liquidStakingContractAddress,
        LiquidStakingV1.requestUnlock(USER_REQUEST_UNLOCK_AMOUNT));
    blockchain.sendAction(
        user,
        liquidStakingContractAddress,
        LiquidStakingV1.requestUnlock(USER_REQUEST_UNLOCK_AMOUNT));
    blockchain.sendAction(
        administrator,
        liquidStakingContractAddress,
        LiquidStakingV1.requestUnlock(USER_REQUEST_UNLOCK_AMOUNT));
  }

  private LiquidStaking.LiquidStakingState liquidStakingState() {
    final LiquidStaking liquidStakingContract =
        new LiquidStaking(getStateClient(), liquidStakingContractAddress);
    return liquidStakingContract.getState();
  }

  private LiquidStakingV1.LiquidStakingState liquidStakingV1State() {
    final LiquidStakingV1 liquidStakingContract =
        new LiquidStakingV1(getStateClient(), liquidStakingContractAddress);
    return liquidStakingContract.getState();
  }
}
