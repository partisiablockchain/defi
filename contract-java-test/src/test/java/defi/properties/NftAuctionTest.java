package defi.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.NftAuction;
import com.partisiablockchain.language.abicodegen.NftContract;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import com.partisiablockchain.language.testenvironment.TxExecution;
import java.math.BigInteger;

/** Test suite for the {@link NftAuction} smart contract. */
public abstract class NftAuctionTest extends JunitContractTest {

  private static final int DOGE_SUPPLY = 100000000;
  private final BigInteger nftId = BigInteger.valueOf(4200);

  public BlockchainAddress ownerDoge;
  public BlockchainAddress auctionOwner;
  public BlockchainAddress bidder1;
  public BlockchainAddress bidder2;
  public BlockchainAddress bidder3;
  public BlockchainAddress nft;
  public BlockchainAddress doge;
  public BlockchainAddress auction;
  private NftAuction auctionContract;

  private static final byte BIDDING = 1;
  private static final byte ENDED = 2;
  private static final long auctionEndTime = 3 * 60 * 60 * 1000;

  private final ContractBytes contractBytesAuction;
  private final ContractBytes contractBytesToken;
  private final ContractBytes contractBytesNft;

  protected NftAuctionTest(
      ContractBytes contractBytesAuction,
      ContractBytes contractBytesToken,
      ContractBytes contractBytesNft) {
    this.contractBytesAuction = contractBytesAuction;
    this.contractBytesToken = contractBytesToken;
    this.contractBytesNft = contractBytesNft;
  }

