#![doc = include_str!("../README.md")]
#[macro_use]
extern crate pbc_contract_codegen;

use create_type_spec_derive::CreateTypeSpec;
use defi_common::{interact_mpc20, token_balances};
use pbc_contract_common::address::Address;
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::EventGroup;
use pbc_contract_common::zk::{CalculationStatus, SecretVarId, ZkInputDef, ZkState, ZkStateChange};
use read_write_rpc_derive::{ReadRPC, WriteRPC};
use read_write_state_derive::ReadWriteState;
use std::collections::VecDeque;
use token_balances::{Token, TokenAmount, TokenBalances};

pub mod zk_compute;

/// Contract state.
#[derive(Debug)]
#[repr(C)]
#[state]
pub struct ContractState {
    /// Address that owns the deposited tokens while these are in deposit.
    deposit_address: Address,
    /// Structure to keep track of token balances while these are deposited.
    token_balances: TokenBalances,
    /// Worklist of orders to execute in the future.
    order_worklist: VecDeque<SecretVarId>,
    /// Orders in state
    orders_stored: u32,
}

/// The maximum order matching size.
type MatchAmountType = u128;

/// Match output value, after executing `zk_compute::match_order`.
#[repr(C)]
#[derive(ReadWriteState, Debug)]
pub struct Match {
    /// [`SecreVarId`] of the token A buyer's [`zk_compute::Order`] variable.
    order_id_a_buyer: SecretVarId,
    /// [`SecreVarId`] of the token A seller's [`zk_compute::Order`] variable.
    order_id_a_seller: SecretVarId,
    /// Amount of token A to trade.
    amount_a: MatchAmountType,
    /// Amount of token B to trade.
    amount_b: MatchAmountType,
}

/// Match output value, sent between [`publicize_order_match`] and [`execute_order_match`].
#[repr(C)]
#[derive(CreateTypeSpec, ReadRPC, WriteRPC, Debug)]
pub struct ResolvedMatch {
    /// Address of the token A buyer.
    a_buyer: Address,
    /// Address of the token A seller.
    a_seller: Address,
    /// Amount of token A to trade.
    amount_a: MatchAmountType,
    /// Amount of token B to trade.
    amount_b: MatchAmountType,
}

impl ContractState {
    /// Executes the given order. Panics if either of the parties is missing tokens.
    fn execute_order(&mut self, order_match: &ResolvedMatch) {
        self.token_balances.move_tokens(
            order_match.a_seller,
            order_match.a_buyer,
            Token::A,
            order_match.amount_a,
        );
        self.token_balances.move_tokens(
            order_match.a_buyer,
            order_match.a_seller,
            Token::B,
            order_match.amount_b,
        );
    }
}

/// Initialize the contract.
///
/// # Parameters
///
///   * `context`: [`ContractContext`] - The contract context containing sender and chain information.
///
///   * `token_a_address`: [`Address`] - The address of token A.
///
///   * `token_b_address`: [`Address`] - The address of token B.
///
///
/// The new state object of type [`ContractState`] with all address fields initialized to their
/// final state and remaining fields initialized to a default value.
#[init(zk = true)]
pub fn initialize(
    context: ContractContext,
    _zk_state: ZkState<zk_compute::VarMetadata>,
    token_a_address: Address,
    token_b_address: Address,
) -> ContractState {
    let token_balances =
        TokenBalances::new(context.contract_address, token_a_address, token_b_address).unwrap();

    ContractState {
        deposit_address: context.contract_address,
        token_balances,
        order_worklist: VecDeque::new(),
        orders_stored: 0,
    }
}

/// Deposit token {A, B} into the calling user's balance on the contract.
///
/// ### Parameters:
///
///  * `context`: [`ContractContext`] - The contract context containing sender and chain information.
///
///  * `state`: [`ContractState`] - The current state of the contract.
///
///  * `token_address`: [`Address`] - The address of the deposited token contract.
///
///  * `amount`: [`TokenAmount`] - The amount to deposit.
///
/// # Returns
/// The unchanged state object of type [`ContractState`].
#[action(shortname = 0x01, zk = true)]
pub fn deposit(
    context: ContractContext,
    state: ContractState,
    _zk_state: ZkState<zk_compute::VarMetadata>,
    token_address: Address,
    amount: TokenAmount,
) -> (ContractState, Vec<EventGroup>) {
    let tokens = state.token_balances.deduce_tokens_in_out(token_address);

    let mut event_group_builder = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(token_address).transfer_from(
        &mut event_group_builder,
        &context.sender,
        &state.deposit_address,
        amount,
    );

    event_group_builder
        .with_callback(SHORTNAME_DEPOSIT_CALLBACK)
        .argument(tokens.token_in)
        .argument(amount)
        .done();

    (state, vec![event_group_builder.build()])
}

