#![doc = include_str!("../README.md")]

#[macro_use]
extern crate pbc_contract_codegen;

use crate::Status::{Accepted, Cancelled, Depositing, Done, Paying, Pending};
use create_type_spec_derive::CreateTypeSpec;
use defi_common::interact_mpc20;
use pbc_contract_common::address::Address;
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::EventGroup;
use read_write_state_derive::ReadWriteState;

/// Type used to represent token amounts.
pub type TokenAmount = interact_mpc20::TokenTransferAmount;
/// Upper bound for CPU usage in callback. Used to ensure that callback is executed successfully.
const CALLBACK_CPU_COST: u64 = 1000;

/// Possible states of the contract.
#[derive(ReadWriteState, CreateTypeSpec, PartialEq, Debug)]
#[repr(C)]
pub enum Status {
    /// Contract is awaiting the seller to enter the agreement
    #[discriminant(0)]
    Pending {},
    /// Tokens are being deposited from the seller to the contract
    #[discriminant(1)]
    Depositing {},
    /// The seller has entered into the agreement
    #[discriminant(2)]
    Accepted {},
    /// Payment is being transferred to seller
    #[discriminant(3)]
    Paying {},
    /// The purchase has been executed
    #[discriminant(4)]
    Done {},
    /// The purchase has been cancelled
    #[discriminant(5)]
    Cancelled {},
}

/// The timespan where the buyer are able to execute the purchase.
#[derive(ReadWriteState, CreateTypeSpec)]
pub struct ExecutionWindow {
    /// Start of the execution window in utc milliseconds
    pub start: i64,
    /// End of the execution window in utc milliseconds
    pub end: i64,
}

/// Information about the call option handled by this contract.
#[state]
pub struct State {
    /// Address of the MPC20 contract handling the token being sold
    pub sell_token: Address,
    /// Address of the MPC20 contract handling the payment token
    pub payment_token: Address,
    /// The buyer of the purchase
    pub buyer: Address,
    /// The seller of the purchase
    pub seller: Address,
    /// The amount of tokens being sold
    pub token_amount: TokenAmount,
    /// The amount of payment tokens to pay to execute the purchase
    pub agreed_payment: TokenAmount,
    /// Deadline for entering into the agreement in utc milliseconds
    pub deadline: i64,
    /// The execution window where the buyer are able to execute the purchase.
    pub execution_window: ExecutionWindow,
    /// Current status of the contract
    pub status: Status,
}

/// Initialize the call option.
///
/// Must be caller by the buyer of the call option.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `sell_token`: The MPC20 contract corresponding to the asset being sold
///  * `payment_token`: The MPC20 contract corresponding to the asset used to pay
///  * `seller`: The seller of tokens
///  * `token_amount`: The number of tokens that are being exchanged
///  * `agreed_payment`: The strike price of the call option
///  * `deadline`: The deadline by which the seller has to enter into the agreement
///  * `window_start`: Start of the timespan where the buyer can execute the call option. In utc milliseconds.
///  * `window_end`: The expiration of the call option. In utc milliseconds.
#[init]
#[allow(clippy::too_many_arguments)]
pub fn initialize(
    context: ContractContext,
    sell_token: Address,
    payment_token: Address,
    seller: Address,
    token_amount: TokenAmount,
    agreed_payment: TokenAmount,
    deadline: i64,
    window_start: i64,
    window_end: i64,
) -> State {
    assert!(
        deadline > context.block_production_time,
        "Deadline has to be in the future"
    );
    assert!(
        window_start > deadline,
        "Execution window must start after the deadline"
    );
    assert!(
        window_end > window_start,
        "Execution window cannot end before it starts"
    );

    State {
        buyer: context.sender,
        sell_token,
        payment_token,
        seller,
        token_amount,
        agreed_payment,
        deadline,
        execution_window: ExecutionWindow {
            start: window_start,
            end: window_end,
        },
        status: Pending {},
    }
}

