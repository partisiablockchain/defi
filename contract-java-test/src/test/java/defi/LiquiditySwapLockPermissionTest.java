package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwapLock;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;

/** {@link LiquiditySwapLock} permission and timeout testing. */
public final class LiquiditySwapLockPermissionTest extends LiquiditySwapLockBaseTest {
  private static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPaths(
          Path.of("../rust/target/wasm32-unknown-unknown/release/liquidity_swap_lock.wasm"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/liquidity_swap_lock.abi"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/liquidity_swap_lock_runner"));

  private static final BigInteger ZERO = BigInteger.ZERO;

  private static final short FEE = 3;

  private static final BigInteger TOTAL_SUPPLY_A = BigInteger.ONE.shiftLeft(105);
  private static final BigInteger TOTAL_SUPPLY_B = BigInteger.ONE.shiftLeft(100);

  private static final BigInteger INITIAL_LIQUIDITY_A = BigInteger.ONE.shiftLeft(52);
  private static final BigInteger INITIAL_LIQUIDITY_B = BigInteger.ONE.shiftLeft(50);

  private static final BigInteger INITIAL_LIQUIDITY_TOKENS_AB = BigInteger.ONE.shiftLeft(51);

  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_A = BigInteger.ONE.shiftLeft(45);
  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_B = BigInteger.ONE.shiftLeft(44);

  private BlockchainAddress contractOwnerAddress;
  private BlockchainAddress nonOwnerAddress1;
  private BlockchainAddress nonOwnerAddress2;
  private BlockchainAddress swapLockContractAddressAtoB;

  private BlockchainAddress contractTokenA;
  private BlockchainAddress contractTokenB;

