#![doc = include_str!("../README.md")]

#[macro_use]
extern crate pbc_contract_codegen;
extern crate core;

use pbc_contract_common::address::{Address, ShortnameCallback};
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::{EventGroup, EventGroupBuilder};
use std::collections::VecDeque;

use create_type_spec_derive::CreateTypeSpec;
use pbc_contract_common::sorted_vec_map::SortedVecMap;
use read_write_state_derive::ReadWriteState;

use read_write_rpc_derive::ReadRPC;

use defi_common::interact_mpc20::MPC20Contract;
use defi_common::liquidity_util::{AcquiredLiquidityLockInformation, LiquidityLockId};
pub use defi_common::token_balances::Token;
use defi_common::token_balances::TokenAmount;

use defi_common::interact_swap::SwapContract;
use defi_common::interact_swap_lock_partial::SwapLockContract;

/// Type of route ids.
pub type RouteId = u128;

/// Information about a lock we still need to acquire in a route.
#[derive(ReadWriteState, CreateTypeSpec)]
struct WantedLockInfo {
    swap_address: Address,
    token_in: Address,
    amount_in: TokenAmount,
    amount_out_minimum: TokenAmount,
}

/// Information about an acquired lock, which is pending to be executed.
#[derive(ReadWriteState, CreateTypeSpec)]
struct AcquiredLockInfo {
    lock_id: LiquidityLockId,
    swap_address: Address,
    token_in: Address,
    token_out: Address,
}

/// Information about token withdrawal, after executing a lock.
#[derive(ReadWriteState, CreateTypeSpec)]
struct PendingWithdrawInfo {
    swap_address: Address,
    withdraw_token: Address,
}

/// Information related to a specific swap we intend to make along a route.
#[derive(ReadWriteState, CreateTypeSpec, PartialEq)]
pub struct SwapInformation {
    swap_address: Address,
    token_in: Address,
    token_out: Address,
}

/// Information about the swap contracts known by the swap-router.
#[derive(ReadWriteState, ReadRPC, CreateTypeSpec)]
pub struct SwapContractInfo {
    swap_address: Address,
    token_a_address: Address,
    token_b_address: Address,
}

/// Handles actions and information related to a specific active swap-route.
#[derive(ReadWriteState, CreateTypeSpec)]
struct RouteInformation {
    /// User this route is executing on behalf of.
    user: Address,
    /// Number of tokens input to route.
    initial_amount_in: TokenAmount,
    /// Initial token inputted.
    initial_token_in: Address,
    /// Expected amount of output tokens.
    final_received_amount: TokenAmount,
    /// Expected output token.
    final_token_out: Address,
    /// Index of pending lock to update with lock id.
    next_pending_lock_update: u32,
    /// Queue of locks yet to be acquired.
    wanted_locks: VecDeque<WantedLockInfo>,
    /// Queue of acquired locks, which are ready for execution.
    acquired_locks: VecDeque<AcquiredLockInfo>,
    /// Withdrawal to be performed from specific swap contract.
    pending_withdraw: Option<PendingWithdrawInfo>,
}

impl RouteInformation {
    /// Readies lock information along a route for execution, to swap `user`'s tokens.
    ///
    /// For each swap in `route`, creates a `MissingLockInfo`, and `PendingLockInfo`,
    /// with the first and last missing long containing the initial swap amount,
    /// and the required output amount, respectively.
    pub fn new(
        route: Vec<SwapInformation>,
        initial_amount_in: TokenAmount,
        amount_out_minimum: TokenAmount,
        user: Address,
    ) -> Self {
        let mut wanted_locks = VecDeque::with_capacity(route.len());
        let mut acquired_locks = VecDeque::with_capacity(route.len());

        // Add missing and "dummy" pending locks for every swap on the route.
        for swap_info in route.iter() {
            let m_lock = WantedLockInfo {
                swap_address: swap_info.swap_address,
                token_in: swap_info.token_in,
                amount_in: 0,
                amount_out_minimum: 0,
            };

            let p_lock = AcquiredLockInfo {
                lock_id: LiquidityLockId::initial_id(),
                swap_address: swap_info.swap_address,
                token_in: swap_info.token_in,
                token_out: swap_info.token_out,
            };

            wanted_locks.push_back(m_lock);
            acquired_locks.push_back(p_lock);
        }

        // Modify the first and last locks to contain known information about the overall swap.
        wanted_locks.front_mut().unwrap().amount_in = initial_amount_in;
        wanted_locks.back_mut().unwrap().amount_out_minimum = amount_out_minimum;

        let first_swap = route.first().unwrap();
        let last_swap = route.last().unwrap();

        Self {
            user,
            initial_amount_in,
            initial_token_in: first_swap.token_in,
            final_received_amount: 0,
            final_token_out: last_swap.token_out,
            next_pending_lock_update: 0,
            wanted_locks,
            acquired_locks,
            pending_withdraw: None,
        }
    }

