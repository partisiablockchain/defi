package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.TokenV2;
import com.partisiablockchain.language.junit.ContractBytes;
import defi.properties.Mpc20LikeTest;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;

/** Test the Token-v2 contract. */
@SuppressWarnings("CPD-START")
public final class TokenV2Test {

  /** {@link ContractBytes} for {@link TokenV2}. */
  public static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPaths(
          Path.of("../rust/target/wasm32-unknown-unknown/release/token_v2.wasm"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/token_v2.abi"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/token_v2_runner"));

  @Nested
  final class Mpc20V2 extends Mpc20LikeTest {
    Mpc20V2() {
      super(CONTRACT_BYTES);
    }

    @Override
    protected Mpc20LikeState deserializeState(BlockchainAddress address) {
      final TokenV2.TokenState state = new TokenV2(getStateClient(), address).getState();
      return new Mpc20V2State(state);
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
      public BlockchainAddress owner() {
        return state.owner();
      }

      @Override
      public BigInteger totalSupply() {
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
}
