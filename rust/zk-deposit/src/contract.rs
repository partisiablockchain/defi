#![doc = include_str!("../README.md")]

#[macro_use]
extern crate pbc_contract_codegen;
extern crate pbc_contract_common;

use create_type_spec_derive::CreateTypeSpec;
use defi_common::interact_mpc20;
use pbc_contract_common::address::Address;
use pbc_contract_common::avl_tree_map::AvlTreeMap;
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::EventGroup;
use pbc_contract_common::shortname::Shortname;
use pbc_contract_common::shortname::ShortnameZkComputation;
use pbc_contract_common::zk::{
    CalculationStatus, SecretVarId, ZkClosed, ZkInputDef, ZkState, ZkStateChange,
};
use read_write_state_derive::ReadWriteState;
use std::collections::VecDeque;

use pbc_zk::SecretBinary;
/// Submodule for MPC computations.
pub mod zk_compute;

/// Type used to represent token amounts. Equal to the MPC20 token sizes.
pub type TokenAmount = interact_mpc20::TokenTransferAmount;

/// Type used as identifiers for [transfers awaiting approval](`ContractState::transfers_yet_to_be_approved`).
pub type PendingTransferId = u32;

/// Contract state of the Zk-deposit contract.
#[state]
pub struct ContractState {
    /// Mapping from account addresses to the id of the account's [`zk_compute::DepositBalanceSecrets`].
    pub balances: AvlTreeMap<Address, SecretVarId>,
    /// Queue of [`WorkListItem`]s that is still waiting to be started. Items can only be started when the
    /// preceeding computation has finished.
    pub work_queue: VecDeque<WorkListItem>,
    /// List of transfers that have not been approved by the approver.
    pub transfers_yet_to_be_approved: AvlTreeMap<PendingTransferId, TransferData>,
    /// User that may approve transfers between users.
    pub transfer_approver: Address,
    /// Address of the token contract that this contract operates over.
    pub token_address: Address,
    /// List of variables that have been marked redundant. These will be removed after the next
    /// computation have finished, and may contain variables that are used by the computation.
    pub redundant_variables: Vec<SecretVarId>,
    /// Hacky work-around needed to include `VariableKind` in the ABI. This field is unused.
    _ignored_variable_kind: Option<VariableKind>,
}

/// Variable metadata for the contract. The enum variants determine the type of data stored in the
/// secret-shares.
#[derive(ReadWriteState, Debug, Clone, CreateTypeSpec)]
pub enum VariableKind {
    /// Secret-share variable is an account balance.
    ///
    /// Indicates that secret data is an [`zk_compute::DepositBalanceSecrets`].
    #[discriminant(1)]
    DepositBalance {
        /// Owner of the balance.
        ///
        /// The [`ContractState::transfer_variables_to_owner`] method ensures that the variable is
        /// owned by this address.
        owner: Address,
    },
    /// Secret-share variable is a work item.
    ///
    /// Indicates that secret data is [`zk_compute::PendingTransferSecrets`] or [`zk_compute::AccountCreationSecrets`], depending upon the computation.
    #[discriminant(2)]
    WorkItemData {
        /// Owner of the work.
        ///
        /// The [`ContractState::transfer_variables_to_owner`] method ensures that the variable is
        /// owned by this address.
        owner: Address,
    },
    /// Secret-share variable is a work item.
    ///
    /// Indicates that secret data is a [`zk_compute::ComputationResult`].
    #[discriminant(3)]
    WorkResult {
        /// Owner of the work result.
        ///
        /// The [`ContractState::transfer_variables_to_owner`] method ensures that the variable is
        /// owned by this address.
        owner: Address,
    },
}

impl VariableKind {
    /// Owner of the variable.
    ///
    /// The [`ContractState::transfer_variables_to_owner`] method ensures that the variable is
    /// owned by this address.
    pub fn owner(&self) -> &Address {
        match self {
            VariableKind::DepositBalance { owner } => owner,
            VariableKind::WorkItemData { owner } => owner,
            VariableKind::WorkResult { owner } => owner,
        }
    }
}

