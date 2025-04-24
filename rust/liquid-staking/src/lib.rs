#![doc = include_str!("../README.md")]

mod upgrade;

#[macro_use]
extern crate pbc_contract_codegen;

use std::ops::Sub;

use create_type_spec_derive::CreateTypeSpec;
use defi_common::interact_mpc20;
use defi_common::token_state::AbstractTokenState;
use pbc_contract_common::address::Address;
use pbc_contract_common::avl_tree_map::AvlTreeMap;
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::EventGroup;
use pbc_traits::ReadWriteState;
use read_write_rpc_derive::{ReadRPC, WriteRPC};
use read_write_state_derive::ReadWriteState;

/// Address pair representing an allowance. Owner allows spender to transfer tokens on behalf of
/// them.
#[derive(ReadWriteState, CreateTypeSpec, Eq, Ord, PartialEq, PartialOrd)]
pub struct AllowedAddress {
    /// Owner of the tokens
    pub owner: Address,
    /// User allowed to transfer on behalf of [`AllowedAddress::owner`].
    pub spender: Address,
}

/// MPC-20 compatible state for the liquid token.
///
/// Uses the [`AbstractTokenState`] to implement [`transfer`].
#[derive(ReadWriteState, CreateTypeSpec)]
pub struct LiquidTokenState {
    /// Liquid token balances for the accounts associated with the contract.
    pub balances: AvlTreeMap<Address, u128>,
    /// The name of the token - e.g. "MyToken".
    pub name: String,
    /// The number of decimals the token uses - e.g. 8,
    /// means to divide the token amount by `100000000` to get its user representation.
    pub symbol: String,
    /// The symbol of the token. E.g. "HIX".
    pub decimals: u8,
    /// Ledger for allowances, that allows users or contracts to transfer tokens on behalf of
    /// others.
    pub allowed: AvlTreeMap<AllowedAddress, u128>,
}

/// Extension trait for inserting into a map holding balances.
///
/// In a balance map only non-zero values are stored.
/// If a key has no value in the map the implied value is zero.
trait BalanceMap<K: Ord, V> {
    /// Insert into the map if `value` is not zero.
    /// Removes the key from the map if `value` is zero.
    ///
    /// ## Parameters
    /// * `key`: Key for map.
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

impl LiquidTokenState {
    /// Create the initial state of the MPC-20 compatible state for the liquid token.
    ///
    /// ## Parameters
    /// * `name`: The name of the liquid token. - e.g "LiquidToken".
    /// * `symbol`: The symbol of the liquid token. - e.g "LT".
    /// * `decimals`: The number of decimals a token amount can have.
    pub fn init(name: String, symbol: String, decimals: u8) -> LiquidTokenState {
        LiquidTokenState {
            balances: AvlTreeMap::new(),
            allowed: AvlTreeMap::new(),
            name,
            symbol,
            decimals,
        }
    }
}

impl AbstractTokenState for LiquidTokenState {
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

/// An unlock request waiting to be redeemed.
#[derive(ReadRPC, WriteRPC, ReadWriteState, CreateTypeSpec)]
pub struct PendingUnlock {
    /// The amount of liquid tokens to be unlocked.
    liquid_amount: u128,
    /// The amount of stake tokens to be unlocked.
    stake_token_amount: u128,
    /// The UNIX time that the unlock was requested.
    created_at: u64,
    /// The UNIX time that the cooldown period ends. The user can redeem tokens after this time.
    cooldown_ends_at: u64,
    /// The UNIX time the redeem period ends. The user cannot redeem tokens after this time.
    expires_at: u64,
}

impl PendingUnlock {
    /// A pending unlock is redeemable if current_time is larger than cooldown_ends_at
    /// and smaller than expires_at
    ///
    /// ## Parameters
    /// * `current_time`: The block production time.
    fn is_within_redeem_period(&self, current_time: u64) -> bool {
        self.cooldown_ends_at < current_time && current_time < self.expires_at
    }

