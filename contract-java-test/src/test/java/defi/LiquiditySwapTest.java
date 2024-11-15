package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquiditySwap;
import com.partisiablockchain.language.junit.ContractBytes;
import defi.properties.DepositWithdrawTest;
import defi.properties.LiquiditySwapEthUsdcTest;
import defi.properties.LiquiditySwapGasBenchmark;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;

/** {@link LiquiditySwap} testing. */
public final class LiquiditySwapTest {

  /** {@link LiquiditySwap} contract bytes. */
  public static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/liquidity_swap.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/liquidity_swap_runner"));

  @Nested
  final class EthUsd extends LiquiditySwapEthUsdcTest {
    EthUsd() {
      super(TokenContractTest.CONTRACT_BYTES, CONTRACT_BYTES);
    }
  }

  @Nested
  final class GasBenchmark extends LiquiditySwapGasBenchmark {
    GasBenchmark() {
      super(TokenV2Test.CONTRACT_BYTES, CONTRACT_BYTES);
    }
  }

  @Nested
  final class DepositWithdraw extends DepositWithdrawTest {
    DepositWithdraw() {
      super(TokenContractTest.CONTRACT_BYTES, CONTRACT_BYTES);
    }

    @Override
    protected byte[] initContractUnderTestRpc(BlockchainAddress token1, BlockchainAddress token2) {
      return LiquiditySwap.initialize(token1, token2, (short) 0);
    }

    @Override
    protected BigInteger getDepositAmount(BlockchainAddress owner) {
      final LiquiditySwap.LiquiditySwapContractState state =
          new LiquiditySwap(getStateClient(), contractUnderTestAddress).getState();
      final var ownerBalances = state.tokenBalances().balances().get(owner);
      return ownerBalances == null ? BigInteger.ZERO : ownerBalances.aTokens();
    }
  }
}
