#![doc = include_str!("../README.md")]

#[macro_use]
extern crate pbc_contract_codegen;

use create_type_spec_derive::CreateTypeSpec;
use defi_common::interact_mpc20;
use defi_common::token_balances::{DepositToken, TokenBalances};
use pbc_contract_common::address::Address;
use pbc_contract_common::avl_tree_map::AvlTreeMap;
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::EventGroup;
use read_write_rpc_derive::ReadWriteRPC;
use read_write_state_derive::ReadWriteState;

/// The ID of a limit order.
#[derive(Copy, Clone, Debug, ReadWriteRPC, ReadWriteState, CreateTypeSpec)]
pub struct LimitOrderId {
    /// The unsigned 64-bit integer used as ID.
    pub raw_id: u64,
}

impl LimitOrderId {
    /// Get the initial available limit order ID.
    ///
    /// Returns:
    ///
    /// The initial limit order ID.
    pub fn initial_id() -> Self {
        LimitOrderId { raw_id: 0 }
    }

    /// Get the next available limit order ID.
    ///
    /// Returns:
    ///
    /// The next limit order ID.
    pub fn next(&self) -> Self {
        LimitOrderId {
            raw_id: self
                .raw_id
                .checked_add(1)
                .expect("All possible LimitOrderIds have been assigned."),
        }
    }
}

/// An amount of tokens that can be transferred.
pub type TokenAmount = u128;

/// The price for a token.
pub type Price = u64;

fn total_price(
    amount: TokenAmount,
    price_per_token: Price,
    price_numerator: u64,
    price_denominator: u64,
) -> TokenAmount {
    assert!(
        amount < u64::MAX.into(),
        "Token amounts larger than u64 are not allowed."
    );
    ((amount * (price_per_token as u128)) / (price_denominator as u128)) * (price_numerator as u128)
}

/// A limit order that can be placed on the double auction contract.
#[derive(Copy, Clone, Debug, ReadWriteRPC, ReadWriteState, CreateTypeSpec)]
pub struct LimitOrder {
    /// The amount of tokens to place on the order.
    pub token_amount: TokenAmount,
    /// The price per token, for which the order is placed on.
    pub price_per_token: Price,
    /// The ID of the order.
    pub id: LimitOrderId,
    /// The owner of the order.
    pub owner: Address,
    /// Whether the order is a bid or an ask.
    pub is_bid: bool,
    /// ID used for cancelling the order.
    pub cancelation_id: u32,
}

/// Request for cancelling a limit order.
/// A limit order is assumed to be uniquely given by the owner of the order and cancelation ID.
#[derive(Copy, Clone, Debug, ReadWriteRPC, ReadWriteState, CreateTypeSpec)]
pub struct CancelationRequest {
    /// The owner of the limit order.
    pub owner: Address,
    /// The cancelation ID.
    pub cancelation_id: u32,
}

/// Received when selling. Also known as "base token".
const CURRENCY_TOKEN: DepositToken = DepositToken::TokenA {};
/// Received when buying.
const ASSET_TOKEN: DepositToken = DepositToken::TokenB {};

fn token_from_address(state: &DoubleAuctionContractState, address: Address) -> DepositToken {
    if address == state.currency_token_address {
        CURRENCY_TOKEN
    } else if address == state.asset_token_address {
        ASSET_TOKEN
    } else {
        panic!(
            "Unknown token {}. Contract only supports {} or {}",
            address, state.currency_token_address, state.asset_token_address
        )
    }
}

/// Key for priority queue used for sorting limit orders.
/// Assumes that entries in the AVL tree implementation are sorted by little endian representation,
/// to ensure that orders are placed in an efficient lookup order.
#[derive(Copy, Clone, Debug, ReadWriteRPC, ReadWriteState, CreateTypeSpec)]
pub struct Priority {
    /// The raw key.
    pub key: [u8; 16],
}

impl Priority {
    /// Creates new `Priority` where cheap prices (primarily) and lower ids (secondarily) are higher priority.
    ///
    /// Parameters:
    ///
    /// * `price` the price of the order.
    /// * `id` the id of the order.
    ///
    /// Returns:
    ///
    /// The priority of the order.
    pub fn cheap_early(price: u64, id: LimitOrderId) -> Self {
        let key = [price.to_be_bytes(), id.raw_id.to_be_bytes()]
            .concat()
            .try_into()
            .unwrap();
        Self { key }
    }