    /// A pending unlock is expired, if current_time is larger than expires_at.
    ///
    /// ## Parameters
    /// * `current_time`: The block production time.
    fn is_expired(&self, current_time: u64) -> bool {
        self.expires_at < current_time
    }
}

/// Liquid Staking contract compatible state.
#[state]
pub struct LiquidStakingState {
    /// The address of the underlying stake token used for liquid staking.
    pub token_for_staking: Address,
    /// Number of stake tokens (in minimal units) that is currently managed by the contract.
    /// Invariant: This is equal to the balance (for this contract) on the stake token.
    /// Invariant: This is smaller than or equal to the total_pool_stake_token.
    pub stake_token_balance: u128,
    /// The account responsible for staking the submitted stake tokens.
    pub staking_responsible: Address,
    /// The account that can change the buy-in.
    pub administrator: Address,
    /// The total amount of the stake token (in minimal units) that can be staked.
    /// Used to calculate the exchange rate.
    /// This amount is equal the amount of tokens currently staked (by the staking responsible) plus
    /// the tokens currently managed by this contract (stake_token_balance).
    /// Invariant: This pool is always greater than or equal to the total_pool_liquid.
    pub total_pool_stake_token: u128,
    /// The total amount of liquid tokens stored on this contract.
    /// Used to calculate the exchange rate.
    pub total_pool_liquid: u128,
    /// Token state that keeps track of the amount of liquid tokens each user has.
    /// Invariant: The sum of all balances is equal to total_pool_liquid.
    pub liquid_token_state: LiquidTokenState,
    /// Map that keeps track of all pending unlocks.
    pub pending_unlocks: AvlTreeMap<Address, Vec<PendingUnlock>>,
    /// Map that keeps track of all staking tokens locked by the buy-in.
    /// Invariant: If buy in is disabled, then there are no locked buy in tokens
    pub buy_in_tokens: AvlTreeMap<Address, u128>,
    /// Number of milliseconds from the unlock request was registered until the pending unlock can be redeemed.
    pub length_of_cooldown_period: u64,
    /// Number of milliseconds from the pending unlock becomes redeemable until the pending unlock expires.
    pub length_of_redeem_period: u64,
    /// Amount of buy-in locked stake tokens
    pub amount_of_buy_in_locked_stake_tokens: u128,
    /// The current buy-in percentage used when user submits tokens.
    pub buy_in_percentage: u128,
    /// The flag to determine if the buy-in is currently used when submitting.
    pub buy_in_enabled: bool,
}

impl LiquidStakingState {
    /// Increase the stake_token_balance with the specified amount.
    ///
    /// ## Parameters
    /// * `stake_token_amount`: The amount of stake tokens to add to the internal representation of the token balance.
    fn add_to_stake_token_balance(&mut self, stake_token_amount: u128) {
        self.stake_token_balance += stake_token_amount
    }

    /// Decrease the stake_token_balance with the specified amount.
    ///
    /// ## Parameters
    /// * `stake_token_amount`: The amount of stake tokens to subtract from the internal representation of the token balance.
    fn subtract_from_stake_token_balance(&mut self, stake_token_amount: u128) {
        self.stake_token_balance -= stake_token_amount
    }

    /// Determines whether the contract has enough stake tokens in order to perform some operation.
    ///
    /// ## Parameters
    /// * `stake_token_amount`: The required amount for some operation to succeed.
    fn does_contract_have_enough_stake_tokens(&self, stake_token_amount: u128) -> bool {
        stake_token_amount <= self.stake_token_balance
    }

    /// Determines whether the specified `account` is the registered administrator.
    fn is_the_administrator(&self, account: Address) -> bool {
        account == self.administrator
    }

    /// Determines whether the specified `account` is the registered staking responsible.
    fn is_the_staking_responsible(&self, account: Address) -> bool {
        account == self.staking_responsible
    }

    /// Determines whether the specified `account` is allowed to clean pending unlocks.
    fn is_allowed_to_clean_pending_unlocks(&self, account: Address) -> bool {
        self.is_the_administrator(account) || self.is_the_staking_responsible(account)
    }

    /// Exchange the specified amount of stake tokens to liquid tokens.
    ///
    /// ## Parameters
    /// * `stake_token_amount`: The amount of stake tokens to be exchanged.
    ///
    /// ## Returns
    /// The calculated amount of liquid tokens.
    fn exchange_stake_tokens_for_liquidity_tokens(&self, stake_token_amount: u128) -> u128 {
        if self.total_pool_stake_token == 0 {
            stake_token_amount
        } else {
            stake_token_amount * self.total_pool_liquid
                / (self.total_pool_stake_token - self.amount_of_buy_in_locked_stake_tokens)
        }
    }