/// Secret transfer between two accounts.
///
/// Transfers in zk-deposit hide the recipient and the transferred amount.
///
/// Transfers start out unapproved in the [`ContractState::transfers_yet_to_be_approved`] list.
/// Once approved, the transfer is added to [`ContractState::work_queue`].
#[derive(ReadWriteState, Debug, Clone, CreateTypeSpec)]
pub struct TransferData {
    /// Sender of the transfer. The senders balance will have the transfer amount deducted, if the
    /// transfer is valid.
    sender: Address,
    /// Identifier for [`zk_compute::PendingTransferSecrets`].
    transfer_data_id: SecretVarId,
}

/// Indicates the type of the item in the work list.
#[derive(ReadWriteState, Debug, Clone, CreateTypeSpec)]
pub enum WorkListItem {
    /// Originally created by [`request_transfer`] invocation, subsequently approved by [`approve_transfer()`] invocation.
    #[discriminant(1)]
    PendingTransfer {
        /// Transfer data.
        transfer: TransferData,
    },

    /// Created by the [`deposit()`] invocation.
    ///
    /// Invariant: The `account` user will have an account in the contract, as it is
    /// checked by [`deposit()`] invocation.
    #[discriminant(2)]
    PendingDeposit {
        /// Account to deposit to
        account: Address,
        /// Amount to deposit
        amount: TokenAmount,
    },

    /// Created by the [`withdraw()`] invocation.
    #[discriminant(3)]
    PendingWithdraw {
        /// Account to withdraw from, and to eventually transfer tokens to.
        account: Address,
        /// Amount to withdraw
        amount: TokenAmount,
    },

    /// Created by the [`create_account`] invocation.
    #[discriminant(4)]
    PendingAccountCreation {
        /// Account to create
        account: Address,
        /// Identifier of secret-shared [`zk_compute::AccountCreationSecrets`].
        account_creation_id: SecretVarId,
    },
}

impl ContractState {
    /// True if and only if the given address has an account in the contract.
    fn has_account(&self, owner: &Address) -> bool {
        self.get_balance_variable_id(owner).is_some()
    }

    /// Get the id of the balance variable associated with the given address.
    fn get_balance_variable_id(&self, owner: &Address) -> Option<SecretVarId> {
        self.balances.get(owner)
    }

    /// Queues a new transfer, and possibly starts the associated computation, if there is nothing
    /// else in the queue.
    fn schedule_new_work_item(
        &mut self,
        context: &ContractContext,
        zk_state: &ZkState<VariableKind>,
        zk_state_change: &mut Vec<ZkStateChange>,
        event_groups: &mut Vec<EventGroup>,
        worklist_item: WorkListItem,
    ) {
        self.work_queue.push_back(worklist_item);
        self.attempt_to_start_next_in_queue(context, zk_state, zk_state_change, event_groups)
    }

