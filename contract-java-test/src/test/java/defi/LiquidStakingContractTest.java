package defi;

import com.partisiablockchain.language.junit.ContractBytes;
import defi.properties.LiquidStakingTest;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;

/** Liquid Staking contract test. */
public final class LiquidStakingContractTest {
  static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/liquid_staking.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/liquid_staking_runner"));

  @Nested
  final class LiquidStakingContract extends LiquidStakingTest {
    LiquidStakingContract() {
      super(CONTRACT_BYTES, TokenContractTest.CONTRACT_BYTES);
    }
  }
}
