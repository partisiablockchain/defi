package defi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.DoubleAuctionOrderMatching;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import defi.properties.DepositWithdrawTest;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;

/** Test suite for the double auction order matching contract. */
public final class DoubleAuctionOrderMatchingTest extends JunitContractTest {

  private static final ContractBytes ORDER_MATCHING_CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of(
              "../rust/target/wasm32-unknown-unknown/release/double_auction_order_matching.pbc"),
          Path.of(
              "../rust/target/wasm32-unknown-unknown/release/"
                  + "double_auction_order_matching_runner"));

  private static final BigInteger TOTAL_SUPPLY =
      BigInteger.valueOf(1200).multiply(BigInteger.TEN.pow(18));
  private static final BigInteger INITIAL_CLIENT_CURRENCY_TOKENS = BigInteger.valueOf(1000);
  private static final BigInteger INITIAL_CLIENT_ASSET_TOKENS = BigInteger.valueOf(500);

  private static final long PRICE_NUMERATOR = 90;
  private static final long PRICE_DENOMINATOR = 60;

  private BlockchainAddress currencyTokenAddress;
  private BlockchainAddress assetTokenAddress;

  private BlockchainAddress client1;
  private BlockchainAddress client2;
  private BlockchainAddress admin;

  private BlockchainAddress orderMatchingAddress;
  private DoubleAuctionOrderMatching orderMatching;

  /** Set up the contracts and blockchain accounts. */
  @ContractTest
  void setUp() {
    client1 = blockchain.newAccount(1);
    client2 = blockchain.newAccount(2);
    admin = blockchain.newAccount(3);

    final byte[] initCurrencyToken = Token.initialize("USD Coin", "USDC", (byte) 18, TOTAL_SUPPLY);
    currencyTokenAddress =
        blockchain.deployContract(admin, TokenContractTest.CONTRACT_BYTES, initCurrencyToken);

    final byte[] initAssetToken = Token.initialize("Polygon", "MATIC", (byte) 18, TOTAL_SUPPLY);
    assetTokenAddress =
        blockchain.deployContract(admin, TokenContractTest.CONTRACT_BYTES, initAssetToken);

    byte[] initOrderMatching =
        DoubleAuctionOrderMatching.initialize(
            currencyTokenAddress, assetTokenAddress, PRICE_NUMERATOR, PRICE_DENOMINATOR);
    orderMatchingAddress =
        blockchain.deployContract(admin, ORDER_MATCHING_CONTRACT_BYTES, initOrderMatching);
    orderMatching = new DoubleAuctionOrderMatching(getStateClient(), orderMatchingAddress);

    depositInitialTokens();
  }

  /**
   * When an account submits a bid and no matching asks have been submitted, it will be placed for
   * its original amount.
   */
  @ContractTest(previous = "setUp")
  void submitBidNoAsks() {
    int amount = 2;
    int price = 40;

    submitBid(client1, price, BigInteger.valueOf(amount), 0);

    assertCurrencyTokenBalance(
        client1,
        INITIAL_CLIENT_CURRENCY_TOKENS.subtract(BigInteger.valueOf(totalPrice(amount, price))));
    assertThat(orderMatching.getState().bids().get(expensiveEarly(price, 0)).tokenAmount())
        .isEqualTo(amount);
  }

  /**
   * When an account submits a bid and smaller matching asks have been submitted, they will be met
   * by their amount.
   */
  @ContractTest(previous = "setUp")
  void submitBidMatchingAsks() {
    int price = 40;

    submitAsk(client2, price, BigInteger.valueOf(3), 0);
    submitAsk(client2, price, BigInteger.valueOf(2), 1);
    submitAsk(client2, price, BigInteger.valueOf(3), 2);
    submitAsk(client2, price + 1, BigInteger.valueOf(1), 3);

    submitBid(client1, price, BigInteger.valueOf(10), 4);

    DoubleAuctionOrderMatching.DoubleAuctionContractState state = orderMatching.getState();

    assertThat(state.asks().get(cheapEarly(price + 1, 3)).tokenAmount()).isEqualTo(1);
    assertThat(state.bids().get(expensiveEarly(price, 4)).tokenAmount()).isEqualTo(2);
  }

  /**
   * When an account submits a bid and larger matching asks have been submitted, they will be
   * partially met by their asking amount.
   */
  @ContractTest(previous = "setUp")
  void submitBidLargerAsk() {
    int price = 40;

    submitAsk(client2, price, BigInteger.valueOf(12), 0);

    submitBid(client1, price, BigInteger.valueOf(10), 1);

    DoubleAuctionOrderMatching.DoubleAuctionContractState state = orderMatching.getState();

    assertThat(state.asks().get(cheapEarly(price, 0)).tokenAmount()).isEqualTo(2);
    assertThat(state.bids().size()).isEqualTo(0);
  }

  /**
   * When an account submits an ask and no matching bids have been submitted, it will be placed for
   * its original amount.
   */
  @ContractTest(previous = "setUp")
  void submitAskNoBids() {
    int amount = 2;
    int price = 40;

    submitAsk(client1, price, BigInteger.valueOf(amount), 0);

    assertAssetTokenBalance(
        client1, INITIAL_CLIENT_ASSET_TOKENS.subtract(BigInteger.valueOf(amount)));
    assertThat(orderMatching.getState().asks().get(cheapEarly(price, 0)).tokenAmount())
        .isEqualTo(amount);
  }

  /**
   * When an account submits an ask and smaller matching bids have been submitted, they will be met
   * by their amount.
   */
  @ContractTest(previous = "setUp")
  void submitAskMatchingBids() {
    int price = 40;

    submitBid(client2, price, BigInteger.valueOf(3), 0);
    submitBid(client2, price, BigInteger.valueOf(2), 1);
    submitBid(client2, price, BigInteger.valueOf(3), 2);
    submitBid(client2, price - 1, BigInteger.valueOf(1), 3);

    submitAsk(client1, price, BigInteger.valueOf(10), 4);

    DoubleAuctionOrderMatching.DoubleAuctionContractState state = orderMatching.getState();

    assertThat(state.bids().get(expensiveEarly(price - 1, 3)).tokenAmount()).isEqualTo(1);
    assertThat(state.asks().get(cheapEarly(price, 4)).tokenAmount()).isEqualTo(2);
  }

  /**
   * When an account submits an ask and larger matching bids have been submitted, they will be
   * partially met by their bidding amount.
   */
  @ContractTest(previous = "setUp")
  void submitAskLargerBid() {
    int price = 40;

    submitBid(client2, price, BigInteger.valueOf(12), 0);

    submitAsk(client1, price, BigInteger.valueOf(10), 1);

    DoubleAuctionOrderMatching.DoubleAuctionContractState state = orderMatching.getState();

    assertThat(state.asks().size()).isEqualTo(0);
    assertThat(state.bids().get(expensiveEarly(price, 0)).tokenAmount()).isEqualTo(2);
  }

  /** An account can cancel a previously placed bid. */
  @ContractTest(previous = "setUp")
  void cancelBid() {
    int amount = 2;
    int price = 40;
    int cancelationId = 1234;

    submitBid(client1, price, BigInteger.valueOf(amount), cancelationId);

    cancelLimitOrder(client1, cancelationId);

    DoubleAuctionOrderMatching.DoubleAuctionContractState state = orderMatching.getState();

    assertCurrencyTokenBalance(client1, INITIAL_CLIENT_CURRENCY_TOKENS);
    assertThat(state.bids().size()).isEqualTo(0);
  }

  /** An account can cancel a previously placed ask. */
  @ContractTest(previous = "setUp")
  void cancelAsk() {
    int amount = 2;
    int price = 40;

    submitAsk(client1, price, BigInteger.valueOf(amount), 0);

    cancelLimitOrder(client1, 0);

    DoubleAuctionOrderMatching.DoubleAuctionContractState state = orderMatching.getState();

    assertAssetTokenBalance(client1, INITIAL_CLIENT_ASSET_TOKENS);
    assertThat(state.asks().size()).isEqualTo(0);
  }

  /** An account cannot cancel a limit order that has not been placed. */
  @ContractTest(previous = "setUp")
  void invalidCancel() {
    int amount = 2;
    int price = 40;

    submitAsk(client1, price, BigInteger.valueOf(amount), 1234);

    assertThatThrownBy(() -> cancelLimitOrder(client1, 4321))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("The given cancelation request did not match any orders.");
  }

  /** An amount of assets cannot be transferred when it exceeds 64 bits. */
  @ContractTest(previous = "setUp")
  void tooLargeAssetAmount() {
    BigInteger amount = BigInteger.ONE.shiftLeft(64);
    BigInteger assetAmount = amount.multiply(BigInteger.valueOf(3));

    transfer(currencyTokenAddress, admin, client1, assetAmount);
    approve(client1, currencyTokenAddress, orderMatchingAddress, assetAmount);
    deposit(client1, currencyTokenAddress, assetAmount);

    assertThatThrownBy(() -> submitBid(client1, 2, amount, 0))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Token amounts larger than u64 are not allowed.");
  }

  /** An account can deposit and withdraw the currency and asset tokens. */
  @Nested
  final class DepositWithdraw extends DepositWithdrawTest {
    DepositWithdraw() {
      super(TokenContractTest.CONTRACT_BYTES, ORDER_MATCHING_CONTRACT_BYTES);
    }

    @Override
    protected byte[] initContractUnderTestRpc(BlockchainAddress token1, BlockchainAddress token2) {
      return DoubleAuctionOrderMatching.initialize(
          token1, token2, PRICE_NUMERATOR, PRICE_DENOMINATOR);
    }

    @Override
    protected BigInteger getDepositAmount(BlockchainAddress owner) {
      final var state = new DoubleAuctionOrderMatching(getStateClient(), contractUnderTestAddress);
      final var ownerBalances = state.getState().tokenBalances().balances().get(owner);
      return ownerBalances == null ? BigInteger.ZERO : ownerBalances.aTokens();
    }
  }

  private void deposit(
      BlockchainAddress executor, BlockchainAddress tokenAddress, BigInteger amount) {
    final byte[] rpc = DoubleAuctionOrderMatching.deposit(tokenAddress, amount);
    blockchain.sendAction(executor, orderMatchingAddress, rpc);
  }

  private void submitBid(
      BlockchainAddress executor, long price, BigInteger amount, int cancelationId) {
    final byte[] rpc = DoubleAuctionOrderMatching.submitBid(price, amount, cancelationId);
    blockchain.sendAction(executor, orderMatchingAddress, rpc);
  }

  private void submitAsk(
      BlockchainAddress executor, long price, BigInteger amount, int cancelationId) {
    final byte[] rpc = DoubleAuctionOrderMatching.submitAsk(price, amount, cancelationId);
    blockchain.sendAction(executor, orderMatchingAddress, rpc);
  }

  private void cancelLimitOrder(BlockchainAddress executor, int cancelationId) {
    final byte[] rpc = DoubleAuctionOrderMatching.cancelLimitOrder(cancelationId);
    blockchain.sendAction(executor, orderMatchingAddress, rpc);
  }

  private int totalPrice(long amount, long price) {
    return (int) (amount * price / PRICE_DENOMINATOR * PRICE_NUMERATOR);
  }

  private DoubleAuctionOrderMatching.Priority cheapEarly(long price, long id) {
    ByteBuffer byteBuffer = ByteBuffer.allocate(Long.BYTES * 2);
    byteBuffer.putLong(price);
    byteBuffer.putLong(id);

    return new DoubleAuctionOrderMatching.Priority(byteBuffer.array());
  }

  private DoubleAuctionOrderMatching.Priority expensiveEarly(long price, long id) {
    ByteBuffer byteBuffer = ByteBuffer.allocate(Long.BYTES * 2);
    byteBuffer.putLong(~price);
    byteBuffer.putLong(id);

    return new DoubleAuctionOrderMatching.Priority(byteBuffer.array());
  }

  private void assertCurrencyTokenBalance(BlockchainAddress account, BigInteger balance) {
    assertThat(getTokenBalance(account).aTokens()).isEqualTo(balance);
  }

  private void assertAssetTokenBalance(BlockchainAddress account, BigInteger balance) {
    assertThat(getTokenBalance(account).bTokens()).isEqualTo(balance);
  }

  private DoubleAuctionOrderMatching.TokenBalance getTokenBalance(BlockchainAddress account) {
    DoubleAuctionOrderMatching.TokenBalance tokenBalance =
        orderMatching.getState().tokenBalances().balances().get(account);

    if (tokenBalance == null) {
      tokenBalance =
          new DoubleAuctionOrderMatching.TokenBalance(
              BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
    }

    return tokenBalance;
  }

  private void depositInitialTokens() {
    transfer(currencyTokenAddress, admin, client1, INITIAL_CLIENT_CURRENCY_TOKENS);
    approve(client1, currencyTokenAddress, orderMatchingAddress, INITIAL_CLIENT_CURRENCY_TOKENS);
    deposit(client1, currencyTokenAddress, INITIAL_CLIENT_CURRENCY_TOKENS);

    transfer(currencyTokenAddress, admin, client2, INITIAL_CLIENT_CURRENCY_TOKENS);
    approve(client2, currencyTokenAddress, orderMatchingAddress, INITIAL_CLIENT_CURRENCY_TOKENS);
    deposit(client2, currencyTokenAddress, INITIAL_CLIENT_CURRENCY_TOKENS);

    transfer(assetTokenAddress, admin, client1, INITIAL_CLIENT_ASSET_TOKENS);
    approve(client1, assetTokenAddress, orderMatchingAddress, INITIAL_CLIENT_ASSET_TOKENS);
    deposit(client1, assetTokenAddress, INITIAL_CLIENT_ASSET_TOKENS);

    transfer(assetTokenAddress, admin, client2, INITIAL_CLIENT_ASSET_TOKENS);
    approve(client2, assetTokenAddress, orderMatchingAddress, INITIAL_CLIENT_ASSET_TOKENS);
    deposit(client2, assetTokenAddress, INITIAL_CLIENT_ASSET_TOKENS);
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
