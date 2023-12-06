package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.NftContract;
import com.partisiablockchain.language.junit.ContractBytes;
import defi.properties.Mpc721LikeTest;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Nested;

/** NFT contract test. */
public final class NftTest {
  static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPaths(
          Path.of("../target/wasm32-unknown-unknown/release/nft_contract.wasm"),
          Path.of("../target/wasm32-unknown-unknown/release/nft_contract.abi"),
          Path.of("../target/wasm32-unknown-unknown/release/nft_contract_runner"));

  @Nested
  final class Mpc721 extends Mpc721LikeTest {
    Mpc721() {
      super(CONTRACT_BYTES);
    }

    @Override
    protected Mpc721LikeState getState() {
      return new Mpc721State(
          NftContract.NFTContractState.deserialize(blockchain.getContractState(contractAddress)));
    }

    @Override
    protected byte[] mintAction(BlockchainAddress to, BigInteger tokenId, String uri) {
      return NftContract.mint(to, tokenId, uriFromString(uri));
    }

    private byte[] uriFromString(String uri) {
      byte[] uriBytes = uri.getBytes(StandardCharsets.UTF_8);
      return Arrays.copyOf(uriBytes, 16);
    }

    private static final class Mpc721State implements Mpc721LikeTest.Mpc721LikeState {
      NftContract.NFTContractState state;

      Mpc721State(NftContract.NFTContractState state) {
        this.state = state;
      }

      @Override
      public String name() {
        return state.name();
      }

      @Override
      public String symbol() {
        return state.symbol();
      }

      @Override
      public Map<BigInteger, BlockchainAddress> owners() {
        return state.owners();
      }

      @Override
      public Map<BigInteger, BlockchainAddress> tokenApprovals() {
        return state.tokenApprovals();
      }

      @Override
      public boolean isApprovedForAll(BlockchainAddress owner, BlockchainAddress operator) {
        return state
            .operatorApprovals()
            .contains(new NftContract.OperatorApproval(owner, operator));
      }

      @Override
      public String uriTemplate() {
        return state.uriTemplate();
      }

      @Override
      public String tokenUriDetails(BigInteger tokenId) {
        byte[] bytes = state.tokenUriDetails().get(tokenId);
        if (bytes == null) {
          return null;
        } else {
          return new String(bytes, StandardCharsets.UTF_8).replaceAll("\0", "");
        }
      }

      @Override
      public BlockchainAddress contractOwner() {
        return state.contractOwner();
      }
    }
  }
}
