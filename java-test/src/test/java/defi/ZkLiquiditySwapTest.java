package defi;

import static java.util.Map.entry;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abiclient.parser.AbiParser;
import com.partisiablockchain.language.abiclient.rpc.FnRpcBuilder;
import com.partisiablockchain.language.abiclient.types.FileAbi;
import com.partisiablockchain.language.abiclient.zk.ZkInputBuilder;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.abicodegen.ZkLiquiditySwap;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import com.partisiablockchain.language.junit.exceptions.SecretInputFailureException;
import com.partisiablockchain.language.testenvironment.zk.node.task.PendingInputId;
import com.secata.stream.CompactBitArray;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;
import org.assertj.core.api.Assertions;

/** {@link ZkLiquiditySwap} testing. */
public final class ZkLiquiditySwapTest extends JunitContractTest {

  private static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPaths(
          Path.of("../target/wasm32-unknown-unknown/release/zk_liquidity_swap.zkwa"),
          Path.of("../target/wasm32-unknown-unknown/release/zk_liquidity_swap.abi"),
          Path.of("../target/wasm32-unknown-unknown/release/zk_liquidity_swap_runner"));

  private static final BigInteger ZERO = BigInteger.ZERO;

  private static final ZkLiquiditySwap.TokenBalance ZERO_BALANCE =
      new ZkLiquiditySwap.TokenBalance(ZERO, ZERO, ZERO);

  private static final BigInteger TOTAL_SUPPLY_A = BigInteger.ONE.shiftLeft(100);
  private static final BigInteger TOTAL_SUPPLY_B = BigInteger.ONE.shiftLeft(90);
  private static final BigInteger INITIAL_LIQUIDITY_A = BigInteger.ONE.shiftLeft(60);
  private static final BigInteger INITIAL_LIQUIDITY_B = BigInteger.ONE.shiftLeft(59);

  private static final BigInteger SWAP_CONSTANT = INITIAL_LIQUIDITY_A.multiply(INITIAL_LIQUIDITY_B);

  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_A = BigInteger.ONE.shiftLeft(15);
  private static final BigInteger NON_OWNER_TOKEN_AMOUNT_B = BigInteger.ONE.shiftLeft(14);

  private BlockchainAddress contractOwnerAddress;
  private BlockchainAddress nonOwnerAddress1;
  private BlockchainAddress nonOwnerAddress2;
  private BlockchainAddress swapContractAddress;

  private BlockchainAddress contractTokenA;
  private BlockchainAddress contractTokenB;

  /** Test deployment and initialization of contract. */
  @ContractTest
  void contractInit() {
    contractOwnerAddress = blockchain.newAccount(3);
    nonOwnerAddress1 = blockchain.newAccount(5);
    nonOwnerAddress2 = blockchain.newAccount(7);

    // Setup tokens.
    byte[] initRpcA = Token.initialize("Token A", "A", (byte) 8, TOTAL_SUPPLY_A);
    contractTokenA =
        blockchain.deployContract(contractOwnerAddress, TokenTest.CONTRACT_BYTES, initRpcA);

    byte[] initRpcB = Token.initialize("Token B", "B", (byte) 8, TOTAL_SUPPLY_B);
    contractTokenB =
        blockchain.deployContract(contractOwnerAddress, TokenTest.CONTRACT_BYTES, initRpcB);

    // Give our non owners some tokens to work with.
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
        contractTokenA,
        Token.transfer(nonOwnerAddress2, NON_OWNER_TOKEN_AMOUNT_A));
    blockchain.sendAction(
        contractOwnerAddress,
        contractTokenB,
        Token.transfer(nonOwnerAddress2, NON_OWNER_TOKEN_AMOUNT_B));

    // Setup swap contract.
    byte[] initRpcSwap = ZkLiquiditySwap.initialize(contractTokenA, contractTokenB);
    swapContractAddress =
        blockchain.deployZkContract(contractOwnerAddress, CONTRACT_BYTES, initRpcSwap);

    ZkLiquiditySwap.ContractState state = getSwapState();