    /// Removes and returns the next lock which needs to be acquired.
    ///
    /// Returns `None` when there are no more missing locks.
    pub fn get_next_missing_lock(&mut self) -> Option<WantedLockInfo> {
        self.wanted_locks.pop_front()
    }

    /// Update the next pending lock with the `lock_id` of a newly acquired missing lock.
    ///
    /// As a missing lock is acquired, this function should be called
    /// to store lock information for execution.
    pub fn update_next_pending_lock_id(&mut self, lock_id: LiquidityLockId) {
        if let Some(pending_lock) = self
            .acquired_locks
            .get_mut(self.next_pending_lock_update as usize)
        {
            pending_lock.lock_id = lock_id;
        }
        self.next_pending_lock_update += 1;
    }

    /// If any missing locks are left, will update the amount of swapped input tokens for the next missing lock to `amount_in`.
    ///
    /// As exchange rates can change while we computed a route, or are acquiring locks, the amounts swapped
    /// in missing locks should be updated by calling this function.
    pub fn update_next_missing_lock_amount_in(&mut self, amount_in: TokenAmount) {
        if let Some(lock) = self.wanted_locks.front_mut() {
            lock.amount_in = amount_in;
        }
    }

    /// Returns a reference to the next lock which needs to be executed.
    ///
    /// Returns `None` when there are no more pending locks.
    pub fn get_next_pending_lock(&self) -> Option<&AcquiredLockInfo> {
        self.acquired_locks.front()
    }

    /// Removes and returns the next lock which needs to be executed.
    ///
    /// Returns `None` when there are no more pending locks.
    pub fn take_next_pending_lock(&mut self) -> Option<AcquiredLockInfo> {
        self.acquired_locks.pop_front()
    }

    /// Updates the route with a pending withdrawal.
    ///
    /// Should be used to update withdrawal information, after executing a pending lock.
    pub fn update_pending_withdraw(&mut self, pending_withdraw: PendingWithdrawInfo) {
        self.pending_withdraw = Some(pending_withdraw);
    }

    /// Removes and returns the next pending withdraw.
    ///
    /// Returns `None` when there are no more pending withdraws.
    pub fn get_pending_withdraw(&mut self) -> Option<PendingWithdrawInfo> {
        self.pending_withdraw.take()
    }

    /// Updates the final output token amount of executing the route being tracked to `amount_out`.
    ///
    /// The information is used when transferring the output tokens back to the original user.
    pub fn update_final_amount_out(&mut self, amount_out: TokenAmount) {
        self.final_received_amount = amount_out;
    }

    pub fn get_ready_pending_locks(&mut self) -> &[AcquiredLockInfo] {
        &self.acquired_locks.make_contiguous()[..self.next_pending_lock_update as usize]
    }
}

/// Tracks currently active routes.
#[derive(ReadWriteState, CreateTypeSpec)]
struct RouteTracker {
    next_route_id: RouteId,
    active_routes: SortedVecMap<RouteId, RouteInformation>,
}

impl RouteTracker {
    /// Creates a new `RouteTracker`, with 0 zeroes, and `next_route_id` starting at 0.
    pub fn new() -> Self {
        Self {
            next_route_id: 0,
            active_routes: SortedVecMap::new(),
        }
    }

