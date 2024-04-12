package defi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.Mocca;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.testenvironment.TxExecution;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test suite of the MOCCA Contract. The MOCCA contract */
public final class MoccaTest extends JunitContractTest {

  private static final ContractBytes MOCCA_CONTRACT =
      ContractBytes.fromPaths(
          Path.of("../rust/target/wasm32-unknown-unknown/release/mocca.wasm"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/mocca.abi"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/mocca_runner"));

  private static final ContractBytes TOKEN_CONTRACT =
      ContractBytes.fromPaths(
          Path.of("../rust/target/wasm32-unknown-unknown/release/token.wasm"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/token.abi"));

  private static final BigInteger TOTAL_SUPPLY =
      BigInteger.valueOf(1200).multiply(BigInteger.TEN.pow(18));
  private BlockchainAddress token;
  private BlockchainAddress mocca;

  private BlockchainAddress tokenOwner;
  private BlockchainAddress escrowUser;
  private BlockchainAddress receivingUser;
  private BlockchainAddress voter1;
  private BlockchainAddress voter2;
  private BlockchainAddress voter3;
  private BlockchainAddress voter4;

  @ContractTest
  void setup() {
    tokenOwner = blockchain.newAccount(1);
    escrowUser = blockchain.newAccount(2);
    receivingUser = blockchain.newAccount(3);
    voter1 = blockchain.newAccount(4);
    voter2 = blockchain.newAccount(5);
    voter3 = blockchain.newAccount(6);
    voter4 = blockchain.newAccount(7);

    final byte[] initRpcUsdCoin = Token.initialize("USD Coin", "USDC", (byte) 18, TOTAL_SUPPLY);
    token = blockchain.deployContract(tokenOwner, TOKEN_CONTRACT, initRpcUsdCoin);
  }

  // Feature: Deployment

  /**
   * Deploy the Token contract, that the MOCCA contract can escrow tokens from, along with the MOCCA
   * contract with the initial criteria.
   */
  @ContractTest(previous = "setup")
  void deployMocca() {

    List<Mocca.Voter> voters =
        List.of(
            new Mocca.Voter(voter1, 5),
            new Mocca.Voter(voter2, 10),
            new Mocca.Voter(voter3, 15),
            new Mocca.Voter(voter4, 30));
    Mocca.Criteria criteria = new Mocca.Criteria(voters, 30);
    final byte[] initMocca = Mocca.initialize(criteria, token);

    mocca = blockchain.deployContract(voter1, MOCCA_CONTRACT, initMocca);

    transfer(token, tokenOwner, escrowUser, BigInteger.valueOf(1_000_000L));
    approve(tokenOwner, token, mocca, BigInteger.valueOf(10_000_000));
    approve(escrowUser, token, mocca, BigInteger.valueOf(1_000_000));

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.criteria().voters().size()).isEqualTo(4);
    assertThat(moccaState.amountOfTokens()).isEqualTo(BigInteger.ZERO);
    assertThat(moccaState.proposal().size()).isEqualTo(0);
    assertThat(totalVoteWeight(moccaState)).isEqualTo(60);
  }

  /** Deploying the MOCCA contract with an empty list of voters is not allowed. */
  @ContractTest(previous = "setup")
  void deployWithEmptyListOfVoters() {

    List<Mocca.Voter> voters = List.of();
    Mocca.Criteria criteria = new Mocca.Criteria(voters, 0);
    final byte[] initMocca = Mocca.initialize(criteria, token);

    assertThatThrownBy(() -> blockchain.deployContract(voter1, MOCCA_CONTRACT, initMocca))
        .hasMessageContaining("Cannot use a criteria without assigned voters.");
  }

  /**
   * Deploying the MOCCA contract with a threshold larger than the total weight of votes, is not
   * allowed.
   */
  @ContractTest(previous = "setup")
  void deployWithThresholdLargerThanTotalVoteWeight() {

    List<Mocca.Voter> voters =
        List.of(
            new Mocca.Voter(voter1, 5),
            new Mocca.Voter(voter2, 10),
            new Mocca.Voter(voter3, 15),
            new Mocca.Voter(voter4, 30));

    Mocca.Criteria criteria = new Mocca.Criteria(voters, 61);
    final byte[] initMocca = Mocca.initialize(criteria, token);

    assertThatThrownBy(() -> blockchain.deployContract(voter1, MOCCA_CONTRACT, initMocca))
        .hasMessageContaining("Threshold cannot be larger then the total weight of votes.");
  }

  // Feature: Escrow tokens with MOCCA

  /** A user can escrow an amount of tokens to the Mocca contract. */
  @ContractTest(previous = "deployMocca")
  void escrowTokens() {

    BigInteger amount = BigInteger.valueOf(10_000L);
    byte[] escrowTokens = Mocca.escrow(amount);
    blockchain.sendAction(escrowUser, mocca, escrowTokens);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.amountOfTokens()).isEqualTo(amount);
  }

