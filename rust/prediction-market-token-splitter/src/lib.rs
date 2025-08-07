#![doc = include_str!("../README.md")]

#[macro_use]
extern crate pbc_contract_codegen;

use create_type_spec_derive::CreateTypeSpec;
use defi_common::interact_mpc20;
use defi_common::token_balances::{DepositToken, TokenBalances};
use pbc_contract_common::address::Address;
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::EventGroup;
use read_write_rpc_derive::ReadWriteRPC;
use read_write_state_derive::ReadWriteState;

/// An amount of tokens that can be transferred.
pub type TokenAmount = u128;

const TRUE_TOKEN: DepositToken = DepositToken::TokenA {};
const FALSE_TOKEN: DepositToken = DepositToken::TokenB {};
const ORIGINAL_TOKEN: DepositToken = DepositToken::LiquidityToken {};

fn token_from_address(state: &TokenSplitterContractState, address: Address) -> DepositToken {
    if address == state.true_token_address {
        TRUE_TOKEN
    } else if address == state.false_token_address {
        FALSE_TOKEN
    } else if address == state.original_token_address {
        ORIGINAL_TOKEN
    } else {
        panic!(
            "Unknown token {}. Contract only supports {}, {} or {}",
            address,
            state.true_token_address,
            state.false_token_address,
            state.original_token_address
        )
    }
}

/// The life stage of the event.
#[derive(PartialEq, Debug, ReadWriteState, ReadWriteRPC, CreateTypeSpec)]
pub enum LifeStage {
    /// The token splitter has not been activated yet.
    #[discriminant(0)]
    PREPARING {},
    /// The token splitter has been activated, and tokens can be split or joined.
    #[discriminant(1)]
    ACTIVE {},
    /// The outcome of the event has been settled as `outcome`, and tokens can be redeemed based on the outcome.
    #[discriminant(2)]
    SETTLED {
        /// Whether the true or false case of the event has happened.
        outcome: bool,
    },
}

/// The state of the contract.
#[state]
pub struct TokenSplitterContractState {
    /// The description of the event, which the contract is based on.
    pub event_description: String,
    /// The symbol of the event.
    pub event_symbol: String,
    /// The address of the token contract being split.
    pub original_token_address: Address,
    /// The address of the token contract being used for the true token.
    pub true_token_address: Address,
    /// The address of the token contract being used for the false token.
    pub false_token_address: Address,
    /// The arbitrator that settles the outcome of the event.
    pub arbitrator_address: Address,
    /// The life stage of the token splitter.
    pub life_stage: LifeStage,
    /// The balances of original tokens, true tokens and false tokens of all users.
    pub token_balances: TokenBalances,
}

/// Initialize the token splitter contract.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `event_description` The description of the event.
/// * `event_symbol` The event symbol.
/// * `original_token_address` The address of the original token contract.
/// * `true_token_address` The address of the true token contract.
/// * `false_token_address` The address of the false token contract.
/// * `arbitrator_address` The address of the arbitrator, who will settle the event.
///
/// Returns:
///
/// The initial state `TokenSplitterContractState` of the contract.
#[init]
pub fn initialize(
    _context: ContractContext,
    event_description: String,
    event_symbol: String,
    original_token_address: Address,
    true_token_address: Address,
    false_token_address: Address,
    arbitrator_address: Address,
) -> TokenSplitterContractState {
    let token_balances = TokenBalances::new(
        original_token_address,
        true_token_address,
        false_token_address,
    )
    .expect("Invalid choice of true and false token contracts.");

    TokenSplitterContractState {
        event_description,
        event_symbol,
        original_token_address,
        true_token_address,
        false_token_address,
        arbitrator_address,
        life_stage: LifeStage::PREPARING {},
        token_balances,
    }
}