    // Check default values of initial contract state
    Assertions.assertThat(state).isNotNull();
    Assertions.assertThat(state.contractOwner()).isEqualTo(contractOwnerAddress);
    Assertions.assertThat(state.tokenPoolAddress()).isEqualTo(swapContractAddress);
    Assertions.assertThat(state.swapConstant()).isEqualTo(0);
    Assertions.assertThat(state.isClosed()).isTrue();
    Assertions.assertThat(state.worklist()).isEmpty();
    Assertions.assertThat(state.unusedVariables()).isEmpty();

    // Check initial value of balances in state.
    ZkLiquiditySwap.TokenBalances b = state.balances();
    Assertions.assertThat(b.tokenAAddress()).isEqualTo(contractTokenA);
    Assertions.assertThat(b.tokenBAddress()).isEqualTo(contractTokenB);
    Assertions.assertThat(b.balances()).containsExactly(entry(swapContractAddress, ZERO_BALANCE));
  }

  /** Tests that contract owner can deposit, and a new account is created. */
  @ContractTest(previous = "contractInit")
  void initialDepositFromOwner() {
    // Deposit initial liquidity to contract owner account.
    depositIntoSwap(contractOwnerAddress, contractTokenA, INITIAL_LIQUIDITY_A);
    depositIntoSwap(contractOwnerAddress, contractTokenB, INITIAL_LIQUIDITY_B);

    // State should include new account.

    Assertions.assertThat(getDepositBalances())
        .containsEntry(
            contractOwnerAddress, createBalance(INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B));
  }

  /** Test that non-owners cannot deposit before pool initialization. */
  @ContractTest(previous = "contractInit")
  void initialDepositFromNonOwner() {
    Assertions.assertThatThrownBy(
            () -> depositIntoSwap(nonOwnerAddress1, contractTokenA, INITIAL_LIQUIDITY_A))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("The contract is closed");
  }

  /** Test liquidity initialization by contract owner. */
  @ContractTest(previous = "initialDepositFromOwner")
  void initializePool() {
    // Initialize liquidity.
    blockchain.sendAction(
        contractOwnerAddress,
        swapContractAddress,
        ZkLiquiditySwap.provideLiquidity(contractTokenA, INITIAL_LIQUIDITY_A));
    blockchain.sendAction(
        contractOwnerAddress,
        swapContractAddress,
        ZkLiquiditySwap.provideLiquidity(contractTokenB, INITIAL_LIQUIDITY_B));

    // Tokens have been moved from owner to liquidity pool.
    Assertions.assertThat(getDepositBalances())
        .containsExactly(
            entry(
                swapContractAddress,
                new ZkLiquiditySwap.TokenBalance(INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B, ZERO)));

    // Contract state is now open.
    Assertions.assertThat(getSwapState().isClosed()).isFalse();
  }

  /** Tests liquidity initialization of only 1 pool. */
  @ContractTest(previous = "initialDepositFromOwner")
  void initializeOnePool() {
    // Initialize liquidity of token A.
    blockchain.sendAction(
        contractOwnerAddress,
        swapContractAddress,
        ZkLiquiditySwap.provideLiquidity(contractTokenA, INITIAL_LIQUIDITY_A));

    // A Tokens have been moved from owner to liquidity pool.
    Assertions.assertThat(getDepositBalances())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                swapContractAddress,
                new ZkLiquiditySwap.TokenBalance(INITIAL_LIQUIDITY_A, ZERO, ZERO),
                contractOwnerAddress,
                new ZkLiquiditySwap.TokenBalance(ZERO, INITIAL_LIQUIDITY_B, ZERO)));

    // Contract state is still closed.
    Assertions.assertThat(getSwapState().isClosed()).isTrue();
  }

  /** Contract owner can close the pool. */
  @ContractTest(previous = "initializePool")
  void closePools() {
    // Initial pools are open.
    Assertions.assertThat(getSwapState().isClosed()).isFalse();

    // Close pools.
    blockchain.sendAction(contractOwnerAddress, swapContractAddress, ZkLiquiditySwap.closePools());

    // Pools are closed, and liquidity is returned to owner.
    Assertions.assertThat(getSwapState().isClosed()).isTrue();

    Assertions.assertThat(getDepositBalances())
        .containsEntry(
            contractOwnerAddress,
            new ZkLiquiditySwap.TokenBalance(INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B, ZERO));
  }

  /** Non owner cannot close pool. */
  @ContractTest(previous = "initializePool")
  void nonOwnerCannotClosePools() {
    // Try to close pools, see it fail.
    Assertions.assertThatCode(
            () ->
                blockchain.sendAction(
                    nonOwnerAddress1, swapContractAddress, ZkLiquiditySwap.closePools()))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Only the contract owner can close the pools");
  }

  /** Test non owner deposit and withdraw without swapping. */
  @ContractTest(previous = "initializePool")
  void withdrawInitial() {
    // Deposit into contact.
    depositIntoSwap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    // Assert that tokens have been moved.
    Assertions.assertThat(getDepositBalances())
        .containsEntry(
            nonOwnerAddress1,
            new ZkLiquiditySwap.TokenBalance(NON_OWNER_TOKEN_AMOUNT_A, ZERO, ZERO));
    Assertions.assertThat(getTokenState(contractTokenA).balances())
        .containsEntry(swapContractAddress, INITIAL_LIQUIDITY_A.add(NON_OWNER_TOKEN_AMOUNT_A));

    // Withdraw from contract.
    withdrawFromSwap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    // Assert that tokens are back to original account.
    Assertions.assertThat(getDepositBalances()).doesNotContainKey(nonOwnerAddress1);
    Assertions.assertThat(getTokenState(contractTokenA).balances())
        .containsEntry(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_A);
    Assertions.assertThat(getTokenState(contractTokenA).balances())
        .containsEntry(swapContractAddress, INITIAL_LIQUIDITY_A);
  }

  /** Test non owner deposit, swap and withdraw. */
  @ContractTest(previous = "initializePool")
  void swapNonOwner() {
    depositIntoSwap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    BigInteger receiving = calculateReceivingAmount(contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    swap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, false);

    // The tokens should have been swapped.
    Assertions.assertThat(getDepositBalances())
        .containsEntry(nonOwnerAddress1, new ZkLiquiditySwap.TokenBalance(ZERO, receiving, ZERO));

    // Now withdraw.
    blockchain.sendAction(
        nonOwnerAddress1, swapContractAddress, ZkLiquiditySwap.withdraw(contractTokenB, receiving));

    // Check that the token is withdrawn from the swap contract.
    Assertions.assertThat(getDepositBalances()).doesNotContainKey(nonOwnerAddress1);

    // Check that the tokens have been transferred.
    Assertions.assertThat(getTokenState(contractTokenB).balances())
        .containsEntry(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_B.add(receiving));
  }

  /** Tests non owner swaps back and forth. */
  @ContractTest(previous = "initializePool")
  void swapMultiple() {
    depositIntoSwap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    BigInteger receivingB = calculateReceivingAmount(contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    swap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, false);

    // First swap went through
    Assertions.assertThat(getDepositBalances())
        .containsEntry(nonOwnerAddress1, new ZkLiquiditySwap.TokenBalance(ZERO, receivingB, ZERO));

    BigInteger receivingA = calculateReceivingAmount(contractTokenB, receivingB);
    swap(nonOwnerAddress1, contractTokenB, receivingB, false);

    // The tokens should have been swapped back.
    Assertions.assertThat(getDepositBalances())
        .containsEntry(
            nonOwnerAddress1,
            new ZkLiquiditySwap.TokenBalance(NON_OWNER_TOKEN_AMOUNT_A, ZERO, ZERO));

    // We now have what we started with.
    Assertions.assertThat(NON_OWNER_TOKEN_AMOUNT_A).isEqualTo(receivingA);
  }

  /** Tests that a user cannot swap if deposit is too low. */
  @ContractTest(previous = "initializePool")
  void depositTooLow() {
    // Try to swap before depositing, and see it fail.
    Assertions.assertThatThrownBy(
            () -> swap(nonOwnerAddress1, contractTokenA, BigInteger.ONE, false))
        .isInstanceOf(SecretInputFailureException.class)
        .hasMessageContaining("Balances are both zero; nothing to swap with");

    // Now deposit some tokens.
    depositIntoSwap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    // Try to swap more than our deposit, and see that it's not processed.
    Assertions.assertThatCode(
            () ->
                swap(
                    nonOwnerAddress1,
                    contractTokenA,
                    NON_OWNER_TOKEN_AMOUNT_A.add(BigInteger.ONE),
                    false))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Insufficient TokenA deposit: 32768/32769");

    Assertions.assertThat(getDepositBalances())
        .containsEntry(
            nonOwnerAddress1,
            new ZkLiquiditySwap.TokenBalance(NON_OWNER_TOKEN_AMOUNT_A, ZERO, ZERO));
  }

  /** Queued swaps happen in correct order. */
  @ContractTest(previous = "initializePool")
  void queuedSwapsCorrectOrder() {
    depositIntoSwap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    depositIntoSwap(nonOwnerAddress2, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B);

    // Stop any zk related actions.
    zkNodes.stop();

    // Start swaps, which are immediately paused.
    BigInteger receiving1B = calculateReceivingAmount(contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    swap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, false);
    swap(nonOwnerAddress2, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B, false);
    swap(nonOwnerAddress1, contractTokenB, receiving1B, false);

    // Confirm all secret inputs.
    for (PendingInputId peid : zkNodes.getPendingInputs(swapContractAddress)) {
      zkNodes.confirmInput(peid);
    }

    // Perform swaps in the contract order.
    performSwapsInQueue(1);

    Assertions.assertThat(getDepositBalances())
        .containsEntry(nonOwnerAddress1, createBalance(ZERO, receiving1B));
    Assertions.assertThat(getDepositBalances())
        .containsEntry(nonOwnerAddress2, createBalance(ZERO, NON_OWNER_TOKEN_AMOUNT_B));

    BigInteger receiving2A = calculateReceivingAmount(contractTokenB, NON_OWNER_TOKEN_AMOUNT_B);
    performSwapsInQueue(1);

    Assertions.assertThat(getDepositBalances())
        .containsEntry(nonOwnerAddress1, createBalance(ZERO, receiving1B));
    Assertions.assertThat(getDepositBalances())
        .containsEntry(nonOwnerAddress2, createBalance(receiving2A, ZERO));

    performSwapsInQueue(1);
    Assertions.assertThat(getDepositBalances())
        .containsEntry(
            nonOwnerAddress1,
            createBalance(NON_OWNER_TOKEN_AMOUNT_A.subtract(BigInteger.TWO), ZERO));
    Assertions.assertThat(getDepositBalances())
        .containsEntry(nonOwnerAddress2, createBalance(receiving2A, ZERO));
  }

  /** Test that queued front swaps if 'only_if_at_front' is true. */
  @ContractTest(previous = "initializePool")
  void frontSwapWhenFrontOnly() {
    depositIntoSwap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    depositIntoSwap(nonOwnerAddress2, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B);

    zkNodes.stop();

    PendingInputId id1 = swap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, true);
    PendingInputId id2 = swap(nonOwnerAddress2, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B, false);
    zkNodes.confirmInput(id1);
    zkNodes.confirmInput(id2);

    // 2 Swaps in queue
    Assertions.assertThat(getSwapState().worklist()).hasSize(2);

    // Perform our first swap, which only happens if it's at the front (which it is).
    BigInteger receiving1B = calculateReceivingAmount(contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    performSwapsInQueue(1);
    Assertions.assertThat(getDepositBalances())
        .containsEntry(nonOwnerAddress1, new ZkLiquiditySwap.TokenBalance(ZERO, receiving1B, ZERO));
  }

  /** Non-front swaps don't happen when `only_if_at_front` is false. */
  @ContractTest(previous = "initializePool")
  void nonFrontNoSwapWhenFrontOnly() {
    depositIntoSwap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    depositIntoSwap(nonOwnerAddress2, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B);

    zkNodes.stop();

    BigInteger receiving1B = calculateReceivingAmount(contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    PendingInputId id1 = swap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, true);
    PendingInputId id2 = swap(nonOwnerAddress2, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B, true);
    PendingInputId id3 = swap(nonOwnerAddress1, contractTokenB, receiving1B, false);
    zkNodes.confirmInput(id1);
    zkNodes.confirmInput(id2);
    zkNodes.confirmInput(id3);

    // 2 Swaps in queue (Second one was ignored, because it wasn't at the front)
    Assertions.assertThat(getSwapState().worklist()).hasSize(2);

    // Perform all swaps in queue
    performSwapsInQueue(2);

    // Middle swap was the one that didn't happen.
    Assertions.assertThat(getDepositBalances())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                nonOwnerAddress1,
                new ZkLiquiditySwap.TokenBalance(NON_OWNER_TOKEN_AMOUNT_A, ZERO, ZERO),
                nonOwnerAddress2,
                new ZkLiquiditySwap.TokenBalance(ZERO, NON_OWNER_TOKEN_AMOUNT_B, ZERO),
                swapContractAddress,
                new ZkLiquiditySwap.TokenBalance(INITIAL_LIQUIDITY_A, INITIAL_LIQUIDITY_B, ZERO)));
  }

  /** Swap in queue happens even if user withdraws, if there is still enough tokens. */
  @ContractTest(previous = "initializePool")
  void withdrawWhileInQueue() {
    depositIntoSwap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    zkNodes.stop();

    // Queue our swaps.
    PendingInputId swapId1 =
        swap(
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.valueOf(4)),
            false);
    PendingInputId swapId2 =
        swap(
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.valueOf(4)),
            false);
    zkNodes.confirmInput(swapId1);
    zkNodes.confirmInput(swapId2);
    Assertions.assertThat(getSwapState().worklist()).hasSize(2);

    // Perform first swap.
    BigInteger receivingB =
        calculateReceivingAmount(
            contractTokenA, NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.valueOf(4)));
    performSwapsInQueue(1);
    Assertions.assertThat(getDepositBalances())
        .containsEntry(
            nonOwnerAddress1,
            new ZkLiquiditySwap.TokenBalance(
                NON_OWNER_TOKEN_AMOUNT_A
                    .multiply(BigInteger.valueOf(3))
                    .divide(BigInteger.valueOf(4)),
                receivingB,
                ZERO));

    // Withdraw remaining free tokens.
    blockchain.sendAction(
        nonOwnerAddress1,
        swapContractAddress,
        ZkLiquiditySwap.withdraw(
            contractTokenA, NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.valueOf(2))));

    // Check that second swap is still performed.
    receivingB =
        receivingB.add(
            calculateReceivingAmount(
                contractTokenA, NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.valueOf(4))));
    performSwapsInQueue(1);
    Assertions.assertThat(getDepositBalances())
        .containsEntry(nonOwnerAddress1, new ZkLiquiditySwap.TokenBalance(ZERO, receivingB, ZERO));
  }

  /** Swap doesn't happen if user withdraws while the swap is in queue. */
  @ContractTest(previous = "initializePool")
  void withdrawTooMuchWhileInQueue() {
    depositIntoSwap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);

    zkNodes.stop();

    // Queue our swaps.
    PendingInputId swapId1 =
        swap(
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO),
            false);
    PendingInputId swapId2 =
        swap(
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO),
            false);
    zkNodes.confirmInput(swapId1);
    zkNodes.confirmInput(swapId2);

    final BigInteger receivingB =
        calculateReceivingAmount(contractTokenA, NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO));
    // Perform first swap.
    performSwapsInQueue(1);

    // Withdraw remaining free tokens + 1.
    withdrawFromSwap(nonOwnerAddress1, contractTokenA, BigInteger.ONE);

    // Check that second swap is not performed.
    Assertions.assertThatCode(() -> performSwapsInQueue(1))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Insufficient TokenA deposit: 16383/16384");
    Assertions.assertThat(getDepositBalances())
        .containsEntry(
            nonOwnerAddress1,
            new ZkLiquiditySwap.TokenBalance(
                NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO).subtract(BigInteger.ONE),
                receivingB,
                ZERO));
  }

  /** Closing the pool will stop queued swaps. */
  @ContractTest(previous = "initializePool")
  void closePoolWithQueue() {
    depositIntoSwap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A);
    depositIntoSwap(nonOwnerAddress2, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B);

    zkNodes.stop();

    // Queue our swaps.
    BigInteger receiving1B =
        calculateReceivingAmount(contractTokenA, NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO));
    BigInteger receiving2A =
        calculateReceivingAmount(contractTokenB, NON_OWNER_TOKEN_AMOUNT_B.divide(BigInteger.TWO));
    PendingInputId swapId1 =
        swap(
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO),
            false);
    PendingInputId swapId2 =
        swap(
            nonOwnerAddress2,
            contractTokenB,
            NON_OWNER_TOKEN_AMOUNT_B.divide(BigInteger.TWO),
            false);
    PendingInputId swapId3 =
        swap(
            nonOwnerAddress1,
            contractTokenA,
            NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.valueOf(4)),
            false);
    PendingInputId swapId4 = swap(nonOwnerAddress2, contractTokenA, receiving2A, false);
    PendingInputId swapId5 = swap(nonOwnerAddress1, contractTokenB, receiving1B, false);
    PendingInputId swapId6 =
        swap(nonOwnerAddress2, contractTokenB, NON_OWNER_TOKEN_AMOUNT_B, false);

    zkNodes.confirmInput(swapId1);
    zkNodes.confirmInput(swapId2);
    zkNodes.confirmInput(swapId3);
    zkNodes.confirmInput(swapId4);
    zkNodes.confirmInput(swapId5);
    zkNodes.confirmInput(swapId6);

    // All swaps are queued.
    Assertions.assertThat(getSwapState().worklist()).hasSize(6);

    // Perform first two swaps.
    performSwapsInQueue(2);
    Map<BlockchainAddress, ZkLiquiditySwap.TokenBalance> afterSwapState =
        Map.of(
            nonOwnerAddress1,
            new ZkLiquiditySwap.TokenBalance(
                NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO), receiving1B, ZERO),
            nonOwnerAddress2,
            new ZkLiquiditySwap.TokenBalance(
                receiving2A.add(BigInteger.TWO),
                NON_OWNER_TOKEN_AMOUNT_B.divide(BigInteger.TWO),
                ZERO));
    Assertions.assertThat(getDepositBalances()).containsAllEntriesOf(afterSwapState);

    // Close contract.
    blockchain.sendAction(contractOwnerAddress, swapContractAddress, ZkLiquiditySwap.closePools());

    // Try to perform remaining swaps. They will fail, as the pools are empty.
    performSwapsInQueue(4);

    // Only the first two swaps have been performed, so state is the same.
    Assertions.assertThat(getDepositBalances()).containsAllEntriesOf(afterSwapState);
  }

  /** User can withdraw while pools are closed. */
  @ContractTest(previous = "initializePool")
  void withdrawFromClosed() {
    // User deposits.
    depositIntoSwap(
        nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO));
    Assertions.assertThat(getTokenState(contractTokenA).balances())
        .containsEntry(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO));

    // Owner closes pools.
    blockchain.sendAction(contractOwnerAddress, swapContractAddress, ZkLiquiditySwap.closePools());

    // User withdraws from contract.
    withdrawFromSwap(
        nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO));

    // Assert that tokens are back to original account.
    Assertions.assertThat(getTokenState(contractTokenA).balances())
        .containsEntry(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_A);
  }

  /** User can withdraw after failed swap. */
  @ContractTest(previous = "initializePool")
  void withdrawAfterFailedSwap() {
    // User deposits.
    depositIntoSwap(
        nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO));
    Assertions.assertThat(getTokenState(contractTokenA).balances())
        .containsEntry(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO));

    // Try to swap too much.
    Assertions.assertThatCode(
            () -> swap(nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A, false))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Insufficient TokenA deposit: 16384/32768");

    // The swap didn't succeed.
    Assertions.assertThat(getDepositBalances())
        .containsEntry(
            nonOwnerAddress1,
            new ZkLiquiditySwap.TokenBalance(
                NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO), ZERO, ZERO));

    // User withdraws from contract.
    withdrawFromSwap(
        nonOwnerAddress1, contractTokenA, NON_OWNER_TOKEN_AMOUNT_A.divide(BigInteger.TWO));

    // Assert that tokens are back to original account.
    Assertions.assertThat(getDepositBalances()).doesNotContainKey(nonOwnerAddress1);
    Assertions.assertThat(getTokenState(contractTokenA).balances())
        .containsEntry(nonOwnerAddress1, NON_OWNER_TOKEN_AMOUNT_A);
  }

  private CompactBitArray createSecretInput(BigInteger amount, boolean direction) {
    // Hack since CodeGen for ZkFunctions not yet supported.
    FileAbi fileAbi = new AbiParser(CONTRACT_BYTES.abi()).parseAbi();
    final ZkInputBuilder builder = ZkInputBuilder.createZkInputBuilder("swap", fileAbi.contract());

    byte bool = direction ? (byte) 0x01 : (byte) 0x00;

    builder.addStruct().addI128(amount).addI8(bool);

    return builder.getBits();
  }

  private byte[] getAdditionalSwapRpc(boolean onlyIfAtFront) {
    FileAbi fileAbi = new AbiParser(CONTRACT_BYTES.abi()).parseAbi();
    final FnRpcBuilder builder = new FnRpcBuilder("swap", fileAbi.contract());
    builder.addBool(onlyIfAtFront);
    return builder.getBytes();
  }

  private ZkLiquiditySwap.ContractState getSwapState() {
    return ZkLiquiditySwap.ContractState.deserialize(
        blockchain.getContractState(swapContractAddress));
  }

  private Token.TokenState getTokenState(BlockchainAddress tokenAddress) {
    return Token.TokenState.deserialize(blockchain.getContractState(tokenAddress));
  }

  private PendingInputId swap(
      BlockchainAddress swapper,
      BlockchainAddress token,
      BigInteger amount,
      boolean onlyIfAtFront) {
    boolean direction = token.equals(contractTokenA);
    return blockchain.sendSecretInput(
        swapContractAddress,
        swapper,
        createSecretInput(amount, direction),
        getAdditionalSwapRpc(onlyIfAtFront));
  }

  private BigInteger calculateReceivingAmount(BlockchainAddress fromToken, BigInteger amount) {
    BigInteger oldFromAmount = getPoolAmountForToken(fromToken);
    BigInteger oldToAmount =
        getPoolAmountForToken(fromToken.equals(contractTokenA) ? contractTokenB : contractTokenA);

    BigInteger newFromAmount = oldFromAmount.add(amount);
    BigInteger[] divrem = SWAP_CONSTANT.divideAndRemainder(newFromAmount);
    BigInteger newToAmount = divrem[0];
    if (!divrem[1].equals(ZERO)) {
      newToAmount = newToAmount.add(BigInteger.ONE);
    }

    return oldToAmount.subtract(newToAmount);
  }

  private BigInteger getPoolAmountForToken(BlockchainAddress token) {
    ZkLiquiditySwap.ContractState state = getSwapState();
    ZkLiquiditySwap.TokenBalance b = state.balances().balances().get(swapContractAddress);
    return token.equals(contractTokenA) ? b.aTokens() : b.bTokens();
  }

  private void depositIntoSwap(
      BlockchainAddress sender, BlockchainAddress contractToken, BigInteger amount) {
    blockchain.sendAction(sender, contractToken, Token.approve(swapContractAddress, amount));
    blockchain.sendAction(
        sender, swapContractAddress, ZkLiquiditySwap.deposit(contractToken, amount));
  }

  private void withdrawFromSwap(
      BlockchainAddress withdrawee, BlockchainAddress tokenAddress, BigInteger amount) {
    blockchain.sendAction(
        withdrawee, swapContractAddress, ZkLiquiditySwap.withdraw(tokenAddress, amount));
  }

  private void performSwapsInQueue(int swapAmount) {
    for (int i = 0; i < swapAmount; ++i) {
      zkNodes.zkCompute(swapContractAddress);
      zkNodes.openVariable(zkNodes.getPendingOpens(swapContractAddress).get(0));
    }
  }

  private Map<BlockchainAddress, ZkLiquiditySwap.TokenBalance> getDepositBalances() {
    return getSwapState().balances().balances();
  }

  private ZkLiquiditySwap.TokenBalance createBalance(BigInteger amountA, BigInteger amountB) {
    return new ZkLiquiditySwap.TokenBalance(amountA, amountB, ZERO);
  }
}