    /// Creates new `Priority` where expensive prices (primarily) and lower ids (secondarily) are higher priority.
    ///
    /// Parameters:
    ///
    /// * `price` the price of the order.
    /// * `id` the id of the order.
    ///
    /// Returns:
    ///
    /// The priority of the order.
    pub fn expensive_early(price: u64, id: LimitOrderId) -> Self {
        let key = [(!price).to_be_bytes(), id.raw_id.to_be_bytes()]
            .concat()
            .try_into()
            .unwrap();
        Self { key }
    }
}

/// The state of the contract.
#[state]
pub struct DoubleAuctionContractState {
    /// Numerator from which to calculate the price of the asset token.
    /// Corresponds to the price of one asset token.
    price_numerator: u64,
    /// Denominator from which to calculate the price of the asset token.
    /// Corresponds to the price of one currency token.
    price_denominator: u64,
    /// The next available order ID to be used for placing limit orders.
    next_order_id: LimitOrderId,
    /// The address of the contract itself.
    pub double_auction_address: Address,
    /// The address of the token used as the currency token.
    pub currency_token_address: Address,
    /// The address of the token used as the address token.
    pub asset_token_address: Address,
    /// The balances of currency tokens and asset tokens of all users.
    pub token_balances: TokenBalances,
    /// All limit orders given by their owner and cancelation ID.
    orders_by_cancelation_request: AvlTreeMap<CancelationRequest, LimitOrder>,
    /// The bids that have been placed on this contract.
    bids: AvlTreeMap<Priority, LimitOrder>,
    /// The asks that have been placed on this contract.
    asks: AvlTreeMap<Priority, LimitOrder>,
}

/// Initialize the order matching contract.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `currency_token_address` The address of the currency token contract.
/// * `asset_token_address` The address of the asset token contract.
/// * `price_numerator` Price numerator from which to calculate the price of the asset.
/// * `price_denominator` Price denominator from which to calculate the price of the asset.
///
/// Returns:
///
/// The initial state of the contract.
///
#[init]
pub fn initialize(
    context: ContractContext,
    currency_token_address: Address,
    asset_token_address: Address,
    price_numerator: u64,
    price_denominator: u64,
) -> DoubleAuctionContractState {
    let token_balances = TokenBalances::new(
        context.contract_address,
        currency_token_address,
        asset_token_address,
    )
    .unwrap();

    DoubleAuctionContractState {
        next_order_id: LimitOrderId::initial_id(),
        price_numerator,
        price_denominator,
        double_auction_address: context.contract_address,
        currency_token_address,
        asset_token_address,
        token_balances,
        orders_by_cancelation_request: AvlTreeMap::new(),
        bids: AvlTreeMap::new(),
        asks: AvlTreeMap::new(),
    }
}

/// Deposit some tokens from the caller onto the token balances.
/// Only the currency token or asset token used for this contract can be deposited.
/// This action transfers the tokens from the caller onto this contract, and then creates a
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
///
#[action(shortname = 0x01)]
pub fn deposit(
    context: ContractContext,
    state: DoubleAuctionContractState,
    token_address: Address,
    amount: TokenAmount,
) -> (DoubleAuctionContractState, Vec<EventGroup>) {
    let token = token_from_address(&state, token_address);

    let mut event_group_builder = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(token_address).transfer_from(
        &mut event_group_builder,
        &context.sender,
        &state.double_auction_address,
        amount,
    );

    event_group_builder
        .with_callback(SHORTNAME_DEPOSIT_CALLBACK)
        .argument(token)
        .argument(amount)
        .done();

    (state, vec![event_group_builder.build()])
}

