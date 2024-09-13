package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.NftV2Contract;
import com.partisiablockchain.language.junit.ContractBytes;
import defi.properties.Mpc721LikeTest;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;

/** NFT v2 contract test. */
public final class NftV2Test {
  static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPaths(
          Path.of("../rust/target/wasm32-unknown-unknown/release/nft_v2_contract.wasm"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/nft_v2_contract.abi"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/nft_v2_contract_runner"));

  @Nested
  final class Mpc721V2 extends Mpc721LikeTest {
    Mpc721V2() {
      super(CONTRACT_BYTES);
    }

    @Override
    protected Mpc721LikeState getState() {
      return new Mpc721State(new NftV2Contract(getStateClient(), contractAddress).getState());
    }

    @Override
    protected byte[] mintAction(BlockchainAddress to, BigInteger tokenId, String uri) {
      return NftV2Contract.mint(to, tokenId, uri);
    }

    private static final class Mpc721State implements Mpc721LikeState {
      NftV2Contract.NFTContractState state;

      Mpc721State(NftV2Contract.NFTContractState state) {
        this.state = state;
      }

      @Override
      public String name() {
        return state.name();
      }

      @Override
      public Map<BigInteger, BlockchainAddress> owners() {
        return state.owners().getNextN(null, 1000).stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      }

      @Override
      public String symbol() {
        return state.symbol();
      }

      @Override
      public Map<BigInteger, BlockchainAddress> tokenApprovals() {
        return state.tokenApprovals().getNextN(null, 1000).stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      }

      @Override
      public boolean isApprovedForAll(BlockchainAddress owner, BlockchainAddress operator) {
        return state.operatorApprovals().get(new NftV2Contract.OperatorApproval(owner, operator))
            != null;
      }

      @Override
      public String uriTemplate() {
        return state.uriTemplate();
      }

      @Override
      public String tokenUriDetails(BigInteger tokenId) {
        return state.tokenUriDetails().get(tokenId);
      }

      @Override
      public BlockchainAddress contractOwner() {
        return state.contractOwner();
      }
    }
  }
}
