#![doc = include_str!("../README.md")]

#[macro_use]
extern crate pbc_contract_codegen;

use create_type_spec_derive::CreateTypeSpec;
use defi_common::interact_mpc20::MPC20Contract;
use pbc_contract_common::address::Address;
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::EventGroup;
use pbc_contract_common::shortname::Shortname;
use pbc_contract_common::zk::{SecretVarId, ZkInputDef, ZkState, ZkStateChange};
use pbc_zk::{Sbi128, Sbi8, SecretBinary};
use read_write_rpc_derive::{ReadRPC, WriteRPC};
use read_write_state_derive::ReadWriteState;
use std::collections::VecDeque;

use defi_common::math::u128_division_ceil;
use defi_common::token_balances::{Token, TokenAmount, TokenBalance, TokenBalances, TokensInOut};

/**
 * Metadata information associated with each individual variable.
 */
#[derive(ReadWriteState, Debug)]
pub struct SecretVarMetadata {
    /// Used to distinguish between input variables and output variables.
    is_output_variable: bool,
    /// If an swap input variable is marked with this, it means that the swap should only be
    /// performed if the swap is the first element of the worklist queue.
    only_if_at_front: bool,
}

/// This is the state of the contract which is persisted on the chain.
///
/// The #\[state\] macro generates serialization logic for the struct.
#[derive(Debug)]
#[state]
pub struct ContractState {
    /// The owner of the contract.
    pub contract_owner: Address,
    /// Address to use as the token pool entry in balances.
    pub liquidity_pool_address: Address,
    /// The invariant used to calculate exchange rates.
    /// It's based on the 'constant product formula': x * y = k, k being the swap_constant.
    pub swap_constant: u128,
    /// User balances.
    pub token_balances: TokenBalances,
    /// Worklist queue containing swaps that have yet to be performed.
    pub worklist: VecDeque<WorklistEntry>,
}

/// An entry in the worklist, including the id of the variable containing the swap information, and
/// the address of the sender of the variable.
#[derive(Debug, ReadWriteState, CreateTypeSpec, Clone, PartialEq, Eq)]
pub struct WorklistEntry {
    /// Variable containing the swap amount and direction.
    variable_id: SecretVarId,
    /// Who sent the swap.
    sender: Address,
}

impl ContractState {
    /// Retrieves a copy of the pool that matches `token`.
    fn get_pools(&self) -> TokenBalance {
        self.token_balances
            .get_balance_for(&self.liquidity_pool_address)
    }

    /// Checks for common invariants.
    fn assert_invariants(&self) {
        let pools = self.get_pools();
        assert!(
            pools.get_amount_of(Token::A) * pools.get_amount_of(Token::B) >= self.swap_constant
        );
    }

    /// Checks that the pools of the contracts have liquidity.
    ///
    /// ### Parameters:
    ///
    ///  * `state`: [`ContractState`] - A reference to the current state of the contract.
    ///
    /// ### Returns:
    /// True if the pools have liquidity, false otherwise [`bool`]
    fn contract_pools_have_liquidity(&self) -> bool {
        let contract_token_balance = self
            .token_balances
            .get_balance_for(&self.liquidity_pool_address);
        contract_token_balance.a_tokens != 0 && contract_token_balance.b_tokens != 0
    }
}

/// Initialize the contract.
///
/// ### Parameters
///
///   * `token_a_address`: The address of token A.
///
///   * `token_b_address`: The address of token B.
///
/// ### Returns
///
/// The new state object of type [`ContractState`] with all address fields initialized to their final state and remaining fields initialized to a default value.
///
#[init(zk = true)]
pub fn initialize(
    context: ContractContext,
    _zk_state: ZkState<SecretVarMetadata>,
    token_a_address: Address,
    token_b_address: Address,
    swap_fee_per_mille: u16,
) -> (ContractState, Vec<EventGroup>) {
    assert_eq!(swap_fee_per_mille, 0, "Non-zero swap fee not implemented");
    let liquidity_pool_address = context.contract_address;
    let token_balances =
        TokenBalances::new(liquidity_pool_address, token_a_address, token_b_address).unwrap();

    let new_state = ContractState {
        liquidity_pool_address,
        contract_owner: context.sender,
        swap_constant: 0,
        token_balances,
        worklist: VecDeque::new(),
    };

    (new_state, vec![])
}