  /**
   * Setup for other tests. Instantiates the accounts of all the contract owners deploys the
   * contracts and transfers and approves tokens for the bidders and the auction contract.
   */
  @ContractTest
  void setup() {
    // instantiate accounts
    ownerDoge = blockchain.newAccount(2);
    auctionOwner = blockchain.newAccount(4);
    bidder1 = blockchain.newAccount(5);
    bidder2 = blockchain.newAccount(6);
    bidder3 = blockchain.newAccount(7);
    byte[] nftUri = new byte[16];

    // deploy token contracts
    byte[] dogeInitRpc =
        Token.initialize("Doge Coin", "DOGE", (byte) 18, BigInteger.valueOf(DOGE_SUPPLY));
    doge = blockchain.deployContract(ownerDoge, contractBytesToken, dogeInitRpc);
    byte[] nftInitRpc = NftContract.initialize("Disinterested Monkey Boat Association", "DMBA", "");
    nft = blockchain.deployContract(auctionOwner, contractBytesNft, nftInitRpc);
    // transfer funds to the bidders and the NFT to the contract owner
    byte[] transferOne = Token.transfer(bidder1, BigInteger.valueOf(500));
    byte[] transferTwo = Token.transfer(bidder2, BigInteger.valueOf(1000));
    byte[] transferThree = Token.transfer(bidder3, BigInteger.valueOf(1500));
    byte[] transferFour = NftContract.mint(auctionOwner, nftId, nftUri);
    blockchain.sendAction(ownerDoge, doge, transferOne);
    blockchain.sendAction(ownerDoge, doge, transferTwo);
    blockchain.sendAction(ownerDoge, doge, transferThree);
    blockchain.sendAction(auctionOwner, nft, transferFour);

    // assert that the transfers were successful
    Token.TokenState dogeState = Token.TokenState.deserialize(blockchain.getContractState(doge));
    assertThat(dogeState.balances().get(bidder1)).isEqualTo(500);
    assertThat(dogeState.balances().get(bidder2)).isEqualTo(1000);
    assertThat(dogeState.balances().get(bidder3)).isEqualTo(1500);

    NftContract.NFTContractState nftState =
        NftContract.NFTContractState.deserialize(blockchain.getContractState(nft));
    assertThat(nftState.owners().get(nftId)).isEqualTo(auctionOwner);
    assertThat(nftState.name()).isEqualTo("Disinterested Monkey Boat Association");
    assertThat(nftState.symbol()).isEqualTo("DMBA");
    assertThat(nftState.tokenApprovals()).isEmpty();
    assertThat(nftState.operatorApprovals()).isEmpty();
    assertThat(nftState.uriTemplate()).isEmpty();
    assertThat(nftState.tokenUriDetails()).containsEntry(nftId, nftUri);
    assertThat(nftState.contractOwner()).isEqualTo(auctionOwner);

    // deploy the auction contract
    byte[] auctionInitRpc =
        NftAuction.initialize(nft, nftId, doge, BigInteger.valueOf(20), BigInteger.valueOf(5), 2);

    auction = blockchain.deployContract(auctionOwner, contractBytesAuction, auctionInitRpc);
    auctionContract = new NftAuction(getStateClient(), auction);

    nftState = NftContract.NFTContractState.deserialize(blockchain.getContractState(nft));

    assertThat(nftState.owners().get(nftId)).isEqualTo(auctionOwner);

    // approve auction contract to make transactions on behalf of owner
    byte[] approveRpc = NftContract.approve(auction, nftId);

    blockchain.sendAction(auctionOwner, nft, approveRpc);

    // approve auction contract to make transactions on behalf of bidders
    byte[] approveForAuctionBidderOneRpc = Token.approve(auction, BigInteger.valueOf(500));
    byte[] approveForAuctionBidderTwoRpc = Token.approve(auction, BigInteger.valueOf(1000));
    byte[] approveForAuctionBidderThreeRpc = Token.approve(auction, BigInteger.valueOf(1500));
    blockchain.sendAction(bidder1, doge, approveForAuctionBidderOneRpc);
    blockchain.sendAction(bidder2, doge, approveForAuctionBidderTwoRpc);
    blockchain.sendAction(bidder3, doge, approveForAuctionBidderThreeRpc);

    dogeState = Token.TokenState.deserialize(blockchain.getContractState(doge));
    assertThat(dogeState.allowed().get(bidder1).get(auction)).isEqualTo(BigInteger.valueOf(500));
    assertThat(dogeState.allowed().get(bidder2).get(auction)).isEqualTo(BigInteger.valueOf(1000));
    assertThat(dogeState.allowed().get(bidder3).get(auction)).isEqualTo(BigInteger.valueOf(1500));

    // start the auction
    byte[] startRpc = NftAuction.start();
    blockchain.sendAction(auctionOwner, auction, startRpc);

    nftState = NftContract.NFTContractState.deserialize(blockchain.getContractState(nft));

    assertThat(nftState.contractOwner()).isEqualTo(auctionOwner);

    NftAuction.NftAuctionContractState auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(BIDDING);
  }

  /**
   * If the owner did not approve the auction contract to transfer the NFT, starting the auction
   * fails .
   */
  @ContractTest
  void startWithoutApprove() {
    // instantiate accounts
    ownerDoge = blockchain.newAccount(2);
    auctionOwner = blockchain.newAccount(4);
    byte[] nftUri = new byte[16];

    // deploy token contracts
    byte[] dogeInitRpc =
        Token.initialize("Doge Coin", "DOGE", (byte) 18, BigInteger.valueOf(DOGE_SUPPLY));
    doge = blockchain.deployContract(ownerDoge, contractBytesToken, dogeInitRpc);

    byte[] nftInitRpc = NftContract.initialize("Disinterested Monkey Boat Association", "DMBA", "");
    nft = blockchain.deployContract(auctionOwner, contractBytesNft, nftInitRpc);

    // transfer the NFT to the contract owner
    byte[] mintRpc = NftContract.mint(auctionOwner, nftId, nftUri);
    blockchain.sendAction(auctionOwner, nft, mintRpc);

    // deploy the auction contract
    byte[] auctionInitRpc =
        NftAuction.initialize(nft, nftId, doge, BigInteger.valueOf(20), BigInteger.valueOf(5), 2);

    auction = blockchain.deployContract(auctionOwner, contractBytesAuction, auctionInitRpc);

    // start the auction, WITHOUT the NFT owner approving the auction contract.
    byte[] startRpc = NftAuction.start();

    // Send interaction to auction contract.
    TxExecution t1 = blockchain.sendActionAsync(auctionOwner, auction, startRpc);

    // Process interaction to auction contract.
    TxExecution t2 = t1.getContractInteraction();
    blockchain.executeEventAsync(t2);

    // Process the interaction between the auction and nft contract, which fails.
    TxExecution t3 = t2.getContractInteraction();
    blockchain.executeEventAsync(t3);

    // Transfer from fails, which should lead to an error in the callback.
    assertThat(t3.getFailureCause().getErrorMessage())
        .contains("MPC-721: transfer caller is not owner nor approved");

    // Process the system callback.
    TxExecution t4 = t3.getSystemCallback();
    blockchain.executeEventAsync(t4);
    // Process the final contract callback.
    blockchain.executeEvent(t4.getContractCallback());

    // Check that the final error is as expected
    assertThat(t4.getContractCallback().getFailureCause().getErrorMessage())
        .contains("Transfer event did not succeed for start");
  }