    /// Adds a new active route with a unique id, for which locks should be acquired and executed.
    ///
    /// Uses `route`, `amount_in`, `minimum_amount_out` and `user` to construct a new `RouteInformation`,
    /// to keep track of locks to be acquired for the route.
    fn add_route(
        &mut self,
        route: Vec<SwapInformation>,
        amount_in: TokenAmount,
        minimum_amount_out: TokenAmount,
        user: Address,
    ) -> RouteId {
        let route_id = self.next_route_id();
        let route_info = RouteInformation::new(route, amount_in, minimum_amount_out, user);

        self.active_routes.insert(route_id, route_info);

        route_id
    }

    /// Returns an id for a new route, and updates state for a future route id.
    fn next_route_id(&mut self) -> RouteId {
        let res = self.next_route_id;
        self.next_route_id += 1;
        res
    }

    /// Retrieves the mutable `RouteInformation` associated with `route_id`
    ///
    /// Panics if no route is associated with `route_id`.
    fn get_mut_route(&mut self, route_id: RouteId) -> &mut RouteInformation {
        self.active_routes
            .get_mut(&route_id)
            .unwrap_or_else(|| panic!("Route {} doesn't exist.", route_id))
    }
}

/// This is the state of the contract which is persisted on the chain.
///
/// The #\[state\] macro generates serialization logic for the struct.
#[state]
pub struct RouterState {
    /// Known swap contracts which can be used for routing through.
    swap_contracts: Vec<SwapContractInfo>,
    /// Tracks routes actively being processed.
    route_tracker: RouteTracker,
}

/// Initialize the routing contract, with `swap_contracts` as the initially known swap contracts.
#[init]
pub fn initialize(
    _context: ContractContext,
    swap_contracts: Vec<SwapContractInfo>,
) -> (RouterState, Vec<EventGroup>) {
    let new_state = RouterState {
        swap_contracts,
        route_tracker: RouteTracker::new(),
    };

    (new_state, vec![])
}

/// Tries to find the best route to swap `token_in` to `token_out`, and execute the swap-chain.
///
/// If possible, finds a route swapping `amount_in` of `token_in`, to a minimum of `amount_out_minimum` of `token_out`.
/// This fails if no such route exists, given the known swap contracts, or `amount_out_minimum` cannot be satisfied.
/// If a route is found, locks are acquired, and if this succeeds, then executed to finalized the swap-chain.
///
/// Fails if the found route is of length 1, prompting the user to perform an instant-swap.
#[action(shortname = 0x01)]
pub fn route_swap(
    context: ContractContext,
    mut state: RouterState,
    swap_route: Vec<Address>,
    token_in: Address,
    token_out: Address,
    amount_in: TokenAmount,
    amount_out_minimum: TokenAmount,
) -> (RouterState, Vec<EventGroup>) {
    assert!(!swap_route.is_empty(), "The given route is empty.");

    let route =
        validate_route_and_add_info(&swap_route, &state.swap_contracts, token_in, token_out);

    // Insert the found route into our state tracker.
    let route_id =
        state
            .route_tracker
            .add_route(route, amount_in, amount_out_minimum, context.sender);

    let route_information: &mut RouteInformation = state.route_tracker.get_mut_route(route_id);

    // First, take control of tokens, so the routing contract can approve tokens along the route.
    let mut transfer_event_builder = EventGroup::builder();
    MPC20Contract::at_address(route_information.initial_token_in).transfer_from(
        &mut transfer_event_builder,
        &route_information.user,
        &context.contract_address,
        route_information.initial_amount_in,
    );

    transfer_event_builder
        .with_callback(SHORTNAME_START_LOCK_CHAIN_CALLBACK)
        .argument(route_id)
        .done();

    (state, vec![transfer_event_builder.build()])
}