    /// Exchange the specified amount of liquid tokens to stake tokens.
    ///
    /// ## Parameters
    /// * `liquid_amount`: The amount of liquid tokens to be exchanged.
    ///
    /// ## Returns
    /// The calculated amount of stake tokens.
    fn exchange_liquidity_tokens_for_stake_tokens(&self, liquid_amount: u128) -> u128 {
        liquid_amount * (self.total_pool_stake_token - self.amount_of_buy_in_locked_stake_tokens)
            / self.total_pool_liquid
    }

    /// Increase the pool of stake tokens with the specified amount.
    ///
    /// ## Parameters
    /// * `stake_token_amount`: The amount of stake tokens to be added to the pool.
    fn add_to_stake_pool(&mut self, stake_token_amount: u128) {
        self.total_pool_stake_token += stake_token_amount
    }

    /// Increase the pool of buy-in locked stake tokens with the specified amount.
    ///
    /// ## Parameters
    /// * `locked_stake_token_amount`: The amount of buy-in locked stake tokens to be added to the pool.
    fn add_to_buy_in_locked_token_pool(&mut self, stake_token_amount: u128) {
        self.amount_of_buy_in_locked_stake_tokens += stake_token_amount
    }

    /// Decrease the pool of stake tokens with the specified amount.
    ///
    /// ## Parameters
    /// * `stake_token_amount`: The amount of stake tokens to be subtracted from the pool.
    fn subtract_from_stake_pool(&mut self, stake_token_amount: u128) {
        self.total_pool_stake_token -= stake_token_amount
    }

    /// Increase the pool of liquid tokens with the specified amount, and increase the liquid
    /// balance of the specified user with the specified amount.
    ///
    /// ## Parameters
    /// * `user`: The user whose balance is updated.
    /// * `liquid_amount`: The amount of liquid tokens to be added to the pool and the user's balance.
    fn add_liquid_tokens_to_user_balance_and_pool(&mut self, user: Address, liquid_amount: u128) {
        let old_balance = self.liquid_token_state.balance_of(&user);
        let new_balance = old_balance + liquid_amount;
        self.liquid_token_state.update_balance(user, new_balance);
        self.total_pool_liquid += liquid_amount;
    }

    /// Decrease the pool of liquid tokens with the specified amount, and decrease the liquid
    /// balance of the specified user with the specified amount.
    ///
    /// ## Parameters
    /// * `user`: The user whose balance is updated.
    /// * `liquid_amount`: The amount of liquid tokens to be subtracted from the pool and the user's balance.
    fn subtract_liquid_tokens_from_user_balance_and_pool(
        &mut self,
        user: Address,
        liquid_amount: u128,
    ) {
        let old_balance = self.liquid_token_state.balance_of(&user);
        let new_balance = old_balance - liquid_amount;
        self.liquid_token_state.update_balance(user, new_balance);
        self.total_pool_liquid -= liquid_amount;
    }

    /// Minting liquid tokens by
    /// * Lock an amount of the submitted stake tokens as buy in.
    /// * Adding the amount of stake tokens (after buy in) to the pool.
    /// * Exchanging the amount of stake tokens (after buy in) to liquid tokens.
    /// * Adding the liquid tokens to the pool and the balance of the specified user.
    ///
    /// ## Parameters
    /// * `user`: The user whose owns the minted tokens.
    /// * `stake_token_amount`: The stake tokens that must be minted.
    fn mint_liquid_tokens(&mut self, user: Address, stake_token_amount: u128) {
        let amount_after_buy_in = self.lock_buy_in(user, stake_token_amount);
        let liquid_amount = self.exchange_stake_tokens_for_liquidity_tokens(amount_after_buy_in);
        self.add_to_buy_in_locked_token_pool(stake_token_amount - amount_after_buy_in);
        self.add_to_stake_pool(stake_token_amount);
        self.add_liquid_tokens_to_user_balance_and_pool(user, liquid_amount);
    }

    /// Burn liquid tokens by
    /// * Subtracting the specified amount of stake tokens from the pool.
    /// * Subtracting the specified amount of liquid tokens from the pool.
    /// * Subtracting the specified amount of liquid tokens from the balance of the specified user.
    ///
    /// ## Parameters
    /// * `user`: The user whose owns the burned tokens.
    /// * `liquid_amount`: The liquid tokens that must be burned.
    /// * `stake_token_amount`: The amount of stake tokens that the liquid tokens have been exchanged to.
    fn burn_liquid_tokens(&mut self, user: Address, liquid_amount: u128, stake_token_amount: u128) {
        self.subtract_from_stake_pool(stake_token_amount);
        self.subtract_liquid_tokens_from_user_balance_and_pool(user, liquid_amount);
    }