  /** The highest bid is registered as highest bid. */
  @ContractTest(previous = "setup")
  void makeBid() {
    NftAuction.NftAuctionContractState auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(BIDDING);

    byte[] bidForFifty = NftAuction.bid(BigInteger.valueOf(50));
    blockchain.sendAction(bidder1, auction, bidForFifty);

    auctionState = auctionContract.getState();

    assertThat(auctionState.highestBidder().bidder()).isEqualTo(bidder1);
    assertThat(auctionState.highestBidder().amount()).isEqualTo(BigInteger.valueOf(50));
  }

  /** A bid smaller than the auction's reserve price is registered in the Claim map immediately. */
  @ContractTest(previous = "setup")
  void tooSmallBid() {
    NftAuction.NftAuctionContractState auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(BIDDING);

    byte[] bidForOne = NftAuction.bid(BigInteger.valueOf(1));
    // Assert that the bid is lower than the reserve price
    assertThat(auctionState.reservePrice().intValue() > 1).isTrue();

    blockchain.sendAction(bidder1, auction, bidForOne);
    auctionState = auctionContract.getState();
    // Assert that the bid can be claimed immediately.
    assertThat(auctionState.claimMap().getNextN(null, 100).size()).isEqualTo(1);
    assertThat(auctionState.claimMap().get(bidder1).tokensForBidding()).isEqualTo(1);
  }

  /** When the auction owner calls cancel in the BIDDING phase, the auction is cancelled. */
  @ContractTest(previous = "setup")
  void cancelAuction() {
    byte[] cancelRpc = NftAuction.cancel();
    blockchain.sendAction(auctionOwner, auction, cancelRpc);
    NftAuction.NftAuctionContractState auctionState =
        NftAuction.NftAuctionContractState.deserialize(blockchain.getContractState(auction));
    byte cancelled = 3;
    assertThat(auctionState.status()).isEqualTo(cancelled);
  }

  /** The winner can claim the auction prize and the auction owner can claim the highest bid. */
  @ContractTest(previous = "setup")
  void bidAndClaim() {
    // make bid
    byte[] bidForThirty = NftAuction.bid(BigInteger.valueOf(30));
    blockchain.sendAction(bidder3, auction, bidForThirty);
    NftAuction.NftAuctionContractState auctionState;

    // pass time
    blockchain.waitForBlockProductionTime(auctionEndTime);

    // execute auction
    byte[] executeRpc = NftAuction.execute();
    blockchain.sendAction(auctionOwner, auction, executeRpc);

    auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(ENDED); // status should be ENDED
    assertThat(auctionState.claimMap().getNextN(null, 100).size())
        .isEqualTo(2); // one claim for bidder and one for seller
    assertThat(auctionState.claimMap().get(bidder3).nftForSale())
        .isEqualTo(nft); // bidder's claim should be equal to the nft for sale
    assertThat(auctionState.claimMap().get(auctionOwner).tokensForBidding())
        .isEqualTo(BigInteger.valueOf(30)); // seller's claim should be equal to bid

    // make claims
    byte[] claimRpc = NftAuction.claim();
    blockchain.sendAction(bidder3, auction, claimRpc);
    blockchain.sendAction(auctionOwner, auction, claimRpc);

    auctionState = auctionContract.getState();

    assertThat(auctionState.claimMap().getNextN(null, 100).size())
        .isEqualTo(2); // size of claim map should remain the same
    assertThat(auctionState.claimMap().get(bidder3).nftForSale())
        .isEqualTo(null); // tokens should now be claimed
    assertThat(auctionState.claimMap().get(auctionOwner).tokensForBidding())
        .isEqualTo(BigInteger.valueOf(0)); // tokens should now be claimed
  }

