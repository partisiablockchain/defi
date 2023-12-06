#![doc = include_str!("../README.md")]

#[cfg(test)]
mod tests;

#[macro_use]
extern crate pbc_contract_codegen;
extern crate core;

use pbc_contract_common::address::Address;
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::EventGroup;
use pbc_contract_common::sorted_vec_map::SortedVecMap;
use std::ops::RangeInclusive;

use create_type_spec_derive::CreateTypeSpec;
use read_write_state_derive::ReadWriteState;

use defi_common::interact_mpc20;
use defi_common::liquidity_util::{
    calculate_swap_to_amount, AcquiredLiquidityLockInformation, LiquidityLockId,
};
use defi_common::math::u128_sqrt;
use defi_common::permission::Permission;
pub use defi_common::token_balances::Token;
use defi_common::token_balances::{TokenAmount, TokenBalance, TokenBalances, TokensInOut};

/// The range of allowed [`LiquiditySwapContractState::swap_fee_per_mille`].
pub const ALLOWED_FEE_PER_MILLE: RangeInclusive<u16> = 0..=1000;

/// Stores data about a lock, which is later used when the lock is executed or cancelled.
#[derive(ReadWriteState, CreateTypeSpec, Debug)]
pub struct LiquidityLock {
    amount_in: TokenAmount,
    amount_out: TokenAmount,
    tokens_in_out: TokensInOut,
    owner: Address,
}

/// The virtual states manages the virtual liquidity pools and any locks.
#[derive(ReadWriteState, CreateTypeSpec)]
pub struct VirtualState {
    /// Id used for the next acquired lock.
    next_lock_id: LiquidityLockId,
    /// Stores lock information needed to execute or cancel locks.
    /// Implicitly defines the virtual state as the actual state plus the sum of the lock values.
    locks: SortedVecMap<LiquidityLockId, LiquidityLock>,
}

impl Default for VirtualState {
    fn default() -> Self {
        Self::new()
    }
}

impl VirtualState {
    /// A new virtual state contains no locks and starts `lock_id` at 0.
    pub fn new() -> Self {
        Self {
            next_lock_id: LiquidityLockId::initial_id(),
            locks: SortedVecMap::new(),
        }
    }

    /// Adds a `lock` to the virtual state, implicitly updating the virtual state as if the swap has happened.
    ///
    /// Will associate the next unique lock id with the `lock`, which is returned.
    fn add_lock(&mut self, lock: LiquidityLock) -> LiquidityLockId {
        let lock_id = self.next_lock_id();
        self.locks.insert(lock_id, lock);

        lock_id
    }

    /// Removes a lock from the virtual state, if `lock_id` is a valid id, and associated with `sender`.
    fn remove_lock(&mut self, lock_id: LiquidityLockId, sender: Address) -> LiquidityLock {
        let lock = self.remove_lock_internal(lock_id);
        assert_eq!(
            sender, lock.owner,
            "Permission denied to handle lockID {:?}.",
            lock_id
        );
        lock
    }

    /// Removes a lock from the virtual state, if `lock_id` is a valid id.
    fn remove_lock_internal(&mut self, lock_id: LiquidityLockId) -> LiquidityLock {
        self.locks
            .remove(&lock_id)
            .unwrap_or_else(|| panic!("{:?} is not a valid lock id.", lock_id))
    }

    /// Returns the virtual pool state, which is `actual_a` + sum(lock_a), `actual_b` + sum(lock_b).
    ///
    /// Expired locks do not contribute, and are subsequently removed from the state.
    fn virtual_liquidity_pools(
        &mut self,
        actual_a: TokenAmount,
        actual_b: TokenAmount,
    ) -> TokenBalance {
        let mut res = TokenBalance {
            a_tokens: actual_a,
            b_tokens: actual_b,
            liquidity_tokens: 0,
        };

        for lock in self.locks.values() {
            *res.get_mut_amount_of(lock.tokens_in_out.token_in) += lock.amount_in;
            *res.get_mut_amount_of(lock.tokens_in_out.token_out) -= lock.amount_out;
        }

        res
    }

