package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.ZkOrderMatching;
import com.partisiablockchain.language.junit.ContractBytes;
import defi.properties.DepositWithdrawTest;
import defi.properties.OrderMatchingZkTest;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;

/** {@link ZkOrderMatching} testing. */
public final class ZkOrderMatchingTest {

  /** {@link ContractBytes} for {@link ZkOrderMatching}. */
  public static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/zk_order_matching.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/zk_order_matching_runner"));

  @Nested
  final class OrderMatchingZk extends OrderMatchingZkTest {
    OrderMatchingZk() {
      super(TokenContractTest.CONTRACT_BYTES, CONTRACT_BYTES);
    }
  }

  @Nested
  final class DepositWithdraw extends DepositWithdrawTest {
    DepositWithdraw() {
      super(TokenContractTest.CONTRACT_BYTES, CONTRACT_BYTES);
    }

    @Override
    protected byte[] initContractUnderTestRpc(BlockchainAddress token1, BlockchainAddress token2) {
      return ZkOrderMatching.initialize(token1, token2);
    }

    @Override
    protected BigInteger getDepositAmount(BlockchainAddress owner) {
      final var state =
          new ZkOrderMatching(getStateClient(), contractUnderTestAddress).getState().openState();
      final var ownerBalances = state.tokenBalances().balances().get(owner);
      return ownerBalances == null ? BigInteger.ZERO : ownerBalances.aTokens();
    }
  }
}