  /**
   * At the end of an auction, the highest bidder can claim the prize, the other bidders can claim
   * their bids, and the owner can claim the highest bid.
   */
  @ContractTest(previous = "setup")
  void biddersAndWinnersCanClaimBidsAndPrize() {
    NftAuction.NftAuctionContractState auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(BIDDING);

    // make bids
    byte[] bidForTwenty = NftAuction.bid(BigInteger.valueOf(20));
    blockchain.sendAction(bidder1, auction, bidForTwenty);
    byte[] bidForThirty = NftAuction.bid(BigInteger.valueOf(30));
    blockchain.sendAction(bidder2, auction, bidForThirty);

    // pass time
    blockchain.waitForBlockProductionTime(auctionEndTime);

    // execute auction
    byte[] executeRpc = NftAuction.execute();
    blockchain.sendAction(auctionOwner, auction, executeRpc);
    auctionState = auctionContract.getState();
    // status should be ENDED
    assertThat(auctionState.status()).isEqualTo(ENDED);
    assertThat(auctionState.claimMap().getNextN(null, 100).size()).isEqualTo(3);
    // bidder who didn't win should be able to claim their bid
    assertThat(auctionState.claimMap().get(bidder1).tokensForBidding())
        .isEqualTo(BigInteger.valueOf(20));
    // the highest bidder (auction winner) should be able to claim the prize of the auction
    assertThat(auctionState.claimMap().get(bidder2).nftForSale()).isEqualTo(nft);
    // the auction owner should be able to claim the highest bid
    assertThat(auctionState.claimMap().get(auctionOwner).tokensForBidding())
        .isEqualTo(BigInteger.valueOf(30));

    byte[] claimRpc = NftAuction.claim();
    blockchain.sendAction(bidder1, auction, claimRpc);
    blockchain.sendAction(bidder2, auction, claimRpc);
    blockchain.sendAction(auctionOwner, auction, claimRpc);

    auctionState = auctionContract.getState();

    // Assert that the claims have been claimed
    assertThat(auctionState.claimMap().get(auctionOwner).tokensForBidding())
        .isEqualTo(BigInteger.valueOf(0));
    assertThat(auctionState.claimMap().get(bidder2).tokensForBidding())
        .isEqualTo(BigInteger.valueOf(0));
    assertThat(auctionState.claimMap().get(bidder1).tokensForBidding())
        .isEqualTo(BigInteger.valueOf(0));
  }

  /**
   * If a bidder bids an amount which is lower than the highest bid, the bidder is able to claim it
   * afterward.
   */
  @ContractTest(previous = "setup")
  void bidsLowerThanHighestBid() {
    NftAuction.NftAuctionContractState auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(BIDDING);

    byte[] bidForTwenty = NftAuction.bid(BigInteger.valueOf(20));
    blockchain.sendAction(bidder1, auction, bidForTwenty);

    // bid a lower amount than the highest bid
    byte[] bidForTen = NftAuction.bid(BigInteger.valueOf(10));
    blockchain.sendAction(bidder2, auction, bidForTen);

    // pass time
    blockchain.waitForBlockProductionTime(auctionEndTime);

    // execute auction
    byte[] executeRpc = NftAuction.execute();
    blockchain.sendAction(auctionOwner, auction, executeRpc);
    auctionState = auctionContract.getState();

    assertThat(auctionState.claimMap().getNextN(null, 100).size()).isEqualTo(3);
    assertThat(auctionState.claimMap().get(bidder2).tokensForBidding())
        .isEqualTo(BigInteger.valueOf(10));
    assertThat(auctionState.claimMap().get(bidder1).nftForSale()).isEqualTo(nft);
  }