/// Callback for `deposit`.
/// Is responsible for adding the deposited tokens onto the callers balance.
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
/// The updated state of the contract, with the tokens deposited.
#[callback(shortname = 0x10)]
pub fn deposit_callback(
    context: ContractContext,
    callback_context: CallbackContext,
    mut state: DoubleAuctionContractState,
    token: DepositToken,
    amount: TokenAmount,
) -> DoubleAuctionContractState {
    assert!(callback_context.success, "Transfer did not succeed.");

    state
        .token_balances
        .add_to_token_balance(context.sender, token, amount);
    state
}

/// Withdraw tokens from the callers balance.
/// Only the currency token or the asset token used for this contract can be withdrawn.
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
    mut state: DoubleAuctionContractState,
    token_address: Address,
    amount: TokenAmount,
    wait_for_callback: bool,
) -> (DoubleAuctionContractState, Vec<EventGroup>) {
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
            .with_callback(SHORTNAME_WAIT_WITHDRAW_CALLBACK)
            .done();
    }
    (state, vec![event_group_builder.build()])
}

/// Callback for `withdraw`. Enables waiting for the withdrawal to be executed.
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
    state: DoubleAuctionContractState,
) -> DoubleAuctionContractState {
    state
}

/// Submit a bid limit order. If matching asks exist, it will meet those asks until the amount
/// placed is met or until no more matching asks exist, at which point it will place the bid
/// for the remaining amount.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `state` The state of the contract.
/// * `price_per_token` The price for one token, to submit the bid for.
/// * `token_amount` The amount of tokens to bid.
/// * `cancelation_id` The ID to be used for cancelling the bid.
///
/// Returns:
///
/// The updated state of the contract, with the bid placed.
#[action(shortname = 0x04)]
fn submit_bid(
    context: ContractContext,
    mut state: DoubleAuctionContractState,
    price_per_token: Price,
    token_amount: TokenAmount,
    cancelation_id: u32,
) -> DoubleAuctionContractState {
    let mut rest_amount = token_amount;

    while (rest_amount > 0) && (!state.asks.is_empty()) {
        let (key, mut ask_order) = state.asks.iter().next().unwrap();

        if ask_order.price_per_token > price_per_token {
            break;
        }

        state.asks.remove(&key);

        let move_amount: TokenAmount;

        if ask_order.token_amount > rest_amount {
            move_amount = rest_amount;
            ask_order.token_amount -= rest_amount;
            state.asks.insert(key, ask_order);
            rest_amount = 0;
        } else {
            move_amount = ask_order.token_amount;
            rest_amount -= ask_order.token_amount;
            let cancelation_request = CancelationRequest {
                owner: ask_order.owner,
                cancelation_id: ask_order.cancelation_id,
            };
            state
                .orders_by_cancelation_request
                .remove(&cancelation_request);
        }

        state.token_balances.move_tokens(
            context.sender,
            ask_order.owner,
            CURRENCY_TOKEN,
            total_price(
                move_amount,
                ask_order.price_per_token,
                state.price_numerator,
                state.price_denominator,
            ),
        );
        state.token_balances.move_tokens(
            state.double_auction_address,
            context.sender,
            ASSET_TOKEN,
            move_amount,
        );
    }

    if rest_amount > 0 {
        let pri = Priority::expensive_early(price_per_token, state.next_order_id);
        let new_bid_order = LimitOrder {
            price_per_token,
            token_amount: rest_amount,
            id: state.next_order_id,
            owner: context.sender,
            is_bid: true,
            cancelation_id,
        };
        state.bids.insert(pri, new_bid_order);
        let cancelation_request = CancelationRequest {
            owner: context.sender,
            cancelation_id,
        };
        state
            .orders_by_cancelation_request
            .insert(cancelation_request, new_bid_order);
        state.token_balances.move_tokens(
            context.sender,
            state.double_auction_address,
            CURRENCY_TOKEN,
            total_price(
                rest_amount,
                price_per_token,
                state.price_numerator,
                state.price_denominator,
            ),
        );

        state.next_order_id = state.next_order_id.next();
    }

    state
}