/// Validates that tokens match for all swaps in `swap_route`, and the the start and end match
/// `token_in` and `token_out`, respectively. Furthermore adds token address information to each swap.
///
/// If any swap is not a part of `known_swap_contracts`, the route is rejected, even if tokens
/// would be valid.
fn validate_route_and_add_info(
    swap_route: &[Address],
    known_swap_contracts: &[SwapContractInfo],
    token_in: Address,
    token_out: Address,
) -> Vec<SwapInformation> {
    let mut res = Vec::with_capacity(swap_route.len());
    let mut prev_output_token = token_in;

    for (i, swap_address) in swap_route.iter().enumerate() {
        let swap_info = known_swap_contracts
            .iter()
            .find(|&contract_info| contract_info.swap_address == *swap_address)
            .unwrap_or_else(|| panic!("Unknown swap address: {:x?}.", swap_address.identifier));

        let (swap_input_token, swap_output_token) =
            if prev_output_token == swap_info.token_a_address {
                (swap_info.token_a_address, swap_info.token_b_address)
            } else if prev_output_token == swap_info.token_b_address {
                (swap_info.token_b_address, swap_info.token_a_address)
            } else {
                panic!(
                    "No tokens at swap contract {:x?} matches token {:x?}, at swap number {}.",
                    swap_info.swap_address.identifier,
                    prev_output_token.identifier,
                    i + 1
                );
            };

        prev_output_token = swap_output_token;
        res.push(SwapInformation {
            swap_address: *swap_address,
            token_in: swap_input_token,
            token_out: swap_output_token,
        })
    }

    assert_eq!(
        token_out, prev_output_token,
        "The output token from the swap route doesn't match the intended output."
    );

    res
}

/// Callback to handle swap-router taking control of tokens.
/// Starts the lock chain by acquiring the first lock.
#[callback(shortname = 0x20)]
fn start_lock_chain_callback(
    _context: ContractContext,
    callback_context: CallbackContext,
    mut state: RouterState,
    route_id: RouteId,
) -> (RouterState, Vec<EventGroup>) {
    if !callback_context.success {
        panic!("Could not take control of tokens.");
    }

    let route_information = state.route_tracker.get_mut_route(route_id);
    let mut lock_event_builder = EventGroup::builder();

    // Acquire the first lock, and let callbacks handle the rest.
    let lock_info = route_information.get_next_missing_lock().unwrap();
    build_acquire_lock_events(&mut lock_event_builder, lock_info, route_id);

    lock_event_builder
        .with_callback(SHORTNAME_LOCK_ROUTE_CALLBACK)
        .argument(route_id)
        .done();

    (state, vec![lock_event_builder.build()])
}

/// Callback to handle acquiring all the locks along a swap-route.
///
/// Acquires one missing lock, at one swap contract, at a time, continuously calling back to ourself,
/// until there are no more locks to acquire.
///
/// When all locks have been acquired, transfers the initial tokens from the user to the routing contract,
/// to execute the acquired locks, and withdraw tokens along the way, on behalf of the user.
///
/// Fails if a lock could not be acquired, which stops execution of the swap-chain,
/// and cancels any so far acquired locks.
#[callback(shortname = 0x03)]
fn lock_route_callback(
    _context: ContractContext,
    callback_context: CallbackContext,
    mut state: RouterState,
    route_id: RouteId,
) -> (RouterState, Vec<EventGroup>) {
    let route_information: &mut RouteInformation = state.route_tracker.get_mut_route(route_id);
    let mut lock_event_builder = EventGroup::builder();

    if !callback_context.success {
        // We couldn't acquire a lock. Cleanup and throw error.
        build_cancel_lock_events(
            &mut lock_event_builder,
            route_information.get_ready_pending_locks(),
            SHORTNAME_COULD_NOT_ACQUIRE_LOCK_ERROR,
        );
    } else {
        // Retrieve the output amount guaranteed from the lock just acquired, and update our state.
        if let Some(exec_result) = callback_context.results.first() {
            let acquired_lock_info: AcquiredLiquidityLockInformation =
                exec_result.get_return_data();

            // The amount in for the next lock is the amount we got from the previous lock.
            route_information.update_next_missing_lock_amount_in(acquired_lock_info.amount_out);
            // Update the lock id for the pending lock in our state.
            route_information.update_next_pending_lock_id(acquired_lock_info.lock_id);
        }

        match route_information.get_next_missing_lock() {
            // Acquire the next lock lock by calling swap contract.
            Some(lock_info) => {
                build_acquire_lock_events(&mut lock_event_builder, lock_info, route_id);
            }
            // All locks have been acquired, now start executing.
            None => {
                let pending_lock = route_information.get_next_pending_lock().unwrap();
                build_execute_approve_events(
                    &mut lock_event_builder,
                    pending_lock,
                    route_id,
                    route_information.initial_amount_in,
                );
            }
        };
    }

    (state, vec![lock_event_builder.build()])
}

