package defi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.PredictionMarketTokenSplitter;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import defi.properties.DepositWithdrawTest;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;

/** Test suite for the prediction market token splitter contract. */
public final class TokenSplitterTest extends JunitContractTest {

  private static final ContractBytes TOKEN_SPLITTER_CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of(
              "../rust/target/wasm32-unknown-unknown/release/prediction_market_token_splitter.pbc"),
          Path.of(
              "../rust/target/wasm32-unknown-unknown/release/"
                  + "prediction_market_token_splitter_runner"));

  private static final BigInteger TOTAL_SUPPLY =
      BigInteger.valueOf(1200).multiply(BigInteger.TEN.pow(18));
  private static final BigInteger INITIAL_TOKEN_SPLITTER_TOKENS =
      BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18));

  private BlockchainAddress originalTokenAddress;
  private BlockchainAddress trueTokenAddress;
  private BlockchainAddress falseTokenAddress;

  private BlockchainAddress client;
  private BlockchainAddress admin;
  private BlockchainAddress arbitratorAddress;
  private BlockchainAddress tokenSplitterAddress;
  private PredictionMarketTokenSplitter tokenSplitter;

  /** Set up the contracts and blockchain accounts. */
  @ContractTest
  void setUp() {
    client = blockchain.newAccount(1);
    admin = blockchain.newAccount(2);
    arbitratorAddress = blockchain.newAccount(3);

    final byte[] initOriginalToken = Token.initialize("USD Coin", "USDC", (byte) 18, TOTAL_SUPPLY);
    originalTokenAddress =
        blockchain.deployContract(admin, TokenContractTest.CONTRACT_BYTES, initOriginalToken);

    final byte[] initTrueToken =
        Token.initialize("USDC If True", "USDCIT", (byte) 18, TOTAL_SUPPLY);
    trueTokenAddress =
        blockchain.deployContract(admin, TokenContractTest.CONTRACT_BYTES, initTrueToken);

    final byte[] initFalseToken =
        Token.initialize("USDC If False", "USDCIF", (byte) 18, TOTAL_SUPPLY);
    falseTokenAddress =
        blockchain.deployContract(admin, TokenContractTest.CONTRACT_BYTES, initFalseToken);

    byte[] initTokenSplitter =
        PredictionMarketTokenSplitter.initialize(
            "eventDesc",
            "eventSymbol",
            originalTokenAddress,
            trueTokenAddress,
            falseTokenAddress,
            arbitratorAddress);
    tokenSplitterAddress =
        blockchain.deployContract(admin, TOKEN_SPLITTER_CONTRACT_BYTES, initTokenSplitter);

    tokenSplitter = new PredictionMarketTokenSplitter(getStateClient(), tokenSplitterAddress);
  }

  /** An account can deposit and withdraw the currency and asset tokens. */
  @Nested
  final class DepositWithdraw extends DepositWithdrawTest {
    DepositWithdraw() {
      super(TokenContractTest.CONTRACT_BYTES, TOKEN_SPLITTER_CONTRACT_BYTES);
    }

    @Override
    protected byte[] initContractUnderTestRpc(BlockchainAddress token1, BlockchainAddress token2) {
      return PredictionMarketTokenSplitter.initialize(
          "eventDesc", "eventSymbol", token1, token2, falseTokenAddress, arbitratorAddress);
    }

    @Override
    protected BigInteger getDepositAmount(BlockchainAddress owner) {
      final var state =
          new PredictionMarketTokenSplitter(getStateClient(), contractUnderTestAddress);
      final var ownerBalances = state.getState().tokenBalances().balances().get(owner);
      return ownerBalances == null ? BigInteger.ZERO : ownerBalances.liquidityTokens();
    }
  }

  /**
   * The token splitter can begin preparing, where the caller transfers some amount of true and
   * false tokens to the token splitter.
   */
  @ContractTest(previous = "setUp")
  void canPrepare() {
    transfer(trueTokenAddress, admin, client, INITIAL_TOKEN_SPLITTER_TOKENS);
    approve(client, trueTokenAddress, tokenSplitterAddress, INITIAL_TOKEN_SPLITTER_TOKENS);
    deposit(client, trueTokenAddress, INITIAL_TOKEN_SPLITTER_TOKENS);

    transfer(falseTokenAddress, admin, client, INITIAL_TOKEN_SPLITTER_TOKENS);
    approve(client, falseTokenAddress, tokenSplitterAddress, INITIAL_TOKEN_SPLITTER_TOKENS);
    deposit(client, falseTokenAddress, INITIAL_TOKEN_SPLITTER_TOKENS);

    prepare(client, INITIAL_TOKEN_SPLITTER_TOKENS);

    assertThat(tokenSplitter.getState().lifeStage())
        .isInstanceOf(PredictionMarketTokenSplitter.LifeStageACTIVE.class);

    assertTrueTokenBalance(tokenSplitterAddress, INITIAL_TOKEN_SPLITTER_TOKENS);
    assertFalseTokenBalance(tokenSplitterAddress, INITIAL_TOKEN_SPLITTER_TOKENS);
    assertTrueTokenBalance(client, BigInteger.ZERO);
    assertFalseTokenBalance(client, BigInteger.ZERO);
  }

  /** The token splitter cannot begin preparing, when it has already prepared. */
  @ContractTest(previous = "setUp")
  void canOnlyPrepareOnce() {
    transfer(trueTokenAddress, admin, client, INITIAL_TOKEN_SPLITTER_TOKENS);
    approve(client, trueTokenAddress, tokenSplitterAddress, INITIAL_TOKEN_SPLITTER_TOKENS);
    deposit(client, trueTokenAddress, INITIAL_TOKEN_SPLITTER_TOKENS);

    transfer(falseTokenAddress, admin, client, INITIAL_TOKEN_SPLITTER_TOKENS);
    approve(client, falseTokenAddress, tokenSplitterAddress, INITIAL_TOKEN_SPLITTER_TOKENS);
    deposit(client, falseTokenAddress, INITIAL_TOKEN_SPLITTER_TOKENS);

    prepare(client, INITIAL_TOKEN_SPLITTER_TOKENS);

    assertThatThrownBy(() -> prepare(client, INITIAL_TOKEN_SPLITTER_TOKENS))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Can only prepare if life stage is Preparing");
  }

  /**
   * An account can split some amount of the original token from its balance, which then adds that
   * same amount of true and false tokens to its balance.
   */
  @ContractTest(previous = "setUp")
  void canSplit() {
    depositInitialTokensAndPrepare();

    transfer(originalTokenAddress, admin, client, BigInteger.TEN);
    approve(client, originalTokenAddress, tokenSplitterAddress, BigInteger.TEN);
    deposit(client, originalTokenAddress, BigInteger.TEN);

    split(client, BigInteger.TEN);

    assertTrueTokenBalance(client, BigInteger.TEN);
    assertFalseTokenBalance(client, BigInteger.TEN);
    assertOriginalTokenBalance(client, BigInteger.ZERO);
  }

  /** An account cannot split its tokens before the token splitter has been prepared. */
  @ContractTest(previous = "setUp")
  void splitBeforePrepared() {
    transfer(originalTokenAddress, admin, client, BigInteger.TEN);
    approve(client, originalTokenAddress, tokenSplitterAddress, BigInteger.TEN);
    deposit(client, originalTokenAddress, BigInteger.TEN);

    assertThatThrownBy(() -> split(client, BigInteger.TEN))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Can only split if life stage is Active");
  }

  /** An account cannot split its tokens after the event has been settled. */
  @ContractTest(previous = "setUp")
  void splitAfterSettled() {
    depositInitialTokensAndPrepare();

    settle(arbitratorAddress, true);

    transfer(originalTokenAddress, admin, client, BigInteger.TEN);
    approve(client, originalTokenAddress, tokenSplitterAddress, BigInteger.TEN);
    deposit(client, originalTokenAddress, BigInteger.TEN);

    assertThatThrownBy(() -> split(client, BigInteger.TEN))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Can only split if life stage is Active");
  }

  /**
   * An account can join together some amount of true and false tokens from its balance, which then
   * adds that same amount of original tokens to its balance.
   */
  @ContractTest(previous = "setUp")
  void canJoin() {
    depositInitialTokensAndPrepare();

    transfer(originalTokenAddress, admin, client, BigInteger.TEN);
    approve(client, originalTokenAddress, tokenSplitterAddress, BigInteger.TEN);
    deposit(client, originalTokenAddress, BigInteger.TEN);

    split(client, BigInteger.TEN);

    join(client, BigInteger.TWO);

    assertTrueTokenBalance(client, BigInteger.TEN.subtract(BigInteger.TWO));
    assertFalseTokenBalance(client, BigInteger.TEN.subtract(BigInteger.TWO));
    assertOriginalTokenBalance(client, BigInteger.TWO);
  }

  /** An account cannot join its tokens before the token splitter has been prepared. */
  @ContractTest(previous = "setUp")
  void joinBeforePrepared() {
    assertThatThrownBy(() -> join(client, BigInteger.TEN))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Can only join if life stage is Active");
  }

  /** An account cannot join its tokens after the event has been settled. */
  @ContractTest(previous = "setUp")
  void joinAfterSettled() {
    depositInitialTokensAndPrepare();

    transfer(originalTokenAddress, admin, client, BigInteger.TEN);
    approve(client, originalTokenAddress, tokenSplitterAddress, BigInteger.TEN);
    deposit(client, originalTokenAddress, BigInteger.TEN);

    split(client, BigInteger.TEN);

    settle(arbitratorAddress, true);

    assertThatThrownBy(() -> join(client, BigInteger.TEN))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Can only join if life stage is Active");
  }

  /** The arbitrator can settle the outcome of the event. */
  @ContractTest(previous = "setUp")
  void settleByArbitrator() {
    depositInitialTokensAndPrepare();

    settle(arbitratorAddress, true);

    PredictionMarketTokenSplitter.LifeStage lifeStage = tokenSplitter.getState().lifeStage();

    assertThat(lifeStage).isInstanceOf(PredictionMarketTokenSplitter.LifeStageSETTLED.class);
    assertThat(((PredictionMarketTokenSplitter.LifeStageSETTLED) lifeStage).outcome()).isTrue();
  }

  /** An address other than that of the arbitrator cannot settle the outcome of the event. */
  @ContractTest(previous = "setUp")
  void settleByNonArbitrator() {
    depositInitialTokensAndPrepare();

    assertThatThrownBy(() -> settle(client, true))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Address other than that of the arbitrator cannot settle the event.");

    assertThat(tokenSplitter.getState().lifeStage())
        .isInstanceOf(PredictionMarketTokenSplitter.LifeStageACTIVE.class);
  }

  /** The arbitrator cannot settle an event that has not been prepared. */
  @ContractTest(previous = "setUp")
  void settleNotPrepared() {
    assertThatThrownBy(() -> settle(arbitratorAddress, true))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Can only settle if life stage is Active");

    assertThat(tokenSplitter.getState().lifeStage())
        .isInstanceOf(PredictionMarketTokenSplitter.LifeStagePREPARING.class);
  }

  /** The arbitrator cannot settle the event when it has already been settled. */
  @ContractTest(previous = "setUp")
  void settleAlreadySettled() {
    depositInitialTokensAndPrepare();

    settle(arbitratorAddress, true);

    assertThatThrownBy(() -> settle(arbitratorAddress, false))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Can only settle if life stage is Active");
  }

  /**
   * When the outcome of the event is true, an account can redeem its true tokens into original
   * tokens.
   */
  @ContractTest(previous = "setUp")
  void redeemTrue() {
    depositInitialTokensAndPrepare();

    transfer(originalTokenAddress, admin, client, BigInteger.TEN);
    approve(client, originalTokenAddress, tokenSplitterAddress, BigInteger.TEN);
    deposit(client, originalTokenAddress, BigInteger.TEN);

    split(client, BigInteger.TEN);

    settle(arbitratorAddress, true);

    redeem(client, BigInteger.TEN);

    assertOriginalTokenBalance(client, BigInteger.TEN);
    assertTrueTokenBalance(client, BigInteger.ZERO);
    assertFalseTokenBalance(client, BigInteger.TEN);
  }

  /**
   * When the outcome of the event is false, an account can redeem its false tokens into original
   * tokens.
   */
  @ContractTest(previous = "setUp")
  void redeemFalse() {
    depositInitialTokensAndPrepare();

    transfer(originalTokenAddress, admin, client, BigInteger.TEN);
    approve(client, originalTokenAddress, tokenSplitterAddress, BigInteger.TEN);
    deposit(client, originalTokenAddress, BigInteger.TEN);

    split(client, BigInteger.TEN);

    settle(arbitratorAddress, false);

    redeem(client, BigInteger.TEN);

    assertOriginalTokenBalance(client, BigInteger.TEN);
    assertTrueTokenBalance(client, BigInteger.TEN);
    assertFalseTokenBalance(client, BigInteger.ZERO);
  }

  /** An account cannot redeem its tokens when the event has not been settled. */
  @ContractTest(previous = "setUp")
  void redeemNotSettled() {
    assertThatThrownBy(() -> redeem(client, BigInteger.TEN))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Can only redeem if life stage is Settled");

    depositInitialTokensAndPrepare();

    assertThatThrownBy(() -> redeem(client, BigInteger.TEN))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Can only redeem if life stage is Settled");
  }

  private void deposit(
      BlockchainAddress executor, BlockchainAddress tokenAddress, BigInteger amount) {
    final byte[] rpc = PredictionMarketTokenSplitter.deposit(tokenAddress, amount);
    blockchain.sendAction(executor, tokenSplitterAddress, rpc);
  }

  private void split(BlockchainAddress executor, BigInteger amount) {
    final byte[] rpc = PredictionMarketTokenSplitter.split(amount);
    blockchain.sendAction(executor, tokenSplitterAddress, rpc);
  }

  private void join(BlockchainAddress executor, BigInteger amount) {
    final byte[] rpc = PredictionMarketTokenSplitter.join(amount);
    blockchain.sendAction(executor, tokenSplitterAddress, rpc);
  }

  private void prepare(BlockchainAddress executor, BigInteger amount) {
    final byte[] rpc = PredictionMarketTokenSplitter.prepare(amount);
    blockchain.sendAction(executor, tokenSplitterAddress, rpc);
  }

  private void settle(BlockchainAddress executor, boolean settleTo) {
    final byte[] rpc = PredictionMarketTokenSplitter.settle(settleTo);
    blockchain.sendAction(executor, tokenSplitterAddress, rpc);
  }

  private void redeem(BlockchainAddress executor, BigInteger amount) {
    final byte[] rpc = PredictionMarketTokenSplitter.redeem(amount);
    blockchain.sendAction(executor, tokenSplitterAddress, rpc);
  }

  private void assertOriginalTokenBalance(BlockchainAddress account, BigInteger balance) {
    assertThat(getTokenBalance(account).liquidityTokens()).isEqualTo(balance);
  }

  private void assertTrueTokenBalance(BlockchainAddress account, BigInteger balance) {
    assertThat(getTokenBalance(account).aTokens()).isEqualTo(balance);
  }

  private void assertFalseTokenBalance(BlockchainAddress account, BigInteger balance) {
    assertThat(getTokenBalance(account).bTokens()).isEqualTo(balance);
  }

  private PredictionMarketTokenSplitter.TokenBalance getTokenBalance(BlockchainAddress account) {
    PredictionMarketTokenSplitter.TokenBalance tokenBalance =
        tokenSplitter.getState().tokenBalances().balances().get(account);

    if (tokenBalance == null) {
      tokenBalance =
          new PredictionMarketTokenSplitter.TokenBalance(
              BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
    }

    return tokenBalance;
  }

  private void depositInitialTokensAndPrepare() {
    transfer(trueTokenAddress, admin, client, INITIAL_TOKEN_SPLITTER_TOKENS);
    approve(client, trueTokenAddress, tokenSplitterAddress, INITIAL_TOKEN_SPLITTER_TOKENS);
    deposit(client, trueTokenAddress, INITIAL_TOKEN_SPLITTER_TOKENS);

    transfer(falseTokenAddress, admin, client, INITIAL_TOKEN_SPLITTER_TOKENS);
    approve(client, falseTokenAddress, tokenSplitterAddress, INITIAL_TOKEN_SPLITTER_TOKENS);
    deposit(client, falseTokenAddress, INITIAL_TOKEN_SPLITTER_TOKENS);

    prepare(client, INITIAL_TOKEN_SPLITTER_TOKENS);
  }

  private void approve(
      BlockchainAddress approver,
      BlockchainAddress contract,
      BlockchainAddress approvee,
      BigInteger amount) {
    final byte[] rpc = Token.approve(approvee, amount);
    blockchain.sendAction(approver, contract, rpc);
  }

  private void transfer(
      BlockchainAddress contract, BlockchainAddress from, BlockchainAddress to, BigInteger amount) {
    final byte[] rpc = Token.transfer(to, amount);
    blockchain.sendAction(from, contract, rpc);
  }
}