/// Initialize pool {a, b} of the contract.
/// This can only be done by the contract owner and the contract has to be in its closed state.
///
/// ### Parameters:
///
///  * `token_address`: The address of the token {a, b}.
///
///  * `pool_size`: The desired size of token pool {a, b}.
///
/// # Returns
/// The unchanged state object of type [`ContractState`].
#[action(shortname = 0x06, zk = true)]
pub fn provide_initial_liquidity(
    context: ContractContext,
    mut state: ContractState,
    _zk_state: ZkState<SecretVarMetadata>,
    token_a_amount: TokenAmount,
    token_b_amount: TokenAmount,
) -> (ContractState, Vec<EventGroup>) {
    assert_eq!(
        context.sender, state.contract_owner,
        "Only the contract owner can initialize contract pools"
    );
    assert!(
        !state.contract_pools_have_liquidity(),
        "Can only initialize when both pools are empty"
    );

    provide_liquidity_internal(
        &mut state,
        &context.sender,
        TokensInOut::A_IN_B_OUT,
        token_a_amount,
        token_b_amount,
    );

    assert!(
        state.contract_pools_have_liquidity(),
        "Contract pools should have been initialized after calling provide_initial_liquidity."
    );

    (state, vec![])
}

/// Moves tokens from the providing user's balance to the contract's and mints liquidity tokens.
///
/// ### Parameters:
///
///  * `state`: [`ContractState`] - The current state of the contract.
///
/// * `user`: [`Address`] - The address of the user providing liquidity.
///
/// * `token_in`: [`Address`] - The address of the token being token_in.
///
///  * `token_in_amount`: [`TokenAmount`] - The input token amount.
///
///  * `token_out_amount`: [`TokenAmount`] - The output token amount. Must be equal value to `token_in_amount` at the current exchange rate.
fn provide_liquidity_internal(
    state: &mut ContractState,
    user: &Address,
    tokens: TokensInOut,
    token_in_amount: TokenAmount,
    token_out_amount: TokenAmount,
) {
    state.token_balances.move_tokens(
        *user,
        state.liquidity_pool_address,
        tokens.token_in,
        token_in_amount,
    );
    state.token_balances.move_tokens(
        *user,
        state.liquidity_pool_address,
        tokens.token_out,
        token_out_amount,
    );

    // Set the swap constant.
    state.swap_constant = token_in_amount.checked_mul(token_out_amount).unwrap();
}

/// Deposit token A or B into the calling users balance on the contract.
/// If the contract is closed, the action fails.
///
/// Requires that the swap contract has been approved at `token_address`
/// by the sender. This is checked in a callback, implicitly guaranteeing
/// that this only returns after the deposit transfer is complete.
///
/// ### Parameters:
///
///  * `token_address`: The address of the deposited token contract.
///
///  * `amount`: The amount to deposit.
///
/// # Returns
/// The unchanged state object of type [`ContractState`].
#[action(shortname = 0x01, zk = true)]
pub fn deposit(
    context: ContractContext,
    state: ContractState,
    _zk_state: ZkState<SecretVarMetadata>,
    token_address: Address,
    amount: TokenAmount,
) -> (ContractState, Vec<EventGroup>) {
    let from_token = state
        .token_balances
        .deduce_tokens_in_out(token_address)
        .token_in;
    let mut event_group_builder = EventGroup::builder();

    MPC20Contract::at_address(token_address).transfer_from(
        &mut event_group_builder,
        &context.sender,
        &state.liquidity_pool_address,
        amount,
    );

    event_group_builder
        .with_callback(SHORTNAME_DEPOSIT_CALLBACK)
        .argument(from_token)
        .argument(amount)
        .done();

    (state, vec![event_group_builder.build()])
}

/// Handles callback from `deposit`.
/// If the transfer event is successful the caller of `deposit` is added to the `state.token_balances`
/// adding `amount` to the `token` pool balance.
///
/// ### Parameters:
///
/// * `token`: Indicating the token pool balance of which to add `amount` to.
///
/// * `amount`: The desired amount to add to `token_type` pool balance.
///
/// ### Returns
///
/// The updated state object of type [`ContractState`] with an updated entry for the caller of `deposit`.
#[callback(shortname = 0x02, zk = true)]
pub fn deposit_callback(
    context: ContractContext,
    callback_context: CallbackContext,
    mut state: ContractState,
    _zk_state: ZkState<SecretVarMetadata>,
    token: Token,
    amount: TokenAmount,
) -> (ContractState, Vec<EventGroup>) {
    assert!(callback_context.success, "Transfer did not succeed");
    state
        .token_balances
        .add_to_token_balance(context.sender, token, amount);
    (state, vec![])
}