/// Callback to handle executing all the acquired locks along a route, which performs the swaps.
///
/// Executes one pending lock at a time, continuously calling back to ourself,
/// until all locks have been executed.
///
/// When all pending locks have been executed, the total output amount is transferred
/// to the original user, at the required output token.
#[callback(shortname = 0x04)]
fn execute_route_callback(
    _context: ContractContext,
    _callback_context: CallbackContext,
    mut state: RouterState,
    route_id: RouteId,
    last_output: TokenAmount,
) -> (RouterState, Vec<EventGroup>) {
    let route_information = state.route_tracker.get_mut_route(route_id);
    let mut execute_lock_event_builder = EventGroup::builder();

    match route_information.get_next_pending_lock() {
        Some(pending_lock) => {
            build_execute_approve_events(
                &mut execute_lock_event_builder,
                pending_lock,
                route_id,
                last_output,
            );
        }
        None => {
            // We finished executing the locks, now we just need to transfer the tokens to the original user.
            MPC20Contract::at_address(route_information.final_token_out).transfer(
                &mut execute_lock_event_builder,
                &route_information.user,
                route_information.final_received_amount,
            );
        }
    }

    (state, vec![execute_lock_event_builder.build()])
}

/// Callback for swap contract approval completion at token contract. Builds events for deposit
/// of the tokens, with a callback to [`deposit_callback`], such that we can execute the next pending lock.
#[callback(shortname = 0x15)]
fn approve_callback(
    _context: ContractContext,
    _callback_context: CallbackContext,
    mut state: RouterState,
    route_id: RouteId,
    last_output: TokenAmount,
) -> (RouterState, Vec<EventGroup>) {
    let route_information = state.route_tracker.get_mut_route(route_id);
    let mut deposit_event_builder = EventGroup::builder();

    let pending_lock = route_information.get_next_pending_lock().unwrap();

    SwapContract::at_address(pending_lock.swap_address).deposit(
        &mut deposit_event_builder,
        &pending_lock.token_in,
        last_output,
    );

    deposit_event_builder
        .with_callback(SHORTNAME_DEPOSIT_CALLBACK)
        .argument(route_id)
        .done();

    (state, vec![deposit_event_builder.build()])
}

/// Callback for token deposit completion at swap contract. Builds events for execution
/// of the next pending lock on route `route_id`, adding a callback to our withdraw handler.
#[callback(shortname = 0x16)]
fn deposit_callback(
    _context: ContractContext,
    _callback_context: CallbackContext,
    mut state: RouterState,
    route_id: RouteId,
) -> (RouterState, Vec<EventGroup>) {
    let route_information = state.route_tracker.get_mut_route(route_id);
    let mut execute_event_builder = EventGroup::builder();

    let pending_lock = route_information.take_next_pending_lock().unwrap();

    SwapLockContract::at_address(pending_lock.swap_address)
        .execute_lock(&mut execute_event_builder, pending_lock.lock_id);

    // Make sure to callback our route withdraw handler.
    execute_event_builder
        .with_callback(SHORTNAME_RECEIVE_OUTPUT_AMOUNT_CALLBACK)
        .argument(route_id)
        .done();

    // Add a pending withdrawal.
    let pending_withdraw = PendingWithdrawInfo {
        swap_address: pending_lock.swap_address,
        withdraw_token: pending_lock.token_out,
    };
    route_information.update_pending_withdraw(pending_withdraw);

    (state, vec![execute_event_builder.build()])
}