    /// Initializes the next transfer in the queue, if the zk state machine is ready for the next
    /// computation.
    ///
    /// The queue will not be changed in the following cases:
    ///
    /// - There is already a computation running.
    /// - There are no more transfers in the queue.
    ///
    /// Transfers may fail in the following cases:
    ///
    /// - The sender does not have an account (for deposit, withdraw or transfer)
    /// - The sender already has an account (for account creation)
    ///
    /// When a transfer fails in this manner it will trigger [`fail_safely`] for that transfer, and
    /// try again.
    ///
    /// This method should be invoked every time a transfer is added to the queue, or every time
    /// a computation completes.
    ///
    /// It is not possible to run [`ContractState::attempt_to_start_next_in_queue`] in the same event as
    /// [`ContractState::clean_up_redundant_secret_variables`], as the latter may remove secret
    /// variables, while the first iterates all variables, and these cannot be done synchronized.
    pub fn attempt_to_start_next_in_queue(
        &mut self,
        context: &ContractContext,
        zk_state: &ZkState<VariableKind>,
        zk_state_change: &mut Vec<ZkStateChange>,
        event_groups: &mut Vec<EventGroup>,
    ) {
        // Calculation must not be doing anything right now.
        if zk_state.calculation_state != CalculationStatus::Waiting {
            return;
        }

        let Some(worklist_item) = self.work_queue.pop_front() else {
            return;
        };

        // Begin calculation for the next transfer
        match worklist_item {
            WorkListItem::PendingAccountCreation {
                account,
                account_creation_id,
            } => {
                if self.has_account(&account) {
                    fail_safely(
                        context,
                        event_groups,
                        "Cannot create new user when account already exists",
                    );
                    return self.attempt_to_start_next_in_queue(
                        context,
                        zk_state,
                        zk_state_change,
                        event_groups,
                    );
                }

                self.redundant_variables.push(account_creation_id);

                zk_state_change.push(zk_compute::create_account_start(
                    account_creation_id,
                    Some(simple_work_item_complete::SHORTNAME),
                    &VariableKind::DepositBalance { owner: account },
                ))
            }
            WorkListItem::PendingDeposit { account, amount } => {
                // NOTE: It should not be possible to enter the `expect` case, as we check that the
                // user has an account before they start the deposit.
                let recipient_balance_variable_id = self
                    .get_balance_variable_id(&account)
                    .expect("User does not possess an account");

                zk_state_change.push(zk_compute::deposit_start(
                    recipient_balance_variable_id,
                    amount,
                    Some(simple_work_item_complete::SHORTNAME),
                    &VariableKind::DepositBalance { owner: account },
                ))
            }
            WorkListItem::PendingWithdraw { account, amount } => {
                let recipient_balance_variable_id = match self.get_balance_variable_id(&account) {
                    Some(id) => id,
                    None => {
                        fail_safely(
                            context,
                            event_groups,
                            &format!("User does not possess an account: {account}"),
                        );
                        return self.attempt_to_start_next_in_queue(
                            context,
                            zk_state,
                            zk_state_change,
                            event_groups,
                        );
                    }
                };

                zk_state_change.push(zk_compute::withdraw_start(
                    recipient_balance_variable_id,
                    amount,
                    Some(withdraw_complete::SHORTNAME),
                    [
                        &VariableKind::DepositBalance { owner: account },
                        &VariableKind::WorkResult { owner: account },
                    ],
                ))
            }
            WorkListItem::PendingTransfer {
                transfer:
                    TransferData {
                        sender,
                        transfer_data_id,
                    },
            } => {
                let sender_balance_variable_id = match self.get_balance_variable_id(&sender) {
                    Some(id) => id,
                    None => {
                        fail_safely(
                            context,
                            event_groups,
                            &format!("User does not possess an account: {sender}"),
                        );
                        return self.attempt_to_start_next_in_queue(
                            context,
                            zk_state,
                            zk_state_change,
                            event_groups,
                        );
                    }
                };

                // First all the possible recipients
                let mut output_variable_metadata: Vec<VariableKind> = zk_state
                    .secret_variables
                    .iter()
                    .filter(|(_id, variable_info)| {
                        matches!(variable_info.metadata, VariableKind::DepositBalance { .. })
                    })
                    .map(|(_id, variable_info)| variable_info.metadata)
                    .collect();

                // Then the result
                output_variable_metadata.push(VariableKind::WorkResult { owner: sender });

                self.redundant_variables.push(transfer_data_id);

                zk_state_change.push(ZkStateChange::start_computation_with_inputs(
                    ShortnameZkComputation::from_u32(0x60),
                    output_variable_metadata,
                    vec![sender_balance_variable_id, transfer_data_id],
                    Some(simple_work_item_complete::SHORTNAME),
                ))
            }
        };
    }