/// Submit an ask limit order. If matching bids exist, it will meet those bids until the amount
/// placed is met or until no more matching bids exist, at which point it will place the ask
/// for the remaining amount.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `state` The state of the contract.
/// * `price_per_token` The price for one token, to submit the ask for.
/// * `token_amount` The amount of tokens to ask.
/// * `cancelation_id` The ID to be used for cancelling the ask.
///
/// Returns:
///
/// The updated state of the contract, with the ask placed.
#[action(shortname = 0x05)]
fn submit_ask(
    context: ContractContext,
    mut state: DoubleAuctionContractState,
    price_per_token: Price,
    token_amount: TokenAmount,
    cancelation_id: u32,
) -> DoubleAuctionContractState {
    let mut rest_amount = token_amount;

    while (rest_amount > 0) && (!state.bids.is_empty()) {
        let (key, mut bid_order) = state.bids.iter().next().unwrap();

        if bid_order.price_per_token < price_per_token {
            break;
        }

        state.bids.remove(&key);

        let move_amount: TokenAmount;

        if bid_order.token_amount > rest_amount {
            move_amount = rest_amount;

            bid_order.token_amount -= rest_amount;
            state.bids.insert(key, bid_order);
            rest_amount = 0;
        } else {
            move_amount = bid_order.token_amount;

            rest_amount -= bid_order.token_amount;
            let cancelation_request = CancelationRequest {
                owner: bid_order.owner,
                cancelation_id: bid_order.cancelation_id,
            };
            state
                .orders_by_cancelation_request
                .remove(&cancelation_request)
        }

        state.token_balances.move_tokens(
            state.double_auction_address,
            context.sender,
            CURRENCY_TOKEN,
            total_price(
                move_amount,
                bid_order.price_per_token,
                state.price_numerator,
                state.price_denominator,
            ),
        );
        state
            .token_balances
            .move_tokens(context.sender, bid_order.owner, ASSET_TOKEN, move_amount);
    }

    if rest_amount > 0 {
        let pri = Priority::cheap_early(price_per_token, state.next_order_id);
        let new_ask_order = LimitOrder {
            price_per_token,
            token_amount: rest_amount,
            id: state.next_order_id,
            owner: context.sender,
            is_bid: false,
            cancelation_id,
        };
        state.asks.insert(pri, new_ask_order);
        let cancelation_request = CancelationRequest {
            owner: context.sender,
            cancelation_id,
        };
        state
            .orders_by_cancelation_request
            .insert(cancelation_request, new_ask_order);
        state.token_balances.move_tokens(
            context.sender,
            state.double_auction_address,
            ASSET_TOKEN,
            rest_amount,
        );

        state.next_order_id = state.next_order_id.next();
    }

    state
}

/// Cancel a previously placed limit order. Limit orders can only be cancelled by the
/// same account that placed the order in the first place.
///
/// Parameters:
///
/// * `context` The context of the call.
/// * `state` The state of the contract.
/// * `cancelation_id` The cancelation ID of the order to be cancelled.
///
/// Returns:
///
/// The updated state of the contract, with the limit order cancelled.
#[action(shortname = 0x06)]
fn cancel_limit_order(
    context: ContractContext,
    mut state: DoubleAuctionContractState,
    cancelation_id: u32,
) -> DoubleAuctionContractState {
    let cancelation_request = CancelationRequest {
        owner: context.sender,
        cancelation_id,
    };
    let order = state
        .orders_by_cancelation_request
        .get(&cancelation_request)
        .unwrap_or_else(|| panic!("The given cancelation request did not match any orders."));

    if order.is_bid {
        let key = Priority::expensive_early(order.price_per_token, order.id);
        let bid_order = state.bids.get(&key).unwrap();
        state.token_balances.move_tokens(
            state.double_auction_address,
            context.sender,
            CURRENCY_TOKEN,
            total_price(
                bid_order.token_amount,
                bid_order.price_per_token,
                state.price_numerator,
                state.price_denominator,
            ),
        );
        state.bids.remove(&key);
    } else {
        let key = Priority::cheap_early(order.price_per_token, order.id);
        let ask_order = state.asks.get(&key).unwrap();
        state.token_balances.move_tokens(
            state.double_auction_address,
            context.sender,
            ASSET_TOKEN,
            ask_order.token_amount,
        );
        state.asks.remove(&key);
    }

    state
        .orders_by_cancelation_request
        .remove(&cancelation_request);

    state
}