    /// Adding an unlock request to pending unlocks, if the contract and
    /// the specified user has enough liquidity.
    ///
    /// ## Parameters
    /// * `user`: The user who requests to unlock.
    /// * `liquid_amount`: The amount of liquid tokens to be unlocked.
    /// * `created_at`: The block production time, when the unlock was requested.
    fn add_to_pending_unlocks(&mut self, user: Address, liquid_amount: u128, created_at: u64) {
        self.assert_whether_user_have_enough_liquidity(user, liquid_amount, created_at);

        let new_pending_unlock = PendingUnlock {
            liquid_amount,
            stake_token_amount: self.exchange_liquidity_tokens_for_stake_tokens(liquid_amount),
            created_at,
            cooldown_ends_at: created_at + self.length_of_cooldown_period,
            expires_at: created_at + self.length_of_cooldown_period + self.length_of_redeem_period,
        };

        let mut unlocks = self.pending_unlocks.get(&user).unwrap_or_default();
        unlocks.push(new_pending_unlock);
        self.pending_unlocks.insert(user, unlocks);
    }

    /// Assert that the specified user has enough liquidity
    ///
    /// ## Parameters
    /// * `user`: The user who requests to unlock.
    /// * `liquid_amount`: The amount of liquid tokens to be unlocked.
    /// * `current_time`: The block production time.
    fn assert_whether_user_have_enough_liquidity(
        &self,
        user: Address,
        liquid_amount: u128,
        current_time: u64,
    ) {
        let liquid_balance = self.liquid_token_state.balance_of(&user);
        let current_pending_unlocks =
            self.total_non_expired_pending_liquid_tokens_for_user(user, current_time);

        if liquid_amount > (liquid_balance - current_pending_unlocks) {
            panic!(
                "Unlock amount too large. Requested {} liquid tokens, which is larger than users balance ({}) minus existing (non-expired) pending unlocks ({}).",
                liquid_amount, liquid_balance, current_pending_unlocks
            )
        }
    }

    /// Overwrite the list of pending unlocks for the specified user.
    /// If the new list is empty, then remove the user from the pending_unlocks map.
    ///
    /// ## Parameters
    /// * `user`: The user who requests to unlock.
    /// * `new_pending_unlocks`: The list of pending unlocks for the user.
    fn replace_pending_unlocks(&mut self, user: Address, new_pending_unlocks: Vec<PendingUnlock>) {
        if new_pending_unlocks.is_empty() {
            self.pending_unlocks.remove(&user)
        } else {
            self.pending_unlocks.insert(user, new_pending_unlocks)
        }
    }

    /// Removes all expired pending unlocks
    ///
    /// ## Parameters
    /// * `current_time`: The block production time, when the clean up was requested.
    fn clean_up_pending_unlocks(&mut self, current_time: u64) {
        for (user, mut user_pending_unlocks) in self.pending_unlocks.iter() {
            user_pending_unlocks.retain(|x| !x.is_expired(current_time));
            self.replace_pending_unlocks(user, user_pending_unlocks);
        }
    }

    /// Calculate the sum of all liquid tokens in the (non-expired) pending unlocks for the specified user.
    ///
    /// ## Parameters
    /// * `user`: The user who requests to unlock.
    /// * `current_time`: The block production time.
    fn total_non_expired_pending_liquid_tokens_for_user(
        &self,
        user: Address,
        current_time: u64,
    ) -> u128 {
        match self.pending_unlocks.get(&user) {
            None => 0,
            Some(user_pending_unlocks) => {
                let mut liquid_amount = 0;
                for pending_unlock in user_pending_unlocks {
                    if !pending_unlock.is_expired(current_time) {
                        liquid_amount += pending_unlock.liquid_amount;
                    }
                }
                liquid_amount
            }
        }
    }

