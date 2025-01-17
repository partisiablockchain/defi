package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.abicodegen.TokenV2;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.JunitContractTest;
import defi.properties.RoutingTest;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;

/** {@link Router} testing. */
public final class RouterTest extends JunitContractTest {

  private static final ContractBytes ROUTER_CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/swap_router.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/swap_router_runner"));

  @Nested
  final class RoutingRegularToken extends RoutingTest {
    RoutingRegularToken() {
      super(
          TokenContractTest.CONTRACT_BYTES,
          LiquiditySwapLockTest.CONTRACT_BYTES,
          ROUTER_CONTRACT_BYTES,
          500_000L,
          150);
    }

    @Override
    public BigInteger getTokenBalance(BlockchainAddress tokenContract, BlockchainAddress key) {
      final Token.TokenState state =
          Token.TokenState.deserialize(blockchain.getContractState(tokenContract));
      return state.balances().getOrDefault(key, BigInteger.ZERO);
    }
  }

  @Nested
  final class RoutingTokenV2 extends RoutingTest {
    RoutingTokenV2() {
      super(
          TokenV2Test.CONTRACT_BYTES,
          LiquiditySwapLockTest.CONTRACT_BYTES,
          ROUTER_CONTRACT_BYTES,
          500_000L,
          500);
    }

    @Override
    public BigInteger getTokenBalance(BlockchainAddress tokenContract, BlockchainAddress key) {
      final TokenV2.TokenState state = new TokenV2(getStateClient(), tokenContract).getState();
      BigInteger val = state.balances().get(key);
      return val != null ? val : BigInteger.ZERO;
    }
  }
}
