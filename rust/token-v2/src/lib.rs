#![doc = include_str!("../README.md")]

#[macro_use]
extern crate pbc_contract_codegen;

use create_type_spec_derive::CreateTypeSpec;
use read_write_rpc_derive::ReadWriteRPC;
use std::ops::Sub;

use defi_common::token_state::AbstractTokenState;
use pbc_contract_common::address::Address;
use pbc_contract_common::avl_tree_map::AvlTreeMap;
use pbc_contract_common::context::ContractContext;
use pbc_traits::ReadWriteState;
use read_write_state_derive::ReadWriteState;

/// MPC-20-v2 token contract compatible state.
///
/// Uses the [`AbstractTokenState`] to implement [`transfer`].
#[state]
pub struct TokenState {
    /// The name of the token - e.g. "MyToken".
    pub name: String,
    /// The symbol of the token. E.g. "HIX".
    pub decimals: u8,
    /// The number of decimals the token uses - e.g. 8,
    /// means to divide the token amount by `100000000` to get its user representation.
    pub symbol: String,
    /// The owner of the contract. Not used for anything beyond the initial minting.
    pub owner: Address,
    /// Current amount of tokens for the TokenContract.
    pub total_supply: u128,
    /// Ledger for the accounts associated with the contract.
    pub balances: AvlTreeMap<Address, u128>,
    /// Ledger for allowances, that allows users or contracts to transfer tokens on behalf of
    /// others.
    pub allowed: AvlTreeMap<AllowedAddress, u128>,
}

/// Address pair representing an allowance. Owner allows spender to transfer tokens on behalf of
/// them.
#[derive(ReadWriteState, CreateTypeSpec, Eq, Ord, PartialEq, PartialOrd)]
pub struct AllowedAddress {
    /// Owner of the tokens
    pub owner: Address,
    /// User allowed to transfer on behalf of [`AllowedAddress::owner`].
    pub spender: Address,
}

/// Extension trait for inserting into a map holding balances.
///
/// In a balance map only non-zero values are stored.
/// If a key has no value in the map the implied value is zero.
trait BalanceMap<K: Ord, V> {
    /// Insert into the map if `value` is not zero.
    /// Removes the key from the map if `value` is zero.
    ///
    /// ## Arguments
    ///
    /// * `key`: Key for map.
    ///
    /// * `value`: The balance value to insert.
    fn insert_balance(&mut self, key: K, value: V);
}

/// Extension for [`AvlTreeMap`] allowing the use of [`BalanceMap::insert_balance`].
///
/// This implementation defines zero as `forall v: v - v = 0` (the subtract of a value from itself), to support a large variety
/// of values. Might not work correctly for unusual implementations of [`Sub::sub`].
impl<V: Sub<V, Output = V> + PartialEq + Copy + ReadWriteState, K: ReadWriteState + Ord>
    BalanceMap<K, V> for AvlTreeMap<K, V>
{
    #[allow(clippy::eq_op)]
    fn insert_balance(&mut self, key: K, value: V) {
        let zero = value - value;
        if value == zero {
            self.remove(&key);
        } else {
            self.insert(key, value);
        }
    }
}

impl AbstractTokenState for TokenState {
    fn get_symbol(&self) -> &str {
        &self.symbol
    }

    fn update_balance(&mut self, owner: Address, amount: u128) {
        self.balances.insert_balance(owner, amount);
    }

    fn balance_of(&self, owner: &Address) -> u128 {
        self.balances.get(owner).unwrap_or(0)
    }

    fn allowance(&self, owner: &Address, spender: &Address) -> u128 {
        self.allowed
            .get(&AllowedAddress {
                owner: *owner,
                spender: *spender,
            })
            .unwrap_or(0)
    }

    fn update_allowance(&mut self, owner: Address, spender: Address, amount: u128) {
        self.allowed
            .insert_balance(AllowedAddress { owner, spender }, amount);
    }
}

/// Initial function to bootstrap the contracts state. Must return the state-struct.
///
/// ### Parameters:
///
/// * `ctx`: [`ContractContext`], initial context.
///
/// * `name`: [`String`], the name of the token - e.g. "MyToken".
///
/// * `symbol`: [`String`], the symbol of the token. E.g. "HIX".
///
/// * `decimals`: [`u8`], the number of decimals the token uses - e.g. 8,
/// means to divide the token amount by `100000000` to get its user representation.
///
/// * `total_supply`: [`u128`], current amount of tokens for the TokenContract.
///
/// ### Returns:
///
/// The new state object of type [`TokenState`] with an initialized ledger.
#[init]
pub fn initialize(
    ctx: ContractContext,
    name: String,
    symbol: String,
    decimals: u8,
    total_supply: u128,
) -> TokenState {
    let mut initial_state = TokenState {
        name,
        symbol,
        decimals,
        owner: ctx.sender,
        total_supply,
        balances: AvlTreeMap::new(),
        allowed: AvlTreeMap::new(),
    };

    initial_state.update_balance(ctx.sender, total_supply);
    initial_state
}