  /** The first of two bids with the same amount bid is registered as the highest. */
  @ContractTest(previous = "setup")
  void bidTheHighestBid() {
    NftAuction.NftAuctionContractState auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(BIDDING);

    byte[] bidder1ForThirty = NftAuction.bid(BigInteger.valueOf(30));
    blockchain.sendAction(bidder1, auction, bidder1ForThirty);

    // Another bidder bids the current highest bid
    byte[] bidder2ForThirty = NftAuction.bid(BigInteger.valueOf(30));
    blockchain.sendAction(bidder2, auction, bidder2ForThirty);

    // pass time
    blockchain.waitForBlockProductionTime(auctionEndTime);

    // execute auction
    byte[] executeRpc = NftAuction.execute();
    blockchain.sendAction(auctionOwner, auction, executeRpc);
    auctionState = auctionContract.getState();

    assertThat(auctionState.claimMap().getNextN(null, 100).size()).isEqualTo(3);
    // The bidder who was the second to bid the highest bid should lose the auction and be able to
    // claim their bid
    assertThat(auctionState.claimMap().get(bidder2).tokensForBidding())
        .isEqualTo(BigInteger.valueOf(30));
    // The first one to bid the highest bid should be able to claim the auction prize.
    assertThat(auctionState.claimMap().get(bidder1).nftForSale()).isEqualTo(nft);
  }

  /**
   * A bidder bids two different bids. The highest is registered as the highest bid and the lowest
   * is added to the claim map.
   */
  @ContractTest(previous = "setup")
  void bidTwoDifferentBids() {
    NftAuction.NftAuctionContractState auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(BIDDING);

    byte[] highBid = NftAuction.bid(BigInteger.valueOf(30));
    blockchain.sendAction(bidder1, auction, highBid);

    // Another bidder bids the current highest bid
    byte[] lowBid = NftAuction.bid(BigInteger.valueOf(20));
    blockchain.sendAction(bidder1, auction, lowBid);

    // pass time
    blockchain.waitForBlockProductionTime(auctionEndTime);

    // execute auction
    byte[] executeRpc = NftAuction.execute();
    blockchain.sendAction(auctionOwner, auction, executeRpc);
    auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(ENDED);
    assertThat(auctionState.claimMap().getNextN(null, 100).size()).isEqualTo(2);
    // The bidder should be able to claim the lower one of their bids.
    assertThat(auctionState.claimMap().get(bidder1).tokensForBidding())
        .isEqualTo(BigInteger.valueOf(20));
    // The bidder should be able to claim the auction prize.
    assertThat(auctionState.claimMap().get(bidder1).nftForSale()).isEqualTo(nft);
    // The auction owner should be able to claim the highest of the two bids.
    assertThat(auctionState.claimMap().get(auctionOwner).tokensForBidding())
        .isEqualTo(BigInteger.valueOf(30));
  }