    /// Updates the [`ContractState::balances`] map to include newly created
    /// [`VariableKind::DepositBalance`] variables, deleting the previous balance if any exists.
    ///
    /// Given a list of secret variables:
    ///
    /// - Transfer ownership of any given variable to the [`VariableKind::owner()`].
    /// - Older [`VariableKind::DepositBalance`] are deleted
    /// - [`VariableKind::DepositBalance`] are stored in the [`ContractState::balances`] map.
    pub fn transfer_variables_to_owner(
        &mut self,
        zk_state: &ZkState<VariableKind>,
        output_variables: Vec<SecretVarId>,
        zk_state_change: &mut Vec<ZkStateChange>,
    ) {
        let mut previous_variable_ids = vec![];

        for variable_id in output_variables {
            let variable = zk_state.get_variable(variable_id).unwrap();

            // Update state balances
            if let VariableKind::DepositBalance { .. } = variable.metadata {
                if let Some(previous_variable_id) = self.balances.get(variable.metadata.owner()) {
                    previous_variable_ids.push(previous_variable_id)
                }

                self.balances
                    .insert(*variable.metadata.owner(), variable.variable_id);
            }

            // Ensure that users can download their variables
            zk_state_change.push(ZkStateChange::TransferVariable {
                variable: variable.variable_id,
                new_owner: *variable.metadata.owner(),
            });
        }

        zk_state_change.push(ZkStateChange::DeleteVariables {
            variables_to_delete: previous_variable_ids,
        })
    }

    /// Should only be called from `zk_on_compute_complete` invocations.
    ///
    /// It is not possible to run [`ContractState::attempt_to_start_next_in_queue`] in the same event as
    /// [`ContractState::clean_up_redundant_secret_variables`], as the latter may remove secret
    /// variables, while the first iterates all variables, and these cannot be done synchronized.
    pub fn clean_up_redundant_secret_variables(&mut self, state_changes: &mut Vec<ZkStateChange>) {
        state_changes.push(ZkStateChange::DeleteVariables {
            variables_to_delete: self.redundant_variables.clone(),
        });
        self.redundant_variables.clear();
    }

    /// Checks that the given address fits with the token contract.
    fn assert_token_contract(&self, addr: Address) {
        assert!(
            addr == self.token_address,
            "Unknown token {}. Contract only supports {}",
            addr,
            self.token_address,
        );
    }
}

/// Initializes contract to a minimum state with no balances and no active transfers.
///
/// `transfer_approver` (same as [`ContractState::transfer_approver`]) must be set to the user,
/// or the governance system that should approve transfers.
///
/// `token_address` (same as [`ContractState::token_address`]) must be set to the token to use as
/// the underlying asset.
#[init(zk = true)]
pub fn initialize(
    _context: ContractContext,
    _zk_state: ZkState<VariableKind>,
    transfer_approver: Address,
    token_address: Address,
) -> ContractState {
    ContractState {
        balances: AvlTreeMap::new(),
        work_queue: VecDeque::new(),
        transfers_yet_to_be_approved: AvlTreeMap::new(),
        transfer_approver,
        token_address,
        redundant_variables: vec![],
        _ignored_variable_kind: None,
    }
}

/// Create a pending transfer from the transaction sender to a secret recipient with a secret amount.
///
/// Transfer must be approved by the approver by calling [`approve_transfer()`]. Once approved, the
/// transfer will be made through MPC, and be completed async.
#[zk_on_secret_input(shortname = 0x4A)]
pub fn request_transfer(
    _context: ContractContext,
    state: ContractState,
    _zk_state: ZkState<VariableKind>,
) -> (
    ContractState,
    Vec<EventGroup>,
    ZkInputDef<VariableKind, zk_compute::PendingTransferSecrets>,
) {
    let input_def = ZkInputDef::with_metadata(
        Some(transfer_request_inputted::SHORTNAME),
        VariableKind::WorkItemData {
            owner: state.transfer_approver,
        },
    );
    (state, vec![], input_def)
}

/// Automatically invoked when user has completed input of [`request_transfer`].
#[zk_on_variable_inputted(shortname = 0x47)]
pub fn transfer_request_inputted(
    _context: ContractContext,
    mut state: ContractState,
    zk_state: ZkState<VariableKind>,
    transfer_data_id: SecretVarId,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    let transfer = TransferData {
        sender: zk_state.get_variable(transfer_data_id).unwrap().owner,
        transfer_data_id,
    };

    let mut zk_state_change = vec![];
    state.transfer_variables_to_owner(&zk_state, vec![transfer_data_id], &mut zk_state_change);

    state
        .transfers_yet_to_be_approved
        .insert(transfer_data_id.raw_id, transfer);
    (state, vec![], zk_state_change)
}

