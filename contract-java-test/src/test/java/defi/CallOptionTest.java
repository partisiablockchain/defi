package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.CallOption;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.testenvironment.TxExecution;
import java.math.BigInteger;
import java.nio.file.Path;
import org.assertj.core.api.Assertions;

/** Testing of the call option contract. */
public final class CallOptionTest extends JunitContractTest {

  /** {@link CallOption} contract bytes. */
  private static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/call_option.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/call_option_runner"));

  private static final BigInteger SELL_AMOUNT = BigInteger.valueOf(1234L);
  private static final BigInteger PAYMENT_AMOUNT = BigInteger.valueOf(7789L);

  private static final long DEADLINE = 1000000L;
  private static final long EXECUTION_WINDOW_START = DEADLINE + 60_000;
  private static final long EXECUTION_WINDOW_END = EXECUTION_WINDOW_START + 60_000;

  private static final long GAS_COST_MPC20_TRANSFER = 15500;
  private static final long GAS_CALLBACK = 1000;

  public BlockchainAddress buyer;
  public BlockchainAddress seller;
  public BlockchainAddress mpcMpc20;
  public BlockchainAddress usdcMpc20;
  private BlockchainAddress agreementContract;

  @ContractTest
  void prepareTokens() {
    buyer = blockchain.newAccount(1);
    seller = blockchain.newAccount(2);

    initializeTokenContracts();
  }

  /** Initializing with a deadline in the past fails. */
  @ContractTest(previous = "prepareTokens")
  void deployInvalidDeadline() {
    long productionTime = blockchain.getBlockProductionTime();
    byte[] initInvalidDeadline =
        CallOption.initialize(
            usdcMpc20,
            mpcMpc20,
            seller,
            SELL_AMOUNT,
            PAYMENT_AMOUNT,
            productionTime,
            productionTime + 100,
            productionTime + 200);
    Assertions.assertThatCode(
            () -> blockchain.deployContract(buyer, CONTRACT_BYTES, initInvalidDeadline))
        .hasMessageContaining("Deadline has to be in the future");
  }

  /** Initializing with an invalid execution window fails. */
  @ContractTest(previous = "prepareTokens")
  void deployInvalidExecutionWindow() {
    long deadline = blockchain.getBlockProductionTime() + 10000;
    byte[] initInvalidWindowStart =
        CallOption.initialize(
            usdcMpc20,
            mpcMpc20,
            seller,
            SELL_AMOUNT,
            PAYMENT_AMOUNT,
            deadline,
            deadline - 1,
            deadline + 1);
    Assertions.assertThatCode(
            () -> blockchain.deployContract(buyer, CONTRACT_BYTES, initInvalidWindowStart))
        .hasMessageContaining("Execution window must start after the deadline");

    long windowStart = deadline + 1000;
    byte[] initInvalidWindowEnd =
        CallOption.initialize(
            usdcMpc20,
            mpcMpc20,
            seller,
            SELL_AMOUNT,
            PAYMENT_AMOUNT,
            deadline,
            windowStart,
            windowStart);
    Assertions.assertThatCode(
            () -> blockchain.deployContract(buyer, CONTRACT_BYTES, initInvalidWindowEnd))
        .hasMessageContaining("Execution window cannot end before it starts");
  }