/// Deposit some tokens from the caller onto the token balances.
/// Only either the original token, true token or false token used for this contract can be deposited.
/// This action transfers the tokens from the caller to this contract, and then creates a
/// callback to `deposit_callback` which adds the tokens to the callers balance.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `state` The state of the contract.
/// * `token_address` The address of the token contract to be deposited.
/// * `amount` The amount to be deposited.
///
/// Returns:
///
/// The state of the contract, and the event group used for creating the callback.
#[action(shortname = 0x01)]
pub fn deposit(
    context: ContractContext,
    state: TokenSplitterContractState,
    token_address: Address,
    amount: TokenAmount,
) -> (TokenSplitterContractState, Vec<EventGroup>) {
    let token = token_from_address(&state, token_address);

    let mut event_group_builder = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(token_address).transfer_from(
        &mut event_group_builder,
        &context.sender,
        &context.contract_address,
        amount,
    );

    event_group_builder
        .with_callback_rpc(deposit_callback::rpc(token, amount))
        .with_cost(300)
        .done();

    (state, vec![event_group_builder.build()])
}

/// Callback for `deposit`.
/// Is responsible for adding the deposited tokens to the callers balance.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `callback_context` The context of the callback.
/// * `state` The state of the contract.
/// * `token` The address of the token contract being deposited.
/// * `amount` The amount of tokens to be deposited.
///
/// Returns:
///
/// The updated state of the contract, with the added token balance.
#[callback(shortname = 0x10)]
pub fn deposit_callback(
    context: ContractContext,
    callback_context: CallbackContext,
    mut state: TokenSplitterContractState,
    token: DepositToken,
    amount: TokenAmount,
) -> TokenSplitterContractState {
    assert!(callback_context.success, "Transfer did not succeed.");

    state
        .token_balances
        .add_to_token_balance(context.sender, token, amount);

    state
}

/// Withdraw tokens from the callers balance.
/// Only either the original token, true token or false token used for this contract can be withdrawn.
/// This action transfers the tokens from the contract to the caller, and then creates a
/// callback to `wait_withdraw_callback` if `wait_for_callback` is set.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `state` The state of the contract.
/// * `token_address` The address of the token contract to be withdrawn.
/// * `amount` The amount to be withdrawn.
/// * `wait_for_callback` Whether to create a callback.
///
/// Returns:
///
/// The updated state of the contract with the amount withdrawn, and optionally the event creating the callback.
#[action(shortname = 0x03)]
pub fn withdraw(
    context: ContractContext,
    mut state: TokenSplitterContractState,
    token_address: Address,
    amount: TokenAmount,
    wait_for_callback: bool,
) -> (TokenSplitterContractState, Vec<EventGroup>) {
    let token = token_from_address(&state, token_address);

    state
        .token_balances
        .deduct_from_token_balance(context.sender, token, amount);

    let mut event_group_builder = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(token_address).transfer(
        &mut event_group_builder,
        &context.sender,
        amount,
    );

    if wait_for_callback {
        event_group_builder
            .with_callback_rpc(wait_withdraw_callback::rpc())
            .with_cost(300)
            .done();
    }
    (state, vec![event_group_builder.build()])
}

/// Callback for `withdraw`. Does nothing, but enables waiting for the withdrawal to be executed.
///
/// Parameters:
///
/// * `_context` The context of the call.
/// * `_callback_context` The context of the callback.
/// * `state` The state of the contract.
///
/// Returns:
///
/// The state of the contract.
#[callback(shortname = 0x15)]
fn wait_withdraw_callback(
    _context: ContractContext,
    _callback_context: CallbackContext,
    state: TokenSplitterContractState,
) -> TokenSplitterContractState {
    state
}

/// Activates the creation of true and false tokens using `split`, by transferring some amount of true and
/// false tokens from the calling contract to this contract. This needs to be done before using
/// `split` and `join`, to be able to receive true and false tokens from this contract, since the true
/// and false tokens are withdrawn from the balance of this contract when splitting.
///
/// Precondition:
///
/// The life stage of the contract is Preparing.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `state` The state of the contract.
/// * `amount` The amount of true and false tokens transferred to the token splitter contract.
///
/// Returns:
///
/// The activated state of the contract.
#[action(shortname = 0x04)]
pub fn prepare(
    context: ContractContext,
    mut state: TokenSplitterContractState,
    amount: TokenAmount,
) -> TokenSplitterContractState {
    assert_eq!(
        state.life_stage,
        LifeStage::PREPARING {},
        "Can only prepare if life stage is Preparing."
    );

    state
        .token_balances
        .move_tokens(context.sender, context.contract_address, TRUE_TOKEN, amount);
    state.token_balances.move_tokens(
        context.sender,
        context.contract_address,
        FALSE_TOKEN,
        amount,
    );
    state.life_stage = LifeStage::ACTIVE {};
    state
}

