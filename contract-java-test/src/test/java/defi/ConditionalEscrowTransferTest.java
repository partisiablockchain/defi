package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.ConditionalEscrowTransfer;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import java.math.BigInteger;
import java.nio.file.Path;
import org.assertj.core.api.Assertions;

/** Test suite for the conditional escrow transfer smart contract. */
public final class ConditionalEscrowTransferTest extends JunitContractTest {

  private static final ContractBytes CONDITIONAL_ESCROW_CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/conditional_escrow_transfer.pbc"),
          Path.of(
              "../rust/target/wasm32-unknown-unknown/release/conditional_escrow_transfer_runner"));

  private static final ContractBytes TOKEN_CONTRACT_BYTES =
      ContractBytes.fromPbcFile(Path.of("../rust/target/wasm32-unknown-unknown/release/token.pbc"));

  private BlockchainAddress doge;
  private BlockchainAddress conditionalEscrow;
  private BlockchainAddress ownerDoge;
  private BlockchainAddress ownerConditionalEscrow;
  private BlockchainAddress sender;
  private BlockchainAddress receiver;
  private BlockchainAddress approver;

  /** Performs setup for the rest of the tests. Deploys contracts and transfers tokens. */
  @ContractTest
  public void setup() {
    ownerDoge = blockchain.newAccount(1);
    byte[] tokenInitRpc = Token.initialize("Doge", "DOGE", (byte) 8, BigInteger.valueOf(1_000_000));
    doge = blockchain.deployContract(ownerDoge, TOKEN_CONTRACT_BYTES, tokenInitRpc);

    ownerConditionalEscrow = blockchain.newAccount(2);
    sender = blockchain.newAccount(3);
    receiver = blockchain.newAccount(4);
    approver = blockchain.newAccount(5);
    byte[] conditionalEscrowInitRpc =
        ConditionalEscrowTransfer.initialize(sender, receiver, approver, doge, 2);
    conditionalEscrow =
        blockchain.deployContract(
            ownerConditionalEscrow, CONDITIONAL_ESCROW_CONTRACT_BYTES, conditionalEscrowInitRpc);

    byte[] transferOne = Token.transfer(sender, BigInteger.valueOf(1000));
    byte[] transferTwo = Token.transfer(receiver, BigInteger.valueOf(1000));
    byte[] transferThree = Token.transfer(approver, BigInteger.valueOf(1000));

    blockchain.sendAction(ownerDoge, doge, transferOne);
    blockchain.sendAction(ownerDoge, doge, transferTwo);
    blockchain.sendAction(ownerDoge, doge, transferThree);

    byte[] approveForEscrowSenderRpc = Token.approve(conditionalEscrow, BigInteger.valueOf(1000));
    blockchain.sendAction(sender, doge, approveForEscrowSenderRpc);
  }

  /** Sender is able to deposit funds. */
  @ContractTest(previous = "setup")
  public void depositFunds() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);
    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));
    Assertions.assertThat(state.balance()).isEqualTo(100);
    Assertions.assertThat(state.status()).isEqualTo((byte) 1);
  }

  /** Receiver is not able to deposit funds. */
  @ContractTest(previous = "setup")
  public void depositFromReceiver() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(receiver, conditionalEscrow, depositHundredRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Deposit can only be called by the sender");
    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));
    Assertions.assertThat(state.balance()).isEqualTo(0);
    Assertions.assertThat(state.status()).isEqualTo((byte) 0);
  }

  /** It's not possible to deposit funds after status has been changed to approved. */
  @ContractTest(previous = "setup")
  public void depositWhenStatusStateApproved() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);

    byte[] approveRpc = ConditionalEscrowTransfer.approve();
    blockchain.sendAction(approver, conditionalEscrow, approveRpc);

    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot deposit tokens after the condition has been fulfilled");
  }

  /** It's not possible to deposit funds after deadline has passed. */
  @ContractTest(previous = "setup")
  public void depositAfterDeadlineIsPassed() {
    blockchain.waitForBlockProductionTime(3 * 60 * 60 * 1000);
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot deposit tokens after deadline is passed");
    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));
    Assertions.assertThat(state.balance()).isEqualTo(0);
    Assertions.assertThat(state.status()).isEqualTo((byte) 0);
  }

  /** It's not possible to deposit more than current account balance. */
  @ContractTest(previous = "setup")
  public void depositMoreTokensThanBalance() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(10000));
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient DOGE allowance for transfer_from! Allowed 1000, but trying to transfer"
                + " 10000");
    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));
    Assertions.assertThat(state.balance()).isEqualTo(0);
    Assertions.assertThat(state.status()).isEqualTo((byte) 0);
  }

  /** Deposited tokens can be approved for transfer. */
  @ContractTest(previous = "setup")
  public void approve() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);

    byte[] approveRpc = ConditionalEscrowTransfer.approve();
    blockchain.sendAction(approver, conditionalEscrow, approveRpc);

    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));
    Assertions.assertThat(state.status()).isEqualTo((byte) 2);
  }

  /** Receiver cannot approve deposited funds for transfer. */
  @ContractTest(previous = "setup")
  public void approveFromReceiver() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);

    byte[] approveRpc = ConditionalEscrowTransfer.approve();
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(sender, conditionalEscrow, approveRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Only the designated approver can approve");

    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));
    Assertions.assertThat(state.status()).isEqualTo((byte) 1);
  }

  /** Approval cannot be made before tokens have been deposited. */
  @ContractTest(previous = "setup")
  public void approveWithNoDeposit() {
    byte[] approveRpc = ConditionalEscrowTransfer.approve();
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(approver, conditionalEscrow, approveRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Tried to approve when status was not STATE_AWAITING_APPROVAL");
  }

  /** Deposited tokens cannot be approved for transfer after deadline has passed. */
  @ContractTest(previous = "setup")
  public void approveAfterDeadline() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);

    blockchain.waitForBlockProductionTime(3 * 60 * 60 * 1000);
    byte[] approveRpc = ConditionalEscrowTransfer.approve();
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(approver, conditionalEscrow, approveRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Condition was fulfilled after deadline was passed");

    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));
    Assertions.assertThat(state.status()).isEqualTo((byte) 1);
  }

  /** Receiver can claim deposited and approved tokens. */
  @ContractTest(previous = "setup")
  public void claim() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);

    byte[] approveRpc = ConditionalEscrowTransfer.approve();
    blockchain.sendAction(approver, conditionalEscrow, approveRpc);

    byte[] claimRpc = ConditionalEscrowTransfer.claim();
    blockchain.sendAction(receiver, conditionalEscrow, claimRpc);

    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));

    Assertions.assertThat(state.balance()).isEqualTo(0);
  }

  /** Sender cannot claim deposited tokens before deadline has passed. */
  @ContractTest(previous = "setup")
  public void claimFromSenderBeforeDeadline() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);

    byte[] claimRpc = ConditionalEscrowTransfer.claim();
    Assertions.assertThatThrownBy(() -> blockchain.sendAction(sender, conditionalEscrow, claimRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("The sender cannot claim tokens before the deadline is passed");

    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));

    Assertions.assertThat(state.balance()).isEqualTo(100);
  }

  /** Sender can claim deposited tokens after deadline has passed for approval. */
  @ContractTest(previous = "setup")
  public void claimFromSenderAfterDeadline() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);

    blockchain.waitForBlockProductionTime(3 * 60 * 60 * 1000);
    byte[] claimRpc = ConditionalEscrowTransfer.claim();
    blockchain.sendAction(sender, conditionalEscrow, claimRpc);

    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));

    Assertions.assertThat(state.balance()).isEqualTo(0);
  }

  /** Sender cannot claim deposited tokens after escrow transfer has been approved. */
  @ContractTest(previous = "setup")
  public void claimFromSenderAfterApproval() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);

    byte[] approveRpc = ConditionalEscrowTransfer.approve();
    blockchain.sendAction(approver, conditionalEscrow, approveRpc);

    byte[] claimRpc = ConditionalEscrowTransfer.claim();
    Assertions.assertThatThrownBy(() -> blockchain.sendAction(sender, conditionalEscrow, claimRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "The sender cannot claim tokens since the condition has been fulfilled");

    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));

    Assertions.assertThat(state.balance()).isEqualTo(100);
  }

  /** It's not possible to claim from an account different from sender or receiver. */
  @ContractTest(previous = "setup")
  public void claimFromNonSenderOrReceiver() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);

    byte[] approveRpc = ConditionalEscrowTransfer.approve();
    blockchain.sendAction(approver, conditionalEscrow, approveRpc);

    byte[] claimRpc = ConditionalEscrowTransfer.claim();
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(blockchain.newAccount(20), conditionalEscrow, claimRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Only the sender and the receiver in the escrow transfer can claim tokens");

    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));

    Assertions.assertThat(state.balance()).isEqualTo(100);
  }

  /** Receiver cannot claim deposited tokens before escrow transfer has been approved. */
  @ContractTest(previous = "setup")
  public void claimFromReceiverNotApproved() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);
    byte[] claimRpc = ConditionalEscrowTransfer.claim();
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(receiver, conditionalEscrow, claimRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "The receiver cannot claim unless transfer condition has been fulfilled");

    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));

    Assertions.assertThat(state.balance()).isEqualTo(100);
  }

  /** Receiver cannot claim twice. */
  @ContractTest(previous = "setup")
  public void receiverClaimTwice() {
    byte[] depositHundredRpc = ConditionalEscrowTransfer.deposit(BigInteger.valueOf(100));
    blockchain.sendAction(sender, conditionalEscrow, depositHundredRpc);

    byte[] approveRpc = ConditionalEscrowTransfer.approve();
    blockchain.sendAction(approver, conditionalEscrow, approveRpc);

    byte[] claimRpc = ConditionalEscrowTransfer.claim();
    blockchain.sendAction(receiver, conditionalEscrow, claimRpc);
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(receiver, conditionalEscrow, claimRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot claim tokens when balance is zero");

    ConditionalEscrowTransfer.ContractState state =
        ConditionalEscrowTransfer.ContractState.deserialize(
            blockchain.getContractState(conditionalEscrow));

    Assertions.assertThat(state.balance()).isEqualTo(0);
  }

  /** Receiver cannot claim before anything has been deposited. */
  @ContractTest(previous = "setup")
  public void receiverClaimBeforeDeposit() {
    byte[] claimRpc = ConditionalEscrowTransfer.claim();
    Assertions.assertThatThrownBy(
            () -> blockchain.sendAction(receiver, conditionalEscrow, claimRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Cannot claim tokens when no tokens have been deposited");
  }

  /** it's not possible to deploy an escrow transfer contract selling a non-public token. */
  @ContractTest(previous = "setup")
  public void escrowNonPublicContractToken() {
    byte[] conditionalEscrowInitRpc =
        ConditionalEscrowTransfer.initialize(
            sender, receiver, approver, blockchain.newAccount(6), 2);
    Assertions.assertThatThrownBy(
            () ->
                blockchain.deployContract(
                    ownerConditionalEscrow,
                    CONDITIONAL_ESCROW_CONTRACT_BYTES,
                    conditionalEscrowInitRpc))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining("Tried to create a contract selling a non publicContract token");
  }
}