/// Individual transfer for use in [`bulk_transfer`].
#[derive(ReadWriteRPC, CreateTypeSpec)]
pub struct Transfer {
    /// The address to transfer to.
    pub to: Address,
    /// The amount to transfer.
    pub amount: u128,
}

/// Transfers `amount` of tokens to address `to` from the caller.
///
/// The function throws if the message caller's account
/// balance does not have enough tokens to spend.
/// If the sender's account goes to 0, the sender's address is removed from state.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`], the context for the action call.
///
/// * `state`: [`TokenState`], the current state of the contract.
///
/// * `to`: [`Address`], the address to transfer to.
///
/// * `amount`: [`u128`], amount to transfer.
///
/// ### Returns
///
/// The new state object of type [`TokenState`] with an updated ledger.
#[action(shortname = 0x01)]
pub fn transfer(
    context: ContractContext,
    mut state: TokenState,
    to: Address,
    amount: u128,
) -> TokenState {
    state.transfer(context.sender, to, amount);
    state
}

/// Transfers a bulk of `amount` of tokens to address `to` from the caller.
///
/// The function throws if the message caller's account
/// balance does not have enough tokens to spend.
/// If the sender's account goes to 0, the sender's address is removed from state.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`], the context for the action call.
///
/// * `state`: [`TokenState`], the current state of the contract.
///
/// * `transfers`: [`Vec[Transfer]`], vector of [the address to transfer to, amount to transfer].
///
/// ### Returns
///
/// The new state object of type [`TokenState`] with an updated ledger.
#[action(shortname = 0x02)]
pub fn bulk_transfer(
    context: ContractContext,
    mut state: TokenState,
    transfers: Vec<Transfer>,
) -> TokenState {
    for t in transfers {
        state.transfer(context.sender, t.to, t.amount);
    }
    state
}

/// Transfers `amount` of tokens from address `from` to address `to`.
///
/// This requires that the sender is allowed to do the transfer by the `from`
/// account through the `approve` action.
/// The function throws if the message caller's account
/// balance does not have enough tokens to spend, or if the tokens were not approved.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`], the context for the action call.
///
/// * `state`: [`TokenState`], the current state of the contract.
///
/// * `from`: [`Address`], the address to transfer from.
///
/// * `to`: [`Address`], the address to transfer to.
///
/// * `amount`: [`u128`], amount to transfer.
///
/// ### Returns
///
/// The new state object of type [`TokenState`] with an updated ledger.
#[action(shortname = 0x03)]
pub fn transfer_from(
    context: ContractContext,
    mut state: TokenState,
    from: Address,
    to: Address,
    amount: u128,
) -> TokenState {
    state.transfer_from(context.sender, from, to, amount);
    state
}

/// Transfers a bulk of `amount` of tokens to address `to` from address `from`.
///
/// This requires that the sender is allowed to do the transfer by the `from`
/// account through the `approve` action.
/// The function throws if the message caller's account
/// balance does not have enough tokens to spend, or if the tokens were not approved.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`], the context for the action call.
///
/// * `state`: [`TokenState`], the current state of the contract.
///
/// * `from`: [`Address`], the address to transfer from.
///
/// * `transfers`: [`Vec[Transfer]`], vector of [the address to transfer to, amount to transfer].
///
/// ### Returns
///
/// The new state object of type [`TokenState`] with an updated ledger.
#[action(shortname = 0x04)]
pub fn bulk_transfer_from(
    context: ContractContext,
    mut state: TokenState,
    from: Address,
    transfers: Vec<Transfer>,
) -> TokenState {
    for t in transfers {
        state.transfer_from(context.sender, from, t.to, t.amount);
    }
    state
}

/// Allows `spender` to withdraw from the owners account multiple times, up to the `amount`.
///
/// If this function is called again it overwrites the current allowance with `amount`.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`], the context for the action call.
///
/// * `state`: [`TokenState`], the current state of the contract.
///
/// * `spender`: [`Address`], the address of the spender.
///
/// * `amount`: [`u128`], approved amount.
///
/// ### Returns
///
/// The new state object of type [`TokenState`] with an updated ledger.
#[action(shortname = 0x05)]
pub fn approve(
    context: ContractContext,
    mut state: TokenState,
    spender: Address,
    amount: u128,
) -> TokenState {
    state.update_allowance(context.sender, spender, amount);
    state
}

/// Allows `spender` to withdraw `delta` additional tokens from the owners account, relative to any
/// pre-existing allowance.
///
/// If there is no pre-existing allowance, this is equivalent to [`approve`], with `delta` = `amount`.
/// If `delta` is negative, the allowance is lowered.
/// If the resulting allowance is negative, the call fails, and the allowance is unchanged.
#[action(shortname = 0x07)]
pub fn approve_relative(
    context: ContractContext,
    mut state: TokenState,
    spender: Address,
    delta: i128,
) -> TokenState {
    state.update_allowance_relative(context.sender, spender, delta);
    state
}
