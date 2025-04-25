package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.TestBlockchain;
import defi.properties.Mpc20ExtensionApproveRelativeTest;
import defi.properties.Mpc20ExtensionBulkTransferTest;
import defi.properties.Mpc20StandardTest;
import defi.util.Mpc20LikeState;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Nested;

/** Test the {@link Token} contract. */
public final class TokenContractTest {

  /** {@link ContractBytes} for the {@link Token} contract. */
  public static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/token.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/token_runner"));

  private BlockchainAddress deploy(
      TestBlockchain blockchain,
      BlockchainAddress creator,
      String tokenName,
      String tokenSymbol,
      byte decimals,
      BigInteger initialTokenSupply,
      ContractBytes contractBytes) {
    final byte[] initRpc = Token.initialize(tokenName, tokenSymbol, decimals, initialTokenSupply);
    return blockchain.deployContract(creator, contractBytes, initRpc);
  }

  @Nested
  final class Mpc20 extends Mpc20StandardTest {
    Mpc20() {
      super(CONTRACT_BYTES);
    }

    @Override
    protected BlockchainAddress deployAndInitializeTokenContract(
        TestBlockchain blockchain,
        BlockchainAddress creator,
        String tokenName,
        String tokenSymbol,
        byte decimals,
        BlockchainAddress initialTokenHolder,
        BigInteger initialTokenSupply,
        ContractBytes contractBytes) {
      return deploy(
          blockchain, creator, tokenName, tokenSymbol, decimals, initialTokenSupply, contractBytes);
    }

    @Override
    protected Mpc20LikeState getContractState(BlockchainAddress address) {
      final Token.TokenState state = new Token(getStateClient(), address).getState();
      return new Mpc20State(state);
    }
  }

  @Nested
  final class Mpc20ExtensionBulkTransfer extends Mpc20ExtensionBulkTransferTest {
    Mpc20ExtensionBulkTransfer() {
      super(CONTRACT_BYTES);
    }

    @Override
    protected BlockchainAddress deployAndInitializeTokenContract(
        TestBlockchain blockchain,
        BlockchainAddress creator,
        String tokenName,
        String tokenSymbol,
        byte decimals,
        BlockchainAddress initialTokenHolder,
        BigInteger initialTokenSupply,
        ContractBytes contractBytes) {
      return deploy(
          blockchain, creator, tokenName, tokenSymbol, decimals, initialTokenSupply, contractBytes);
    }

    @Override
    protected Mpc20LikeState getContractState(BlockchainAddress address) {
      final Token.TokenState state = new Token(getStateClient(), address).getState();
      return new Mpc20State(state);
    }
  }

  @Nested
  final class Mpc20ExtensionApproveRelative extends Mpc20ExtensionApproveRelativeTest {
    Mpc20ExtensionApproveRelative() {
      super(CONTRACT_BYTES);
    }

    @Override
    protected BlockchainAddress deployAndInitializeTokenContract(
        TestBlockchain blockchain,
        BlockchainAddress creator,
        String tokenName,
        String tokenSymbol,
        byte decimals,
        BlockchainAddress initialTokenHolder,
        BigInteger initialTokenSupply,
        ContractBytes contractBytes) {
      return deploy(
          blockchain, creator, tokenName, tokenSymbol, decimals, initialTokenSupply, contractBytes);
    }

    @Override
    protected Mpc20LikeState getContractState(BlockchainAddress address) {
      final Token.TokenState state = new Token(getStateClient(), address).getState();
      return new Mpc20State(state);
    }
  }

  private static final class Mpc20State implements Mpc20LikeState {
    Token.TokenState state;

    Mpc20State(Token.TokenState state) {
      this.state = state;
    }

    @Override
    public String name() {
      return state.name();
    }

    @Override
    public byte decimals() {
      return state.decimals();
    }

    @Override
    public String symbol() {
      return state.symbol();
    }

    @Override
    public BigInteger currentTotalSupply() {
      return state.totalSupply();
    }

    @Override
    public Map<BlockchainAddress, BigInteger> balances() {
      return state.balances();
    }

    @Override
    public BigInteger allowance(final BlockchainAddress owner, final BlockchainAddress spender) {
      final var ownerAllowances = state.allowed().get(owner);
      return ownerAllowances == null ? null : ownerAllowances.get(spender);
    }
  }
}
