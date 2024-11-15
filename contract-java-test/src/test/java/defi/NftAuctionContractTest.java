package defi;

import com.partisiablockchain.language.abicodegen.NftAuction;
import com.partisiablockchain.language.junit.ContractBytes;
import defi.properties.NftAuctionTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;

/** Test declarations for the {@link NftAuction} smart contract. */
public final class NftAuctionContractTest {

  /** Contract bytes for {@link NftAuction} contract. */
  static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/nft_auction.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/nft_auction_runner"));

  @Nested
  final class Auction extends NftAuctionTest {
    Auction() {
      super(CONTRACT_BYTES, TokenContractTest.CONTRACT_BYTES, NftTest.CONTRACT_BYTES);
    }
  }
}