/// Approve a previously requested transfer, and add it to the work queue.
///
/// Transfers must be created through [`request_transfer`] before they can be approved.
///
/// The transfer is added to the [`ContractState::work_queue`], and started if is the first in the
/// queue.
#[action(shortname = 0x4B, zk = true)]
pub fn approve_transfer(
    context: ContractContext,
    mut state: ContractState,
    zk_state: ZkState<VariableKind>,
    pending_request_id: PendingTransferId,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    assert!(
        context.sender == state.transfer_approver,
        "Approver is the only user that can approve transfers"
    );

    let transfer = match state.transfers_yet_to_be_approved.get(&pending_request_id) {
        Some(transfer) => transfer,
        None => panic!("Could not find a pending request with id {pending_request_id}"),
    };

    state
        .transfers_yet_to_be_approved
        .remove(&pending_request_id);

    let mut zk_state_change = vec![];
    let mut event_groups = vec![];
    state.schedule_new_work_item(
        &context,
        &zk_state,
        &mut zk_state_change,
        &mut event_groups,
        WorkListItem::PendingTransfer { transfer },
    );
    (state, event_groups, zk_state_change)
}

/// Create a new account for the transaction sender.
///
/// The account is required for:
///
/// - Depositing tokens
/// - Sending tokens
/// - Receiving tokens
/// - Withdrawing tokens
///
/// The account creation process involves the creation of a high-entropy `recipient_key`, which is
/// used by senders when they transfer to the created account. `recipient_key`s must be unique; if
/// the specified `recipient_key` is already in use, the key will be replaced with `0`.
///
/// Transaction sender can only have a single account.
#[zk_on_secret_input(shortname = 0x49)]
pub fn create_account(
    context: ContractContext,
    state: ContractState,
    _zk_state: ZkState<VariableKind>,
) -> (
    ContractState,
    Vec<EventGroup>,
    ZkInputDef<VariableKind, zk_compute::AccountCreationSecrets>,
) {
    let input_def = ZkInputDef::with_metadata(
        Some(create_account_inputted::SHORTNAME),
        VariableKind::WorkItemData {
            owner: context.sender,
        },
    );

    (state, vec![], input_def)
}

/// Automatically invoked when user has completed input of [`create_account`].
///
/// The create_account is added to the [`ContractState::work_queue`], and started if is the first in the
/// queue.
#[zk_on_variable_inputted(shortname = 0x42)]
pub fn create_account_inputted(
    context: ContractContext,
    mut state: ContractState,
    zk_state: ZkState<VariableKind>,
    account_creation_id: SecretVarId,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    let mut zk_state_change = vec![];
    let mut event_groups = vec![];
    state.schedule_new_work_item(
        &context,
        &zk_state,
        &mut zk_state_change,
        &mut event_groups,
        WorkListItem::PendingAccountCreation {
            account: zk_state.get_variable(account_creation_id).unwrap().owner,
            account_creation_id,
        },
    );
    (state, event_groups, zk_state_change)
}

/// Deposit token into the calling user's balance on the contract.
///
/// Requires that the swap contract has been approved at `token_address`
/// by the sender. This is checked in a callback, implicitly guaranteeing
/// that this only returns after the deposit transfer is complete.
#[action(shortname = 0x01, zk = true)]
pub fn deposit(
    context: ContractContext,
    state: ContractState,
    _zk_state: ZkState<VariableKind>,
    token_address: Address,
    amount: TokenAmount,
) -> (ContractState, Vec<EventGroup>) {
    state.assert_token_contract(token_address);
    assert!(
        state.has_account(&context.sender),
        "User does not possess an account: {}. Please create one before depositing!",
        context.sender
    );

    let mut event_group_builder = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(token_address).transfer_from(
        &mut event_group_builder,
        &context.sender,
        &context.contract_address,
        amount,
    );

    event_group_builder
        .with_callback_rpc(deposit_callback::rpc(context.sender, amount))
        .done();

    (state, vec![event_group_builder.build()])
}

