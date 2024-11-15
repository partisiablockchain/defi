package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwapLock;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import java.math.BigInteger;
import java.nio.file.Path;
import org.assertj.core.api.Assertions;

/** {@link LiquiditySwapLock} testing for a specific exploit. */
public final class LiquiditySwapRegressionTest extends LiquiditySwapLockBaseTest {
  static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/liquidity_swap_lock.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/liquidity_swap_lock_runner"));

  private static final BigInteger INITIAL_LIQUIDITY_A = new BigInteger("1000000000");
  private static final BigInteger INITIAL_LIQUIDITY_B = new BigInteger("1000000000");

  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_A = new BigInteger("2000000000");
  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_B = new BigInteger("2000000000");

  private static final BigInteger TOTAL_SUPPLY_A =
      INITIAL_LIQUIDITY_A.add(NON_OWNER_TOKEN_AMOUNT_A);
  private static final BigInteger TOTAL_SUPPLY_B =
      INITIAL_LIQUIDITY_B.add(NON_OWNER_TOKEN_AMOUNT_B);

  private static final BigInteger INITIAL_LIQUIDITY_TOKENS_AB = new BigInteger("1000000000");

  private BlockchainAddress contractOwnerAddress;
  private BlockchainAddress nonOwnerAddress1;
  private BlockchainAddress swapLockContractAddress;

  private BlockchainAddress contractTokenA;
  private BlockchainAddress contractTokenB;

  @Override
  BlockchainAddress swapLockContractAddressAtoB() {
    return swapLockContractAddress;
  }

  @Override
  BlockchainAddress swapLockContractAddressBtoC() {
    return null;
  }

  @Override
  BlockchainAddress contractTokenA() {
    return contractTokenA;
  }

  @Override
  BlockchainAddress contractTokenB() {
    return contractTokenB;
  }

  @Override
  BlockchainAddress contractTokenC() {
    return null;
  }

  @Override
  BlockchainAddress contractTokenD() {
    return null;
  }

  static final LiquiditySwapTestingUtility swapUtil = new LiquiditySwapTestingUtility();

  /** The contract can be correctly deployed. */
  @ContractTest
  void contractInit() {
    contractOwnerAddress = blockchain.newAccount(3);
    nonOwnerAddress1 = blockchain.newAccount(5);

    // Deploy token contracts.
    initializeTokenContracts(contractOwnerAddress);

    // Give tokens to non owners, to perform swaps.
    provideNonOwnersWithInitialTokens();

    // Deploy the LiquiditySwapContracts.
    byte[] initRpcSwap =
        LiquiditySwapLock.initialize(
            contractTokenA, contractTokenB, (short) 0, new LiquiditySwapLock.PermissionAnybody());
    swapLockContractAddress =
        blockchain.deployContract(contractOwnerAddress, CONTRACT_BYTES, initRpcSwap);

    // Provide initial liquidity
    provideInitialLiquidity();

    // Check that initial virtual liquidity state matches actual (both 0).
    assertLiquidityInvariant(swapLockContractAddress);
  }

  /**
   * Regression test for the exploit found by PBM.
   *
   * <p>The exploit here uses the fact that a unified virtual liquidity pool does not track the
   * extreme values correctly, allowing an attacker to manipulate the virtual pool in a fashion that
   * allows them to extract most of the liquidity available in the contract.
   */
  @ContractTest(previous = "contractInit")
  void regressionTestForPeterExploit() {

    depositIntoSwap(
        swapLockContractAddress, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    depositIntoSwap(
        swapLockContractAddress, nonOwnerAddress1, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B);

    final LiquiditySwapLock.LiquidityLockId lockId1 =
        acquireLock(
            swapLockContractAddressAtoB(),
            nonOwnerAddress1,
            contractTokenA,
            new BigInteger("10000000000"),
            ZERO);

    final LiquiditySwapLock.LiquidityLockId lockId2 =
        acquireLock(
            swapLockContractAddressAtoB(),
            nonOwnerAddress1,
            contractTokenB,
            INITIAL_LIQUIDITY_A.subtract(BigInteger.ONE),
            ZERO);

    final LiquiditySwapLock.LiquidityLockId lockId3 =
        acquireLock(
            swapLockContractAddressAtoB(),
            nonOwnerAddress1,
            contractTokenB,
            INITIAL_LIQUIDITY_B.subtract(BigInteger.ONE),
            ZERO);

    Assertions.assertThat(
            getContractLiquidityConstantAfterLocksExecuted(swapLockContractAddressAtoB()))
        .isGreaterThanOrEqualTo(INITIAL_LIQUIDITY_TOKENS_AB);

    blockchain.sendAction(
        nonOwnerAddress1, swapLockContractAddress, LiquiditySwapLock.cancelLock(lockId1));

    executeLockSwap(nonOwnerAddress1, lockId2);

    executeLockSwap(nonOwnerAddress1, lockId3);
    Assertions.assertThat(
            getContractLiquidityConstantAfterLocksExecuted(swapLockContractAddressAtoB()))
        .isGreaterThanOrEqualTo(INITIAL_LIQUIDITY_TOKENS_AB);
  }

  private void provideInitialLiquidity() {
    depositIntoSwap(
        swapLockContractAddress, contractOwnerAddress, contractTokenA, INITIAL_LIQUIDITY_A);
    depositIntoSwap(
        swapLockContractAddress, contractOwnerAddress, contractTokenB, INITIAL_LIQUIDITY_B);
    blockchain.sendAction(
        contractOwnerAddress,
        swapLockContractAddress,
        LiquiditySwapLock.provideInitialLiquidity(INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B));
  }

  private void provideNonOwnersWithInitialTokens() {
    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenA,
        Token.transfer(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_A));
    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenB,
        Token.transfer(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_B));
  }

  private void initializeTokenContracts(BlockchainAddress owner) {
    byte[] initRpcA = Token.initialize("Token A", "A", (byte) 8, TOTAL_SUPPLY_A);
    contractTokenA = blockchain.deployContract(owner, TokenContractTest.CONTRACT_BYTES, initRpcA);

    byte[] initRpcB = Token.initialize("Token B", "B", (byte) 8, TOTAL_SUPPLY_B);
    contractTokenB = blockchain.deployContract(owner, TokenContractTest.CONTRACT_BYTES, initRpcB);
  }
}