/// Swap `amount` of token A or B to the opposite token at the exchange rate dictated by `the constant product formula`.
/// The swap is executed on the user balances of tokens for the calling user.
/// If the contract is closed or if the caller does not have a sufficient balance of the token, the action fails.
///
/// ### Parameters:
///
///  * `only_if_at_front`: If true, the swap will only be performed if the swap variable
///  is the first in the worklist queue. This feature can be used to prevent frontrunning between
///  the time when this invocation is called, and when the swap variable is fully input.
///
///  * `amount` (ZK): The amount to swap of the token matching `input_token`.
///
/// # Returns
/// The updated state object of type [`ContractState`] yielding the result of the swap.
#[zk_on_secret_input(shortname = 0x02, secret_type = "SecretAmountAndDirection")]
pub fn swap(
    context: ContractContext,
    state: ContractState,
    _zk_state: ZkState<SecretVarMetadata>,
    only_if_at_front: bool,
) -> (
    ContractState,
    Vec<EventGroup>,
    ZkInputDef<SecretVarMetadata, SecretAmountAndDirection>,
) {
    assert!(
        state.contract_pools_have_liquidity(),
        "The contract is closed"
    );

    // Check that sender have non-zero balances. The swap would fail anyway if they don't have
    // anything to swap with.
    let balance = state.token_balances.get_balance_for(&context.sender);
    assert!(
        !balance.user_has_no_tokens(),
        "Balances are both zero; nothing to swap with."
    );

    let input_def = ZkInputDef::with_metadata(SecretVarMetadata {
        only_if_at_front,
        is_output_variable: false,
    });
    (state, vec![], input_def)
}

fn start_next_in_queue(state: &ContractState, zk_events: &mut Vec<ZkStateChange>) {
    if let Some(entry) = state.worklist.front() {
        zk_events.push(ZkStateChange::OpenVariables {
            variables: vec![entry.variable_id],
        })
    }
}

/// Automatic callback for when some previously announced user variable is fully input.
///
/// Will create a new worklist entry, and possibly start computation, if no previous computation is
/// active.
#[zk_on_variable_inputted]
pub fn swap_variable_inputted(
    _context: ContractContext,
    mut state: ContractState,
    zk_state: ZkState<SecretVarMetadata>,
    variable_id: SecretVarId,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    let secret_var_info = zk_state.get_variable(variable_id).unwrap();
    let only_if_at_front = secret_var_info.metadata.only_if_at_front;

    // Swap already in progress; let's wait for it to finish
    let mut zk_events = Vec::new();
    let is_at_the_front_of_the_queue = state.worklist.is_empty();
    if only_if_at_front && !is_at_the_front_of_the_queue {
        zk_events.push(ZkStateChange::DeleteVariables {
            variables_to_delete: vec![variable_id],
        });
    } else {
        let worklist_entry = WorklistEntry {
            variable_id,
            sender: secret_var_info.owner,
        };

        state.worklist.push_back(worklist_entry);
        if is_at_the_front_of_the_queue {
            // Swap not in progress, push and start
            start_next_in_queue(&state, &mut zk_events);
        }
    }

    (state, vec![], zk_events)
}

/// Will immediately open the result of the computation.
#[zk_on_compute_complete]
pub fn computation_complete(
    _context: ContractContext,
    state: ContractState,
    _zk_state: ZkState<SecretVarMetadata>,
    output_variables: Vec<SecretVarId>,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    (
        state,
        vec![],
        vec![ZkStateChange::OpenVariables {
            variables: output_variables,
        }],
    )
}