/// Splits some amount of original tokens from the balance of the sender, into one true token, and
/// one false token for each original token split. The true and false tokens are then added to
/// the balance of the sender.
///
/// Precondition:
///
/// The life stage of the contract is Active.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `state` The state of the contract.
/// * `amount` The amount to be split.
///
/// Returns:
///
/// The updated state of the contract, with the original token split into true tokens and false tokens.
#[action(shortname = 0x05)]
pub fn split(
    context: ContractContext,
    mut state: TokenSplitterContractState,
    amount: TokenAmount,
) -> TokenSplitterContractState {
    assert_eq!(
        state.life_stage,
        LifeStage::ACTIVE {},
        "Can only split if life stage is Active."
    );

    state.token_balances.move_tokens(
        context.sender,
        context.contract_address,
        ORIGINAL_TOKEN,
        amount,
    );
    state
        .token_balances
        .move_tokens(context.contract_address, context.sender, TRUE_TOKEN, amount);
    state.token_balances.move_tokens(
        context.contract_address,
        context.sender,
        FALSE_TOKEN,
        amount,
    );
    state
}

/// Joins some true and false tokens into some original tokens, taken from the balance of the sender.
/// One original token will be given for each pair of true and false token taken. This is the inverse
/// of the `split` action. To convert true and false tokens to original tokens after the event has been
/// resolved, see the `prepare`, `settle` and `redeem` actions.
///
/// Precondition:
///
/// The life stage of the contract is Active.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `state` The state of the contract.
/// * `amount` The amount to be joined.
///
/// Returns:
///
/// The updated state of the contract, with the true and false tokens joined into original tokens.
#[action(shortname = 0x06)]
pub fn join(
    context: ContractContext,
    mut state: TokenSplitterContractState,
    amount: TokenAmount,
) -> TokenSplitterContractState {
    assert_eq!(
        state.life_stage,
        LifeStage::ACTIVE {},
        "Can only join if life stage is Active."
    );

    state
        .token_balances
        .move_tokens(context.sender, context.contract_address, TRUE_TOKEN, amount);
    state.token_balances.move_tokens(
        context.sender,
        context.contract_address,
        FALSE_TOKEN,
        amount,
    );
    state.token_balances.move_tokens(
        context.contract_address,
        context.sender,
        ORIGINAL_TOKEN,
        amount,
    );
    state
}

/// Settle the outcome of the event. This action can only be invoked by the arbitrator.
///
/// Precondition:
///
/// The life stage of the contract is Active.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `state` The state of the contract.
/// * `outcome` The outcome of the event.
///
/// Returns:
///
/// The updated state of the contract, with the event settled.
#[action(shortname = 0x07)]
pub fn settle(
    context: ContractContext,
    mut state: TokenSplitterContractState,
    outcome: bool,
) -> TokenSplitterContractState {
    assert_eq!(
        state.life_stage,
        LifeStage::ACTIVE {},
        "Can only settle if life stage is Active."
    );
    assert_eq!(
        context.sender, state.arbitrator_address,
        "Address other than that of the arbitrator cannot settle the event."
    );

    state.life_stage = LifeStage::SETTLED { outcome };

    state
}

/// Redeem some amount of true or false tokens back into the original tokens, based on the
/// outcome of the event.
///
/// Precondition:
///
/// The life stage of the contract is Settled.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `state` The state of the contract.
/// * `amount` The amount of tokens to redeem.
///
/// Returns:
///
/// The updated state of the contract, with the tokens redeemed.
#[action(shortname = 0x08)]
pub fn redeem(
    context: ContractContext,
    mut state: TokenSplitterContractState,
    amount: TokenAmount,
) -> TokenSplitterContractState {
    let token = match state.life_stage {
        LifeStage::SETTLED { outcome } => {
            if outcome {
                TRUE_TOKEN
            } else {
                FALSE_TOKEN
            }
        }
        _ => {
            panic!("Can only redeem if life stage is Settled.")
        }
    };

    state
        .token_balances
        .move_tokens(context.sender, context.contract_address, token, amount);
    state.token_balances.move_tokens(
        context.contract_address,
        context.sender,
        ORIGINAL_TOKEN,
        amount,
    );

    state
}