/// Handles callback from [`deposit()`].
///
/// If the transfer event is successful,
/// the caller of [`deposit()`] is registered as a user of the contract with (additional) `amount` added to their balance.
#[callback(shortname = 0x10, zk = true)]
pub fn deposit_callback(
    context: ContractContext,
    callback_context: CallbackContext,
    mut state: ContractState,
    zk_state: ZkState<VariableKind>,
    account: Address,
    amount: TokenAmount,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    assert!(callback_context.success, "Transfer did not succeed");

    let mut zk_state_change = vec![];
    let mut event_groups = vec![];
    state.schedule_new_work_item(
        &context,
        &zk_state,
        &mut zk_state_change,
        &mut event_groups,
        WorkListItem::PendingDeposit { account, amount },
    );
    (state, event_groups, zk_state_change)
}

/// Triggered on the completion of the computation for either of [`WorkListItem::PendingTransfer`],
/// [`WorkListItem::PendingDeposit`] or [`WorkListItem::PendingAccountCreation`].
///
/// Transfers ownership of the output variables to the owners defined by [`VariableKind::owner()`].
#[zk_on_compute_complete(shortname = 0x52)]
pub fn simple_work_item_complete(
    context: ContractContext,
    mut state: ContractState,
    zk_state: ZkState<VariableKind>,
    output_variables: Vec<SecretVarId>,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    // Start next in queue
    let mut zk_state_change = vec![];

    // Move all variables to their expected owners
    state.transfer_variables_to_owner(&zk_state, output_variables, &mut zk_state_change);
    state.clean_up_redundant_secret_variables(&mut zk_state_change);

    // Trigger [`continue_queue`]
    let mut event_groups = vec![];
    trigger_continue_queue_if_needed(context, &state, &mut event_groups);

    (state, event_groups, zk_state_change)
}

/// Creates a new event for continue running the work queue.
///
/// It is not possible to run [`ContractState::attempt_to_start_next_in_queue`] in the same event as
/// [`ContractState::clean_up_redundant_secret_variables`], as the latter may remove secret
/// variables, while the first iterates all variables, and these cannot be done synchronized.
pub fn trigger_continue_queue_if_needed(
    context: ContractContext,
    state: &ContractState,
    event_groups: &mut Vec<EventGroup>,
) {
    if !state.work_queue.is_empty() {
        let mut event_group_builder = EventGroup::builder();
        event_group_builder
            .call(context.contract_address, Shortname::from_u32(0x09)) // Public invocation prefix
            .argument(0x10u8) // Shortname
            .done();
        event_groups.push(event_group_builder.build());
    }
}

/// Utility action for continuing the queue.
///
/// Mainly invoked by [`trigger_continue_queue_if_needed`], see that for more documentation.
#[action(shortname = 0x10, zk = true)]
pub fn continue_queue(
    context: ContractContext,
    mut state: ContractState,
    zk_state: ZkState<VariableKind>,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    assert!(
        context.sender == context.contract_address,
        "This is an internal invocation. Must not be invoked by outside users."
    );

    // Start next in queue
    let mut zk_state_change = vec![];
    let mut event_groups = vec![];
    state.attempt_to_start_next_in_queue(
        &context,
        &zk_state,
        &mut zk_state_change,
        &mut event_groups,
    );

    (state, event_groups, zk_state_change)
}

/// Withdraw `amount` of token from the contract for the calling user.
/// This fails if `amount` is larger than the token balance of the corresponding token.
///
/// `wait_for_callback` cannot be implemented with this contract, and is ignored.
#[action(shortname = 0x03, zk = true)]
pub fn withdraw(
    context: ContractContext,
    mut state: ContractState,
    zk_state: ZkState<VariableKind>,
    token_address: Address,
    amount: TokenAmount,
    _wait_for_callback: bool,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    state.assert_token_contract(token_address);

    let mut zk_state_change = vec![];
    let mut event_groups = vec![];
    state.schedule_new_work_item(
        &context,
        &zk_state,
        &mut zk_state_change,
        &mut event_groups,
        WorkListItem::PendingWithdraw {
            account: context.sender,
            amount,
        },
    );
    (state, event_groups, zk_state_change)
}

