package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.abicodegen.TokenV2;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.TestBlockchain;
import defi.properties.Mpc20ExtensionApproveRelativeTest;
import defi.properties.Mpc20ExtensionBulkTransferTest;
import defi.properties.Mpc20StandardTest;
import defi.util.Mpc20LikeState;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;

/** Test the {@link TokenV2} contract. */
@SuppressWarnings("CPD-START")
public final class TokenV2Test {

  /** {@link ContractBytes} for the {@link TokenV2} contract. */
  public static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/token_v2.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/token_v2_runner"));

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
  final class Mpc20V2 extends Mpc20StandardTest {
    Mpc20V2() {
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
      final TokenV2.TokenState state = new TokenV2(getStateClient(), address).getState();
      return new Mpc20V2State(state);
    }
  }

  @Nested
  final class Mpc20V2ExtensionBulkTransfer extends Mpc20ExtensionBulkTransferTest {
    Mpc20V2ExtensionBulkTransfer() {
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
      final TokenV2.TokenState state = new TokenV2(getStateClient(), address).getState();
      return new Mpc20V2State(state);
    }
  }

  @Nested
  final class Mpc20V2ExtensionApproveRelative extends Mpc20ExtensionApproveRelativeTest {
    Mpc20V2ExtensionApproveRelative() {
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
      final TokenV2.TokenState state = new TokenV2(getStateClient(), address).getState();
      return new Mpc20V2State(state);
    }
  }

  private static final class Mpc20V2State implements Mpc20LikeState {
    TokenV2.TokenState state;

    Mpc20V2State(TokenV2.TokenState state) {
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
      return state.balances().getNextN(null, 1000).stream()
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public BigInteger allowance(final BlockchainAddress owner, final BlockchainAddress spender) {
      return state.allowed().get(new TokenV2.AllowedAddress(owner, spender));
    }
  }
}
