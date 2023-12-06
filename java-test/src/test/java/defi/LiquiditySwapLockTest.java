package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwapLock;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.testenvironment.TxExecution;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.HashSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;

/** {@link LiquiditySwapLock} testing. */
public final class LiquiditySwapLockTest extends LiquiditySwapLockBaseTest {
  static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPaths(
          Path.of("../target/wasm32-unknown-unknown/release/liquidity_swap_lock.wasm"),
          Path.of("../target/wasm32-unknown-unknown/release/liquidity_swap_lock.abi"),
          Path.of("../target/wasm32-unknown-unknown/release/liquidity_swap_lock_runner"));

  private static final BigInteger TOTAL_SUPPLY_A = BigInteger.ONE.shiftLeft(105);
  private static final BigInteger TOTAL_SUPPLY_B = BigInteger.ONE.shiftLeft(100);
  private static final BigInteger TOTAL_SUPPLY_C = BigInteger.ONE.shiftLeft(95);
  private static final BigInteger TOTAL_SUPPLY_D = BigInteger.ONE.shiftLeft(90);

  private static final BigInteger INITIAL_LIQUIDITY_A = BigInteger.ONE.shiftLeft(52);
  private static final BigInteger INITIAL_LIQUIDITY_B = BigInteger.ONE.shiftLeft(50);
  private static final BigInteger INITIAL_LIQUIDITY_C = BigInteger.ONE.shiftLeft(48);
  private static final BigInteger INITIAL_LIQUIDITY_D = BigInteger.ONE.shiftLeft(46);

  private static final BigInteger INITIAL_LIQUIDITY_TOKENS_AB = BigInteger.ONE.shiftLeft(51);
  private static final BigInteger INITIAL_LIQUIDITY_TOKENS_BC = BigInteger.ONE.shiftLeft(49);
  private static final BigInteger INITIAL_LIQUIDITY_TOKENS_CD = BigInteger.ONE.shiftLeft(47);

  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_A = BigInteger.ONE.shiftLeft(45);
  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_B = BigInteger.ONE.shiftLeft(44);
  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_C = BigInteger.ONE.shiftLeft(43);
  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_D = BigInteger.ONE.shiftLeft(42);

  private BlockchainAddress contractOwnerAddress;
  private BlockchainAddress nonOwnerAddress1;
  private BlockchainAddress nonOwnerAddress2;
  private BlockchainAddress swapLockContractAddressAtoB;
  private BlockchainAddress swapLockContractAddressBtoC;
  private BlockchainAddress swapLockContractAddressCtoD;

  private BlockchainAddress contractTokenA;
  private BlockchainAddress contractTokenB;
  private BlockchainAddress contractTokenC;
  private BlockchainAddress contractTokenD;

  @Override
  BlockchainAddress swapLockContractAddressAtoB() {
    return swapLockContractAddressAtoB;
  }

  @Override
  BlockchainAddress swapLockContractAddressBtoC() {
    return swapLockContractAddressBtoC;
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
    return contractTokenC;
  }

  @Override
  BlockchainAddress contractTokenD() {
    return contractTokenD;
  }

  static final LiquiditySwapTestingUtility swapUtil = new LiquiditySwapTestingUtility();

  @BeforeEach
  void setUp() {
    lockTrackerAB = new HashSet<>();
    lockTrackerBC = new HashSet<>();
    lockTrackerCD = new HashSet<>();
  }

  /** The contract can be correctly deployed. */
  @ContractTest
  void contractInit() {
    contractOwnerAddress = blockchain.newAccount(3);
    nonOwnerAddress1 = blockchain.newAccount(5);
    nonOwnerAddress2 = blockchain.newAccount(7);

    // Deploy token contracts.
    initializeTokenContracts(contractOwnerAddress);

    // Give tokens to non owners, to perform swaps.
    provideNonOwnersWithInitialTokens();

    // Deploy the LiquiditySwapContracts.
    byte[] initRpcSwapAtoB =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenA, contractTokenB, FEE);
    swapLockContractAddressAtoB =
        blockchain.deployContract(contractOwnerAddress, CONTRACT_BYTES, initRpcSwapAtoB);