/// Triggered once a [`WorkListItem::PendingWithdraw`] is completed.
///
/// Will open the result variable to check that the withdraw succeeded.
///
/// Transfers ownership of the output variables to the owners defined by [`VariableKind::owner()`].
#[zk_on_compute_complete(shortname = 0x53)]
pub fn withdraw_complete(
    context: ContractContext,
    mut state: ContractState,
    zk_state: ZkState<VariableKind>,
    output_variables: Vec<SecretVarId>,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    let result_id: SecretVarId = *output_variables.get(1).unwrap();

    // Start next in queue
    let mut zk_state_change = vec![];
    let mut event_groups = vec![];

    // Move all variables to their expected owners
    state.transfer_variables_to_owner(&zk_state, output_variables, &mut zk_state_change);
    state.clean_up_redundant_secret_variables(&mut zk_state_change);
    trigger_continue_queue_if_needed(context, &state, &mut event_groups);

    zk_state_change.push(ZkStateChange::OpenVariables {
        variables: vec![result_id],
    });

    (state, event_groups, zk_state_change)
}

/// Will check the opened result to determine whether the withdraw succeeded or not.
///
/// Triggered by [`withdraw_complete()`].
#[zk_on_variables_opened]
pub fn withdraw_result_opened(
    context: ContractContext,
    state: ContractState,
    zk_state: ZkState<VariableKind>,
    opened_variables: Vec<SecretVarId>,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    // Determine result
    let result_id: SecretVarId = *opened_variables.first().unwrap();
    let result_variable = zk_state.get_variable(result_id).unwrap();
    let result = read_result(&result_variable);

    // Always remove result variable
    let zk_state_change = vec![ZkStateChange::DeleteVariables {
        variables_to_delete: vec![result_id],
    }];

    // Check that deposit with successful
    let mut event_groups = vec![];
    if !result.successful {
        fail_safely(
            &context,
            &mut event_groups,
            &format!(
                "Insufficient deposit balance! Could not withdraw {} tokens, as user do not have that amount deposited",
                result.amount
            ),
        );
    } else {
        let recipient = result_variable.owner;

        // Send transfer if user has enough tokens
        let mut event_group_builder = EventGroup::builder();
        interact_mpc20::MPC20Contract::at_address(state.token_address).transfer(
            &mut event_group_builder,
            &recipient,
            result.amount as u128,
        );
        event_groups.push(event_group_builder.build());
    }

    (state, event_groups, zk_state_change)
}

/// Reads a [`zk_compute::ComputationResultPub`] from the opened secret-shared data of the given
/// variable.
fn read_result(result_variable: &ZkClosed<VariableKind>) -> zk_compute::ComputationResultPub {
    let result_bytes: &Vec<u8> = result_variable.data.as_ref().unwrap();
    zk_compute::ComputationResultPub::secret_read_from(&mut result_bytes.as_slice())
}

/// Utility action to indicate failures, while allowing other systems to save.
///
/// It is intended for internal usage; it will instantly fail. There is no reason for a contract
/// user to call this invocation, and it cannot be exploited.
///
/// Called from [`fail_safely`]; see for more documentation.
#[action(shortname = 0x4C, zk = true)]
pub fn fail_in_separate_action(
    _context: ContractContext,
    _state: ContractState,
    _zk_state: ZkState<VariableKind>,
    error_message: String,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    panic!("{error_message}");
}

/// Indicates failure to the user by spawning a new failing event.
///
/// This is done to prevent stalling the queue, as individual [`WorkListItem`]s are that can fail, but it should
/// not be possible to make a denial of service attack by inputting a failing [`WorkListItem`].
pub fn fail_safely(
    context: &ContractContext,
    event_groups: &mut Vec<EventGroup>,
    error_message: &str,
) {
    let mut event_group_builder = EventGroup::builder();
    event_group_builder
        .call(context.contract_address, Shortname::from_u32(0x09)) // Public invocation prefix
        .argument(fail_in_separate_action::SHORTNAME) // Shortname
        .argument(String::from(error_message)) // Error message
        .done();

    event_groups.push(event_group_builder.build());
}