    /// Redeem all redeemable pending unlocks for the specified user.
    ///
    /// ## Parameters
    /// * `user`: The user wants to redeem all his redeemable tokens.
    /// * `current_time`: The block production time, when the redeem was requested.
    fn redeem(&mut self, user: Address, current_time: u64) -> u128 {
        let user_pending_unlocks = self
            .pending_unlocks
            .get(&user)
            .unwrap_or_else(|| panic!("User has no pending unlocks."));

        let mut liquid_amount = 0;
        let mut stake_token_amount = 0;
        let mut remaining_pending_unlocks = Vec::new();

        for pending_unlock in user_pending_unlocks {
            if pending_unlock.is_within_redeem_period(current_time) {
                liquid_amount += pending_unlock.liquid_amount;
                stake_token_amount += pending_unlock.stake_token_amount;
            } else {
                remaining_pending_unlocks.push(pending_unlock);
            }
        }

        if liquid_amount == 0 {
            panic!("User has no pending unlocks that are ready to be redeemed.");
        }

        if !self.does_contract_have_enough_stake_tokens(stake_token_amount) {
            panic!(
                "Tried to redeem more tokens than available on the contract. Token balance: {}, but tried to redeem {}",
                self.stake_token_balance, stake_token_amount
            )
        }

        self.burn_liquid_tokens(user, liquid_amount, stake_token_amount);
        self.replace_pending_unlocks(user, remaining_pending_unlocks);
        self.subtract_from_stake_token_balance(stake_token_amount);

        stake_token_amount
    }

    /// Mint all stake tokens in the buy_in_tokens, and reset the buy_in_tokens map.
    fn exchange_staking_tokens_from_buy_in(&mut self) {
        let mut accounts_to_reset = Vec::new();
        for (token_owner, amount_locked) in self.buy_in_tokens.iter() {
            self.mint_liquid_tokens(token_owner, amount_locked);
            accounts_to_reset.push(token_owner);
        }

        for account in accounts_to_reset {
            self.buy_in_tokens.insert(account, 0);
        }
        self.subtract_from_stake_pool(self.amount_of_buy_in_locked_stake_tokens);
        self.amount_of_buy_in_locked_stake_tokens = 0;
    }

    /// Disable buy in: exchange all locked buy in tokens and set buy in percentage to zero.
    fn disable_buy_in(&mut self) {
        self.buy_in_percentage = 0u128;
        self.buy_in_enabled = false;
        self.exchange_staking_tokens_from_buy_in();
    }

    /// Change the buy in percentage.
    ///
    /// ## Parameters
    /// * `new_buy_in_percentage`: The percentage the buy in will be changed to.
    fn change_buy_in(&mut self, new_buy_in_percentage: u128) {
        self.buy_in_percentage = new_buy_in_percentage;
        self.buy_in_enabled = true
    }

    /// Calculate how many of the submitted stake tokens that are locked by the buy in.
    ///
    /// ## Parameters
    /// * `amount_submitted`: The amount of stake tokens the user submitted.
    ///
    /// ## Returns
    /// The amount of stake tokens to be locked by the buy in.
    fn calculate_buy_in_amount(&mut self, amount_submitted: u128) -> u128 {
        amount_submitted * self.buy_in_percentage / 100
    }