  @Override
  BlockchainAddress swapLockContractAddressAtoB() {
    return swapLockContractAddressAtoB;
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

  @BeforeEach
  void setUp() {
    lockTrackerAB = new HashSet<>();
  }

  @ContractTest
  void setupTokenContracts() {
    contractOwnerAddress = blockchain.newAccount(5);
    nonOwnerAddress1 = blockchain.newAccount(7);
    nonOwnerAddress2 = blockchain.newAccount(11);

    // Deploy token contracts.
    byte[] initRpcA = Token.initialize("Token A", "A", (byte) 8, TOTAL_SUPPLY_A);
    contractTokenA =
        blockchain.deployContract(contractOwnerAddress, TokenContractTest.CONTRACT_BYTES, initRpcA);

    byte[] initRpcB = Token.initialize("Token B", "B", (byte) 8, TOTAL_SUPPLY_B);
    contractTokenB =
        blockchain.deployContract(contractOwnerAddress, TokenContractTest.CONTRACT_BYTES, initRpcB);

    // Give tokens to non owners, to perform swaps.
    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenA,
        Token.transfer(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_A));

    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenB,
        Token.transfer(nonOwnerAddress2, NON_OWNER_TOKEN_AMOUNT_B));
  }

  /** The swap contract can be deployed with 'Anybody' permissions. */
  @ContractTest(previous = "setupTokenContracts")
  void contractInitAnybody() {
    // Deploy the LiquiditySwapContract.
    byte[] initRpcSwapAtoB =
        LiquiditySwapLock.initialize(
            contractTokenA, contractTokenB, FEE, new LiquiditySwapLock.PermissionAnybody());
    swapLockContractAddressAtoB =
        blockchain.deployContract(contractOwnerAddress, CONTRACT_BYTES, initRpcSwapAtoB);

    // Provide initial liquidity
    provideInitialLiquidity();

    // Check state has been correctly initialized.
    assertInitialContractState();
  }

  /** The swap contract can be deployed with specific permissions. */
  @ContractTest(previous = "setupTokenContracts")
  void contractInitSpecific() {
    // Deploy the LiquiditySwapContract.
    byte[] initRpcSwapAtoB =
        LiquiditySwapLock.initialize(
            contractTokenA,
            contractTokenB,
            FEE,
            new LiquiditySwapLock.PermissionSpecific(List.of(nonOwnerAddress1)));
    swapLockContractAddressAtoB =
        blockchain.deployContract(contractOwnerAddress, CONTRACT_BYTES, initRpcSwapAtoB);

    // Provide initial liquidity
    provideInitialLiquidity();

    // Check state has been correctly initialized.
    assertInitialContractState();
  }

  /** Any user can acquire locks, when the permission is 'Anybody'. */
  @ContractTest(previous = "contractInitAnybody")
  void anybodyAcquireLock() {
    // Creator can acquire locks.
    lock(contractOwnerAddress, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, ZERO);
    lock(contractOwnerAddress, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B, ZERO);

    // Two other users can also acquire locks.
    lock(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, ZERO);
    lock(nonOwnerAddress1, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B, ZERO);

    lock(nonOwnerAddress2, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, ZERO);
    lock(nonOwnerAddress2, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B, ZERO);
  }

  /** Only allowed users can acquire locks, when the permission is specific. */
  @ContractTest(previous = "contractInitSpecific")
  void specificUserAcquireLock() {
    // The allowed user can acquire locks.
    lock(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, ZERO);

    // A user without permissions cannot acquire a lock.
    Assertions.assertThatCode(
            () -> lock(nonOwnerAddress2, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, ZERO))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Address Account %s did not have permission \"lock swap\"",
            addressAsFormattedByteString(nonOwnerAddress2));

    // Even the creator cannot acquire a lock, when not permitted to.
    Assertions.assertThatCode(
            () -> lock(contractOwnerAddress, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, ZERO))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Address Account %s did not have permission \"lock swap\"",
            addressAsFormattedByteString(contractOwnerAddress));
  }

  private void provideInitialLiquidity() {
    depositIntoSwap(contractOwnerAddress, contractTokenA, INITIAL_LIQUIDITY_A);
    depositIntoSwap(contractOwnerAddress, contractTokenB, INITIAL_LIQUIDITY_B);
    blockchain.sendAction(
        contractOwnerAddress,
        swapLockContractAddressAtoB,
        LiquiditySwapLock.provideInitialLiquidity(INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B));
  }

  private void assertInitialContractState() {
    LiquiditySwapLock.LiquiditySwapContractState state = getSwapState();

    // Check default values of initial contract state
    Assertions.assertThat(state).isNotNull();
    Assertions.assertThat(state.liquidityPoolAddress()).isEqualTo(swapLockContractAddressAtoB);
    Assertions.assertThat(state.swapFeePerMille()).isEqualTo(FEE);
    Assertions.assertThat(state.tokenBalances()).isNotNull();
    Assertions.assertThat(state.virtualState()).isNotNull();

    // Check initial value of balances in state.
    LiquiditySwapLock.TokenBalances b = state.tokenBalances();
    Assertions.assertThat(b.tokenAAddress()).isEqualTo(contractTokenA);
    Assertions.assertThat(b.tokenBAddress()).isEqualTo(contractTokenB);
    Assertions.assertThat(b.balances().get(swapLockContractAddressAtoB))
        .isEqualTo(
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B, INITIAL_LIQUIDITY_TOKENS_AB));

    // Check initial state of virtual balances.
    LiquiditySwapLock.VirtualState vb = state.virtualState();
    Assertions.assertThat(vb.locks().getNextN(null, 100)).isEmpty();
    Assertions.assertThat(vb.nextLockId())
        .isEqualTo(new LiquiditySwapLock.LiquidityLockId(BigInteger.ZERO));
  }

  /**
   * Formats a blockchain address as a string of the address identifier. The format is the same as
   * rust contracts, e.g. [1a, ff, ..]. 0 prefixes are removed, e.g. 0b becomes b.
   *
   * @param address the address to the formatted.
   * @return string representing the formatted address identifier.
   */
  public static String addressAsFormattedByteString(BlockchainAddress address) {
    String s = address.writeAsString();

    StringBuilder res = new StringBuilder();
    res.append("[");

    for (int i = 2; i < s.length() - 1; i += 2) {
      String tmp = s.substring(i, i + 2);
      // Remove 0 prefix for byte.
      tmp = tmp.replaceAll("0(\\d|[a-f])", "$1");
      res.append(tmp);
      res.append(", ");
    }

    res.delete(res.length() - 2, res.length());
    res.append("]");

    return res.toString();
  }
}