    /// Returns an id for a requested lock, and updates state for a future lock id.
    fn next_lock_id(&mut self) -> LiquidityLockId {
        let res = self.next_lock_id;
        self.next_lock_id = self.next_lock_id.next();
        res
    }

    /// True if locks currently exists, otherwise false.
    pub fn any_locked_liquidity(&self) -> bool {
        self.locks.is_empty()
    }
}

/// This is the state of the contract which is persisted on the chain.
///
/// The #\[state\] macro generates serialization logic for the struct.
#[state]
pub struct LiquiditySwapContractState {
    /// Who is allowed to acquired locks.
    pub permission_lock_swap: Permission,
    /// The address of this contract
    pub liquidity_pool_address: Address,
    /// The fee for making swaps per mille. Must be in range [`ALLOWED_FEE_PER_MILLE`].
    pub swap_fee_per_mille: u16,
    /// The map containing all token balances of all users and the contract itself. <br>
    /// The contract should always have a balance equal to the sum of all token balances.
    pub token_balances: TokenBalances,
    /// Contains the virtual liquidity pool state, and its locks.
    pub virtual_state: VirtualState,
}

impl LiquiditySwapContractState {
    /// Checks that the pools of the contracts have liquidity.
    ///
    /// ### Parameters:
    ///
    ///  * `state`: [`&LiquiditySwapContractState`] - A reference to the current state of the contract.
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
/// # Parameters
///
///   * `context`: [`ContractContext`] - The contract context containing sender and chain information.
///
///   * `token_a_address`: [`Address`] - The address of token A.
///
///   * `token_b_address`: [`Address`] - The address of token B.
///
///   * `swap_fee_per_mille`: [`TokenAmount`] - The fee for swapping, in per mille, i.e. a fee set to 3 corresponds to a fee of 0.3%.
///
///
/// The new state object of type [`LiquiditySwapContractState`] with all address fields initialized to their final state and remaining fields initialized to a default value.
///
#[init]
pub fn initialize(
    context: ContractContext,
    permission_lock_swap: Permission,
    token_a_address: Address,
    token_b_address: Address,
    swap_fee_per_mille: u16,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
    if !ALLOWED_FEE_PER_MILLE.contains(&swap_fee_per_mille) {
        panic!("Swap fee must be in range [0,1000]");
    }

    let token_balances =
        match TokenBalances::new(context.contract_address, token_a_address, token_b_address) {
            Ok(tb) => tb,
            Err(msg) => panic!("{}", msg),
        };

    let new_state = LiquiditySwapContractState {
        permission_lock_swap,
        liquidity_pool_address: context.contract_address,
        swap_fee_per_mille,
        token_balances,
        virtual_state: VirtualState::default(),
    };

    (new_state, vec![])
}

/// Deposit token {A, B} into the calling user's balance on the contract.
///
/// Requires that the swap contract has been approved at `token_address`
/// by the sender. This is checked in a callback, implicitly guaranteeing
/// that this only returns after the deposit transfer is complete.
///
/// ### Parameters:
///
///  * `context`: [`ContractContext`] - The contract context containing sender and chain information.
///
///  * `state`: [`LiquiditySwapContractState`] - The current state of the contract.
///
///  * `token_address`: [`Address`] - The address of the deposited token contract.
///
///  * `amount`: [`TokenAmount`] - The amount to deposit.
///
/// # Returns
/// The unchanged state object of type [`LiquiditySwapContractState`].
#[action(shortname = 0x01)]
pub fn deposit(
    context: ContractContext,
    state: LiquiditySwapContractState,
    token_address: Address,
    amount: TokenAmount,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
    let tokens = state.token_balances.deduce_tokens_in_out(token_address);

    let mut event_group_builder = EventGroup::builder();
    interact_mpc20::MPC20Contract::at_address(token_address).transfer_from(
        &mut event_group_builder,
        &context.sender,
        &state.liquidity_pool_address,
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
/// * `state`: [`LiquiditySwapContractState`] - The current state of the contract.
///
/// * `token`: [`Token`] - Indicating the token of which to add `amount` to.
///
/// * `amount`: [`TokenAmount`] - The desired amount to add to the user's total amount of `token`.
/// ### Returns
///
/// The updated state object of type [`LiquiditySwapContractState`] with an updated entry for the caller of `deposit`.
#[callback(shortname = 0x10)]
pub fn deposit_callback(
    context: ContractContext,
    callback_context: CallbackContext,
    mut state: LiquiditySwapContractState,
    token: Token,
    amount: TokenAmount,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
    assert!(callback_context.success, "Transfer did not succeed");

    state
        .token_balances
        .add_to_token_balance(context.sender, token, amount);

    (state, vec![])
}

/// Swap <em>amount</em> of token A or B to the output token at the exchange rate dictated by <em>the constant product formula</em>.
/// The swap is executed on the token balances for the calling user.
///
/// The action will fail when:
///
/// - The contract does not have any liquidity.
/// - The caller does not have sufficient input token balance.
/// - The amount of output tokens is less than minimum specified (`amount_out_minimum`).
///
/// ### Parameters:
///
///  * `context`: [`ContractContext`] - The contract context containing sender and chain information.
///
///  * `state`: [`LiquiditySwapContractState`] - The current state of the contract.
///
///  * `token_address`: [`Address`] - The address of the token contract being swapped from.
///
///  * `amount_in`: [`TokenAmount`] - The amount to swap of the token matching `input_token`.
///
///  * `amount_out_minimum`: [`TokenAmount`] - The minimum allowed amount of output tokens from the
///    swap. Should basically never be `0`, and should preferably be computed client-side with
///    a set amount of allowed slippage.
///
/// # Returns
/// The updated state object of type [`LiquiditySwapContractState`] yielding the result of the swap.
#[action(shortname = 0x02)]
pub fn instant_swap(
    context: ContractContext,
    mut state: LiquiditySwapContractState,
    token_in: Address,
    amount_in: TokenAmount,
    amount_out_minimum: TokenAmount,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
    assert!(
        state.contract_pools_have_liquidity(),
        "Pools must have existing liquidity to perform a swap"
    );

    // Instant swaps can be represented by acquiring a lock, and executing it straight away.
    let (lock_id, _) = lock_internal(
        &mut state,
        amount_in,
        token_in,
        amount_out_minimum,
        context.sender,
    );
    execute_lock_swap_internal(&mut state, lock_id, context.sender);

    (state, vec![])
}

/// Withdraw <em>amount</em> of token {A, B} from the contract for the calling user.
/// This fails if `amount` is larger than the token balance of the corresponding token.
///
/// It preemptively updates the state of the user's balance before making the transfer.
/// This means that if the transfer fails, the contract could end up with more money than it has registered, which is acceptable.
/// This is to incentivize the user to spend enough gas to complete the transfer.
/// If `wait_for_callback` is true, any callbacks will happen only after the withdrawal has completed.
///
/// ### Parameters:
///
///  * `context`: [`ContractContext`] - The contract context containing sender and chain information.
///
///  * `state`: [`LiquiditySwapContractState`] - The current state of the contract.
///
///  * `token_address`: [`Address`] - The address of the token contract to withdraw to.
///
///  * `amount`: [`TokenAmount`] - The amount to withdraw.
///
/// # Returns
/// The unchanged state object of type [`LiquiditySwapContractState`].
#[action(shortname = 0x03)]
pub fn withdraw(
    context: ContractContext,
    mut state: LiquiditySwapContractState,
    token_address: Address,
    amount: TokenAmount,
    wait_for_callback: bool,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
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

#[callback(shortname = 0x15)]
fn wait_withdraw_callback(
    _context: ContractContext,
    _callback_context: CallbackContext,
    state: LiquiditySwapContractState,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
    (state, vec![])
}

/// Become a liquidity provider to the contract by providing `amount` of tokens from the caller's balance. <br>
/// An equivalent amount of the output token is required to succeed and will be token_in implicitly. <br>
/// This is the inverse of [`reclaim_liquidity`].
///
/// ### Parameters:
///
///  * `context`: [`ContractContext`] - The contract context containing sender and chain information.
///
///  * `state`: [`LiquiditySwapContractState`] - The current state of the contract.
///
///  * `token_address`: [`Address`] - The address of the input token.
///
///  * `token_amount`: [`TokenAmount`] - The amount to provide.
///
/// # Returns
/// The unchanged state object of type [`LiquiditySwapContractState`].
#[action(shortname = 0x04)]
pub fn provide_liquidity(
    context: ContractContext,
    mut state: LiquiditySwapContractState,
    token_address: Address,
    amount: TokenAmount,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
    let user = &context.sender;
    let tokens = state.token_balances.deduce_tokens_in_out(token_address);
    let contract_token_balance = state
        .token_balances
        .get_balance_for(&state.liquidity_pool_address);

    let (token_out_equivalent, minted_liquidity_tokens) = calculate_equivalent_and_minted_tokens(
        amount,
        contract_token_balance.get_amount_of(tokens.token_in),
        contract_token_balance.get_amount_of(tokens.token_out),
        contract_token_balance.liquidity_tokens,
    );
    assert!(
        minted_liquidity_tokens > 0,
        "The given input amount yielded 0 minted liquidity"
    );

    provide_liquidity_internal(
        &mut state,
        user,
        tokens,
        amount,
        token_out_equivalent,
        minted_liquidity_tokens,
    );
    (state, vec![])
}

/// Reclaim a calling user's share of the contract's total liquidity based on `liquidity_token_amount`. <br>
/// This is the inverse of [`provide_liquidity`].
///
/// Liquidity tokens are synonymous to weighted shares of the contract's total liquidity. <br>
/// As such, we calculate how much to output of token A and B,
/// based on the ratio between the input liquidity token amount and the total amount of liquidity minted by the contract.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`] - The context for the action call.
///
/// * `state`: [`LiquiditySwapContractState`] - The current state of the contract.
///
/// * `liquidity_token_amount`: [`TokenAmount`] - The amount of liquidity tokens to burn.
///
/// ### Returns
///
/// The updated state object of type [`LiquiditySwapContractState`].
#[action(shortname = 0x05)]
pub fn reclaim_liquidity(
    context: ContractContext,
    mut state: LiquiditySwapContractState,
    liquidity_token_amount: TokenAmount,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
    assert!(
        state.virtual_state.any_locked_liquidity(),
        "Cannot reclaim liquidity while locks are present."
    );

    let user = &context.sender;

    state
        .token_balances
        .deduct_from_token_balance(*user, Token::LIQUIDITY, liquidity_token_amount);

    let contract_token_balance = state
        .token_balances
        .get_balance_for(&state.liquidity_pool_address);

    let (a_output, b_output) = calculate_reclaim_output(
        liquidity_token_amount,
        contract_token_balance.a_tokens,
        contract_token_balance.b_tokens,
        contract_token_balance.liquidity_tokens,
    );

    state
        .token_balances
        .move_tokens(state.liquidity_pool_address, *user, Token::A, a_output);
    state
        .token_balances
        .move_tokens(state.liquidity_pool_address, *user, Token::B, b_output);
    state.token_balances.deduct_from_token_balance(
        state.liquidity_pool_address,
        Token::LIQUIDITY,
        liquidity_token_amount,
    );

    (state, vec![])
}

/// Initialize token liquidity pools, and mint initial liquidity tokens.
///
/// Calling this action makes the calling user the first liquidity provider, receiving liquidity
/// tokens amounting to 100% of the contract's total liquidity, until another user becomes an
/// liquidity provider.
///
/// ### Parameters:
///
///  * `context`: [`ContractContext`] - The contract context containing sender and chain information.
///
///  * `state`: [`LiquiditySwapContractState`] - The current state of the contract.
///
///  * `token_a_amount`: [`TokenAmount`] - The amount to initialize pool A with.
///
///  * `token_b_amount`: [`TokenAmount`] - The amount to initialize pool B with.
///
/// # Returns
///
/// The updated state object of type [`LiquiditySwapContractState`].
#[action(shortname = 0x06)]
pub fn provide_initial_liquidity(
    context: ContractContext,
    mut state: LiquiditySwapContractState,
    token_a_amount: TokenAmount,
    token_b_amount: TokenAmount,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
    assert!(
        !state.contract_pools_have_liquidity(),
        "Can only initialize when both pools are empty"
    );

    let minted_liquidity_tokens = initial_liquidity_tokens(token_a_amount, token_b_amount);
    assert!(
        minted_liquidity_tokens > 0,
        "The given input amount yielded 0 minted liquidity"
    );

    provide_liquidity_internal(
        &mut state,
        &context.sender,
        TokensInOut::A_IN_B_OUT,
        token_a_amount,
        token_b_amount,
        minted_liquidity_tokens,
    );
    (state, vec![])
}

/// Saves a lock on the current state of the liquidity pools for Token A and B,
/// implicitly updating the virtual pools.
///
/// A lock acts as a privilege for swapping `amount_in` of `token_in`, and receiving at least
/// `amount_out_minimum` of the token being swapped to, at a later point in time,
/// at the minimum exchange rate given by the actual and virtual liquidity pool states,
/// at the acquisition time of the lock.
/// The id, and output amount of the lock is returned to any callbacks.
/// Other users can still interact with the swap contract while the lock exists.
///
/// # Fails
///
/// Fails if `amount_out_minimum` is greater than what the current contract state will provide.
/// Fails if the sender (caller) does not have permission to acquire locks.
#[action(shortname = 0x07)]
pub fn acquire_swap_lock(
    context: ContractContext,
    mut state: LiquiditySwapContractState,
    token_in: Address,
    amount_in: TokenAmount,
    amount_out_minimum: TokenAmount,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
    state
        .permission_lock_swap
        .assert_permission_for(&context.sender, "lock swap");
    assert!(
        state.contract_pools_have_liquidity(),
        "Pools must have existing liquidity to acquire a lock"
    );

    // Acquire a lock internally.
    let (lock_id, amount_out) = lock_internal(
        &mut state,
        amount_in,
        token_in,
        amount_out_minimum,
        context.sender,
    );

    // Pass the lock id to any callbacks.
    let mut event_group_builder = EventGroup::builder();
    let lock_info = AcquiredLiquidityLockInformation {
        lock_id,
        amount_out,
    };
    event_group_builder.return_data(lock_info);

    (state, vec![event_group_builder.build()])
}

/// Calculates the received amount of the outgoing swap token, if swapping `amount_in` of `token_in`,
/// and updates the virtual state with a lock.
///
/// Fails if the calculated receiving amount is less than `amount_out_minimum`.
/// The `owner` becomes the address associated with the lock, who has sole permission to execute it.
fn lock_internal(
    state: &mut LiquiditySwapContractState,
    amount_in: TokenAmount,
    token_in: Address,
    amount_out_minimum: TokenAmount,
    owner: Address,
) -> (LiquidityLockId, TokenAmount) {
    let tokens = state.token_balances.deduce_tokens_in_out(token_in);

    let amount_out = calculate_minimum_swap_to_amount(state, amount_in, &tokens);

    if amount_out < amount_out_minimum {
        panic!(
            "Swap would produce {} output tokens, but minimum was set to {}.",
            amount_out, amount_out_minimum
        );
    }

    let tokens_in_out = state.token_balances.deduce_tokens_in_out(token_in);

    let lock = LiquidityLock {
        amount_in,
        amount_out,
        tokens_in_out,
        owner,
    };
    (state.virtual_state.add_lock(lock), amount_out)
}

/// Executes a previously acquired lock, performing the intended swap and
/// updating the actual balances of the contract.
///
/// Returns the amount received from the swap to any registered callbacks.
///
/// # Fails
///
/// If an unknown `lock_id` is provided this fails.
/// Also fails if a user who didn't acquire the lock associated with `lock_id` tries to execute it.
#[action(shortname = 0x08)]
pub fn execute_lock_swap(
    context: ContractContext,
    mut state: LiquiditySwapContractState,
    lock_id: LiquidityLockId,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
    let output_amount = execute_lock_swap_internal(&mut state, lock_id, context.sender);

    let mut return_event = EventGroup::builder();
    return_event.return_data(output_amount);

    (state, vec![return_event.build()])
}

/// Removes the lock associated with `lock_id` from the internal state and executes the corresponding swap,
/// exchanging tokens on the actual liquidity pools.
///
/// Returns the output amount of the lock.
///
/// Does nothing if the lock was not acquired by `sender`.
fn execute_lock_swap_internal(
    state: &mut LiquiditySwapContractState,
    lock_id: LiquidityLockId,
    sender: Address,
) -> TokenAmount {
    let lock = state.virtual_state.remove_lock(lock_id, sender);

    state.token_balances.move_tokens(
        lock.owner,
        state.liquidity_pool_address,
        lock.tokens_in_out.token_in,
        lock.amount_in,
    );
    state.token_balances.move_tokens(
        state.liquidity_pool_address,
        lock.owner,
        lock.tokens_in_out.token_out,
        lock.amount_out,
    );

    lock.amount_out
}

/// Cancels a previously acquired lock, updating the virtual balances of the contract,
/// as if the swap didn't happen.
///
/// If an unknown `lockID` is provided this fails.
/// Also fails if a user who didn't acquire the lock associated with `lockID` tries to cancel the lock.
#[action(shortname = 0x09)]
pub fn cancel_lock(
    context: ContractContext,
    mut state: LiquiditySwapContractState,
    lock_id: LiquidityLockId,
) -> (LiquiditySwapContractState, Vec<EventGroup>) {
    state.virtual_state.remove_lock(lock_id, context.sender);

    (state, vec![])
}

/// Determines the initial amount of liquidity tokens, or shares, representing some sensible '100%' of the contract's liquidity. <br>
/// This implementation is derived from section 3.4 of: [Uniswap v2 whitepaper](https://uniswap.org/whitepaper.pdf). <br>
/// It guarantees that the value of a liquidity token becomes independent of the ratio at which liquidity was initially token_in.
fn initial_liquidity_tokens(
    token_a_amount: TokenAmount,
    token_b_amount: TokenAmount,
) -> TokenAmount {
    u128_sqrt(token_a_amount * token_b_amount).into()
}

/// Given a fee, calculates the minimum amount of output tokens received between
/// the actual and virtual pool states when swapping `swap_amount_in` input tokens.
///
/// If there are locks present the states of the actual and virtual pools don't match,
/// and the exchange rate is given as the minimum exchange rate between the actual and virtual pools,
/// as calculated by [`calculate_swap_to_amount`].
/// When no locks are present, this is equivalent to [`calculate_swap_to_amount`].
fn calculate_minimum_swap_to_amount(
    state: &mut LiquiditySwapContractState,
    amount_in: TokenAmount,
    tokens_in_out: &TokensInOut,
) -> TokenAmount {
    let actual_balance = state
        .token_balances
        .get_balance_for(&state.liquidity_pool_address);

    let actual_a = actual_balance.get_amount_of(Token::A);
    let actual_b = actual_balance.get_amount_of(Token::B);
    let virtual_balance = state
        .virtual_state
        .virtual_liquidity_pools(actual_a, actual_b);

    let non_locked_rate = calculate_swap_to_amount(
        actual_balance.get_amount_of(tokens_in_out.token_in),
        actual_balance.get_amount_of(tokens_in_out.token_out),
        amount_in,
        state.swap_fee_per_mille,
    );
    let locked_rate = calculate_swap_to_amount(
        virtual_balance.get_amount_of(tokens_in_out.token_in),
        virtual_balance.get_amount_of(tokens_in_out.token_out),
        amount_in,
        state.swap_fee_per_mille,
    );

    non_locked_rate.min(locked_rate)
}

/// Finds the equivalent value of the output token during [`provide_liquidity`] based on the input amount and the weighted shares that they correspond to. <br>
/// Due to integer rounding, a user may be depositing an additional token and mint one less than expected. <br>
/// Calculations are derived from section 2.1.2 of [UniSwap v1 whitepaper](https://github.com/runtimeverification/verified-smart-contracts/blob/uniswap/uniswap/x-y-k.pdf)
///
/// ### Parameters:
///
/// * `token_in_amount`: [`TokenAmount`] - The amount being token_in to the contract.
///
/// * `token_in_pool`: [`TokenAmount`] - The token pool matching the input token.
///
/// * `token_out_pool`: [`TokenAmount`] - The token_out pool.
///
/// * `total_minted_liquidity` [`TokenAmount`] - The total current minted liquidity.
/// # Returns
/// The new A pool, B pool and minted liquidity values ([`TokenAmount`], [`TokenAmount`], [`TokenAmount`])
fn calculate_equivalent_and_minted_tokens(
    token_in_amount: TokenAmount,
    token_in_pool: TokenAmount,
    token_out_pool: TokenAmount,
    total_minted_liquidity: TokenAmount,
) -> (TokenAmount, TokenAmount) {
    // Handle zero-case
    let token_out_equivalent = if token_in_amount > 0 {
        (token_in_amount * token_out_pool / token_in_pool) + 1
    } else {
        0
    };
    let minted_liquidity_tokens = token_in_amount * total_minted_liquidity / token_in_pool;
    (token_out_equivalent, minted_liquidity_tokens)
}

/// Calculates the amount of token {A, B} that the input amount of liquidity tokens correspond to during [`reclaim_liquidity`]. <br>
/// Due to integer rounding, a user may be withdrawing less of each pool token than expected. <br>
/// Calculations are derived from section 2.2.2 of [UniSwap v1 whitepaper](
/// https://github.com/runtimeverification/verified-smart-contracts/blob/uniswap/uniswap/x-y-k.pdf)
///
/// ### Parameters:
///
/// * `liquidity_token_amount`: [`TokenAmount`] - The amount of liquidity tokens being reclaimed.
///
/// * `pool_a`: [`TokenAmount`] - Pool a of this contract.
///
/// * `pool_b`: [`TokenAmount`] - Pool b of this contract.
///
/// * `minted_liquidity` [`TokenAmount`] - The total current minted liquidity.
/// # Returns
/// The new A pool, B pool and minted liquidity values ([`TokenAmount`], [`TokenAmount`], [`TokenAmount`])
fn calculate_reclaim_output(
    liquidity_token_amount: TokenAmount,
    pool_a: TokenAmount,
    pool_b: TokenAmount,
    minted_liquidity: TokenAmount,
) -> (TokenAmount, TokenAmount) {
    let a_output = pool_a * liquidity_token_amount / minted_liquidity;
    let b_output = pool_b * liquidity_token_amount / minted_liquidity;
    (a_output, b_output)
}

/// Moves tokens from the providing user's balance to the contract's and mints liquidity tokens.
///
/// ### Parameters:
///
///  * `state`: [`LiquiditySwapContractState`] - The current state of the contract.
///
/// * `user`: [`&Address`] - The address of the user providing liquidity.
///
/// * `token_in`: [`Address`] - The address of the token being token_in.
///
///  * `token_in_amount`: [`TokenAmount`] - The input token amount.
///
///  * `token_out_amount`: [`TokenAmount`] - The output token amount. Must be equal value to `token_in_amount` at the current exchange rate.
///
///  * `minted_liquidity_tokens`: [`TokenAmount`] - The amount of liquidity tokens that the input tokens yields.
fn provide_liquidity_internal(
    state: &mut LiquiditySwapContractState,
    user: &Address,
    tokens: TokensInOut,
    token_in_amount: TokenAmount,
    token_out_amount: TokenAmount,
    minted_liquidity_tokens: TokenAmount,
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

    state
        .token_balances
        .add_to_token_balance(*user, Token::LIQUIDITY, minted_liquidity_tokens);
    state.token_balances.add_to_token_balance(
        state.liquidity_pool_address,
        Token::LIQUIDITY,
        minted_liquidity_tokens,
    );
}