/// Handles callback from [`deposit`]. <br>
/// If the transfer event is successful,
/// the caller of [`deposit`] is registered as a user of the contract with (additional) `amount` added to their balance.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`] - The contractContext for the callback.
///
/// * `callback_context`: [`CallbackContext`] - The callbackContext.
///
/// * `state`: [`ContractState`] - The current state of the contract.
///
/// * `token`: [`Token`] - Indicating the token of which to add `amount` to.
///
/// * `amount`: [`TokenAmount`] - The desired amount to add to the user's total amount of `token`.
/// ### Returns
///
/// The updated state object of type [`ContractState`] with an updated entry for the caller of `deposit`.
#[callback(shortname = 0x10, zk = true)]
pub fn deposit_callback(
    context: ContractContext,
    callback_context: CallbackContext,
    mut state: ContractState,
    _zk_state: ZkState<zk_compute::VarMetadata>,
    token: Token,
    amount: TokenAmount,
) -> (ContractState, Vec<EventGroup>) {
    assert!(callback_context.success, "Transfer did not succeed");

    state
        .token_balances
        .add_to_token_balance(context.sender, token, amount);

    (state, vec![])
}

/// Places an [`zk_compute::Order`] with the contract.
#[zk_on_secret_input(shortname = 0x13, secret_type = "zk_compute::Order")]
pub fn place_order(
    context: ContractContext,
    state: ContractState,
    _zk_state: ZkState<zk_compute::VarMetadata>,
) -> (
    ContractState,
    Vec<EventGroup>,
    ZkInputDef<zk_compute::VarMetadata, zk_compute::Order>,
) {
    // Check that sender have non-zero token_balances. The placed_order would fail anyway if they don't have
    // anything to placed_order with.
    let balance = state.token_balances.get_balance_for(&context.sender);
    assert!(
        !balance.user_has_no_tokens(),
        "token_balances are both zero; nothing to placed_order with."
    );

    let input_def = ZkInputDef::with_metadata(zk_compute::VarMetadata {
        variable_type: zk_compute::VARIABLE_TYPE_ORDER,
        deposit_amount_a: balance.a_tokens,
        deposit_amount_b: balance.b_tokens,
    });
    (state, vec![], input_def)
}

/// Triggers the next order in the queue.
pub fn start_next_order_in_queue(
    state: &mut ContractState,
    zk_state: &ZkState<zk_compute::VarMetadata>,
    newly_resolved_orders: &[SecretVarId],
) -> Vec<ZkStateChange> {
    // Possibly already working on something.
    if zk_state.calculation_state != CalculationStatus::Waiting {
        return vec![];
    }

    // Determine the next order to work on.
    // Ignores resolved orders.
    let mut next_variable_id = None;
    while !state.order_worklist.is_empty() {
        let variable_id = state.order_worklist.pop_front();
        if zk_state.get_variable(variable_id.unwrap()).is_some()
            && !newly_resolved_orders.contains(&variable_id.unwrap())
        {
            next_variable_id = variable_id;
            break;
        }
    }

    match (next_variable_id, state.orders_stored) {
        (None, _) => vec![],
        (_, 1) => vec![],
        (Some(next_variable_id), _) => {
            vec![zk_compute::match_order_start(
                next_variable_id,
                &zk_compute::VarMetadata {
                    variable_type: zk_compute::VARIABLE_TYPE_MATCH,
                    deposit_amount_a: 0,
                    deposit_amount_b: 0,
                },
            )]
        }
    }
}

/// Automatic callback for when [`place_order`] is finalized.
#[zk_on_variable_inputted]
pub fn order_variable_inputted(
    _context: ContractContext,
    mut state: ContractState,
    zk_state: ZkState<zk_compute::VarMetadata>,
    variable_id: SecretVarId,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    state.order_worklist.push_back(variable_id);
    state.orders_stored += 1;
    let zk_events = start_next_order_in_queue(&mut state, &zk_state, &[]);
    (state, vec![], zk_events)
}