  /** A user cannot escrow more tokens than they own. */
  @ContractTest(previous = "deployMocca")
  void escrowMoreTokensThanOwned() {
    BigInteger amount = BigInteger.valueOf(2_000_000L);
    byte[] escrowTokens = Mocca.escrow(amount);

    TxExecution escrowAction = blockchain.sendActionAsync(escrowUser, mocca, escrowTokens);
    TxExecution escrowEvent = blockchain.executeEventAsync(escrowAction.getContractInteraction());
    TxExecution transferEvent = blockchain.executeEventAsync(escrowEvent.getContractInteraction());

    assertThat(transferEvent.getFailureCause().getErrorMessage())
        .contains("Insufficient allowance for transfer_from: 1000000/2000000");

    TxExecution systemCallback = blockchain.executeEventAsync(transferEvent.getSystemCallback());
    TxExecution callbackEscrow = blockchain.executeEvent(systemCallback.getContractCallback());

    assertThat(callbackEscrow.getFailureCause().getErrorMessage())
        .contains(
            "Could not escrow 2000000 tokens, from 00B2E734B5D8DA089318D0D2B076C19F59C450855A.");

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));
    assertThat(moccaState.amountOfTokens()).isEqualTo(BigInteger.ZERO);
  }

  // Feature: Transfer proposal.

  /** A user can propose a transfer of funds. */
  @ContractTest(previous = "deployMocca")
  void proposeTransfer() {

    byte[] proposeTransfer =
        Mocca.propose(new Mocca.ProposalType.Transfer(BigInteger.valueOf(1000L), receivingUser));

    blockchain.sendAction(escrowUser, mocca, proposeTransfer);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.proposal().size()).isEqualTo(1);
    assertThat(moccaState.proposal().get(0).proposalType())
        .isInstanceOf(Mocca.ProposalType.Transfer.class);
  }

  /**
   * A transfer proposal where the weight of the "Yes" votes are larger than the threshold is
   * executed, and the funds are sent.
   */
  @ContractTest(previous = "deployMocca")
  void yesVoteWeightEnoughForTransferExecute() {

    setupTransferProposal();

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVote);
    blockchain.sendAction(voter2, mocca, yesVote);
    blockchain.sendAction(voter3, mocca, yesVote);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));
    Token.TokenState tokenState = Token.TokenState.deserialize(blockchain.getContractState(token));

    Mocca.Proposal proposal = moccaState.proposal().get(0);

    assertThat(proposal.votes().size()).isEqualTo(3);
    assertThat(proposal.result()).isNull();
    assertThat(tokenState.balances().get(receivingUser)).isNull();

    byte[] executeProposal = Mocca.execute(0);
    blockchain.sendAction(voter4, mocca, executeProposal);

    moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));
    proposal = moccaState.proposal().get(0);
    assertThat(proposal.result()).isInstanceOf(Mocca.VoteResult.Approved.class);

    tokenState = Token.TokenState.deserialize(blockchain.getContractState(token));
    assertThat(tokenState.balances().get(receivingUser)).isEqualTo(1000);
  }

  /**
   * A transfer proposal where the weight of the "No" votes are larger than the total vote weight -
   * threshold, is denied, and the transfer is not executed.
   */
  @ContractTest(previous = "deployMocca")
  void noVoteWeightLargerEnoughForDeniedTransfer() {

    setupTransferProposal();
    Mocca.MoccaState moccaState;

    byte[] noVote = Mocca.vote(0, new Mocca.Vote.No());
    blockchain.sendAction(voter1, mocca, noVote);
    blockchain.sendAction(voter4, mocca, noVote);

    moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));
    Token.TokenState tokenState = Token.TokenState.deserialize(blockchain.getContractState(token));

    Mocca.Proposal proposal = moccaState.proposal().get(0);

    assertThat(proposal.votes().size()).isEqualTo(2);
    assertThat(proposal.result()).isNull();
    assertThat(tokenState.balances().get(receivingUser)).isNull();

    byte[] executeProposal = Mocca.execute(0);

    blockchain.sendAction(voter4, mocca, executeProposal);

    moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));
    tokenState = Token.TokenState.deserialize(blockchain.getContractState(token));
    proposal = moccaState.proposal().get(0);

    assertThat(proposal.result()).isInstanceOf(Mocca.VoteResult.Denied.class);
    assertThat(tokenState.balances().get(receivingUser)).isNull();
    assertThat(moccaState.amountOfTokens()).isEqualTo(BigInteger.valueOf(10_000L));
  }

  // Setup a valid transfer proposal
  private void setupTransferProposal() {
    BigInteger amount = BigInteger.valueOf(10_000L);
    byte[] escrowTokens = Mocca.escrow(amount);
    blockchain.sendAction(escrowUser, mocca, escrowTokens);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));
    assertThat(moccaState.amountOfTokens()).isEqualTo(BigInteger.valueOf(10_000L));

    byte[] proposeTransfer =
        Mocca.propose(new Mocca.ProposalType.Transfer(BigInteger.valueOf(1000L), receivingUser));

    blockchain.sendAction(escrowUser, mocca, proposeTransfer);
  }

  // Feature: New criteria proposal.

  /** A user can propose a new criteria to use. */
  @ContractTest(previous = "deployMocca")
  void proposeNewCriteria() {

    List<Mocca.Voter> voterWeights =
        List.of(
            new Mocca.Voter(voter1, 5), new Mocca.Voter(voter2, 6), new Mocca.Voter(voter3, 10));
    Mocca.Criteria newCriteria = new Mocca.Criteria(voterWeights, 11);
    byte[] proposeNewCriteria = Mocca.propose(new Mocca.ProposalType.NewCriteria(newCriteria));

    blockchain.sendAction(escrowUser, mocca, proposeNewCriteria);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.proposal().size()).isEqualTo(1);
    assertThat(moccaState.proposal().get(0).proposalType())
        .isInstanceOf(Mocca.ProposalType.NewCriteria.class);
    assertThat(moccaState.proposal().get(0).result()).isNull();
  }

  /**
   * A new criteria proposal where the weight of the "Yes" votes are larger than the threshold is
   * executed successfully.
   */
  @ContractTest(previous = "deployMocca")
  void yesVoteWeightForNewCriteriaLargerThanThreshold() {

    List<Mocca.Voter> voterWeights =
        List.of(
            new Mocca.Voter(voter1, 5), new Mocca.Voter(voter2, 6), new Mocca.Voter(voter3, 10));
    Mocca.Criteria newCriteria = new Mocca.Criteria(voterWeights, 11);
    byte[] proposeNewCriteria = Mocca.propose(new Mocca.ProposalType.NewCriteria(newCriteria));

    blockchain.sendAction(escrowUser, mocca, proposeNewCriteria);

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVote);
    blockchain.sendAction(voter2, mocca, yesVote);
    blockchain.sendAction(voter3, mocca, yesVote);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    Mocca.Proposal proposal = moccaState.proposal().get(0);

    assertThat(proposal.result()).isNull();
    assertThat(proposal.votes().size()).isEqualTo(3);

    byte[] executeProposal = Mocca.execute(0);
    blockchain.sendAction(voter4, mocca, executeProposal);

    moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));
    proposal = moccaState.proposal().get(0);

    assertThat(moccaState.criteria()).isEqualTo(newCriteria);
    assertThat(proposal.result()).isInstanceOf(Mocca.VoteResult.Approved.class);
  }

  /**
   * A new criteria proposal where the weight of the "No" votes are larger than the total vote
   * weight - threshold, is denied, and the criteria is not updated.
   */
  @ContractTest(previous = "deployMocca")
  void noVoteWeightForNewCriteriaEnoughForDenied() {

    List<Mocca.Voter> voterWeights =
        List.of(
            new Mocca.Voter(voter1, 5), new Mocca.Voter(voter2, 6), new Mocca.Voter(voter3, 10));
    Mocca.Criteria newCriteria = new Mocca.Criteria(voterWeights, 11);
    byte[] proposeNewCriteria = Mocca.propose(new Mocca.ProposalType.NewCriteria(newCriteria));

    blockchain.sendAction(escrowUser, mocca, proposeNewCriteria);

    byte[] noVote = Mocca.vote(0, new Mocca.Vote.No());
    blockchain.sendAction(voter2, mocca, noVote);
    blockchain.sendAction(voter3, mocca, noVote);
    blockchain.sendAction(voter4, mocca, noVote);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));
    final Mocca.Criteria originCriteria = moccaState.criteria();

    Mocca.Proposal proposal = moccaState.proposal().get(0);

    assertThat(proposal.votes().size()).isEqualTo(3);
    assertThat(proposal.result()).isNull();

    byte[] executeProposal = Mocca.execute(0);
    blockchain.sendAction(voter4, mocca, executeProposal);

    moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));
    proposal = moccaState.proposal().get(0);

    assertThat(proposal.result()).isInstanceOf(Mocca.VoteResult.Denied.class);
    assertThat(moccaState.criteria()).isNotEqualTo(newCriteria);
    assertThat(moccaState.criteria()).isEqualTo(originCriteria);
  }

  /** A new criteria proposal, where the list of voters is empty, is not allowed. */
  @ContractTest(previous = "deployMocca")
  void newCriteriaWithEmptyListOfVoters() {
    List<Mocca.Voter> voterWeights = List.of();
    Mocca.Criteria newCriteria = new Mocca.Criteria(voterWeights, 11);
    byte[] proposeNewCriteria = Mocca.propose(new Mocca.ProposalType.NewCriteria(newCriteria));

    assertThatThrownBy(() -> blockchain.sendAction(escrowUser, mocca, proposeNewCriteria))
        .hasMessageContaining("Cannot use a criteria without assigned voters.");
  }

  /**
   * A new criteria proposal, where the threshold is larger than the total weight of votes, is not
   * allowed.
   */
  @ContractTest(previous = "deployMocca")
  void newCriteriaWithThresholdLargerThanTotalWeight() {
    List<Mocca.Voter> voterWeights =
        List.of(
            new Mocca.Voter(voter1, 5), new Mocca.Voter(voter2, 6), new Mocca.Voter(voter3, 10));
    Mocca.Criteria newCriteria = new Mocca.Criteria(voterWeights, 22);
    byte[] proposeNewCriteria = Mocca.propose(new Mocca.ProposalType.NewCriteria(newCriteria));

    assertThatThrownBy(() -> blockchain.sendAction(escrowUser, mocca, proposeNewCriteria))
        .hasMessageContaining("Threshold cannot be larger then the total weight of votes.");
  }

  // Feature: Voting

  /** A voter can vote "Yes" to a new criteria proposal. */
  @ContractTest(previous = "proposeNewCriteria")
  void voteYesToNewCriteria() {

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVote);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    Mocca.Proposal proposal = moccaState.proposal().get(0);
    assertThat(proposal.votes().size()).isEqualTo(1);
  }

  /** A voter can vote "Yes" to a transfer proposal. */
  @ContractTest(previous = "proposeTransfer")
  void voteYesToTransferProposal() {

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVote);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    Mocca.Proposal proposal = moccaState.proposal().get(0);
    assertThat(proposal.votes().size()).isEqualTo(1);
  }

  /** A voter can vote "Yes" to a new criteria proposal. */
  @ContractTest(previous = "deployMocca")
  void voteOnNonExistingProposal() {

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    assertThatThrownBy(() -> blockchain.sendAction(voter1, mocca, yesVote))
        .hasMessageContaining("No proposal with id 0");
  }

  /** A user, that is not registered in the criteria as a voter, is not allowed to vote. */
  @ContractTest(previous = "proposeTransfer")
  void userNotRegisteredAsVoterCannotVote() {

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    assertThatThrownBy(() -> blockchain.sendAction(escrowUser, mocca, yesVote))
        .hasMessageContaining("Only addresses registered in the criteria as voters can vote.");
  }

  /** A voter can change its vote if the proposal has not been executed. */
  @ContractTest(previous = "proposeTransfer")
  void voterCanChangeVote() {

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVote);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.proposal().size()).isEqualTo(1);
    assertThat(moccaState.proposal().get(0).votes().get(voter1).discriminant())
        .isEqualTo(Mocca.VoteD.YES);

    byte[] noVote = Mocca.vote(0, new Mocca.Vote.No());
    blockchain.sendAction(voter1, mocca, noVote);

    moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.proposal().size()).isEqualTo(1);
    assertThat(moccaState.proposal().get(0).votes().get(voter1).discriminant())
        .isEqualTo(Mocca.VoteD.NO);
  }

  /** A voter cannot vote on executed proposals. */
  @ContractTest(previous = "proposeNewCriteria")
  void voterCannotVoteOnProposalWithResult() {

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVote);
    blockchain.sendAction(voter2, mocca, yesVote);
    blockchain.sendAction(voter3, mocca, yesVote);

    byte[] executeProposal = Mocca.execute(0);
    blockchain.sendAction(voter4, mocca, executeProposal);

    byte[] noVote = Mocca.vote(0, new Mocca.Vote.No());
    assertThatThrownBy(() -> blockchain.sendAction(voter1, mocca, noVote))
        .hasMessageContaining("Cannot vote on proposal that has been executed.");
  }

  /** A voting with the weight of "Yes" votes equal to the threshold is approved. */
  @ContractTest(previous = "proposeNewCriteria")
  void weightOfYesVotesIsEqualToThreshold() {

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVote);
    blockchain.sendAction(voter2, mocca, yesVote);
    blockchain.sendAction(voter3, mocca, yesVote);

    byte[] executeProposal = Mocca.execute(0);
    blockchain.sendAction(voter4, mocca, executeProposal);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.proposal().get(0).result()).isInstanceOf(Mocca.VoteResult.Approved.class);
  }

  /** A voting with the weight of "No" votes equal to the threshold is inconclusive. */
  @ContractTest(previous = "proposeTransfer")
  void weightOfNoVotesIsEqualToThreshold() {

    byte[] noVote = Mocca.vote(0, new Mocca.Vote.No());
    blockchain.sendAction(voter1, mocca, noVote);
    blockchain.sendAction(voter2, mocca, noVote);
    blockchain.sendAction(voter3, mocca, noVote);

    byte[] executeProposal = Mocca.execute(0);
    assertThatThrownBy(() -> blockchain.sendAction(voter4, mocca, executeProposal))
        .hasMessageContaining("The voting was not conclusive.");

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.proposal().get(0).result()).isNull();
  }

  /** A voting with the weight of "No" votes larger than the threshold is Denied. */
  @ContractTest(previous = "proposeTransfer")
  void weightOfNoVotesLargerThanThreshold() {

    byte[] noVote = Mocca.vote(0, new Mocca.Vote.No());
    blockchain.sendAction(voter2, mocca, noVote);
    blockchain.sendAction(voter3, mocca, noVote);
    blockchain.sendAction(voter4, mocca, noVote);

    byte[] executeProposal = Mocca.execute(0);
    blockchain.sendAction(voter4, mocca, executeProposal);

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.proposal().get(0).result()).isInstanceOf(Mocca.VoteResult.Denied.class);
  }

  /** Votes from a prior voter list does not count, when a new criteria is instated. */
  @ContractTest(previous = "deployMocca")
  void oldVotesDoesNotCountWhenNewCriteriaIsSet() {
    List<Mocca.Voter> voterWeights =
        List.of(
            new Mocca.Voter(voter1, 5), new Mocca.Voter(voter2, 6), new Mocca.Voter(voter3, 10));
    Mocca.Criteria newCriteria = new Mocca.Criteria(voterWeights, 11);
    byte[] proposeNewCriteria = Mocca.propose(new Mocca.ProposalType.NewCriteria(newCriteria));

    blockchain.sendAction(escrowUser, mocca, proposeNewCriteria);

    byte[] proposeTransfer =
        Mocca.propose(new Mocca.ProposalType.Transfer(BigInteger.valueOf(1000L), receivingUser));

    blockchain.sendAction(escrowUser, mocca, proposeTransfer);

    byte[] yesVoteNewCriteria = Mocca.vote(0, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVoteNewCriteria);
    blockchain.sendAction(voter2, mocca, yesVoteNewCriteria);
    blockchain.sendAction(voter3, mocca, yesVoteNewCriteria);

    byte[] yesVoteTransfer = Mocca.vote(1, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVoteTransfer);
    blockchain.sendAction(voter4, mocca, yesVoteTransfer);

    byte[] executeNewCriteria = Mocca.execute(0);
    blockchain.sendAction(voter4, mocca, executeNewCriteria);

    byte[] executeTransfer = Mocca.execute(1);
    assertThatThrownBy(() -> blockchain.sendAction(voter4, mocca, executeTransfer))
        .hasMessageContaining("The voting was not conclusive.");

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.proposal().get(1).result()).isNull();
    assertThat(moccaState.proposal().get(1).votes().size()).isEqualTo(2);
  }

  // Feature: Execute

  /** Cannot execute a proposal twice. */
  @ContractTest(previous = "proposeNewCriteria")
  void executeAnAlreadyExecutedProposal() {

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVote);
    blockchain.sendAction(voter2, mocca, yesVote);
    blockchain.sendAction(voter3, mocca, yesVote);

    byte[] executeProposal = Mocca.execute(0);
    blockchain.sendAction(voter4, mocca, executeProposal);

    assertThatThrownBy(() -> blockchain.sendAction(voter4, mocca, executeProposal))
        .hasMessageContaining("The proposal has already been executed.");
  }

  /**
   * A failure to transfer tokens from a proposal, from lack of funds, resets the proposal result.
   */
  @ContractTest(previous = "proposeTransfer")
  void executeTransferProposalWithInsufficientFunds() {

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVote);
    blockchain.sendAction(voter2, mocca, yesVote);
    blockchain.sendAction(voter3, mocca, yesVote);

    byte[] executeProposal = Mocca.execute(0);
    TxExecution executeAction = blockchain.sendActionAsync(voter4, mocca, executeProposal, 17000);
    TxExecution executeEvent = blockchain.executeEventAsync(executeAction.getContractInteraction());
    TxExecution transferEvent = blockchain.executeEventAsync(executeEvent.getContractInteraction());

    assertThat(transferEvent.getFailureCause().getErrorMessage())
        .contains("Insufficient funds for transfer: 0/1000");

    blockchain.executeEvent(transferEvent.getSystemCallback());

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.proposal().get(0).result()).isNull();
    assertThat(moccaState.amountOfTokens()).isEqualTo(0);
  }

  /** A failure to transfer tokens from a proposal, from lack of gas, resets the proposal result. */
  @ContractTest(previous = "proposeTransfer")
  void executeTransferProposalWithoutEnoughGas() {

    BigInteger amount = BigInteger.valueOf(10_000L);
    byte[] escrowTokens = Mocca.escrow(amount);
    blockchain.sendAction(escrowUser, mocca, escrowTokens);

    byte[] yesVote = Mocca.vote(0, new Mocca.Vote.Yes());
    blockchain.sendAction(voter1, mocca, yesVote);
    blockchain.sendAction(voter2, mocca, yesVote);
    blockchain.sendAction(voter3, mocca, yesVote);

    createLargeTokenState(tokenOwner, token);

    byte[] executeProposal = Mocca.execute(0);
    TxExecution executeAction = blockchain.sendActionAsync(voter4, mocca, executeProposal);
    TxExecution executeEvent = blockchain.executeEventAsync(executeAction.getContractInteraction());
    TxExecution transferEvent = blockchain.executeEventAsync(executeEvent.getContractInteraction());

    assertThat(transferEvent.getFailureCause().getErrorMessage())
        .contains("Out of instruction cycles! 15180001/15180000 instructions run");

    blockchain.executeEvent(transferEvent.getSystemCallback());

    Mocca.MoccaState moccaState = Mocca.MoccaState.deserialize(blockchain.getContractState(mocca));

    assertThat(moccaState.proposal().get(0).result()).isNull();
    assertThat(moccaState.amountOfTokens()).isEqualTo(10_000);
  }

  // Create a large state in the token contract, so gas failure happens on transfer.
  private void createLargeTokenState(BlockchainAddress sender, BlockchainAddress tokenContract) {
    ArrayList<Token.Transfer> dummyTransfer = new ArrayList<>();
    for (int i = 10; i < 20000; i++) {
      BlockchainAddress address = blockchain.newAccount(i);
      Token.Transfer transfer = new Token.Transfer(address, BigInteger.ONE);
      dummyTransfer.add(transfer);
    }
    byte[] bulkTransfer = Token.bulkTransfer(dummyTransfer);
    blockchain.sendAction(sender, tokenContract, bulkTransfer);
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

  private int totalVoteWeight(Mocca.MoccaState state) {
    int result = 0;
    for (Mocca.Voter voter : state.criteria().voters()) {
      result += voter.weight();
    }
    return result;
  }
}