/// Called when the swap result is available.
///
/// Will:
///
/// 1. Trigger [`execute_swap`]. This is triggered in a separate event in order to provide
///    a separate atomic context for making swaps and failing.
/// 2. Start the next swap in the [`ContractState::worklist`], if any are present. Should always
///    happen, even if the swap turns out to be bad.
/// 3. Remove unused variables.
#[zk_on_variables_opened]
pub fn swap_opened(
    context: ContractContext,
    mut state: ContractState,
    zk_state: ZkState<SecretVarMetadata>,
    opened_result_variables: Vec<SecretVarId>,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    // Invariant checking
    assert_eq!(
        opened_result_variables.len(),
        1,
        "Should only be possible to open current swap variable"
    );

    let worklist_entry_processed = state.worklist.pop_front().unwrap();

    // Read the opened swap
    let swap = {
        let amount_and_direction: AmountAndDirection = zk_state
            .get_variable(opened_result_variables[0])
            .and_then(|v| v.open_value())
            .unwrap();

        //
        let TokensInOut {
            token_in,
            token_out,
            ..
        } = state
            .token_balances
            .deduce_tokens_in_out_b(amount_and_direction.is_from_a);

        // Create swap
        Swap {
            sender: worklist_entry_processed.sender,
            token_in,
            token_out,
            amount_in: amount_and_direction.amount,
        }
    };

    // Call execute_swap
    let mut event_group_builder = EventGroup::builder();
    event_group_builder
        .call(context.contract_address, SHORTNAME_ZK_PUBLIC_INVOCATION)
        .argument(SHORTNAME_EXECUTE_SWAP)
        .argument(swap)
        .done();

    // Start next in worklist
    let mut variables_to_delete = opened_result_variables;
    variables_to_delete.push(worklist_entry_processed.variable_id);
    let mut zk_events = vec![ZkStateChange::DeleteVariables {
        variables_to_delete,
    }];
    start_next_in_queue(&state, &mut zk_events);

    // Delete old variables
    (state, vec![event_group_builder.build()], zk_events)
}

/// Records the publicized swap.
#[derive(WriteRPC, ReadRPC, CreateTypeSpec)]
pub struct Swap {
    /// Sender of the swap.
    sender: Address,
    /// Token to input
    token_in: Token,
    /// Token to output
    token_out: Token,
    /// Amount of [`Swap::token_in`] tokens to input.
    amount_in: TokenSwapAmount,
}

/**
 * Shortname to call public invocations of a ZK contract.
 */
const SHORTNAME_ZK_PUBLIC_INVOCATION: Shortname = Shortname::from_u32(0x09);

/** Shortname of the [`execute_swap`] invocation. Must be called using
 * [`SHORTNAME_ZK_PUBLIC_INVOCATION`].
 */
const SHORTNAME_EXECUTE_SWAP: Shortname = Shortname::from_u32(0x20);

/// The executor of [`Swap`]s. Can only be called by the contract itself.
#[action(shortname = 0x20, zk = true)]
pub fn execute_swap(
    context: ContractContext,
    mut state: ContractState,
    _zk_state: ZkState<SecretVarMetadata>,
    swap_info: Swap,
) -> ContractState {
    assert_eq!(context.sender, context.contract_address);
    assert!(
        state.contract_pools_have_liquidity(),
        "The contract is closed"
    );

    let liquidity_pools = state.get_pools();
    let amount_out = calculate_swap_to_amount(
        liquidity_pools.get_amount_of(swap_info.token_in),
        liquidity_pools.get_amount_of(swap_info.token_out),
        state.swap_constant,
        swap_info.amount_in,
    )
    .unwrap();

    state.token_balances.move_tokens(
        swap_info.sender,
        state.liquidity_pool_address,
        swap_info.token_in,
        swap_info.amount_in,
    );
    state.token_balances.move_tokens(
        state.liquidity_pool_address,
        swap_info.sender,
        swap_info.token_out,
        amount_out,
    );

    state.assert_invariants();

    state
}

/// Computes how many `token_out` tokens should be given for the having swapped in the given amount
/// of `token_in` tokens.
pub fn calculate_swap_to_amount(
    token_in_liquidity_pool: TokenSwapAmount,
    token_out_liquidity_pool: TokenSwapAmount,
    swap_constant: TokenSwapAmount,
    amount_in: TokenSwapAmount,
) -> Result<TokenSwapAmount, &'static str> {
    let token_in_pool_updated = token_in_liquidity_pool
        .checked_add(amount_in)
        .ok_or("Overflow in token pool")?;
    let token_out_pool_updated = u128_division_ceil(swap_constant, token_in_pool_updated)?;

    token_out_liquidity_pool
        .checked_sub(token_out_pool_updated)
        .ok_or("Underflow in token pool")
}