  /** Initialization succeeds when called with valid parameters. */
  @ContractTest(previous = "prepareTokens")
  void deployAgreement() {
    byte[] initInvalidWindowStart =
        CallOption.initialize(
            mpcMpc20,
            usdcMpc20,
            seller,
            SELL_AMOUNT,
            PAYMENT_AMOUNT,
            DEADLINE,
            EXECUTION_WINDOW_START,
            EXECUTION_WINDOW_END);
    agreementContract = blockchain.deployContract(buyer, CONTRACT_BYTES, initInvalidWindowStart);

    CallOption.State state = getAgreementState();
    Assertions.assertThat(state.buyer()).isEqualTo(buyer);
    Assertions.assertThat(state.sellToken()).isEqualTo(mpcMpc20);
    Assertions.assertThat(state.paymentToken()).isEqualTo(usdcMpc20);
    Assertions.assertThat(state.seller()).isEqualTo(seller);
    Assertions.assertThat(state.tokenAmount()).isEqualTo(SELL_AMOUNT);
    Assertions.assertThat(state.agreedPayment()).isEqualTo(PAYMENT_AMOUNT);
    Assertions.assertThat(state.deadline()).isEqualTo(DEADLINE);
    Assertions.assertThat(state.executionWindow().start()).isEqualTo(EXECUTION_WINDOW_START);
    Assertions.assertThat(state.executionWindow().end()).isEqualTo(EXECUTION_WINDOW_END);
    Assertions.assertThat(state.status()).isEqualTo(new CallOption.StatusPending());
  }

  /** When seller accepts agreement the tokens to sell are transferred to the contract. */
  @ContractTest(previous = "deployAgreement")
  void acceptAgreement() {
    blockchain.sendAction(seller, mpcMpc20, Token.approve(agreementContract, SELL_AMOUNT));

    blockchain.sendAction(seller, agreementContract, CallOption.enterAgreement());

    CallOption.State state = getAgreementState();
    Assertions.assertThat(state.status()).isEqualTo(new CallOption.StatusAccepted());
    Assertions.assertThat(getTokenState(mpcMpc20).balances().get(agreementContract))
        .isEqualTo(SELL_AMOUNT);
  }

  /**
   * Acceptance of agreement set the status of the contract to Depositing while transferring tokens.
   */
  @ContractTest(previous = "deployAgreement")
  void acceptAgreementSetsStatus() {
    blockchain.sendAction(seller, mpcMpc20, Token.approve(agreementContract, SELL_AMOUNT));

    TxExecution signedTransactionExecution =
        blockchain.sendActionAsync(seller, agreementContract, CallOption.enterAgreement());

    TxExecution contractAction = signedTransactionExecution.getSpawnedEvents().get(0);
    TxExecution execution = blockchain.executeEventAsync(contractAction);

    CallOption.State state = getAgreementState();
    Assertions.assertThat(state.status()).isEqualTo(new CallOption.StatusDepositing());

    blockchain.executeEvent(execution.getSpawnedEvents().get(0));
    Assertions.assertThat(getAgreementState().status()).isEqualTo(new CallOption.StatusAccepted());
  }

  /** Accepting with too little gas does not change the state of the contract. */
  @ContractTest(previous = "deployAgreement")
  void acceptAgreementTooLittleGas() {
    // No gas available for CPU and network making the transaction fail
    long cost = GAS_COST_MPC20_TRANSFER + GAS_CALLBACK;
    Assertions.assertThatThrownBy(
            () ->
                blockchain.sendAction(seller, agreementContract, CallOption.enterAgreement(), cost))
        .hasMessageContaining("Cannot allocate gas for events");

    CallOption.State state = getAgreementState();
    Assertions.assertThat(state.status()).isEqualTo(new CallOption.StatusPending());
    Assertions.assertThat(getTokenState(mpcMpc20).balances().get(agreementContract)).isNull();
  }

