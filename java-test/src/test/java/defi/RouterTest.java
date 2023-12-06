package defi;

import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.JunitContractTest;
import defi.properties.RoutingTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;

/** {@link Router} testing. */
public final class RouterTest extends JunitContractTest {

  private static final ContractBytes ROUTER_CONTRACT_BYTES =
      ContractBytes.fromPaths(
          Path.of("../target/wasm32-unknown-unknown/release/swap_router.wasm"),
          Path.of("../target/wasm32-unknown-unknown/release/swap_router.abi"),
          Path.of("../target/wasm32-unknown-unknown/release/swap_router_runner"));

  @Nested
  final class RoutingRegularToken extends RoutingTest {
    RoutingRegularToken() {
      super(
          TokenContractTest.CONTRACT_BYTES,
          LiquiditySwapLockTest.CONTRACT_BYTES,
          ROUTER_CONTRACT_BYTES);
    }
  }
}