/// Withdraw `amount` of token A or B from the contract for the calling user.
/// This fails if `amount` is larger than the user balance of the corresponding token.
///
/// It preemptively updates the state of the user's balance before making the transfer.
/// This means that if the transfer fails, the contract could end up with more money than it has registered, which is acceptable.
/// This is to incentivize the user to spend enough gas to complete the transfer.
/// If `wait_for_callback` is true, any callbacks will happen only after the withdrawal has completed.
///
/// ### Parameters:
///
///  * `token_address`: The address of the token contract to withdraw to.
///
///  * `amount`: The amount to withdraw.
///
/// # Returns
/// The unchanged state object of type [`ContractState`].
#[action(shortname = 0x03, zk = true)]
pub fn withdraw(
    context: ContractContext,
    mut state: ContractState,
    _zk_state: ZkState<SecretVarMetadata>,
    token_address: Address,
    amount: TokenAmount,
    wait_for_callback: bool,
) -> (ContractState, Vec<EventGroup>) {
    let token_in = state
        .token_balances
        .deduce_tokens_in_out(token_address)
        .token_in;

    state
        .token_balances
        .deduct_from_token_balance(context.sender, token_in, amount);

    let mut event_group_builder = EventGroup::builder();

    MPC20Contract::at_address(token_address).transfer(
        &mut event_group_builder,
        &context.sender,
        amount,
    );

    if wait_for_callback {
        event_group_builder
            .with_callback(SHORTNAME_WAIT_WITHDRAW_CALLBACK)
            .done();
    }

    (state, vec![event_group_builder.build()])
}

#[callback(shortname = 0x15, zk = true)]
fn wait_withdraw_callback(
    _context: ContractContext,
    _callback_context: CallbackContext,
    state: ContractState,
    _zk_state: ZkState<SecretVarMetadata>,
) -> (ContractState, Vec<EventGroup>) {
    (state, vec![])
}

/// Empties the pools into the contract owner's balance and closes the contract.
/// Fails if called by anyone but the contract owner.
///
/// ### Returns
///
/// The updated state object of type [`ContractState`].
#[action(shortname = 0x05, zk = true)]
pub fn close_pools(
    context: ContractContext,
    mut state: ContractState,
    _zk_state: ZkState<SecretVarMetadata>,
) -> (ContractState, Vec<EventGroup>) {
    assert_eq!(
        context.sender, state.contract_owner,
        "Only the contract owner can close the pools"
    );
    assert!(
        state.contract_pools_have_liquidity(),
        "The contract is closed"
    );

    let liquidity_pools = state.get_pools().clone();
    state.token_balances.move_tokens(
        state.liquidity_pool_address,
        state.contract_owner,
        Token::A,
        liquidity_pools.get_amount_of(Token::A),
    );
    state.token_balances.move_tokens(
        state.liquidity_pool_address,
        state.contract_owner,
        Token::B,
        liquidity_pools.get_amount_of(Token::B),
    );

    // Assert correctly closed
    let liquidity_pools = state.get_pools();
    assert_eq!(liquidity_pools.get_amount_of(Token::A), 0);
    assert_eq!(liquidity_pools.get_amount_of(Token::B), 0);

    (state, vec![])
}

/// * HELPER FUNCTIONS *

/// Type used for swap amounts.
///
/// Currently a much smaller type than `TokenAmount`, due to limitations in zk-computations.
pub type TokenSwapAmount = u128;

/// Public version of `AmountAndDirection`.
#[derive(ReadWriteState)]
#[repr(C)]
pub struct AmountAndDirection {
    /// Amount of tokens to swap
    pub amount: TokenSwapAmount,
    /// Whether to swap from or to a.
    pub is_from_a: bool,
}

/// Public version of `SecretAmountAndDirection`.
#[derive(CreateTypeSpec, SecretBinary)]
#[allow(dead_code)]
pub struct SecretAmountAndDirection {
    /// Token amount.
    amount: Sbi128,
    /// The direction of the token swap. Only the lowest bit is used.
    direction: Sbi8,
}
