package defi;

import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import defi.properties.Mpc20LikeTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;

/** {@link Token} testing. */
public final class TokenContractTest {

  /** {@link ContractBytes} for the {@link Token} contract. */
  public static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPaths(
          Path.of("../target/wasm32-unknown-unknown/release/token.wasm"),
          Path.of("../target/wasm32-unknown-unknown/release/token.abi"),
          Path.of("../target/wasm32-unknown-unknown/release/token_runner"));

  @Nested
  final class Mpc20 extends Mpc20LikeTest {
    Mpc20() {
      super(CONTRACT_BYTES);
    }
  }
}