  /** Acceptance is only allowed by the seller. */
  @ContractTest(previous = "deployAgreement")
  void onlySellerCanAccept() {
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(buyer, agreementContract, CallOption.enterAgreement()))
        .hasMessageContaining("Only the seller are allowed to enter into the agreement");
  }

  /** Acceptance fails if the seller has not approved the contract. */
  @ContractTest(previous = "deployAgreement")
  void acceptanceFailsWithoutAcceptance() {
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(seller, agreementContract, CallOption.enterAgreement()))
        .hasMessageContaining("Insufficient MPC allowance for transfer_from");

    Assertions.assertThat(getAgreementState().status()).isEqualTo(new CallOption.StatusPending());
  }

  /** Acceptance fails if the deadline has been passed. */
  @ContractTest(previous = "deployAgreement")
  void acceptanceFailsAfterDeadline() {
    blockchain.waitForBlockProductionTime(DEADLINE);
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(seller, agreementContract, CallOption.enterAgreement()))
        .hasMessageContaining("Unable to enter into the agreement after the deadline");
  }

  /** Acceptance fails if the agreement has already been entered. */
  @ContractTest(previous = "acceptAgreement")
  void unableToEnterTwice() {
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(seller, agreementContract, CallOption.enterAgreement()))
        .hasMessageContaining("The contract must be Pending for the agreemet to be accepted");
  }

  /** Buyer can execute the agreement which transfers payment to seller and tokens to buyer. */
  @ContractTest(previous = "acceptAgreement")
  void executeAgreement() {
    blockchain.sendAction(buyer, usdcMpc20, Token.approve(agreementContract, PAYMENT_AMOUNT));

    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_START);

    blockchain.sendAction(buyer, agreementContract, CallOption.execute());

    CallOption.State state = getAgreementState();
    Assertions.assertThat(state.status()).isEqualTo(new CallOption.StatusDone());
    Assertions.assertThat(getTokenState(mpcMpc20).balances().get(buyer)).isEqualTo(SELL_AMOUNT);
    Assertions.assertThat(getTokenState(usdcMpc20).balances().get(seller))
        .isEqualTo(PAYMENT_AMOUNT);
  }

  /** Execution of agreement set the status of the contract to Paying while transferring tokens. */
  @ContractTest(previous = "acceptAgreement")
  void executeAgreementSetsStatus() {
    blockchain.sendAction(buyer, usdcMpc20, Token.approve(agreementContract, PAYMENT_AMOUNT));

    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_START);

    TxExecution signedTransactionExecution =
        blockchain.sendActionAsync(buyer, agreementContract, CallOption.execute());

    TxExecution contractAction = signedTransactionExecution.getSpawnedEvents().get(0);
    TxExecution execution = blockchain.executeEventAsync(contractAction);

    CallOption.State state = getAgreementState();
    Assertions.assertThat(state.status()).isEqualTo(new CallOption.StatusPaying());

    blockchain.executeEvent(execution.getSpawnedEvents().get(0));
    Assertions.assertThat(getAgreementState().status()).isEqualTo(new CallOption.StatusDone());
  }

  /** Executing with too little gas does not change the state of the contract. */
  @ContractTest(previous = "acceptAgreement")
  void executeAgreementTooLittleGas() {
    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_START);

    // No gas available for CPU and network making the transaction fail
    long cost = GAS_COST_MPC20_TRANSFER + GAS_COST_MPC20_TRANSFER + GAS_CALLBACK;
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(buyer, agreementContract, CallOption.execute(), cost))
        .hasMessageContaining("Cannot allocate gas for events");

    CallOption.State state = getAgreementState();
    Assertions.assertThat(state.status()).isEqualTo(new CallOption.StatusAccepted());
    Assertions.assertThat(getTokenState(mpcMpc20).balances().get(agreementContract))
        .isEqualTo(SELL_AMOUNT);
    Assertions.assertThat(getTokenState(usdcMpc20).balances().get(seller)).isNull();
  }

  /** Execution of agreement is not allowed for the seller. */
  @ContractTest(previous = "acceptAgreement")
  void onlyBuyerCanExecute() {
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(seller, agreementContract, CallOption.execute()))
        .hasMessageContaining("Only the buyer are allowed to execute the agreement");
  }

  /** Execution of agreement fails if the buyer has not approved the contract. */
  @ContractTest(previous = "acceptAgreement")
  void executionFailsIfTokensAreNotApproved() {
    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_START);

    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(buyer, agreementContract, CallOption.execute()))
        .hasMessageContaining("Insufficient USDC allowance for transfer_from");

    Assertions.assertThat(getAgreementState().status()).isEqualTo(new CallOption.StatusAccepted());
  }

  /** Execution of agreement fails if the contract has not been accepted. */
  @ContractTest(previous = "deployAgreement")
  void unableToExecuteWithoutAcceptance() {
    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_START);

    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(buyer, agreementContract, CallOption.execute()))
        .hasMessageContaining("Only an accepted agreement can be executed");
  }

  /** Execution of agreement fails if the window has not started. */
  @ContractTest(previous = "acceptAgreement")
  void unableToExecuteAgreementPriorToExecutionWindow() {
    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_START - 2);
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(buyer, agreementContract, CallOption.execute()))
        .hasMessageContaining(
            "It is only possible to execute the agreement during the execution window");
  }

  /** Execution of agreement fails if the window has ended. */
  @ContractTest(previous = "acceptAgreement")
  void unableToExecuteAgreementAfterExecutionWindow() {
    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_END);
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(buyer, agreementContract, CallOption.execute()))
        .hasMessageContaining(
            "It is only possible to execute the agreement during the execution window");
  }

  /** The seller can cancel the agreement after the execution window. */
  @ContractTest(previous = "acceptAgreement")
  void sellerCanCancelAfterWindow() {
    BigInteger balanceBefore = getTokenState(mpcMpc20).balances().get(seller);

    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_END);
    blockchain.sendAction(seller, agreementContract, CallOption.cancel());

    Assertions.assertThat(getTokenState(mpcMpc20).balances().get(seller).subtract(balanceBefore))
        .isEqualTo(SELL_AMOUNT);
  }

  /** Canceling with too little gas does not change the state of the contract. */
  @ContractTest(previous = "acceptAgreement")
  void cancelAgreementTooLittleGas() {
    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_END);

    // No gas available for CPU and network making the transaction fail
    Assertions.assertThatThrownBy(
            () ->
                blockchain.sendAction(
                    seller, agreementContract, CallOption.cancel(), GAS_COST_MPC20_TRANSFER))
        .hasMessageContaining("Cannot allocate gas for events");

    CallOption.State state = getAgreementState();
    Assertions.assertThat(state.status()).isEqualTo(new CallOption.StatusAccepted());
    Assertions.assertThat(getTokenState(mpcMpc20).balances().get(agreementContract))
        .isEqualTo(SELL_AMOUNT);
  }

  /** Cancellation of agreement is not allowed by the buyer. */
  @ContractTest(previous = "acceptAgreement")
  void buyerCannotCancel() {
    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_END);
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(buyer, agreementContract, CallOption.cancel()))
        .hasMessageContaining("Only the seller are allowed to cancel the agreement");
  }

  /** Cancellation of agreement fails if the contract has not been accepted. */
  @ContractTest(previous = "deployAgreement")
  void onlyAcceptedAgreementCanBeCancelled() {
    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_END);
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(seller, agreementContract, CallOption.cancel()))
        .hasMessageContaining("It is only possible to cancel an accepted agreement");
  }

  /** Cancellation of agreement fails if the window has not yet ended. */
  @ContractTest(previous = "acceptAgreement")
  void unableToCancelBeforeExecutionWindow() {
    blockchain.waitForBlockProductionTime(EXECUTION_WINDOW_START);
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(seller, agreementContract, CallOption.cancel()))
        .hasMessageContaining(
            "It is not possible to cancel the agreement prior to the execution window ending");
  }

  private Token.TokenState getTokenState(BlockchainAddress contract) {
    return new Token(getStateClient(), contract).getState();
  }

  private CallOption.State getAgreementState() {
    return new CallOption(getStateClient(), agreementContract).getState();
  }

  private void initializeTokenContracts() {
    final byte[] initRpcEth =
        Token.initialize("MPC", "MPC", (byte) 3, BigInteger.valueOf(1_000_000L));
    mpcMpc20 = blockchain.deployContract(seller, TokenContractTest.CONTRACT_BYTES, initRpcEth);

    final byte[] initRpcUsdCoin =
        Token.initialize("USD Coin", "USDC", (byte) 6, BigInteger.valueOf(1_000_000L));
    usdcMpc20 = blockchain.deployContract(buyer, TokenContractTest.CONTRACT_BYTES, initRpcUsdCoin);
  }
}