/// Accept the call option by moving the tokens into escrow on the contract. Moves token from
/// seller into escrow.
///
/// Only callable by the seller.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
#[action(shortname = 0x01)]
pub fn enter_agreement(context: ContractContext, mut state: State) -> (State, Vec<EventGroup>) {
    assert_eq!(
        context.sender, state.seller,
        "Only the seller are allowed to enter into the agreement"
    );
    assert_eq!(
        state.status,
        Pending {},
        "The contract must be Pending for the agreemet to be accepted"
    );
    assert!(
        state.deadline > context.block_production_time,
        "Unable to enter into the agreement after the deadline"
    );

    let mut event_group_builder = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(state.sell_token).transfer_from(
        &mut event_group_builder,
        &context.sender,
        &context.contract_address,
        state.token_amount,
    );

    event_group_builder
        .with_callback_rpc(deposit_callback::rpc())
        .with_cost(CALLBACK_CPU_COST)
        .done();

    state.status = Depositing {};

    (state, vec![event_group_builder.build()])
}

/// Handle the result of transferring tokens to escrow. If the tokens were successfully transferred
/// to escrow the call option will be marked as Accepted.
///
/// # Parameters:
///
///  * `_context`: The contract context containing sender and chain information.
///  * `callback_context`: Callback context with execution result of the payment transaction
///  * `state`: The current state of the contract.
#[callback(shortname = 0x10)]
pub fn deposit_callback(
    _context: ContractContext,
    callback_context: CallbackContext,
    mut state: State,
) -> State {
    state.status = if callback_context.success {
        Accepted {}
    } else {
        Pending {}
    };

    state
}

/// Execute the previously accepted call option. Moved payment from buyer to seller and tokens from
/// escrow to the buyer.
///
/// Only callable by the buyer.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
#[action(shortname = 0x02)]
pub fn execute(context: ContractContext, mut state: State) -> (State, Vec<EventGroup>) {
    assert_eq!(
        context.sender, state.buyer,
        "Only the buyer are allowed to execute the agreement"
    );
    assert_eq!(
        state.status,
        Accepted {},
        "Only an accepted agreement can be executed"
    );
    assert!(
        state.execution_window.start <= context.block_production_time
            && state.execution_window.end >= context.block_production_time,
        "It is only possible to execute the agreement during the execution window"
    );

    let mut event_group_builder = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(state.payment_token).transfer_from(
        &mut event_group_builder,
        &context.sender,
        &state.seller,
        state.agreed_payment,
    );

    event_group_builder
        .with_callback_rpc(payment_callback::rpc())
        .with_cost(interact_mpc20::MPC20Contract::GAS_COST_TRANSFER + CALLBACK_CPU_COST)
        .done();

    state.status = Paying {};

    (state, vec![event_group_builder.build()])
}

/// Handle the result of transferring payment to the seller. If the payment was successful the
/// tokens in escrow will be transferred to the buyer.
///
/// # Parameters:
///
///  * `_context`: The contract context containing sender and chain information.
///  * `callback_context`: Callback context with execution result of the payment transaction
///  * `state`: The current state of the contract.
#[callback(shortname = 0x11)]
pub fn payment_callback(
    _context: ContractContext,
    callback_context: CallbackContext,
    mut state: State,
) -> (State, Vec<EventGroup>) {
    if callback_context.success {
        state.status = Done {};

        let mut event_group_builder = EventGroup::builder();
        interact_mpc20::MPC20Contract::at_address(state.sell_token).transfer(
            &mut event_group_builder,
            &state.buyer,
            state.token_amount,
        );

        (state, vec![event_group_builder.build()])
    } else {
        state.status = Accepted {};
        (state, vec![])
    }
}

/// Cancel the call option after it has expired. Returns the tokens from escrow to the sellers
/// account.
///
/// Only callable by the seller.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
#[action(shortname = 0x03)]
pub fn cancel(context: ContractContext, mut state: State) -> (State, Vec<EventGroup>) {
    assert_eq!(
        context.sender, state.seller,
        "Only the seller are allowed to cancel the agreement"
    );
    assert_eq!(
        state.status,
        Accepted {},
        "It is only possible to cancel an accepted agreement"
    );
    assert!(
        context.block_production_time > state.execution_window.end,
        "It is not possible to cancel the agreement prior to the execution window ending"
    );

    state.status = Cancelled {};

    let mut event_group_builder = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(state.sell_token).transfer(
        &mut event_group_builder,
        &state.seller,
        state.token_amount,
    );

    (state, vec![event_group_builder.build()])
}
