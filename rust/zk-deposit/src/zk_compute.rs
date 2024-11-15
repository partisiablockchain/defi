/// Submodule for MPC computations.
use create_type_spec_derive::CreateTypeSpec;
use pbc_zk::*;

type TransferKey = Sbu128;
type TokenAmountPub = u128;
type TokenAmount = Sbu128;

/// Secret shared balance.
#[derive(Debug, Clone, CreateTypeSpec, SecretBinary)]
pub struct DepositBalanceSecrets {
    /// The secret-shared balance of the account.
    balance: TokenAmount,
    /// The secret-shared ID of the account owning the balance.
    recipient_key: TransferKey,
}

/// Secret-shared pending transfer.
#[derive(Debug, Clone, Copy, CreateTypeSpec, SecretBinary)]
pub struct PendingTransferSecrets {
    /// Secret-shared key to transfer to.
    recipient_key: TransferKey,
    /// Amount of tokens to transfer.
    amount: TokenAmount,
}

/// Secret-shared information for creating new users.
#[derive(Debug, Clone, Copy, CreateTypeSpec, SecretBinary)]
pub struct AccountCreationSecrets {
    /// Secret-shared key used to transfer to the user that is being created.
    recipient_key: TransferKey,
}

/// General computation result, used to convey how many tokens have been moved, and whether the
/// computation was successful.
#[derive(Debug, Clone, Copy, CreateTypeSpec, SecretBinary)]
pub struct ComputationResult {
    /// Number of tokens that the computation effected or tried to effect.
    amount: TokenAmount,
    /// Whether the computation was successful.
    successful: Sbu1,
}

/// Public version of [`ComputationResult`] for deserialization.
#[derive(Debug, Clone, Copy, CreateTypeSpec, SecretBinary)]
pub struct ComputationResultPub {
    /// Number of tokens that the computation effected or tried to effect.
    pub amount: TokenAmountPub,
    /// Whether the computation was successful.
    pub successful: bool,
}

/// Performs the transfer of tokens from one balance to another.
///
/// Computation produces a set of replacement balances for the public contract to use, one for
/// every single of the existing balances. These are produced in exactly the same order as they
/// were input (an important invariant.)
///
/// The computation goes through the following phases:
///
/// 1. Compute updated sender balance
/// 2. Find recipient balance
/// 3. Compute updated recipient balance
/// 4. Inject computed balances into set of balances.
///
/// Assumptions:
///
/// - There exists at most one recipient with any given [`DepositBalanceSecrets::recipient_key`].
///
/// A transfer is only completed and successful, if:
///
/// - Sender exist.
/// - Sender has enough balance.
/// - Recipient exists.
/// - Recipient balance must not overflow.
///
/// Once these conditions are confirmed, the computation will:
///
/// - Subtract [`PendingTransferSecrets::amount`] from the sending [`DepositBalanceSecrets::balance`].
/// - Add [`PendingTransferSecrets::amount`] to the first balance with the correct [`DepositBalanceSecrets::recipient_key`].
#[zk_compute(shortname = 0x60)]
pub fn transfer(sender_balance_id: SecretVarId, transfer_id: SecretVarId) {
    let transfer: PendingTransferSecrets = load_sbi::<PendingTransferSecrets>(transfer_id);

    // Check that sender has enough tokens to transfer
    let sender_balance_original: DepositBalanceSecrets =
        load_sbi::<DepositBalanceSecrets>(sender_balance_id);
    let sender_balance_updated: TokenAmount = sender_balance_original.balance - transfer.amount;
    let sender_conditions_correct: Sbu1 = !is_negative(sender_balance_updated);

    // Check that recipient exists
    let recipient_balance = find_recipient_balance(transfer.recipient_key, sender_balance_id);
    let recipient_balance_updated: TokenAmount =
        recipient_balance.recipient_balance.balance + transfer.amount;
    let recipient_conditions_correct: Sbu1 =
        recipient_balance.exists && !is_negative(recipient_balance_updated);

    // Disable recipient transfer in, if sender wasn't correct
    let all_conditions_correct = sender_conditions_correct && recipient_conditions_correct;

    // Update recipient balances
    update_all_balances(
        transfer.recipient_key,
        sender_balance_updated,
        recipient_balance_updated,
        sender_balance_id,
        all_conditions_correct,
    );

    // Set computation result
    save_sbi::<ComputationResult>(ComputationResult {
        amount: transfer.amount,
        successful: all_conditions_correct,
    });
}

/// Balance of the recipient, and whether the balance even exist.
///
/// Workaround for the lack of [`Option`] in ZkRust.
#[derive(Debug, Clone, SecretBinary)]
struct RecipientBalance {
    /// Whether the balance exists.
    exists: Sbu1,
    /// The value of the balance.
    recipient_balance: DepositBalanceSecrets,
}

