package defi.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.ContractTest;
import com.partisiablockchain.language.junit.JunitContractTest;
import com.partisiablockchain.language.junit.Previous;
import com.partisiablockchain.language.junit.TestBlockchain;
import com.partisiablockchain.language.junit.exceptions.ActionFailureException;
import defi.util.Mpc20LikeState;
import defi.util.Mpc20Utility;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Test that a token contract fulfils the MPC20 extension: Bulk transfer. When performing a bulk
 * transfer, the owner of the tokens can bundle transfers to different accounts into one action.
 */
public abstract class Mpc20ExtensionBulkTransferTest extends JunitContractTest {

  public static final BigInteger INITIAL_SUPPLY = BigInteger.valueOf(123123);

  public final ContractBytes contractBytes;
  public BlockchainAddress tokenContract;
  public Mpc20Utility mpc20Utility;

  public BlockchainAddress accountA;
  public BlockchainAddress accountB;
  public BlockchainAddress accountC;
  public BlockchainAddress accountD;

  /**
   * Initialize the test class.
   *
   * @param contractBytes Contract bytes to initialize the contract.
   */
  public Mpc20ExtensionBulkTransferTest(ContractBytes contractBytes) {
    this.contractBytes = contractBytes;
  }

  /** Setup for all the other tests. Deploys token contract and instantiates accounts. */
  @ContractTest
  void setup() {
    accountA = blockchain.newAccount(5);
    accountB = blockchain.newAccount(6);
    accountC = blockchain.newAccount(7);
    accountD = blockchain.newAccount(8);

    tokenContract =
        deployAndInitializeTokenContract(
            blockchain,
            accountA,
            "My Cool Token",
            "COOL",
            (byte) 8,
            accountA,
            INITIAL_SUPPLY,
            contractBytes);

    mpc20Utility = new Mpc20Utility(tokenContract, INITIAL_SUPPLY);

    final Mpc20LikeState state = getContractState(tokenContract);
    assertThat(state.name()).isEqualTo("My Cool Token");
    assertThat(state.symbol()).isEqualTo("COOL");

    final BigInteger transferAmount = BigInteger.ONE;

    mpc20Utility.transfer(blockchain, accountA, accountB, transferAmount);

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(balance(accountA)).isEqualTo(INITIAL_SUPPLY.subtract(transferAmount));
    assertThat(balance(accountB)).isEqualTo(transferAmount);
  }

  /** A user can send tokens to different account, by sending a bulk transfer. */
  @Test
  @Previous("setup")
  void bulkTransferTokens() {
    assertThat(balance(accountC)).isZero();
    byte[] transfer = Token.transfer(accountB, BigInteger.TWO);
    blockchain.sendAction(accountA, tokenContract, transfer);

    List<Token.Transfer> transfers =
        List.of(
            new Token.Transfer(accountC, BigInteger.ONE),
            new Token.Transfer(accountD, BigInteger.ONE));

    byte[] bulkTransfer = Token.bulkTransfer(transfers);
    blockchain.sendAction(accountB, tokenContract, bulkTransfer);
    assertThat(balance(accountC)).isEqualTo(BigInteger.ONE);
    assertThat(balance(accountD)).isEqualTo(BigInteger.ONE);
    assertThat(balance(accountB)).isEqualTo(BigInteger.ONE);
  }

  /** A user can bulk transfer approved tokens for an account to two other accounts. */
  @Test
  @Previous("setup")
  void bulkTransferFrom() {
    assertThat(balance(accountC)).isZero();

    byte[] approve = Token.approve(accountD, BigInteger.valueOf(2));
    blockchain.sendAction(accountA, tokenContract, approve);

    List<Token.Transfer> transfers =
        List.of(
            new Token.Transfer(accountB, BigInteger.ONE),
            new Token.Transfer(accountC, BigInteger.ONE));

    byte[] bulkTransfer = Token.bulkTransferFrom(accountA, transfers);
    blockchain.sendAction(accountD, tokenContract, bulkTransfer);

    assertThat(balance(accountB)).isEqualTo(BigInteger.TWO);
    assertThat(balance(accountC)).isEqualTo(BigInteger.ONE);
  }

  /**
   * A user bulk transferring to two accounts, where the user does not have enough tokens for both
   * transfers makes both transfers fail.
   */
  @Test
  @Previous("setup")
  void bulkTransferTokensOneFails() {
    assertThat(balance(accountC)).isZero();

    assertThat(balance(accountB)).isEqualTo(BigInteger.ONE);

    List<Token.Transfer> transfers =
        List.of(
            new Token.Transfer(accountC, BigInteger.ONE),
            new Token.Transfer(accountD, BigInteger.ONE));

    byte[] bulkTransfer = Token.bulkTransfer(transfers);

    assertThatThrownBy(() -> blockchain.sendAction(accountB, tokenContract, bulkTransfer))
        .isInstanceOf(ActionFailureException.class)
        .hasMessageContaining(
            "Insufficient COOL tokens for transfer! Have 0, but trying to transfer 1 (in minimal"
                + " units)");

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(balance(accountB)).isEqualTo(BigInteger.ONE);
    assertThat(balance(accountC)).isZero();
    assertThat(balance(accountD)).isZero();
  }

  /** Bulk transfer must be given a non-empty list of transfers to perform. */
  @Test
  @Previous("setup")
  void bulkTransferNoTransfers() {
    assertThat(balance(accountB)).isEqualTo(BigInteger.ONE);

    List<Token.Transfer> transfers = List.of();

    byte[] bulkTransfer = Token.bulkTransfer(transfers);
    blockchain.sendAction(accountB, tokenContract, bulkTransfer);

    mpc20Utility.assertStateInvariants(getContractState(tokenContract));
    assertThat(balance(accountB)).isEqualTo(BigInteger.ONE);
  }

  /**
   * Deploy a Token contract with the given argument.
   *
   * @param blockchain the blockchain to deploy to.
   * @param creator the creator of the contract.
   * @param tokenName the token name.
   * @param tokenSymbol the token symbol.
   * @param decimals the amount of decimals a token can have.
   * @param initialTokenHolder the account that owns the initial supply of tokens.
   * @param initialTokenSupply the initial supply of the token.
   * @return the address for the deployed token contract
   */
  protected abstract BlockchainAddress deployAndInitializeTokenContract(
      TestBlockchain blockchain,
      BlockchainAddress creator,
      String tokenName,
      String tokenSymbol,
      byte decimals,
      BlockchainAddress initialTokenHolder,
      BigInteger initialTokenSupply,
      ContractBytes contractBytes);

  /**
   * Gets the state of the token contract with the given address.
   *
   * @param contractAddress Address of the contract. Not nullable.
   * @return MPC-20 like state. Not nullable.
   */
  protected abstract Mpc20LikeState getContractState(BlockchainAddress contractAddress);

  /**
   * Helper function to retrieve the balance of a given owner.
   *
   * @param owner The account that owns the tokens.
   * @return The amount of tokens the owner has in his balance.
   */
  private BigInteger balance(final BlockchainAddress owner) {
    final BigInteger balance = getContractState(tokenContract).balances().get(owner);
    return balance == null ? BigInteger.ZERO : balance;
  }
}