  /** Non-owner cannot start the auction. */
  @ContractTest(previous = "setup")
  void startCalledByNonOwner() {
    byte[] startRpc = NftAuction.start();
    assertThatThrownBy(() -> blockchain.sendAction(blockchain.newAccount(15), auction, startRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Start can only be called by the creator of the contract");
  }

  /** Non-owner cannot cancel an auction. */
  @ContractTest(previous = "setup")
  void cancelAuctionNonOwner() {
    byte[] cancelRpc = NftAuction.cancel();
    assertThatThrownBy(() -> blockchain.sendAction(blockchain.newAccount(25), auction, cancelRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Only the contract owner can cancel the auction");
  }

  /** Cancelling the auction when the phase is not BIDDING is not possible. */
  @ContractTest(previous = "setup")
  void cancelAuctionStatusNotBidding() {
    byte[] cancelRpc = NftAuction.cancel();
    blockchain.sendAction(auctionOwner, auction, cancelRpc);
    assertThatThrownBy(() -> blockchain.sendAction(auctionOwner, auction, cancelRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Tried to cancel the auction when the status isn't Bidding");
  }

  /** The auction cannot be executed before the deadline. */
  @ContractTest(previous = "setup")
  void executeBeforeEndTime() {
    byte[] executeRpc = NftAuction.execute();
    assertThatThrownBy(() -> blockchain.sendAction(auctionOwner, auction, executeRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Tried to execute the auction before auction end block time");
  }

  /** User who has not bid does not appear on the claim map, and cannot claim anything. */
  @ContractTest(previous = "setup")
  void claimFromUserWithNoClaim() {
    BlockchainAddress account = blockchain.newAccount(30);
    byte[] claimRpc = NftAuction.claim();
    blockchain.sendAction(account, auction, claimRpc);
    var auctionState = auctionContract.getState();
    assertThat(auctionState.claimMap().getNextN(null, 100).size()).isEqualTo(0);
  }

  /** The auction owner cannot claim a payment before the auction is executed. */
  @ContractTest(previous = "setup")
  void ownerClaimBeforeAuctionEnded() {
    NftAuction.NftAuctionContractState auctionState;
    byte[] bidTwentyRpc = NftAuction.bid(BigInteger.valueOf(20));
    byte[] bidThirtyRpc = NftAuction.bid(BigInteger.valueOf(30));

    blockchain.sendAction(bidder2, auction, bidTwentyRpc);
    blockchain.sendAction(bidder3, auction, bidThirtyRpc);
    auctionState = auctionContract.getState();
    assertThat(auctionState.claimMap().get(auctionOwner).nftForSale()).isEqualTo(null);

    byte[] claimRpc = NftAuction.claim();
    blockchain.sendAction(auctionOwner, auction, claimRpc);
    auctionState = auctionContract.getState();
    assertThat(auctionState.claimMap().get(auctionOwner).nftForSale()).isEqualTo(null);
    assertThat(auctionState.highestBidder().bidder()).isEqualTo(bidder3);
  }

  /** Execute an auction before it was started is not possible. */
  @ContractTest(previous = "setup")
  void executeWhenStatusNotBidding() {
    byte[] bidRpc = NftAuction.bid(BigInteger.TEN);
    blockchain.sendAction(bidder3, auction, bidRpc);

    blockchain.waitForBlockProductionTime(auctionEndTime);
    byte[] executeRpc = NftAuction.execute();
    NftAuction.NftAuctionContractState auctionState;

    blockchain.sendAction(
        auctionOwner, auction, executeRpc); // execute first to change status from Bidding
    auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(ENDED);
    assertThatThrownBy(() -> blockchain.sendAction(auctionOwner, auction, executeRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Tried to execute the auction when the status isn't Bidding");
  }

  /** An auction that has already ended, cannot be cancelled. */
  @ContractTest(previous = "setup")
  void cancelAuctionAfterEnd() {
    blockchain.waitForBlockProductionTime(auctionEndTime);
    NftAuction.NftAuctionContractState auctionState =
        NftAuction.NftAuctionContractState.deserialize(blockchain.getContractState(auction));
    assertThat(auctionState.status()).isEqualTo(BIDDING);

    byte[] cancelRpc = NftAuction.cancel();
    assertThatThrownBy(() -> blockchain.sendAction(auctionOwner, auction, cancelRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Tried to cancel the auction after auction end block time");
  }

  /** The highest bidder cannot claim the auctioned NFT before the auction is executed. */
  @ContractTest(previous = "setup")
  void highestBidderClaimBeforeAuctionEnded() {
    NftAuction.NftAuctionContractState auctionState;
    byte[] bidTwentyRpc = NftAuction.bid(BigInteger.valueOf(20));
    byte[] bidThirtyRpc = NftAuction.bid(BigInteger.valueOf(30));

    blockchain.sendAction(bidder2, auction, bidTwentyRpc);
    blockchain.sendAction(bidder3, auction, bidThirtyRpc);
    auctionState = auctionContract.getState();
    assertThat(auctionState.claimMap().get(bidder3)).isNull();

    byte[] claimRpc = NftAuction.claim();
    blockchain.sendAction(bidder3, auction, claimRpc);
    auctionState = auctionContract.getState();
    assertThat(auctionState.claimMap().get(bidder3)).isNull();
    assertThat(auctionState.highestBidder().bidder()).isEqualTo(bidder3);
  }

  /** An auction cannot be deployed with a non-public token Address for bidding. */
  @ContractTest(previous = "setup")
  void nonPublicBidToken() {
    byte[] auctionInitRpcBidIllegal =
        NftAuction.initialize(
            nft,
            nftId,
            blockchain.newAccount(12),
            BigInteger.valueOf(20),
            BigInteger.valueOf(5),
            2);

    assertThatThrownBy(
            () ->
                blockchain.deployContract(
                    auctionOwner, contractBytesAuction, auctionInitRpcBidIllegal))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Tried to create a contract buying a non publicContract token");
  }

  /** An auction cannot be selling a non-public NFT. */
  @ContractTest(previous = "setup")
  void nonPublicSaleAuction() {
    byte[] auctionInitRpcSaleIllegal =
        NftAuction.initialize(
            blockchain.newAccount(10),
            BigInteger.valueOf(1),
            doge,
            BigInteger.valueOf(20),
            BigInteger.valueOf(5),
            2);

    assertThatThrownBy(
            () ->
                blockchain.deployContract(
                    auctionOwner, contractBytesAuction, auctionInitRpcSaleIllegal))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Tried to create a contract selling a non publicContract NFT");
  }

  /** The start of an auction is not allowed unless it is in the CREATION phase. */
  @ContractTest(previous = "setup")
  void startCalledNotCreationStatus() {
    NftAuction.NftAuctionContractState auctionState = auctionContract.getState();
    byte creation = 0;
    assertThat(auctionState.status()).isNotEqualTo(creation);
    byte[] startRpc = NftAuction.start();
    assertThatThrownBy(() -> blockchain.sendAction(auctionOwner, auction, startRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Start should only be called while setting up the contract");
  }

  /**
   * A bid is not registered before the tokens are approved for the auction contract to transfer.
   */
  @ContractTest(previous = "setup")
  void bidTokenNotApproved() {
    NftAuction.NftAuctionContractState auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(BIDDING);
    byte[] transferThousand = Token.transfer(bidder2, BigInteger.valueOf(1000));
    BlockchainAddress bidderNotApproved = blockchain.newAccount(17);
    blockchain.sendAction(ownerDoge, doge, transferThousand); // transfer funds to bidder

    byte[] bidForTen = NftAuction.bid(BigInteger.valueOf(10));
    // bidder tries to bid before being approved to transfer the token
    assertThatThrownBy(() -> blockchain.sendAction(bidderNotApproved, auction, bidForTen))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient DOGE allowance for transfer_from! Allowed 0, but trying to transfer 10");
  }

  /**
   * A bid higher than the amount approved for the auction contract to transfer is not registered.
   */
  @ContractTest(previous = "setup")
  void bidTokenNotEnoughFunds() {
    NftAuction.NftAuctionContractState auctionState = auctionContract.getState();
    assertThat(auctionState.status()).isEqualTo(BIDDING);

    byte[] bidForTenThousand = NftAuction.bid(BigInteger.valueOf(10_000));
    assertThatThrownBy(() -> blockchain.sendAction(bidder1, auction, bidForTenThousand))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient DOGE allowance for transfer_from! Allowed 500, but trying to transfer"
                + " 10000");
  }
}