/// Automatic callback for when [`start_next_order_in_queue`] is complete.
#[zk_on_compute_complete]
pub fn order_match_complete(
    _context: ContractContext,
    state: ContractState,
    _zk_state: ZkState<zk_compute::VarMetadata>,
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

/// Opens the [`Match`] variable. Might result in an order match, or it might not. In both cases it
/// will start the next order in queue.
///
/// If an order have been matched, it will trigger [`execute_order_match`].
///
/// Determining and execution of the match have been split to enforce atomicity for each.
/// [`publicize_order_match`] must _never_ panic, as it will stop the automatic queue. The queue
/// can at least be restarted by inputting a new value. [`execute_order_match`] _must_ throw if
/// either of the transfers fails.
#[zk_on_variables_opened]
pub fn publicize_order_match(
    _context: ContractContext,
    mut state: ContractState,
    zk_state: ZkState<zk_compute::VarMetadata>,
    opened_result_variables: Vec<SecretVarId>,
) -> (ContractState, Vec<EventGroup>, Vec<ZkStateChange>) {
    // Check whether a match was found.
    let order_match = read_match(&zk_state, &opened_result_variables[0]);

    let mut variables_to_delete = opened_result_variables;
    if let Some(order_match) = order_match {
        // Queue removal of matched variables
        variables_to_delete.push(order_match.order_id_a_buyer);
        variables_to_delete.push(order_match.order_id_a_seller);

        let a_buyer = zk_state
            .get_variable(order_match.order_id_a_buyer)
            .map(|x| x.owner);
        let a_seller = zk_state
            .get_variable(order_match.order_id_a_seller)
            .map(|x| x.owner);

        // Trigger order match
        if let (Some(a_buyer), Some(a_seller)) = (a_buyer, a_seller) {
            let resolved_match = ResolvedMatch {
                a_buyer,
                a_seller,
                amount_a: order_match.amount_a,
                amount_b: order_match.amount_b,
            };
            state.execute_order(&resolved_match);
            state.orders_stored -= 2;
        }
    };

    // Continue queue
    let mut zk_events = start_next_order_in_queue(&mut state, &zk_state, &variables_to_delete);

    // Delete old variables
    zk_events.insert(
        0,
        ZkStateChange::DeleteVariables {
            variables_to_delete,
        },
    );

    (state, vec![], zk_events)
}

/// Withdraw <em>amount</em> of token {A, B} from the contract for the calling user.
/// This fails if `amount` is larger than the token balance of the corresponding token.
///
/// It preemptively updates the state of the user's balance before making the transfer.
/// This means that if the transfer fails, the contract could end up with more money than it has registered, which is acceptable.
/// This is to incentivize the user to spend enough gas to complete the transfer.
///
/// ### Parameters:
///
///  * `context`: [`ContractContext`] - The contract context containing sender and chain information.
///
///  * `state`: [`ContractState`] - The current state of the contract.
///
///  * `token_address`: [`Address`] - The address of the token contract to withdraw to.
///
///  * `amount`: [`TokenAmount`] - The amount to withdraw.
///
/// # Returns
/// The unchanged state object of type [`ContractState`].
#[action(shortname = 0x03, zk = true)]
pub fn withdraw(
    context: ContractContext,
    mut state: ContractState,
    _zk_state: ZkState<zk_compute::VarMetadata>,
    token_address: Address,
    amount: TokenAmount,
    wait_for_callback: bool,
) -> (ContractState, Vec<EventGroup>) {
    let tokens = state.token_balances.deduce_tokens_in_out(token_address);

    state
        .token_balances
        .deduct_from_token_balance(context.sender, tokens.token_in, amount);

    let mut event_group_builder = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(token_address).transfer(
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
    _zk_state: ZkState<zk_compute::VarMetadata>,
) -> ContractState {
    state
}

/// Reads a variable's data as [`Match`].
fn read_match(
    zk_state: &ZkState<zk_compute::VarMetadata>,
    match_variable_id: &SecretVarId,
) -> Option<Match> {
    zk_state
        .get_variable(*match_variable_id)
        .and_then(|v| v.open_value::<Match>())
        .filter(|o| o.order_id_a_buyer != o.order_id_a_seller)
}
