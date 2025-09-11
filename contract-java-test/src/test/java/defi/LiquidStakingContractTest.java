package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.LiquidStaking;
import com.partisiablockchain.language.abicodegen.Token;
import com.partisiablockchain.language.junit.ContractBytes;
import com.partisiablockchain.language.junit.TestBlockchain;
import defi.properties.LiquidStakingTest;
import defi.properties.Mpc20StandardTest;
import defi.util.Mpc20LikeState;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;

/** Liquid Staking contract test. */
public final class LiquidStakingContractTest {

  /** {@link ContractBytes} for the current {@link LiquidStakingContract} contract. */
  static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/liquid_staking.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/liquid_staking_runner"));

  /** {@link ContractBytes} for the {@link Token} contract. */
  public static final ContractBytes TOKEN_CONTRACT_BYTES = TokenContractTest.CONTRACT_BYTES;

  @Nested
  final class LiquidStakingContract extends LiquidStakingTest {
    LiquidStakingContract() {
      super(CONTRACT_BYTES, TOKEN_CONTRACT_BYTES);
    }
  }

  /**
   * Deploy a {@link LiquidStaking} contract along with its underlying {@link Token} contract.
   *
   * @param blockchain the blockchain to deploy to.
   * @param creator the creator of the contract.
   * @param tokenName the token name.
   * @param tokenSymbol the token symbol.
   * @param decimals the amount of decimals a token can have.
   * @param initialTokenHolder the account that owns the initial supply of tokens.
   * @param initialTokenSupply the initial supply of the token.
   * @return the address for the deployed token contract
   */
  public static BlockchainAddress deployAndInitializeLiquidStakingContractWithUnderlyingToken(
      TestBlockchain blockchain,
      BlockchainAddress creator,
      String tokenName,
      String tokenSymbol,
      byte decimals,
      BlockchainAddress initialTokenHolder,
      BigInteger initialTokenSupply,
      BigInteger initialStakingSupply,
      ContractBytes contractBytes) {

    byte[] tokenInitRpc = Token.initialize(tokenName, tokenSymbol, (byte) 8, initialTokenSupply);
    BlockchainAddress tokenAddress =
        blockchain.deployContract(initialTokenHolder, TOKEN_CONTRACT_BYTES, tokenInitRpc);

    byte[] initRpc =
        LiquidStaking.initialize(
            tokenAddress,
            creator,
            creator,
            100,
            100,
            BigInteger.ZERO,
            tokenName,
            tokenSymbol,
            decimals);

    BlockchainAddress liquidStakingAddress =
        blockchain.deployContract(creator, contractBytes, initRpc);

    byte[] approveRpc = Token.approve(liquidStakingAddress, initialStakingSupply);
    blockchain.sendAction(initialTokenHolder, tokenAddress, approveRpc);

    byte[] rpc = LiquidStaking.submit(initialStakingSupply);
    blockchain.sendAction(initialTokenHolder, liquidStakingAddress, rpc);

    return liquidStakingAddress;
  }

  @Nested
  final class Mpc20LiquidToken extends Mpc20StandardTest {
    public Mpc20LiquidToken() {
      super(CONTRACT_BYTES);
    }

    @Override
    public BlockchainAddress deployAndInitializeTokenContract(
        TestBlockchain blockchain,
        BlockchainAddress creator,
        String tokenName,
        String tokenSymbol,
        byte decimals,
        BlockchainAddress initialTokenHolder,
        BigInteger initialTokenSupply,
        ContractBytes contractBytes) {
      return deployAndInitializeLiquidStakingContractWithUnderlyingToken(
          blockchain,
          creator,
          tokenName,
          tokenSymbol,
          decimals,
          initialTokenHolder,
          initialTokenSupply,
          initialTokenSupply,
          contractBytes);
    }

    @Override
    protected Mpc20LikeState getContractState(BlockchainAddress address) {
      final LiquidStaking.LiquidStakingState state =
          new LiquidStaking(getStateClient(), address).getState();
      return new Mpc20LiquidStakingState(state);
    }
  }

  private static final class Mpc20LiquidStakingState implements Mpc20LikeState {
    LiquidStaking.LiquidStakingState state;

    Mpc20LiquidStakingState(LiquidStaking.LiquidStakingState state) {
      this.state = state;
    }

    @Override
    public String name() {
      return state.liquidTokenState().name();
    }

    @Override
    public byte decimals() {
      return state.liquidTokenState().decimals();
    }

    @Override
    public String symbol() {
      return state.liquidTokenState().symbol();
    }

    @Override
    public BigInteger currentTotalSupply() {
      return state.totalPoolLiquid();
    }

    @Override
    public Map<BlockchainAddress, BigInteger> balances() {
      return state.liquidTokenState().balances().getNextN(null, 1000).stream()
          .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public BigInteger allowance(final BlockchainAddress owner, final BlockchainAddress spender) {
      return state
          .liquidTokenState()
          .allowed()
          .get(new LiquidStaking.AllowedAddress(owner, spender));
    }
  }
}