/// Callback to handle withdrawing tokens, after executing a lock in the swap-chain.
///
/// Deserializes the output amount returned from the swap contract, as a result of executing a lock.
/// This amount is withdrawn to the token contract, and passed along to the execution callback,
/// to handle the next pending lock.
#[callback(shortname = 0x05)]
fn receive_output_amount_callback(
    _context: ContractContext,
    callback_context: CallbackContext,
    mut state: RouterState,
    route_id: RouteId,
) -> (RouterState, Vec<EventGroup>) {
    let route_information = state.route_tracker.get_mut_route(route_id);

    // Handle received amount from swap lock contract.
    let received_amount: TokenAmount = callback_context.results.last().unwrap().get_return_data();
    route_information.update_final_amount_out(received_amount);

    let mut withdraw_event_builder = EventGroup::builder();

    // Withdraw the amount from the swap contract.
    let pending_withdraw = route_information.get_pending_withdraw().unwrap();
    SwapContract::at_address(pending_withdraw.swap_address).withdraw(
        &mut withdraw_event_builder,
        &pending_withdraw.withdraw_token,
        received_amount,
        true,
    );

    // Callback to execute the next pending lock.
    withdraw_event_builder
        .with_callback(SHORTNAME_EXECUTE_ROUTE_CALLBACK)
        .argument(route_id)
        .argument(received_amount)
        .done();

    (state, vec![withdraw_event_builder.build()])
}

/// Panics with an error message saying locks could not be acquired.
///
/// Meant to be used as a callback when lock-acquisition fails, to allow cancelling locks
/// before throwing error.
#[callback(shortname = 0x07)]
fn could_not_acquire_lock_error(
    _context: ContractContext,
    _callback_context: CallbackContext,
    _state: RouterState,
) -> (RouterState, Vec<EventGroup>) {
    panic!("Could not acquire all locks in route.");
}

/// Panics with an error message saying that swap-router couldn't gain control of user tokens.
///
/// Meant to be used as a callback when lock-acquisition fails, to allow cancelling locks
/// before throwing error.
#[callback(shortname = 0x017)]
fn could_not_take_control_of_tokens(
    _context: ContractContext,
    _callback_context: CallbackContext,
    _state: RouterState,
) -> (RouterState, Vec<EventGroup>) {
    panic!("Could not take control of tokens.");
}

/// Builds the events needed to cancel all acquired locks, and callback to throw routing error.
fn build_cancel_lock_events(
    event_builder: &mut EventGroupBuilder,
    locks_to_cancel: &[AcquiredLockInfo],
    error_callback: ShortnameCallback,
) {
    for lock in locks_to_cancel {
        SwapLockContract::at_address(lock.swap_address).cancel_lock(event_builder, lock.lock_id);
    }
    event_builder.with_callback(error_callback).done();
}

/// Builds the events needed to acquire a lock, and callback our lock handler, for any potential missing locks.
fn build_acquire_lock_events(
    event_builder: &mut EventGroupBuilder,
    lock_info: WantedLockInfo,
    route_id: RouteId,
) {
    SwapLockContract::at_address(lock_info.swap_address).acquire_swap_lock(
        event_builder,
        &lock_info.token_in,
        lock_info.amount_in,
        lock_info.amount_out_minimum,
    );

    // Recursively call self to update route information.
    event_builder
        .with_callback(SHORTNAME_LOCK_ROUTE_CALLBACK)
        .argument(route_id)
        .done();
}

fn build_execute_approve_events(
    event_builder: &mut EventGroupBuilder,
    pending_lock: &AcquiredLockInfo,
    route_id: RouteId,
    approval_amount: TokenAmount,
) {
    // Build the execution events.
    MPC20Contract::at_address(pending_lock.token_in).approve_relative(
        event_builder,
        &pending_lock.swap_address,
        approval_amount.try_into().unwrap(),
    );

    event_builder
        .with_callback(SHORTNAME_APPROVE_CALLBACK)
        .argument(route_id)
        .argument(approval_amount)
        .done();
}