/// Finds the balance of the recipient based on the [`TransferKey`].
///
/// Produces a [`RecipientBalance`], with `exists` true if and only if the balance could be found.
/// Treats all zero keys as non-existant keys.
#[allow(clippy::collapsible_if)]
fn find_recipient_balance(
    recipient_key: TransferKey,
    sender_balance_id: SecretVarId,
) -> RecipientBalance {
    let mut recipient_balance = RecipientBalance {
        exists: Sbu1::from(false),
        recipient_balance: DepositBalanceSecrets {
            balance: Sbu128::from(0),
            recipient_key,
        },
    };

    for variable_id in secret_variable_ids() {
        if is_account_balance(variable_id) {
            let balance: DepositBalanceSecrets = load_sbi::<DepositBalanceSecrets>(variable_id);
            let is_sender = sender_balance_id.raw_id == variable_id.raw_id;
            if !is_sender {
                if balance.recipient_key == recipient_key {
                    recipient_balance.exists = Sbu1::from(true);
                    recipient_balance.recipient_balance = balance;
                }
            }
        }
    }

    if recipient_balance.recipient_balance.recipient_key == Sbu128::from(0) {
        recipient_balance.exists = Sbu1::from(false);
    }

    recipient_balance
}

/// Creates and saves new balances.
///
/// Effects:
///
/// - Sender balance is replaced with the updated sender balance.
/// - Recipient balance is replaced with the updated recipient balance.
/// - Other balances are identical to their previous value.
///
/// If `all_conditions_correct == false`, none of the balances will be changed.
#[allow(clippy::collapsible_if)]
#[allow(clippy::collapsible_else_if)]
fn update_all_balances(
    recipient_key: TransferKey,
    sender_balance_updated: TokenAmount,
    recipient_balance_updated: TokenAmount,
    sender_balance_id: SecretVarId,
    all_conditions_correct: Sbu1,
) {
    for variable_id in secret_variable_ids() {
        if is_account_balance(variable_id) {
            let mut balance: DepositBalanceSecrets = load_sbi::<DepositBalanceSecrets>(variable_id);

            let is_sender = sender_balance_id.raw_id == variable_id.raw_id;

            if is_sender {
                if all_conditions_correct {
                    balance.balance = sender_balance_updated;
                }
            } else {
                if all_conditions_correct && balance.recipient_key == recipient_key {
                    balance.balance = recipient_balance_updated;
                }
            }

            save_sbi::<DepositBalanceSecrets>(balance);
        }
    }
}

/// Initializes a new contract account.
///
/// The computation verifies that the given [`DepositBalanceSecrets::recipient_key`] haven't been
/// used yet. If the `recipient_key` has been used, it will create a new account with `recipient_key` zero.
#[zk_compute(shortname = 0x65)]
pub fn create_account(sender_balance_id: SecretVarId) -> DepositBalanceSecrets {
    let mut account_details: AccountCreationSecrets =
        load_sbi::<AccountCreationSecrets>(sender_balance_id);

    let recipient_balance =
        find_recipient_balance(account_details.recipient_key, sender_balance_id);

    let recipient_key = if recipient_balance.exists {
        Sbu128::from(0)
    } else {
        account_details.recipient_key
    };
    DepositBalanceSecrets {
        recipient_key,
        balance: Sbu128::from(0),
    }
}

/// Deposits the given token amount into the given balance.
///
/// Cannot fail.
///
/// Produces a single variable, with the added amount.
#[zk_compute(shortname = 0x61)]
pub fn deposit(sender_balance_id: SecretVarId, amount: TokenAmountPub) -> DepositBalanceSecrets {
    let mut sender_balance: DepositBalanceSecrets =
        load_sbi::<DepositBalanceSecrets>(sender_balance_id);

    sender_balance.balance = sender_balance.balance + Sbu128::from(amount);
    sender_balance
}

/// Withdraws the given token amount from the given balance.
///
/// Can fail if the withdrawal results in a negative balance.
///
/// Produces two variables: 1. The updated balance, 2. Whether the computation succeeded or not.
#[zk_compute(shortname = 0x62)]
pub fn withdraw(
    sender_balance_id: SecretVarId,
    amount: TokenAmountPub,
) -> (DepositBalanceSecrets, ComputationResult) {
    let mut sender_balance: DepositBalanceSecrets =
        load_sbi::<DepositBalanceSecrets>(sender_balance_id);
    let mut successful = Sbu1::from(false);

    let updated_sender_balance = sender_balance.balance - Sbu128::from(amount);

    if !is_negative(updated_sender_balance) {
        sender_balance.balance = updated_sender_balance;
        successful = Sbu1::from(true);
    }

    (
        sender_balance,
        ComputationResult {
            amount: Sbu128::from(amount),
            successful,
        },
    )
}

/// Metadata discriminant for [`DepositBalanceSecrets`].
const VARIABLE_KIND_DISCRIMINANT_DEPOSIT_BALANCE: u8 = 1;

/// Produces true if the given [`SecretVarId`] points to a [`DepositBalanceSecrets`].
fn is_account_balance(variable_id: SecretVarId) -> bool {
    load_metadata::<u8>(variable_id) == VARIABLE_KIND_DISCRIMINANT_DEPOSIT_BALANCE
}

/// Produces true if the given [`Sbu128`] would be negative if casted to [`Sbi128`].
fn is_negative(x: Sbu128) -> Sbu1 {
    let bits = x.to_le_bits();
    bits[128 - 1]
}
