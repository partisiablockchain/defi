package defi;

import com.partisiablockchain.language.abicodegen.LiquidStaking;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import defi.properties.LiquidStakingBaseTest;
import org.assertj.core.api.Assertions;

/**
 * Tests for testing the successful upgrade process for {@link LiquidStakingContract}.
 *
 * @see LiquidStakingContractTest LiquidStakingContractTest for tests of the upgrade permission
 *     system error cases.
 */
public final class LiquidStakingContractUpgradeTest extends LiquidStakingBaseTest {

  /** {@link ContractBytes} for the current {@link LiquidStakingContract} contract. */
  static final ContractBytes CONTRACT_BYTES_CURRENT = LiquidStakingContractTest.CONTRACT_BYTES;

  /** The current version of the {@link LiquidStaking} contract for testing. */
  @ContractTest
  void setupCurrentVersion() {
    initializeUsersAndDeployTokenAndLiquidStakingContract(
        CONTRACT_BYTES_CURRENT, TokenContractTest.CONTRACT_BYTES);
  }

  /** The staking responsible cannot upgrade the contract. */
  @ContractTest(previous = "setupCurrentVersion")
  void stakingResponsibleCannotUpgrade() {
    initialSetupWithAsserts(20, 0, 0, 0);

    Assertions.assertThat(getLiquidBalance(user1)).isEqualTo(20);
    assertLiquidStakingStateInvariant();

    Assertions.assertThatThrownBy(
            () ->
                blockchain.upgradeContract(
                    stakingResponsible, liquidStakingAddress, CONTRACT_BYTES_CURRENT, new byte[0]))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Contract did not allow this upgrade");

    assertLiquidStakingStateInvariant();
  }

  /** A user cannot upgrade the contract. */
  @ContractTest(previous = "setupCurrentVersion")
  void userCannotUpgrade() {
    initialSetupWithAsserts(20, 0, 0, 0);

    Assertions.assertThat(getLiquidBalance(user1)).isEqualTo(20);
    assertLiquidStakingStateInvariant();

    Assertions.assertThatThrownBy(
            () ->
                blockchain.upgradeContract(
                    user1, liquidStakingAddress, CONTRACT_BYTES_CURRENT, new byte[0]))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Contract did not allow this upgrade");

    assertLiquidStakingStateInvariant();
  }
}