    /// If buy in is enabled, then lock an amount of the submitted stake tokens.
    ///
    /// ## Parameters
    /// * `sender`: The user who submitted stake tokens.
    /// * `amount_submitted`: The amount of stake tokens the user submitted.
    ///
    /// ## Returns
    /// The amount of stake tokens remaining after the buy in tokens have been subtracted.
    fn lock_buy_in(&mut self, sender: Address, amount_submitted: u128) -> u128 {
        if self.buy_in_enabled {
            let current_amount_locked = self.buy_in_tokens.get(&sender).unwrap_or(0);
            let buy_in_amount = self.calculate_buy_in_amount(amount_submitted);
            self.buy_in_tokens
                .insert(sender, buy_in_amount + current_amount_locked);
            amount_submitted - buy_in_amount
        } else {
            amount_submitted
        }
    }
}

/// Initial function to bootstrap the contracts state. Must return the state-struct.
///
/// # Parameters:
///
/// * `_context`: initial context.
/// * `token_for_staking`: the address of the token used for liquid staking.
/// * `staking_responsible`: the address of the account responsible for staking.
/// * `administrator`: the address of the account responsible administrative tasks.
/// * `length_of_cooldown_period`: Number of milliseconds (ms) from the unlock request was registered until the pending unlock can be redeemed.
/// * `length_of_redeem_period`: Number of milliseconds (ms) from the pending unlock becomes redeemable until the pending unlock expires.
/// * `initial_buy_in_percentage`: The initial buy-in percentage used when user submits tokens.
/// * `liquid_token_name`: The name for the liquid token.  e.g. "LiquidMpcStakingToken".
/// * `liquid_token_symbol`:  The symbol of the token. E.g. "LMPCST".
/// * `decimals`: The number of decimals the token uses - e.g. 8,
/// means to divide the token amount by `100000000` to get its user representation.
///
#[init]
#[allow(clippy::too_many_arguments)]
pub fn initialize(
    _context: ContractContext,
    token_for_staking: Address,
    staking_responsible: Address,
    administrator: Address,
    length_of_cooldown_period: u64,
    length_of_redeem_period: u64,
    initial_buy_in_percentage: u128,
    liquid_token_name: String,
    liquid_token_symbol: String,
    decimals: u8,
) -> LiquidStakingState {
    LiquidStakingState {
        token_for_staking,
        stake_token_balance: 0,
        staking_responsible,
        administrator,
        total_pool_stake_token: 0,
        total_pool_liquid: 0,
        amount_of_buy_in_locked_stake_tokens: 0,
        liquid_token_state: LiquidTokenState::init(
            liquid_token_name,
            liquid_token_symbol,
            decimals,
        ),
        pending_unlocks: AvlTreeMap::new(),
        buy_in_tokens: AvlTreeMap::new(),
        length_of_cooldown_period,
        length_of_redeem_period,
        buy_in_percentage: initial_buy_in_percentage,
        buy_in_enabled: true,
    }
}

/// Transfers `amount` of liquid tokens to address `to` from the caller.
///
/// The function throws if the message caller's account
/// balance does not have enough tokens to spend.
/// If the sender's account goes to 0, the sender's address is removed from state.
///
/// # Parameters:
///
/// * `context`: The context for the action call.
/// * `state`: The current state of the contract.
/// * `to`: The address to transfer to.
/// * `amount`: Amount to transfer.
///
#[action(shortname = 0x01)]
pub fn transfer(
    context: ContractContext,
    mut state: LiquidStakingState,
    to: Address,
    amount: u128,
) -> (LiquidStakingState, Vec<EventGroup>) {
    state
        .liquid_token_state
        .transfer(context.sender, to, amount);
    (state, vec![])
}

/// Transfers `amount` of liquid tokens from address `from` to address `to`.
///
/// This requires that the sender is allowed to do the transfer by the `from`
/// account through the `approve` action.
/// The function throws if the message caller's account
/// balance does not have enough tokens to spend, or if the tokens were not approved.
///
/// # Parameters:
///
/// * `context`:  The context for the action call.
/// * `state`:  The current state of the contract.
/// * `from`:  The address to transfer from.
/// * `to`:  The address to transfer to.
/// * `amount`:  Amount to transfer.
///
#[action(shortname = 0x03)]
pub fn transfer_from(
    context: ContractContext,
    mut state: LiquidStakingState,
    from: Address,
    to: Address,
    amount: u128,
) -> (LiquidStakingState, Vec<EventGroup>) {
    state
        .liquid_token_state
        .transfer_from(context.sender, from, to, amount);
    (state, vec![])
}

/// Allows `spender` to withdraw liquid tokens from the owners account multiple times, up to the `amount`.
///
/// If this function is called again it overwrites the current allowance with `amount`.
///
/// # Parameters:
///
/// * `context`: The context for the action call.
/// * `state`: The current state of the contract.
/// * `spender`: The address of the spender.
/// * `amount`: Approved amount.
///
#[action(shortname = 0x05)]
pub fn approve(
    context: ContractContext,
    mut state: LiquidStakingState,
    spender: Address,
    amount: u128,
) -> (LiquidStakingState, Vec<EventGroup>) {
    state
        .liquid_token_state
        .update_allowance(context.sender, spender, amount);
    (state, vec![])
}

/// Submit staking tokens for liquid staking.
/// The tokens are minted to liquid tokens and added to the pools and user's liquid balance.
///
/// This requires that this contract is allowed to do the transfer on behalf of the user
/// through the `approve` action on the token contract.
/// The function throws if the message caller's account balance does not have enough tokens
/// to spend, or if the tokens were not approved.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
///  * `stake_token_amount`: The amount of stake tokens to submit.
///
#[action(shortname = 0x10)]
pub fn submit(
    context: ContractContext,
    state: LiquidStakingState,
    stake_token_amount: u128,
) -> (LiquidStakingState, Vec<EventGroup>) {
    if stake_token_amount == 0 {
        panic!("Cannot submit zero tokens for liquid staking.")
    }

    let mut event_group = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(state.token_for_staking).transfer_from(
        &mut event_group,
        &context.sender,
        &context.contract_address,
        stake_token_amount,
    );
    event_group
        .with_callback(SHORTNAME_SUBMIT_CALLBACK)
        .argument(stake_token_amount)
        .with_cost(600)
        .done();
    (state, vec![event_group.build()])
}

/// Handles callback from [`submit`]. <br>
///
/// # Parameters:
///
/// * `context`: The contractContext for the callback.
/// * `callback_context`: The callbackContext.
/// * `state`: The current state of the contract.
/// * `stake_token_amount`: The amount of the stake token the user submits to the contract.
///
#[callback(shortname = 0x10)]
pub fn submit_callback(
    context: ContractContext,
    callback_context: CallbackContext,
    mut state: LiquidStakingState,
    stake_token_amount: u128,
) -> LiquidStakingState {
    assert!(callback_context.success, "Transfer did not succeed");

    state.mint_liquid_tokens(context.sender, stake_token_amount);
    state.add_to_stake_token_balance(stake_token_amount);

    state
}

/// Withdraws an amount of the stake tokens from this contract.
/// This does not change the pools or any user balances.
///
/// Only the staking responsible is allowed to withdraw tokens from the contract.
/// The function throws if the contract's balance does not have enough tokens
/// to spend.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
///  * `stake_token_amount`: The amount of the stake tokens to withdraw.
///
#[action(shortname = 0x11)]
pub fn withdraw(
    context: ContractContext,
    mut state: LiquidStakingState,
    stake_token_amount: u128,
) -> (LiquidStakingState, Vec<EventGroup>) {
    if !state.is_the_staking_responsible(context.sender) {
        panic!(
            "Unauthorized to withdraw tokens. Only the registered staking responsible (at address: {}) can withdraw tokens.",
            state.staking_responsible
        )
    }
    if stake_token_amount == 0 {
        panic!("Cannot withdraw zero tokens.")
    }
    if !state.does_contract_have_enough_stake_tokens(stake_token_amount) {
        panic!(
            "The staking responsible tried to withdraw more tokens than available on the contract."
        )
    }

    state.subtract_from_stake_token_balance(stake_token_amount);

    let mut event_group = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(state.token_for_staking).transfer(
        &mut event_group,
        &context.sender,
        stake_token_amount,
    );
    (state, vec![event_group.build()])
}

/// Accrue rewards by adding the rewarded amount of the stake tokens to the pool.
///
/// Only the staking responsible is allowed to accrue rewards to the contract.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
///  * `stake_token_amount`: The amount of the stake tokens in the reward.
///
#[action(shortname = 0x12)]
pub fn accrue_rewards(
    context: ContractContext,
    mut state: LiquidStakingState,
    stake_token_amount: u128,
) -> LiquidStakingState {
    if !state.is_the_staking_responsible(context.sender) {
        panic!(
            "Unauthorized to accrue rewards. Only the registered staking responsible (at address: {}) can accrue rewards.",
            state.staking_responsible
        )
    }
    if stake_token_amount == 0 {
        panic!("Cannot accrue rewards of zero tokens.")
    }

    state.add_to_stake_pool(stake_token_amount);

    state
}

/// Request unlock of liquid tokens.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
///  * `liquid_amount`: The amount of liquid tokens to unlock.
///
#[action(shortname = 0x13)]
pub fn request_unlock(
    context: ContractContext,
    mut state: LiquidStakingState,
    liquid_amount: u128,
) -> LiquidStakingState {
    if liquid_amount == 0 {
        panic!("Cannot unlock zero tokens.")
    }

    state.add_to_pending_unlocks(
        context.sender,
        liquid_amount,
        context.block_production_time as u64,
    );

    state
}

/// Deposit an amount of the stake tokens to this contract.
/// This does not change the pools or any user balances.
///
/// Only the staking responsible is allowed to deposit tokens to the contract.
/// The function throws if the message caller's account balance does not have enough tokens
/// to spend, or if the tokens were not approved.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
///  * `stake_token_amount`: The amount of the stake tokens to submit.
///
#[action(shortname = 0x14)]
pub fn deposit(
    context: ContractContext,
    state: LiquidStakingState,
    stake_token_amount: u128,
) -> (LiquidStakingState, Vec<EventGroup>) {
    if !state.is_the_staking_responsible(context.sender) {
        panic!(
            "Unauthorized to deposit tokens. Only the registered staking responsible (at address: {}) can deposit tokens.",
            state.staking_responsible
        )
    }
    if stake_token_amount == 0 {
        panic!("Cannot deposit zero tokens.")
    }

    let mut event_group = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(state.token_for_staking).transfer_from(
        &mut event_group,
        &context.sender,
        &context.contract_address,
        stake_token_amount,
    );
    event_group
        .with_callback(SHORTNAME_DEPOSIT_CALLBACK)
        .argument(stake_token_amount)
        .with_cost(600)
        .done();

    (state, vec![event_group.build()])
}

/// Handles callback from [`deposit`]. <br>
///
/// # Parameters:
///
/// * `_context`: The contractContext for the callback.
/// * `callback_context`: The callbackContext.
/// * `state`: The current state of the contract.
/// * `stake_token_amount`: The amount of the stake token the user submits to the contract.
///
#[callback(shortname = 0x15)]
pub fn deposit_callback(
    _context: ContractContext,
    callback_context: CallbackContext,
    mut state: LiquidStakingState,
    stake_token_amount: u128,
) -> LiquidStakingState {
    assert!(callback_context.success, "Transfer did not succeed");

    state.add_to_stake_token_balance(stake_token_amount);

    state
}

/// Redeem all liquid tokens from the users pending unlocks that are in the redeem period.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
///
#[action(shortname = 0x15)]
pub fn redeem(
    context: ContractContext,
    mut state: LiquidStakingState,
) -> (LiquidStakingState, Vec<EventGroup>) {
    let stake_token_amount = state.redeem(context.sender, context.block_production_time as u64);

    let mut event_group = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(state.token_for_staking).transfer(
        &mut event_group,
        &context.sender,
        stake_token_amount,
    );
    (state, vec![event_group.build()])
}

/// Change the percentage of buy-in locked tokens taken from all future submission.
///
/// Only the administrator is allowed to change the buy in percentage.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
///  * `new_buy_in_percentage`: The new percentage locked on submission.
///
#[action(shortname = 0x16)]
pub fn change_buy_in(
    context: ContractContext,
    mut state: LiquidStakingState,
    new_buy_in_percentage: u128,
) -> LiquidStakingState {
    if !state.is_the_administrator(context.sender) {
        panic!(
            "Cannot change the buy-in percentage. Only the registered administrator (at address: {}) can change the buy-in percentage.",
            state.administrator
        )
    }

    state.change_buy_in(new_buy_in_percentage);
    state
}

/// Disable the buy-in, such that all submission will not get a percentage locked anymore.
/// Unlock all currently buy-in locked tokens and exchange the tokens to the liquid token.
///
/// Only the administrator is allowed to disable the buy in.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
///
#[action(shortname = 0x17)]
pub fn disable_buy_in(
    context: ContractContext,
    mut state: LiquidStakingState,
) -> LiquidStakingState {
    if !state.is_the_administrator(context.sender) {
        panic!(
            "Cannot disable buy-in. Only the registered administrator (at address: {}) can disable buy-in.",
            state.administrator
        )
    }
    if !state.buy_in_enabled {
        panic!("Cannot disable buy-in, when it is already disabled.")
    }
    state.disable_buy_in();

    state
}

/// Clean up by removing all expired pending unlocks.
/// If a user does not redeem a pending unlock within the redeem period, then the pending unlock expires.
///
/// Only the administrator is allowed to clean up the pending unlocks.
///
/// # Parameters:
///
///  * `context`: The contract context containing sender and chain information.
///  * `state`: The current state of the contract.
///
#[action(shortname = 0x18)]
pub fn clean_up_pending_unlocks(
    context: ContractContext,
    mut state: LiquidStakingState,
) -> LiquidStakingState {
    if !state.is_allowed_to_clean_pending_unlocks(context.sender) {
        panic!(
            "Cannot clean up pending unlocks. Only the registered administrator (at address: {}) or staking responsible (at address: {}) can clean up pending unlocks.",
            state.administrator, state.staking_responsible
        )
    }
    state.clean_up_pending_unlocks(context.block_production_time as u64);

    state
}
