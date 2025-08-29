package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwap;
import com.partisiablockchain.language.abicodegen.ZkLiquiditySwap;
import com.partisiablockchain.language.junit.ContractBytes;
import defi.properties.DepositWithdrawTest;
import defi.properties.LiquiditySwapZkTest;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;

/** {@link LiquiditySwap} testing. */
public final class ZkLiquiditySwapContractTest {

  /** {@link LiquiditySwap} contract bytes. */
  private static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/zk_liquidity_swap.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/zk_liquidity_swap_runner"));

  @Nested
  final class Zk extends LiquiditySwapZkTest {
    Zk() {
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
      return ZkLiquiditySwap.initialize(token1, token2, (short) 0);
    }

    @Override
    protected BigInteger getDepositAmount(BlockchainAddress owner) {
      final var state =
          new ZkLiquiditySwap(getStateClient(), contractUnderTestAddress).getState().openState();
      final var ownerBalances = state.tokenBalances().balances().get(owner);
      return ownerBalances == null ? BigInteger.ZERO : ownerBalances.aTokens();
    }
  }
}