    byte[] initRpcSwapBtoC =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenB, contractTokenC, FEE);
    swapLockContractAddressBtoC =
        blockchain.deployContract(contractOwnerAddress, CONTRACT_BYTES, initRpcSwapBtoC);

    byte[] initRpcSwapCtoD =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenC, contractTokenD, FEE);
    swapLockContractAddressCtoD =
        blockchain.deployContract(contractOwnerAddress, CONTRACT_BYTES, initRpcSwapCtoD);

    // Provide initial liquidity
    provideInitialLiquidity();

    // Check state has been correctly initialized.
    assertInitialContractState(
        swapLockContractAddressAtoB,
        contractTokenA,
        contractTokenB,
        INITIAL_LIQUIDITY_A,
        INITIAL_LIQUIDITY_B,
        INITIAL_LIQUIDITY_TOKENS_AB);
    assertInitialContractState(
        swapLockContractAddressBtoC,
        contractTokenB,
        contractTokenC,
        INITIAL_LIQUIDITY_B,
        INITIAL_LIQUIDITY_C,
        INITIAL_LIQUIDITY_TOKENS_BC);
    assertInitialContractState(
        swapLockContractAddressCtoD,
        contractTokenC,
        contractTokenD,
        INITIAL_LIQUIDITY_C,
        INITIAL_LIQUIDITY_D,
        INITIAL_LIQUIDITY_TOKENS_CD);
  }

  /** The contract cannot be deployed with a swap fee less than 0 per mille. */
  @ContractTest
  void deployFeeTooLow() {
    contractOwnerAddress = blockchain.newAccount(3);

    // Deploy token contracts.
    initializeTokenContracts(contractOwnerAddress);

    byte[] initRpcSwapAtoB =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenA, contractTokenB, (short) -1);
    Assertions.assertThatCode(
            () -> blockchain.deployContract(contractOwnerAddress, CONTRACT_BYTES, initRpcSwapAtoB))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Swap fee must be in range [0,1000]");
  }

  /** The contract cannot be deployed with a swap fee above 1000 per mille. */
  @ContractTest
  void deployFeeTooHigh() {
    contractOwnerAddress = blockchain.newAccount(3);

    // Deploy token contracts.
    initializeTokenContracts(contractOwnerAddress);

    byte[] initRpcSwapAtoB =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(),
            contractTokenA,
            contractTokenB,
            (short) 1001);
    Assertions.assertThatCode(
            () -> blockchain.deployContract(contractOwnerAddress, CONTRACT_BYTES, initRpcSwapAtoB))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Swap fee must be in range [0,1000]");
  }

  /** The contract cannot be deployed with a token contract address which isn't a contract. */
  @ContractTest
  void deployUserAddressAsToken() {
    contractOwnerAddress = blockchain.newAccount(3);
    nonOwnerAddress1 = blockchain.newAccount(5);

    // Deploy token contracts.
    initializeTokenContracts(contractOwnerAddress);

    byte[] initRpcSwapAtoB =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), nonOwnerAddress1, contractTokenB, FEE);
    Assertions.assertThatCode(
            () -> blockchain.deployContract(contractOwnerAddress, CONTRACT_BYTES, initRpcSwapAtoB))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Token address A must be a contract address");
  }

  /**
   * A user cannot become an initial liquidity provider, if the liquidity would result in 0 minted
   * liquidity.
   */
  @ContractTest
  void provideTooLittleInitialLiquidity() {
    contractOwnerAddress = blockchain.newAccount(3);

    // Deploy token contracts.
    initializeTokenContracts(contractOwnerAddress);

    // Deploy the LiquiditySwapContract.
    byte[] initRpcSwapAtoB =
        LiquiditySwapLock.initialize(
            new LiquiditySwapLock.Permission.Anybody(), contractTokenA, contractTokenB, FEE);
    swapLockContractAddressAtoB =
        blockchain.deployContract(contractOwnerAddress, CONTRACT_BYTES, initRpcSwapAtoB);

    // Provide (too little) initial liquidity
    depositIntoSwap(
        swapLockContractAddressAtoB, contractOwnerAddress, contractTokenA, INITIAL_LIQUIDITY_A);
    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    contractOwnerAddress,
                    swapLockContractAddressAtoB,
                    LiquiditySwapLock.provideInitialLiquidity(INITIAL_LIQUIDITY_A, ZERO)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("The given input amount yielded 0 minted liquidity");
  }

  /** A user cannot become an initial liquidity provider, if the contract contains liquidity. */
  @ContractTest(previous = "contractInit")
  void provideInitialLiquidityLate() {
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B);
    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    nonOwnerAddress1,
                    swapLockContractAddressAtoB,
                    LiquiditySwapLock.provideInitialLiquidity(
                        NON_OWNER_TOKEN_AMOUNT_A, NON_OWNER_TOKEN_AMOUNT_B)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Can only initialize when both pools are empty");
  }

  /** A user cannot deposit more tokens than they own. */
  @ContractTest(previous = "contractInit")
  void depositMoreThanOwned() {
    BigInteger depositAmount = NON_OWNER_TOKEN_AMOUNT_A.add(BigInteger.ONE);

    // First approve
    blockchain.sendAction(
        nonOwnerAddress1,
        contractTokenA,
        Token.approve(swapLockContractAddressAtoB, depositAmount));

    // Send a deposit action
    TxExecution s1 =
        blockchain.sendActionAsync(
            nonOwnerAddress1,
            swapLockContractAddressAtoB,
            LiquiditySwapLock.deposit(contractTokenA, depositAmount));

    // Process the interaction.
    TxExecution s2 = s1.getContractInteraction();
    blockchain.executeEventAsync(s2);

    // Process the interaction between the swap and token contract.
    TxExecution s3 = s2.getContractInteraction();
    blockchain.executeEventAsync(s3);

    // Process the system callback.
    TxExecution s4 = s3.getSystemCallback();
    blockchain.executeEventAsync(s4);
    // Process the final contract callback.
    blockchain.executeEvent(s4.getContractCallback());

    // Check that the final error is as expected
    Assertions.assertThat(s4.getContractCallback().getFailureCause().getErrorMessage())
        .contains("Transfer did not succeed");
  }

  /** A user can acquire a lock, which updates the virtual balances of the contract. */
  @ContractTest(previous = "contractInit")
  void acquireLock() {
    final BigInteger swapAmountB =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    final LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            swapAmountB);
    Assertions.assertThat(getVirtualSwapState(swapLockContractAddressAtoB).locks()).hasSize(1);

    LiquiditySwapLock.LiquiditySwapContractState state = getSwapState(swapLockContractAddressAtoB);

    // actual state should be unchanged
    LiquiditySwapLock.TokenBalances b = state.tokenBalances();
    Assertions.assertThat(b.balances())
        .containsEntry(
            swapLockContractAddressAtoB,
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B, INITIAL_LIQUIDITY_TOKENS_AB));

    // Virtual state should be updated to contain the lock.
    assertThatLocksContain(
        getVirtualSwapState(swapLockContractAddressAtoB).locks(),
        lockId,
        new LiquiditySwapLock.LiquidityLock(
            NON_OWNER_TOKEN_AMOUNT_A, swapAmountB, swapUtil.tokenAinBout(), nonOwnerAddress1));
  }

  /** A user cannot acquire a lock when pools doesn't have liquidity. */
  @ContractTest(previous = "contractInit")
  void acquireLockFailWhenNoLiquidity() {
    // Contract owner reclaims all liquidity.
    blockchain.sendAction(
        contractOwnerAddress,
        swapLockContractAddressAtoB,
        LiquiditySwapLock.reclaimLiquidity(INITIAL_LIQUIDITY_TOKENS_AB));

    // Trying to acquire a lock fails.
    Assertions.assertThatCode(
            () ->
                lock(
                    swapLockContractAddressAtoB,
                    nonOwnerAddress1,
                    contractTokenA,
                    BigInteger.ONE,
                    ZERO))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Pools must have existing liquidity to acquire a lock");
  }

  /**
   * A user with a lock can execute the lock-swap, resulting in swapped tokens, and updated actual
   * and virtual balances.
   */
  @ContractTest(previous = "contractInit")
  void executeLockedSwap() {
    BigInteger receivingAmount =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            receivingAmount);

    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, lockId);

    LiquiditySwapLock.LiquiditySwapContractState state = getSwapState(swapLockContractAddressAtoB);

    // Actual and virtual balances should be updated to reflect the swap.
    LiquiditySwapLock.TokenBalances b = state.tokenBalances();
    Assertions.assertThat(b.balances())
        .containsEntry(
            swapLockContractAddressAtoB,
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A.add(NON_OWNER_TOKEN_AMOUNT_A),
                INITIAL_LIQUIDITY_B.subtract(receivingAmount),
                INITIAL_LIQUIDITY_TOKENS_AB));
    Assertions.assertThat(b.balances())
        .containsEntry(
            nonOwnerAddress1, new LiquiditySwapLock.TokenBalance(ZERO, receivingAmount, ZERO));

    // Virtual state should be updated
    Assertions.assertThat(getVirtualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(b.balances().get(swapLockContractAddressAtoB));
  }

  /** A user can withdraw tokens after performing a lock-swap, resulting in updates balances. */
  @ContractTest(previous = "contractInit")
  void withdrawAfterLockSwap() {
    BigInteger receivingAmount =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    // Get a lock.
    LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            receivingAmount);

    // Deposit and execute the lock.
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, lockId);

    // Now withdraw
    blockchain.sendAction(
        nonOwnerAddress1,
        swapLockContractAddressAtoB,
        LiquiditySwapLock.withdraw(contractTokenB, receivingAmount, false));

    LiquiditySwapLock.LiquiditySwapContractState state = getSwapState(swapLockContractAddressAtoB);

    // Actual and virtual balances should be updated to reflect the swap and withdraw.
    LiquiditySwapLock.TokenBalances b = state.tokenBalances();
    Assertions.assertThat(b.balances())
        .containsEntry(
            swapLockContractAddressAtoB,
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A.add(NON_OWNER_TOKEN_AMOUNT_A),
                INITIAL_LIQUIDITY_B.subtract(receivingAmount),
                INITIAL_LIQUIDITY_TOKENS_AB));
    Assertions.assertThat(b.balances()).doesNotContainKey(nonOwnerAddress1);

    // Virtual state should be updated
    Assertions.assertThat(getVirtualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(b.balances().get(swapLockContractAddressAtoB));

    // User has received the tokens at the token contract.
    Assertions.assertThat(getTokenState(contractTokenB).balances())
        .containsEntry(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_B.add(receivingAmount));
    Assertions.assertThat(getTokenState(contractTokenB).balances())
        .containsEntry(swapLockContractAddressAtoB, INITIAL_LIQUIDITY_B.subtract(receivingAmount));
  }

  /** A user cannot execute an acquired lock, when the lock is on more tokens than deposited. */
  @ContractTest(previous = "contractInit")
  void depositTooLowForLockExecute() {
    BigInteger receivingAmount =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.add(BigInteger.ONE),
            receivingAmount);

    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    Assertions.assertThatCode(
            () -> executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, lockId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Insufficient TokenA deposit: %s/%s",
            NON_OWNER_TOKEN_AMOUNT_A, NON_OWNER_TOKEN_AMOUNT_A.add(BigInteger.ONE));

    LiquiditySwapLock.LiquiditySwapContractState state = getSwapState(swapLockContractAddressAtoB);

    // Actual state is unchanged.
    LiquiditySwapLock.TokenBalances b = state.tokenBalances();
    Assertions.assertThat(b.balances())
        .containsEntry(
            swapLockContractAddressAtoB,
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B, INITIAL_LIQUIDITY_TOKENS_AB));

    // Virtual state still contains the lock.
    assertThatLocksContain(
        getVirtualSwapState(swapLockContractAddressAtoB).locks(),
        lockId,
        new LiquiditySwapLock.LiquidityLock(
            NON_OWNER_TOKEN_AMOUNT_A.add(BigInteger.ONE),
            receivingAmount.add(BigInteger.ONE),
            swapUtil.tokenAinBout(),
            nonOwnerAddress1));
  }

  /**
   * A user can cancel an acquired lock, resulting in no swapped tokens, along with updated virtual
   * balances.
   */
  @ContractTest(previous = "contractInit")
  void cancelAcquiredLock() {
    BigInteger receivingAmount =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.add(BigInteger.ONE),
            receivingAmount);

    blockchain.sendAction(
        nonOwnerAddress1, swapLockContractAddressAtoB, LiquiditySwapLock.cancelLock(lockId));

    LiquiditySwapLock.LiquiditySwapContractState state = getSwapState(swapLockContractAddressAtoB);

    // Both actual and virtual states are back to normal
    LiquiditySwapLock.TokenBalances b = state.tokenBalances();
    Assertions.assertThat(b.balances())
        .containsEntry(
            swapLockContractAddressAtoB,
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B, INITIAL_LIQUIDITY_TOKENS_AB));
    Assertions.assertThat(getVirtualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(getActualContractBalance(swapLockContractAddressAtoB));
  }

  /**
   * Multiple users can acquire locks at the same time, at the worse exchange rate of the actual and
   * virtual pools.
   */
  @ContractTest(previous = "contractInit")
  void multipleLocksDifferentUsers() {
    BigInteger receivingAmountOne =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    LiquiditySwapLock.LiquidityLockId lockOne =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            ZERO);

    // The second lock exchange rate is based on the virtual balances (where there is more Token A
    // after the first lock):
    BigInteger receivingAmountTwoNoLock =
        calculateReceivingAmountNoLock(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    BigInteger receivingAmountTwoLocked =
        calculateReceivingAmountLocked(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    LiquiditySwapLock.LiquidityLockId lockTwo =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress2,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            ZERO);

    Assertions.assertThat(getVirtualSwapState(swapLockContractAddressAtoB).locks()).hasSize(2);

    Assertions.assertThat(receivingAmountTwoLocked).isLessThan(receivingAmountTwoNoLock);
    Assertions.assertThat(receivingAmountTwoLocked).isLessThan(receivingAmountOne);

    LiquiditySwapLock.VirtualState state = getVirtualSwapState(swapLockContractAddressAtoB);
    Assertions.assertThat(state.locks().get(lockOne).amountOut()).isEqualTo(receivingAmountOne);
    Assertions.assertThat(state.locks().get(lockTwo).amountOut())
        .isEqualTo(receivingAmountTwoLocked);

    // Acquire big lock on B
    lock(
        swapLockContractAddressAtoB,
        nonOwnerAddress1,
        contractTokenB,
        NON_OWNER_TOKEN_AMOUNT_A.multiply(BigInteger.TWO),
        ZERO);

    // Now the worse exchange rate is based on the actual balances.
    BigInteger receivingAmountThreeLock =
        calculateReceivingAmountLocked(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    BigInteger receivingAmountThreeNoLock =
        calculateReceivingAmountNoLock(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    Assertions.assertThat(receivingAmountThreeNoLock).isLessThan(receivingAmountThreeLock);

    LiquiditySwapLock.LiquidityLockId lockThree =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress2,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            ZERO);
    Assertions.assertThat(
            getVirtualSwapState(swapLockContractAddressAtoB).locks().get(lockThree).amountOut())
        .isEqualTo(receivingAmountThreeNoLock);
  }

  /** If a user executes the last lock, the actual and virtual balances agree. */
  @ContractTest(previous = "contractInit")
  void executeLastLock() {
    // Acquire multiple locks.
    final LiquiditySwapLock.LiquidityLockId lockOne =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO),
            ZERO);
    final LiquiditySwapLock.LiquidityLockId lockTwo =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress2,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO),
            ZERO);

    // Check that states are different
    Assertions.assertThat(getActualContractBalance(swapLockContractAddressAtoB))
        .isNotEqualTo(getVirtualContractBalance(swapLockContractAddressAtoB));

    // Deposit and execute 1 lock
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, lockOne);

    // States are still different
    Assertions.assertThat(getActualContractBalance(swapLockContractAddressAtoB))
        .isNotEqualTo(getVirtualContractBalance(swapLockContractAddressAtoB));

    // Execute the second (last) lock, and states should match
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress2, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    executeLockSwap(nonOwnerAddress2, swapLockContractAddressAtoB, lockTwo);
    Assertions.assertThat(getActualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(getVirtualContractBalance(swapLockContractAddressAtoB));
  }

  /** If a user cancels the last lock, the actual and virtual balances agree. */
  @ContractTest(previous = "contractInit")
  void cancelLastLock() {
    // Acquire multiple locks.
    final LiquiditySwapLock.LiquidityLockId lockOne =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO),
            ZERO);
    final LiquiditySwapLock.LiquidityLockId lockTwo =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress2,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO),
            ZERO);

    // Check that states are different
    Assertions.assertThat(getActualContractBalance(swapLockContractAddressAtoB))
        .isNotEqualTo(getVirtualContractBalance(swapLockContractAddressAtoB));

    // Cancel the first lock
    blockchain.sendAction(
        nonOwnerAddress1, swapLockContractAddressAtoB, LiquiditySwapLock.cancelLock(lockOne));

    // States are still different
    Assertions.assertThat(getActualContractBalance(swapLockContractAddressAtoB))
        .isNotEqualTo(getVirtualContractBalance(swapLockContractAddressAtoB));

    // Cancel the second (last) lock, and states should match
    blockchain.sendAction(
        nonOwnerAddress2, swapLockContractAddressAtoB, LiquiditySwapLock.cancelLock(lockTwo));
    Assertions.assertThat(getActualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(getVirtualContractBalance(swapLockContractAddressAtoB));
  }

  /** A single user can acquire multiple locks, which all update the virtual balance. */
  @ContractTest(previous = "contractInit")
  void singleUserMultipleLocks() {
    BigInteger receivingAmountOne =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    lock(
        swapLockContractAddressAtoB,
        nonOwnerAddress1,
        contractTokenA,
        NON_OWNER_TOKEN_AMOUNT_A,
        ZERO);

    Assertions.assertThat(getVirtualSwapState(swapLockContractAddressAtoB).locks()).hasSize(1);
    Assertions.assertThat(getVirtualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A.add(NON_OWNER_TOKEN_AMOUNT_A),
                INITIAL_LIQUIDITY_B.subtract(receivingAmountOne),
                ZERO));

    BigInteger receivingAmountTwo =
        calculateReceivingAmountLocked(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_B);
    lock(
        swapLockContractAddressAtoB,
        nonOwnerAddress1,
        contractTokenA,
        NON_OWNER_TOKEN_AMOUNT_B,
        ZERO);

    Assertions.assertThat(getVirtualSwapState(swapLockContractAddressAtoB).locks()).hasSize(2);
    Assertions.assertThat(getVirtualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A.add(NON_OWNER_TOKEN_AMOUNT_A.add(NON_OWNER_TOKEN_AMOUNT_B)),
                INITIAL_LIQUIDITY_B.subtract(receivingAmountOne.add(receivingAmountTwo)),
                ZERO));

    BigInteger receivingThree =
        calculateReceivingAmountLocked(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_C);
    lock(
        swapLockContractAddressAtoB,
        nonOwnerAddress1,
        contractTokenA,
        NON_OWNER_TOKEN_AMOUNT_C,
        ZERO);

    Assertions.assertThat(getVirtualSwapState(swapLockContractAddressAtoB).locks()).hasSize(3);
    Assertions.assertThat(getVirtualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A.add(
                    NON_OWNER_TOKEN_AMOUNT_A
                        .add(NON_OWNER_TOKEN_AMOUNT_B)
                        .add(NON_OWNER_TOKEN_AMOUNT_C)),
                INITIAL_LIQUIDITY_B.subtract(
                    receivingAmountOne.add(receivingAmountTwo).add(receivingThree)),
                ZERO));
  }

  /**
   * A user can acquire multiple locks across different swap contracts, to safely swap A -> B -> C
   * -> D at a required rate.
   */
  @ContractTest(previous = "contractInit")
  void lockSwapChain() {
    BigInteger receivingB =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    final LiquiditySwapLock.LiquidityLockId lockAtoB =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            receivingB);

    BigInteger receivingC =
        calculateReceivingAmount(swapLockContractAddressBtoC, contractTokenB, receivingB);
    final LiquiditySwapLock.LiquidityLockId lockBtoC =
        lock(swapLockContractAddressBtoC, nonOwnerAddress1, contractTokenB, receivingB, receivingC);

    BigInteger receivingD =
        calculateReceivingAmount(swapLockContractAddressCtoD, contractTokenC, receivingC);
    final LiquiditySwapLock.LiquidityLockId lockCtoD =
        lock(swapLockContractAddressCtoD, nonOwnerAddress1, contractTokenC, receivingC, receivingD);

    // Another user swaps a big amount C -> D, thus devaluing C
    BigInteger swapAmount = INITIAL_LIQUIDITY_C.divide(BigInteger.TWO);
    blockchain.sendAction(
        contractOwnerAddress, contractTokenC, Token.transfer(nonOwnerAddress2, swapAmount));

    depositIntoSwap(swapLockContractAddressCtoD, nonOwnerAddress2, contractTokenC, swapAmount);
    instantSwap(nonOwnerAddress2, swapLockContractAddressCtoD, contractTokenC, swapAmount, ZERO);

    // Can no longer get a good lock on C -> D
    BigInteger newReceivingD =
        calculateReceivingAmountLocked(swapLockContractAddressCtoD, contractTokenC, receivingC);
    Assertions.assertThatCode(
            () ->
                lock(
                    swapLockContractAddressCtoD,
                    nonOwnerAddress1,
                    contractTokenC,
                    receivingC,
                    receivingD))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Swap would produce %s output tokens, but minimum was set to %s.",
            newReceivingD, receivingD);

    // But we can still perform all of our locked-swaps:
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, lockAtoB);

    depositIntoSwap(swapLockContractAddressBtoC, nonOwnerAddress1, contractTokenB, receivingB);
    executeLockSwap(nonOwnerAddress1, swapLockContractAddressBtoC, lockBtoC);

    depositIntoSwap(swapLockContractAddressCtoD, nonOwnerAddress1, contractTokenC, receivingC);
    executeLockSwap(nonOwnerAddress1, swapLockContractAddressCtoD, lockCtoD);

    LiquiditySwapLock.LiquiditySwapContractState stateCtoD =
        getSwapState(swapLockContractAddressCtoD);
    LiquiditySwapLock.TokenBalances b = stateCtoD.tokenBalances();
    Assertions.assertThat(b.balances())
        .containsEntry(
            nonOwnerAddress1, new LiquiditySwapLock.TokenBalance(ZERO, receivingD, ZERO));
  }

  /**
   * A user cannot acquire a lock with a better rate than the minimum rate of actual and virtual
   * balanaces.
   */
  @ContractTest(previous = "contractInit")
  void cannotAcquireLockAtBetterThanMinimumRate() {
    // Lock at the initial rate (no locks)
    BigInteger bestRateSwap =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    lock(
        swapLockContractAddressAtoB,
        nonOwnerAddress1,
        contractTokenA,
        NON_OWNER_TOKEN_AMOUNT_A,
        bestRateSwap);

    // Try to lock at a better rate than the minimum between actual and virtual.
    BigInteger minRate =
        calculateReceivingAmountNoLock(
                swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A)
            .min(
                calculateReceivingAmountLocked(
                    swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A));
    Assertions.assertThatCode(
            () ->
                lock(
                    swapLockContractAddressAtoB,
                    nonOwnerAddress1,
                    contractTokenA,
                    NON_OWNER_TOKEN_AMOUNT_A,
                    minRate.add(BigInteger.ONE)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Swap would produce %s output tokens, but minimum was set to %s.",
            minRate, minRate.add(BigInteger.ONE));
  }

  /**
   * A user can execute a lock-swap to swap at the locked rate, even when the current rate is now
   * lower.
   */
  @ContractTest(previous = "contractInit")
  void executeLockAtLockedRate() {
    BigInteger receivingAmount =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    final LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            receivingAmount);

    // Someone does a big swap, lowering the A -> B rate.
    BigInteger bigSwapAmount = INITIAL_LIQUIDITY_A.divide(BigInteger.TWO);
    blockchain.sendAction(
        contractOwnerAddress, contractTokenA, Token.transfer(nonOwnerAddress2, bigSwapAmount));
    depositIntoSwap(swapLockContractAddressAtoB, nonOwnerAddress2, contractTokenA, bigSwapAmount);
    instantSwap(nonOwnerAddress2, swapLockContractAddressAtoB, contractTokenA, bigSwapAmount, ZERO);

    // If swapping now (on actual contract state) the received amount would be lower.
    BigInteger newReceivingAmount =
        calculateReceivingAmountNoLock(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    Assertions.assertThat(newReceivingAmount).isLessThan(receivingAmount);

    // But we can still perform our locked swap, at the locked rate.
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, lockId);

    LiquiditySwapLock.LiquiditySwapContractState state = getSwapState(swapLockContractAddressAtoB);
    LiquiditySwapLock.TokenBalances b = state.tokenBalances();
    Assertions.assertThat(b.balances())
        .containsEntry(
            nonOwnerAddress1, new LiquiditySwapLock.TokenBalance(ZERO, receivingAmount, ZERO));
  }

  /** A user can perform an instant-swap when there are no locks, which swaps the tokens. */
  @ContractTest(previous = "contractInit")
  void instantSwapWithNoLocks() {
    // A user deposits into the swap.
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    // Then does an instant swap. Note that no locks are present.
    Assertions.assertThat(getVirtualSwapState(swapLockContractAddressAtoB).locks()).hasSize(0);
    BigInteger receivingAmount =
        calculateReceivingAmountNoLock(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    instantSwap(
        nonOwnerAddress1,
        swapLockContractAddressAtoB,
        contractTokenA,
        NON_OWNER_TOKEN_AMOUNT_A,
        receivingAmount);

    // The swap succeeded at the rate of the actual liquidity pool.
    Assertions.assertThat(getSwapState(swapLockContractAddressAtoB).tokenBalances().balances())
        .containsEntry(
            nonOwnerAddress1, new LiquiditySwapLock.TokenBalance(ZERO, receivingAmount, ZERO));

    // Both actual and virtual states are updated to reflect the swap.
    LiquiditySwapLock.TokenBalance newState =
        new LiquiditySwapLock.TokenBalance(
            INITIAL_LIQUIDITY_A.add(NON_OWNER_TOKEN_AMOUNT_A),
            INITIAL_LIQUIDITY_B.subtract(receivingAmount),
            INITIAL_LIQUIDITY_TOKENS_AB);
    Assertions.assertThat(getActualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(newState);
    Assertions.assertThat(getVirtualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(newState);
  }

  /**
   * A user can perform an instant-swap when locks are present, which swaps at the minimum exchange
   * rate of the actual and virtual liquidity pools.
   */
  @ContractTest(previous = "contractInit")
  void instantSwapWithLocksPresent() {
    // Someone gets an A -> B lock, devaluing A in the virtual liquidity pool.
    final BigInteger receivingAmountLock =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    lock(
        swapLockContractAddressAtoB,
        nonOwnerAddress2,
        contractTokenA,
        NON_OWNER_TOKEN_AMOUNT_A,
        ZERO);

    // Calculate different rates between actual and virtual pool.
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    BigInteger receivingAmountActual =
        calculateReceivingAmountNoLock(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    BigInteger receivingAmountVirtual =
        calculateReceivingAmountLocked(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    BigInteger minimumReceivingAmount = receivingAmountActual.min(receivingAmountVirtual);

    // We cannot perform the swap at a rate higher than the minimum.
    Assertions.assertThatCode(
            () ->
                instantSwap(
                    nonOwnerAddress1,
                    swapLockContractAddressAtoB,
                    contractTokenA,
                    NON_OWNER_TOKEN_AMOUNT_A,
                    minimumReceivingAmount.add(BigInteger.ONE)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Swap would produce %s output tokens, but minimum was set to %s.",
            minimumReceivingAmount, minimumReceivingAmount.add(BigInteger.ONE));

    // We can however perform the swap at the minimum rate.
    instantSwap(
        nonOwnerAddress1,
        swapLockContractAddressAtoB,
        contractTokenA,
        NON_OWNER_TOKEN_AMOUNT_A,
        minimumReceivingAmount);
    Assertions.assertThat(getSwapState(swapLockContractAddressAtoB).tokenBalances().balances())
        .containsEntry(
            nonOwnerAddress1,
            new LiquiditySwapLock.TokenBalance(ZERO, minimumReceivingAmount, ZERO));

    // Both actual and virtual states are updated to reflect the swap.
    Assertions.assertThat(getActualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A.add(NON_OWNER_TOKEN_AMOUNT_A),
                INITIAL_LIQUIDITY_B.subtract(minimumReceivingAmount),
                INITIAL_LIQUIDITY_TOKENS_AB));
    Assertions.assertThat(getVirtualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A.add(NON_OWNER_TOKEN_AMOUNT_A).add(NON_OWNER_TOKEN_AMOUNT_A),
                INITIAL_LIQUIDITY_B.subtract(minimumReceivingAmount).subtract(receivingAmountLock),
                ZERO));
  }

  /** A user cannot perform an instant-swap when there is no liquidity. */
  @ContractTest(previous = "contractInit")
  void instantSwapFailsWithNoLiquidity() {
    // Contract owner reclaims all liquidity
    blockchain.sendAction(
        contractOwnerAddress,
        swapLockContractAddressAtoB,
        LiquiditySwapLock.reclaimLiquidity(INITIAL_LIQUIDITY_TOKENS_AB));

    // A user deposits into the swap.
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    Assertions.assertThatCode(
            () ->
                instantSwap(
                    nonOwnerAddress1,
                    swapLockContractAddressAtoB,
                    contractTokenA,
                    NON_OWNER_TOKEN_AMOUNT_A,
                    ZERO))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Pools must have existing liquidity to perform a swap");
  }

  /**
   * A user can provide liquidity when there are locks present, which updates both the actual pool
   * and retains and locks.
   */
  @ContractTest(previous = "contractInit")
  void provideLiquidityWithLocks() {
    // First get a lock:
    final BigInteger lockReceivingAmount =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    final LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            ZERO);

    // A user can still become a liquidity provider.
    final BigInteger equivalentAmount =
        calculateEquivalentLiquidity(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    final BigInteger mintedAmount =
        calculateMintedLiquidity(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress2, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress2, contractTokenB, equivalentAmount);
    blockchain.sendAction(
        nonOwnerAddress2,
        swapLockContractAddressAtoB,
        LiquiditySwapLock.provideLiquidity(contractTokenA, NON_OWNER_TOKEN_AMOUNT_A));

    // Liquidity of of the actual pool is changed.
    Assertions.assertThat(getSwapState(swapLockContractAddressAtoB).tokenBalances().balances())
        .containsEntry(
            nonOwnerAddress2, new LiquiditySwapLock.TokenBalance(ZERO, ZERO, mintedAmount));
    Assertions.assertThat(getSwapState(swapLockContractAddressAtoB).tokenBalances().balances())
        .containsEntry(
            swapLockContractAddressAtoB,
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A.add(NON_OWNER_TOKEN_AMOUNT_A),
                INITIAL_LIQUIDITY_B.add(equivalentAmount),
                INITIAL_LIQUIDITY_TOKENS_AB.add(mintedAmount)));
    // The virtual pool still contains the lock.
    assertThatLocksContain(
        getVirtualSwapState(swapLockContractAddressAtoB).locks(),
        lockId,
        new LiquiditySwapLock.LiquidityLock(
            NON_OWNER_TOKEN_AMOUNT_A,
            lockReceivingAmount,
            swapUtil.tokenAinBout(),
            nonOwnerAddress1));
  }

  /**
   * A user cannot provide liquidity, if the given input amount would result in 0 minted liquidity
   * tokens.
   */
  @ContractTest(previous = "contractInit")
  void provideTooLittleLiquidity() {
    // A user deposits and becomes a liquidity provider.
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress2, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    nonOwnerAddress2,
                    swapLockContractAddressAtoB,
                    LiquiditySwapLock.provideLiquidity(contractTokenA, ZERO)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("The given input amount yielded 0 minted liquidity");
  }

  /**
   * A liquidity provider can reclaim their liquidity, when no locks are present, which updates both
   * actual and virtual balances.
   */
  @ContractTest(previous = "contractInit")
  void reclaimLiquidityNoLocks() {
    final BigInteger equivalentAmount =
        calculateEquivalentLiquidity(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    final BigInteger mintedAmount =
        calculateMintedLiquidity(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    // A user deposits and becomes a liquidity provider.
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress2, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress2, contractTokenB, equivalentAmount);
    blockchain.sendAction(
        nonOwnerAddress2,
        swapLockContractAddressAtoB,
        LiquiditySwapLock.provideLiquidity(contractTokenA, NON_OWNER_TOKEN_AMOUNT_A));

    // Other user perform a lock-swaps.
    final BigInteger lockReceivingAmount =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    final LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            ZERO);
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, lockId);

    // We can reclaim liquidity after the locks have been processed.
    LiquiditySwapLock.TokenBalance reclaimAmounts =
        calculateReclaim(swapLockContractAddressAtoB, mintedAmount);
    blockchain.sendAction(
        nonOwnerAddress2,
        swapLockContractAddressAtoB,
        LiquiditySwapLock.reclaimLiquidity(mintedAmount));
    Assertions.assertThat(getActualContractBalance(swapLockContractAddressAtoB))
        .isEqualTo(
            new LiquiditySwapLock.TokenBalance(
                INITIAL_LIQUIDITY_A
                    .add(NON_OWNER_TOKEN_AMOUNT_A)
                    .add(NON_OWNER_TOKEN_AMOUNT_A.subtract(reclaimAmounts.aTokens())),
                INITIAL_LIQUIDITY_B
                    .subtract(lockReceivingAmount)
                    .add(equivalentAmount.subtract(reclaimAmounts.bTokens())),
                INITIAL_LIQUIDITY_TOKENS_AB));
  }

  /** A liquidity provider cannot reclaim liquidity while there are locks present. */
  @ContractTest(previous = "contractInit")
  void cannotReclaimLiquidityWhenLocksArePresent() {
    final BigInteger equivalentAmount =
        calculateEquivalentLiquidity(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    final BigInteger mintedAmount =
        calculateMintedLiquidity(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    // A user deposits and becomes a liquidity provider.
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress2, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress2, contractTokenB, equivalentAmount);
    blockchain.sendAction(
        nonOwnerAddress2,
        swapLockContractAddressAtoB,
        LiquiditySwapLock.provideLiquidity(contractTokenA, NON_OWNER_TOKEN_AMOUNT_A));

    // Acquire a lock
    lock(
        swapLockContractAddressAtoB,
        nonOwnerAddress1,
        contractTokenA,
        NON_OWNER_TOKEN_AMOUNT_A,
        ZERO);

    // Trying to reclaim liquidity will result in an error.
    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    nonOwnerAddress2,
                    swapLockContractAddressAtoB,
                    LiquiditySwapLock.reclaimLiquidity(mintedAmount)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Cannot reclaim liquidity while locks are present.");
  }

  /** A user who tries to execute a lock-swap with an invalid lock id will receive an error. */
  @ContractTest(previous = "contractInit")
  void executeLockInvalidLockId() {
    // Acquire lock
    LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            ZERO);

    LiquiditySwapLock.LiquidityLockId invalidId =
        new LiquiditySwapLock.LiquidityLockId(lockId.rawId().add(BigInteger.ONE));

    // Trying to execute a non-existing lock fails.
    Assertions.assertThatCode(
            () -> executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, invalidId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "LiquidityLockId { raw_id: %s } is not a valid lock id.", invalidId.rawId());
  }

  /** A user can execute a lock-swap, which removes the lock, and it cannot be executed again. */
  @ContractTest(previous = "contractInit")
  void executeLockTwice() {
    // Acquire lock
    BigInteger receivingAmount =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            ZERO);

    // Execute the first lock-swap.
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, lockId);
    Assertions.assertThat(getSwapState(swapLockContractAddressAtoB).tokenBalances().balances())
        .containsEntry(
            nonOwnerAddress1, new LiquiditySwapLock.TokenBalance(ZERO, receivingAmount, ZERO));

    // Trying to execute the lock again (same id) fails.
    Assertions.assertThatCode(
            () -> executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, lockId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "LiquidityLockId { raw_id: %s } is not a valid lock id.", lockId.rawId());
  }

  /** A user who tries to cancel a lock with an invalid lock id will receive an error. */
  @ContractTest(previous = "contractInit")
  void cancelLockInvalidLockId() {
    // Acquire lock
    LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            ZERO);

    LiquiditySwapLock.LiquidityLockId invalidId =
        new LiquiditySwapLock.LiquidityLockId(lockId.rawId().add(BigInteger.ONE));

    // Trying to cancel a non-existing lock fails.
    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    nonOwnerAddress1,
                    swapLockContractAddressAtoB,
                    LiquiditySwapLock.cancelLock(invalidId)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "LiquidityLockId { raw_id: %s } is not a valid lock id.", invalidId.rawId());
  }

  /** A user who didn't acquire a specific lock-id cannot execute that lock. */
  @ContractTest(previous = "contractInit")
  void differentUserExecuteLock() {
    // Acquire lock
    LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            ZERO);

    // A different user tries to deposit and execute the lock, which fails
    depositIntoSwap(
        swapLockContractAddressAtoB, nonOwnerAddress2, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    Assertions.assertThatCode(
            () -> executeLockSwap(nonOwnerAddress2, swapLockContractAddressAtoB, lockId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Permission denied to handle lockID LiquidityLockId { raw_id: %s }.", lockId.rawId());

    // The lock still exists.
    Assertions.assertThat(getVirtualSwapState(swapLockContractAddressAtoB).locks()).hasSize(1);
  }

  /** A user who didn't acquire a specific lock-id cannot cancel that lock. */
  @ContractTest(previous = "contractInit")
  void differentUserCancelLock() {
    // Acquire lock
    LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            ZERO);

    // A different user tries to cancel the lock, which fails.
    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    nonOwnerAddress2,
                    swapLockContractAddressAtoB,
                    LiquiditySwapLock.cancelLock(lockId)))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Permission denied to handle lockID LiquidityLockId { raw_id: %s }.", lockId.rawId());

    // The lock still exists.
    Assertions.assertThat(getVirtualSwapState(swapLockContractAddressAtoB).locks()).hasSize(1);
  }

  /**
   * A user who executes a lock with too little deposit does not lose the lock, and can execute it
   * later.
   */
  @ContractTest(previous = "contractInit")
  void executeTooLittleDepositDoesNotLoseLock() {
    final BigInteger receivingAmount =
        calculateReceivingAmount(
            swapLockContractAddressAtoB, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    final LiquiditySwapLock.LiquidityLockId lockId =
        lock(
            swapLockContractAddressAtoB,
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A,
            ZERO);

    // Executing with too little of a deposit fails.
    depositIntoSwap(
        swapLockContractAddressAtoB,
        nonOwnerAddress1,
        contractTokenA,
        NON_OWNER_TOKEN_AMOUNT_A.subtract(BigInteger.ONE));
    Assertions.assertThatCode(
            () -> executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, lockId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining(
            "Insufficient TokenA deposit: %s/%s",
            NON_OWNER_TOKEN_AMOUNT_A.subtract(BigInteger.ONE), NON_OWNER_TOKEN_AMOUNT_A);

    // Lock still exists.
    Assertions.assertThat(getVirtualSwapState(swapLockContractAddressAtoB).locks()).hasSize(1);

    // Deposit missing amount, execute now succeeds
    depositIntoSwap(swapLockContractAddressAtoB, nonOwnerAddress1, contractTokenA, BigInteger.ONE);
    executeLockSwap(nonOwnerAddress1, swapLockContractAddressAtoB, lockId);
    Assertions.assertThat(getSwapState(swapLockContractAddressAtoB).tokenBalances().balances())
        .containsEntry(
            nonOwnerAddress1, new LiquiditySwapLock.TokenBalance(ZERO, receivingAmount, ZERO));
  }

  /** A user should not be able to gain a more favorable exchange rate, by acquiring a lock. */
  @ContractTest
  void notFavorableToLockBeforeSwap() {
    BlockchainAddress ownerAddress = blockchain.newAccount(13);
    BlockchainAddress nonOwnerAddress = blockchain.newAccount(17);

    // Deploy token contracts.
    byte[] initRpcA = Token.initialize("Token tA", "tA", (byte) 8, TOTAL_SUPPLY_A);
    BlockchainAddress tokenA =
        blockchain.deployContract(ownerAddress, TokenContractTest.CONTRACT_BYTES, initRpcA);
    byte[] initRpcB = Token.initialize("Token tB", "tB", (byte) 8, TOTAL_SUPPLY_B);
    BlockchainAddress tokenB =
        blockchain.deployContract(ownerAddress, TokenContractTest.CONTRACT_BYTES, initRpcB);

    StateInitializer si =
        () -> {
          byte[] initRpc =
              LiquiditySwapLock.initialize(
                  new LiquiditySwapLock.Permission.Anybody(), tokenA, tokenB, FEE);
          BlockchainAddress res = blockchain.deployContract(ownerAddress, CONTRACT_BYTES, initRpc);

          // Provide initial liquidity
          depositIntoSwap(res, ownerAddress, tokenA, INITIAL_LIQUIDITY_A);
          depositIntoSwap(res, ownerAddress, tokenB, INITIAL_LIQUIDITY_B);
          blockchain.sendAction(
              ownerAddress,
              res,
              LiquiditySwapLock.provideInitialLiquidity(INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B));

          return res;
        };

    TimelineExecutor<BigInteger> t1 =
        (address) -> {
          // Non owner acquires a lock.
          BigInteger receivingAmountLock =
              deltaCalculation(NON_OWNER_TOKEN_AMOUNT_A, INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B);
          lock(address, nonOwnerAddress, tokenA, NON_OWNER_TOKEN_AMOUNT_A, ZERO);

          // Owner acquires a lock, to try to sway rate.
          BigInteger ownerLockAmount =
              inverseDelta(
                  NON_OWNER_TOKEN_AMOUNT_A,
                  INITIAL_LIQUIDITY_B.subtract(receivingAmountLock),
                  INITIAL_LIQUIDITY_A.add(NON_OWNER_TOKEN_AMOUNT_A));
          lock(address, ownerAddress, tokenB, ownerLockAmount, ZERO);

          // Owner now does an instant-swap with a possibly swayed rate.
          depositIntoSwap(address, ownerAddress, tokenA, NON_OWNER_TOKEN_AMOUNT_A);
          instantSwap(ownerAddress, address, tokenA, NON_OWNER_TOKEN_AMOUNT_A, ZERO);

          // return the output of the swap
          return getSwapState(address).tokenBalances().balances().get(ownerAddress).bTokens();
        };

    TimelineExecutor<BigInteger> t2 =
        (address) -> {
          // Owner does an instant-swap.
          depositIntoSwap(address, ownerAddress, tokenA, NON_OWNER_TOKEN_AMOUNT_A);
          instantSwap(ownerAddress, address, tokenA, NON_OWNER_TOKEN_AMOUNT_A, ZERO);

          // return the output of the swap
          return getSwapState(address).tokenBalances().balances().get(ownerAddress).bTokens();
        };
    // The output from timeline 1 should be less than or equal to output from timeline 2.
    TimelineAsserter<BigInteger> ta =
        (b1, b2) -> {
          int comparisonResult = b1.compareTo(b2);

          Assertions.assertThat(comparisonResult)
              .as("Timeline 1 swap:\n  %s\nwas greater than timeline 2 swap:\n  %s", b1, b2)
              .isLessThanOrEqualTo(0);
        };

    assertTimeline(si, t1, t2, ta);
  }

  private void assertInitialContractState(
      BlockchainAddress swapContractAddress,
      BlockchainAddress tokenA,
      BlockchainAddress tokenB,
      BigInteger initialLiquidityA,
      BigInteger initialLiquidityB,
      BigInteger initialLiquidityTokens) {
    LiquiditySwapLock.LiquiditySwapContractState state = getSwapState(swapContractAddress);

    // Check default values of initial contract state
    Assertions.assertThat(state).isNotNull();
    Assertions.assertThat(state.liquidityPoolAddress()).isEqualTo(swapContractAddress);
    Assertions.assertThat(state.swapFeePerMille()).isEqualTo(FEE);
    Assertions.assertThat(state.tokenBalances()).isNotNull();
    Assertions.assertThat(state.virtualState()).isNotNull();

    // Check initial value of balances in state.
    LiquiditySwapLock.TokenBalances b = state.tokenBalances();
    Assertions.assertThat(b.tokenAAddress()).isEqualTo(tokenA);
    Assertions.assertThat(b.tokenBAddress()).isEqualTo(tokenB);
    Assertions.assertThat(b.balances())
        .containsEntry(
            swapContractAddress,
            new LiquiditySwapLock.TokenBalance(
                initialLiquidityA, initialLiquidityB, initialLiquidityTokens));

    // Check initial value of virtual balances.
    LiquiditySwapLock.VirtualState vb = state.virtualState();
    Assertions.assertThat(vb.locks()).isEmpty();
    Assertions.assertThat(vb.nextLockId())
        .isEqualTo(new LiquiditySwapLock.LiquidityLockId(BigInteger.ZERO));
  }

  private void provideInitialLiquidity() {
    depositIntoSwap(
        swapLockContractAddressAtoB, contractOwnerAddress, contractTokenA, INITIAL_LIQUIDITY_A);
    depositIntoSwap(
        swapLockContractAddressAtoB, contractOwnerAddress, contractTokenB, INITIAL_LIQUIDITY_B);
    blockchain.sendAction(
        contractOwnerAddress,
        swapLockContractAddressAtoB,
        LiquiditySwapLock.provideInitialLiquidity(INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B));

    depositIntoSwap(
        swapLockContractAddressBtoC, contractOwnerAddress, contractTokenB, INITIAL_LIQUIDITY_B);
    depositIntoSwap(
        swapLockContractAddressBtoC, contractOwnerAddress, contractTokenC, INITIAL_LIQUIDITY_C);
    blockchain.sendAction(
        contractOwnerAddress,
        swapLockContractAddressBtoC,
        LiquiditySwapLock.provideInitialLiquidity(INITIAL_LIQUIDITY_B, INITIAL_LIQUIDITY_C));

    depositIntoSwap(
        swapLockContractAddressCtoD, contractOwnerAddress, contractTokenC, INITIAL_LIQUIDITY_C);
    depositIntoSwap(
        swapLockContractAddressCtoD, contractOwnerAddress, contractTokenD, INITIAL_LIQUIDITY_D);
    blockchain.sendAction(
        contractOwnerAddress,
        swapLockContractAddressCtoD,
        LiquiditySwapLock.provideInitialLiquidity(INITIAL_LIQUIDITY_C, INITIAL_LIQUIDITY_D));
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
    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenC,
        Token.transfer(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_C));
    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenD,
        Token.transfer(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_D));

    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenA,
        Token.transfer(nonOwnerAddress2, NON_OWNER_TOKEN_AMOUNT_A));
    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenB,
        Token.transfer(nonOwnerAddress2, NON_OWNER_TOKEN_AMOUNT_B));
    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenC,
        Token.transfer(nonOwnerAddress2, NON_OWNER_TOKEN_AMOUNT_C));
    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenD,
        Token.transfer(nonOwnerAddress2, NON_OWNER_TOKEN_AMOUNT_D));
  }

  private void initializeTokenContracts(BlockchainAddress owner) {
    byte[] initRpcA = Token.initialize("Token A", "A", (byte) 8, TOTAL_SUPPLY_A);
    contractTokenA = blockchain.deployContract(owner, TokenContractTest.CONTRACT_BYTES, initRpcA);

    byte[] initRpcB = Token.initialize("Token B", "B", (byte) 8, TOTAL_SUPPLY_B);
    contractTokenB = blockchain.deployContract(owner, TokenContractTest.CONTRACT_BYTES, initRpcB);

    byte[] initRpcC = Token.initialize("Token C", "C", (byte) 8, TOTAL_SUPPLY_C);
    contractTokenC = blockchain.deployContract(owner, TokenContractTest.CONTRACT_BYTES, initRpcC);

    byte[] initRpcD = Token.initialize("Token D", "D", (byte) 8, TOTAL_SUPPLY_D);
    contractTokenD = blockchain.deployContract(owner, TokenContractTest.CONTRACT_BYTES, initRpcD);
  }

  private <T> void assertTimeline(
      StateInitializer si, TimelineExecutor<T> t1, TimelineExecutor<T> t2, TimelineAsserter<T> as) {
    BlockchainAddress alpha = si.initializeState();
    BlockchainAddress beta = si.initializeState();

    T r1 = t1.executeTimeline(alpha);
    T r2 = t2.executeTimeline(beta);

    as.assertTimeline(r1, r2);
  }

  private interface StateInitializer {
    BlockchainAddress initializeState();
  }

  private interface TimelineExecutor<T> {
    T executeTimeline(BlockchainAddress address);
  }

  private interface TimelineAsserter<T> {
    void assertTimeline(T t1, T t2);
  }
}
