package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import defi.properties.Mpc20LikeTest;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Nested;

/** {@link Token} testing. */
public final class TokenContractTest {

  /** {@link ContractBytes} for the {@link Token} contract. */
  public static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/token.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/token_runner"));

  @Nested
  final class Mpc20 extends Mpc20LikeTest {
    Mpc20() {
      super(CONTRACT_BYTES);
    }

    @Override
    protected Mpc20LikeState getContractState(BlockchainAddress address) {
      final Token.TokenState state = new Token(getStateClient(), address).getState();
      return new Mpc20State(state);
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
      public BlockchainAddress owner() {
        return state.owner();
      }

      @Override
      public BigInteger totalSupply() {
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
}
