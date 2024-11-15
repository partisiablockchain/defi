package defi;

import com.partisiablockchain.BlockchainAddress;
import com.partisiablockchain.language.abicodegen.ZkDeposit;
import com.partisiablockchain.language.junit.ContractBytes;
import com.secata.stream.BitOutput;
import com.secata.stream.CompactBitArray;
import com.secata.stream.SafeDataOutputStream;
import defi.properties.DepositWithdrawTest;
import defi.properties.ZkDepositTest;
import java.math.BigInteger;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;

/** {@link ZkDeposit} testing. */
public final class ZkDepositContractTest {

  private static BlockchainAddress approver =
      BlockchainAddress.fromString("000000000000000000000000000000000000000000");

  /** {@link ContractBytes} for {@link ZkDepositMatching}. */
  public static final ContractBytes CONTRACT_BYTES =
      ContractBytes.fromPbcFile(
          Path.of("../rust/target/wasm32-unknown-unknown/release/zk_deposit.pbc"),
          Path.of("../rust/target/wasm32-unknown-unknown/release/zk_deposit_runner"));

  @Nested
  final class ZkDepositSpecific extends ZkDepositTest {
    ZkDepositSpecific() {
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
      return ZkDeposit.initialize(approver, token1);
    }

    @Override
    protected void callbackForAccountCreation() {
      createAccount(users.get(0), BigInteger.ZERO);
      createAccount(users.get(1), BigInteger.ONE);
    }

    /**
     * Create account for test users.
     *
     * @param user User to create account for. Not nullable.
     * @param recipientKey Recipient key for the user. Unused for {@link DepositWithdrawTest}. Not
     *     nullable.
     */
    private void createAccount(BlockchainAddress user, BigInteger recipientKey) {
      final byte[] publicRpc =
          SafeDataOutputStream.serialize(s -> s.writeByte(ZkDepositTest.SHORTNAME_CREATE_ACCOUNT));

      final CompactBitArray secretRpc =
          BitOutput.serializeBits(
              s -> s.writeUnsignedBigInteger(recipientKey, ZkDepositTest.RECIPIENT_KEY_BIT_SIZE));

      blockchain.sendSecretInput(contractUnderTestAddress, user, secretRpc, publicRpc);
    }

    @Override
    protected BigInteger getDepositAmount(BlockchainAddress owner) {
      final BigInteger accountBalance =
          ZkDepositTest.getDepositBalance(
                  zkNodes, getStateClient(), contractUnderTestAddress, owner)
              .accountBalance();
      return accountBalance == null ? BigInteger.ZERO : accountBalance;
    }
  }
}
