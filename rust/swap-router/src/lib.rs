#![doc = include_str!("../README.md")]

#[macro_use]
extern crate pbc_contract_codegen;

use pbc_contract_common::address::Address;
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::{EventGroup, EventGroupBuilder, GasCost};
use std::cmp::max;
use std::collections::VecDeque;

use create_type_spec_derive::CreateTypeSpec;
use pbc_contract_common::avl_tree_map::AvlTreeMap;
use read_write_state_derive::ReadWriteState;

use read_write_rpc_derive::{ReadRPC, WriteRPC};

use defi_common::liquidity_util::{AcquiredLiquidityLockInformation, LiquidityLockId};
use defi_common::token_balances::TokenAmount;

use defi_common::interact_mpc20::MPC20Contract;
use defi_common::interact_swap::SwapContract;
use defi_common::interact_swap_lock_partial::SwapLockContract;
use defi_common::permission::Permission;

/// Type of route ids.
pub type RouteId = u128;

/// The maximum length of the token swap route.
///
/// # Why?
///
/// Partisia Blockchain limits the number of chained spawned events to around 127, which can easily
/// be hit when executing routes longer than this constant.
const MAX_ROUTE_LENGTH: usize = 5;

/// Indicates the directional token swap that we intend to make along the route, including what to
/// input, get as output, and where to make the swap.
#[derive(ReadWriteState, CreateTypeSpec, Clone)]
pub struct SwapInformation {
    /// The swap contract we have a lock it.
    swap_address: Address,
    /// The input token on the swap.
    token_in: Address,
    /// The output token on the swap.
    token_out: Address,
}

/// Information about a lock we still need to acquire in a route.
#[derive(ReadWriteState, CreateTypeSpec)]
struct WantedLockInfo {
    /// Information about the swap that we intend to use the lock for.
    swap_info: SwapInformation,

    /// The amount of token `token_in` to input.
    amount_in: TokenAmount,

    /// The amount of token `token_out` that is wanted to be produced. If less that this is
    /// produced, the lock will be cancelled.
    amount_out_minimum: TokenAmount,
}

/// Information about an acquired lock, which is pending to be executed.
#[derive(ReadWriteState, CreateTypeSpec, Clone)]
struct AcquiredLockInfo {
    /// Information about the swap that we intend to use the lock for.
    swap_info: SwapInformation,

    /// The id of the lock.
    lock_id: LiquidityLockId,
}

/// Information about token withdrawal, after executing a lock.
#[derive(ReadWriteState, CreateTypeSpec)]
struct PendingWithdrawInfo {
    /// The swap contract we have a deposit on.
    swap_address: Address,
    /// The deposited token.
    withdraw_token: Address,
}

/// Information about the swap contracts known by the swap-router.
#[derive(ReadWriteState, ReadRPC, WriteRPC, CreateTypeSpec)]
pub struct SwapContractInfo {
    /// The swap contract we have a lock in.
    swap_address: Address,
    /// The address of the token that the swap considers the A token.
    token_a_address: Address,
    /// The address of the token that the swap considers the B token.
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
    /// Queue of locks yet to be acquired.
    locks_wanted: VecDeque<WantedLockInfo>,
    /// Queue of acquired locks, which are ready for execution.
    locks_waiting_for_execution: VecDeque<AcquiredLockInfo>,
    /// Withdrawal to be performed from specific swap contract.
    pending_withdraw: Option<PendingWithdrawInfo>,
}

impl RouteInformation {
    /// Readies lock information along a route for execution, to swap `user`'s tokens.
    ///
    /// For each swap in `route`, creates a [`WantedLockInfo`] with the first and last missing long
    /// containing the initial swap amount, and the required output amount, respectively.
    pub fn new(
        route: Vec<SwapInformation>,
        initial_amount_in: TokenAmount,
        amount_out_minimum: TokenAmount,
        user: Address,
    ) -> Self {
        let initial_token_in = route.first().unwrap().token_in;
        let final_token_out = route.last().unwrap().token_out;

        let mut locks_wanted = VecDeque::with_capacity(route.len());

        // Add missing and "dummy" pending locks for every swap on the route.
        for swap_info in route.into_iter() {
            let m_lock = WantedLockInfo {
                swap_info,
                amount_in: 0,
                amount_out_minimum: 0,
            };

            locks_wanted.push_back(m_lock);
        }

        // Modify the first and last locks to contain known information about the overall swap.
        locks_wanted.front_mut().unwrap().amount_in = initial_amount_in;
        locks_wanted.back_mut().unwrap().amount_out_minimum = amount_out_minimum;

        Self {
            user,
            initial_amount_in,
            initial_token_in,
            final_received_amount: 0,
            final_token_out,
            locks_wanted,
            locks_waiting_for_execution: VecDeque::with_capacity(0),
            pending_withdraw: None,
        }
    }

    /// Removes and returns the next lock which needs to be acquired.
    ///
    /// Returns `None` when there are no more missing locks.
    pub fn pop_next_wanted_lock(&mut self) -> Option<WantedLockInfo> {
        self.locks_wanted.pop_front()
    }

    pub fn peek_next_wanted_lock(&self) -> Option<&WantedLockInfo> {
        self.locks_wanted.front()
    }

    /// Update the next pending lock with the `lock_id` of a newly acquired missing lock.
    ///
    /// As a missing lock is acquired, this function should be called
    /// to store lock information for execution.
    pub fn update_next_pending_lock_id(
        &mut self,
        swap_info: SwapInformation,
        lock_id: LiquidityLockId,
    ) {
        self.locks_waiting_for_execution
            .push_back(AcquiredLockInfo { swap_info, lock_id });
    }

    /// If any missing locks are left, will update the amount of swapped input tokens for the next missing lock to `amount_in`.
    ///
    /// As exchange rates can change while we computed a route, or are acquiring locks, the amounts swapped
    /// in missing locks should be updated by calling this function.
    pub fn update_next_wanted_lock_amount_in(&mut self, amount_in: TokenAmount) {
        if let Some(lock) = self.locks_wanted.front_mut() {
            lock.amount_in = amount_in;
        }
    }

    /// Returns a reference to the next lock which needs to be executed.
    ///
    /// Returns `None` when there are no more pending locks.
    pub fn peek_next_pending_lock(&self) -> Option<&AcquiredLockInfo> {
        self.locks_waiting_for_execution.front()
    }

    /// Removes and returns the next lock which needs to be executed.
    ///
    /// Returns `None` when there are no more pending locks.
    pub fn take_next_pending_lock(&mut self) -> Option<AcquiredLockInfo> {
        self.locks_waiting_for_execution.pop_front()
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
    pub fn take_pending_withdraw(&mut self) -> Option<PendingWithdrawInfo> {
        self.pending_withdraw.take()
    }

    /// Updates the final output token amount of executing the route being tracked to `amount_out`.
    ///
    /// The information is used when transferring the output tokens back to the original user.
    pub fn update_final_amount_out(&mut self, amount_out: TokenAmount) {
        self.final_received_amount = amount_out;
    }
}

/// Tracks currently active routes.
#[derive(ReadWriteState, CreateTypeSpec)]
struct RouteTracker {
    next_route_id: RouteId,
    active_routes: AvlTreeMap<RouteId, RouteInformation>,
}

impl RouteTracker {
    /// Creates a new `RouteTracker`, with 0 zeroes, and `next_route_id` starting at 0.
    pub fn new() -> Self {
        Self {
            next_route_id: 0,
            active_routes: AvlTreeMap::new(),
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

    /// Retrieves a [`RouteInformation`] associated with `route_id`.
    ///
    /// Panics if no route is associated with `route_id`.
    fn get_route(&self, route_id: RouteId) -> RouteInformation {
        self.active_routes.get(&route_id).unwrap()
    }

    /// Retrieves and modifies the route for the given [`RouteId`]. Can produce a return value.
    ///
    /// Useful wrapper for making modifications to a route.
    fn modify_route<F, T>(&mut self, route_id: RouteId, f: F) -> T
    where
        F: FnOnce(&mut RouteInformation) -> T,
    {
        let mut route = self.get_route(route_id);
        let result_value = f(&mut route);
        self.active_routes.insert(route_id, route);
        result_value
    }
}

/// This is the state of the contract which is persisted on the chain.
///
/// The #\[state\] macro generates serialization logic for the struct.
#[state]
pub struct RouterState {
    /// [`Permission`] for who is allowed to add swap contracts, which can be used for routing.
    permission_add_swap: Permission,
    /// Known swap contracts which can be used for routing through.
    swap_contracts: Vec<SwapContractInfo>,
    /// Tracks routes actively being processed.
    route_tracker: RouteTracker,
}

/// Initialize the routing contract, with `swap_contracts` as the initially known swap contracts.
#[init]
pub fn initialize(
    _context: ContractContext,
    permission_add_swap: Permission,
    swap_contracts: Vec<SwapContractInfo>,
) -> (RouterState, Vec<EventGroup>) {
    let new_state = RouterState {
        permission_add_swap,
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
    let route_length = route.len();

    // Insert the found route into our state tracker.
    let route_id =
        state
            .route_tracker
            .add_route(route, amount_in, amount_out_minimum, context.sender);

    // First, take control of tokens, so the routing contract can approve tokens along the route.
    let route_information: RouteInformation = state.route_tracker.get_route(route_id);
    let mut transfer_event_builder = EventGroup::builder();

    MPC20Contract::at_address(route_information.initial_token_in).transfer_from(
        &mut transfer_event_builder,
        &route_information.user,
        &context.contract_address,
        route_information.initial_amount_in,
    );

    let total_cost = calculate_min_total_gas_cost(route_length);

    transfer_event_builder
        .with_callback_rpc(start_lock_chain_callback::rpc(route_id))
        .with_cost(total_cost)
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
    assert!(
        swap_route.len() <= MAX_ROUTE_LENGTH,
        "Swap route length ({}) is greater than maximum allowed ({}).",
        swap_route.len(),
        MAX_ROUTE_LENGTH
    );

    let mut res = Vec::with_capacity(swap_route.len());
    let mut prev_output_token = token_in;

    for (i, swap_address) in swap_route.iter().enumerate() {
        let swap_info = known_swap_contracts
            .iter()
            .find(|&contract_info| contract_info.swap_address == *swap_address)
            .unwrap_or_else(|| panic!("Unknown swap address: {:x?}.", swap_address.identifier()));

        let (swap_input_token, swap_output_token) =
            if prev_output_token == swap_info.token_a_address {
                (swap_info.token_a_address, swap_info.token_b_address)
            } else if prev_output_token == swap_info.token_b_address {
                (swap_info.token_b_address, swap_info.token_a_address)
            } else {
                panic!(
                    "No tokens at swap contract {:x?} matches token {:x?}, at swap number {}.",
                    swap_info.swap_address.identifier(),
                    prev_output_token.identifier(),
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
    state: RouterState,
    route_id: RouteId,
) -> (RouterState, Vec<EventGroup>) {
    if !callback_context.success {
        panic!("Could not take control of tokens.");
    }

    let route = state.route_tracker.get_route(route_id);

    let lock_info = route.peek_next_wanted_lock().unwrap();

    // Acquire the first lock, and let callbacks handle the rest.
    let mut lock_event_builder = EventGroup::builder();
    build_acquire_lock_events(&mut lock_event_builder, lock_info, route_id);
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
    let mut lock_event_builder = EventGroup::builder();
    state
        .route_tracker
        .modify_route(route_id, |route_information| {
            if !callback_context.success {
                // We couldn't acquire a lock. Cleanup and throw error.
                build_events_cancel_route(&mut lock_event_builder, route_information);
            } else {
                // Retrieve the output amount guaranteed from the lock just acquired, and update our state.
                if let Some(exec_result) = callback_context.results.first() {
                    let acquired_lock_info: AcquiredLiquidityLockInformation =
                        exec_result.get_return_data();

                    let wanted_lock_for_acquired =
                        route_information.pop_next_wanted_lock().unwrap();

                    // The amount in for the next lock is the amount we got from the previous lock.
                    route_information
                        .update_next_wanted_lock_amount_in(acquired_lock_info.amount_out);
                    // Update the lock id for the pending lock in our state.
                    route_information.update_next_pending_lock_id(
                        wanted_lock_for_acquired.swap_info,
                        acquired_lock_info.lock_id,
                    );
                }

                match route_information.peek_next_wanted_lock() {
                    // Acquire the next lock by calling swap contract.
                    Some(lock_info) => {
                        build_acquire_lock_events(&mut lock_event_builder, lock_info, route_id);
                    }
                    // All locks have been acquired, now start executing.
                    None => {
                        let pending_lock = route_information.peek_next_pending_lock().unwrap();
                        build_execute_approve_events(
                            &mut lock_event_builder,
                            pending_lock,
                            route_id,
                            route_information.initial_amount_in,
                        );
                    }
                };
            }
        });
    let events = vec![lock_event_builder.build()];
    (state, events)
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
    let mut execute_lock_event_builder = EventGroup::builder();

    state
        .route_tracker
        .modify_route(route_id, |route_information| {
            match route_information.peek_next_pending_lock() {
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
        });

    let events = vec![execute_lock_event_builder.build()];
    (state, events)
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
    let pending_lock: AcquiredLockInfo = state
        .route_tracker
        .modify_route(route_id, |route_information| {
            route_information.peek_next_pending_lock().unwrap().clone()
        });

    let mut deposit_event_builder = EventGroup::builder();

    SwapContract::at_address(pending_lock.swap_info.swap_address).deposit(
        &mut deposit_event_builder,
        &pending_lock.swap_info.token_in,
        last_output,
    );

    deposit_event_builder
        .with_callback_rpc(deposit_callback::rpc(route_id))
        .done();

    let events = vec![deposit_event_builder.build()];
    (state, events)
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
    let mut execute_event_builder = EventGroup::builder();

    state
        .route_tracker
        .modify_route(route_id, |route_information| {
            let pending_lock = route_information.take_next_pending_lock().unwrap();

            SwapLockContract::at_address(pending_lock.swap_info.swap_address)
                .execute_lock_swap(&mut execute_event_builder, pending_lock.lock_id);

            // Make sure to callback our route withdraw handler.
            execute_event_builder
                .with_callback_rpc(receive_output_amount_callback::rpc(route_id))
                .done();

            // Add a pending withdrawal.
            let pending_withdraw = PendingWithdrawInfo {
                swap_address: pending_lock.swap_info.swap_address,
                withdraw_token: pending_lock.swap_info.token_out,
            };
            route_information.update_pending_withdraw(pending_withdraw);
        });
    let events = vec![execute_event_builder.build()];
    (state, events)
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
    let received_amount: TokenAmount = callback_context.results.last().unwrap().get_return_data();

    let pending_withdraw = state
        .route_tracker
        .modify_route(route_id, |route_information| {
            // Handle received amount from swap lock contract.
            route_information.update_final_amount_out(received_amount);

            // Withdraw the amount from the swap contract.
            route_information.take_pending_withdraw().unwrap()
        });

    let mut withdraw_event_builder = EventGroup::builder();
    SwapContract::at_address(pending_withdraw.swap_address).withdraw(
        &mut withdraw_event_builder,
        &pending_withdraw.withdraw_token,
        received_amount,
        true,
    );

    // Callback to execute the next pending lock.
    withdraw_event_builder
        .with_callback_rpc(execute_route_callback::rpc(route_id, received_amount))
        .done();

    let events = vec![withdraw_event_builder.build()];
    (state, events)
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

/// Update state with the swap address at `swap_address` between token `token_a_address` and `token_b_address`
/// to the known swap contracts, which can be used for routing.
///
/// Fails if the sender does not have permission for updating the known swap contracts.
#[action(shortname = 0x08)]
fn add_swap_contract(
    context: ContractContext,
    mut state: RouterState,
    swap_address: Address,
    token_a_address: Address,
    token_b_address: Address,
) -> (RouterState, Vec<EventGroup>) {
    state
        .permission_add_swap
        .assert_permission_for(&context.sender, "add swap");

    state.swap_contracts.push(SwapContractInfo {
        swap_address,
        token_a_address,
        token_b_address,
    });

    (state, vec![])
}

/// Builds event set to free acquired locks and to return tokens to owner.
fn build_events_cancel_route(event_builder: &mut EventGroupBuilder, route: &RouteInformation) {
    // Cancel locks
    for lock in route.locks_waiting_for_execution.iter() {
        SwapLockContract::at_address(lock.swap_info.swap_address)
            .cancel_lock(event_builder, lock.lock_id);
    }

    // Refund input tokens
    MPC20Contract::at_address(route.initial_token_in).transfer(
        event_builder,
        &route.user,
        route.initial_amount_in,
    );

    // Create a failing interaction
    event_builder
        .with_callback_rpc(could_not_acquire_lock_error::rpc())
        .done();
}

/// Builds the events needed to acquire a lock, and callback our lock handler, for any potential missing locks.
fn build_acquire_lock_events(
    event_builder: &mut EventGroupBuilder,
    lock_info: &WantedLockInfo,
    route_id: RouteId,
) {
    SwapLockContract::at_address(lock_info.swap_info.swap_address).acquire_swap_lock(
        event_builder,
        &lock_info.swap_info.token_in,
        lock_info.amount_in,
        lock_info.amount_out_minimum,
    );

    // Recursively call self to update route information.
    event_builder
        .with_callback_rpc(lock_route_callback::rpc(route_id))
        .done();
}

fn build_execute_approve_events(
    event_builder: &mut EventGroupBuilder,
    pending_lock: &AcquiredLockInfo,
    route_id: RouteId,
    approval_amount: TokenAmount,
) {
    // Build the initial approve event.
    build_max_approve_event_for_execute(event_builder, pending_lock);

    // Callback to start deposits.
    event_builder
        .with_callback_rpc(approve_callback::rpc(route_id, approval_amount))
        .done();
}

/// Builds the approval event for swap execution, with the max possible approval amount ([`TokenAmount::MAX`]).
///
/// This is sub-optimal from a security perspective, but a convenient way to support token
/// contracts that does not implement [`MPC20Contract::approve_relative`], like the BYOC contracts.
fn build_max_approve_event_for_execute(
    event_builder: &mut EventGroupBuilder,
    pending_lock: &AcquiredLockInfo,
) {
    MPC20Contract::at_address(pending_lock.swap_info.token_in).approve(
        event_builder,
        &pending_lock.swap_info.swap_address,
        TokenAmount::MAX,
    );
}

/// Gas amount sufficient for covering [`start_lock_chain_callback`]'s internal gas requirements.
const INTERNAL_GAS_COST_START_LOCK_CHAIN_CALLBACK: GasCost = 1500;

/// Gas amount sufficient for covering [`lock_route_callback`]'s internal gas requirements.
const INTERNAL_GAS_COST_LOCK_ROUTE_CALLBACK: GasCost = 1500;

/// Gas amount sufficient for covering [`approve_callback`]'s internal gas requirements.
const INTERNAL_GAS_COST_APPROVE_CALLBACK: GasCost = 1500;

/// Gas amount sufficient for covering [`deposit_callback`]'s internal gas requirements.
const INTERNAL_GAS_COST_DEPOSIT_CALLBACK: GasCost = 1500;

/// Gas amount sufficient for covering [`receive_output_amount_callback`]'s internal gas requirements.
const INTERNAL_GAS_COST_RECEIVE_OUTPUT_AMOUNT_CALLBACK: GasCost = 1500;

/// Gas amount sufficient for covering [`execute_route_callback`]'s internal gas requirements.
const INTERNAL_GAS_COST_EXECUTE_ROUTE_CALLBACK: GasCost = 1500;

/// Gas amount sufficient for covering [`could_not_acquire_lock_error`]'s internal gas requirements.
const INTERNAL_GAS_COST_CANCEL_LOCK_ERROR_CALLBACK: GasCost = 1500;

/// Given the number of swaps on a route, calculates the worst-case minimum amount of gas for routing to succeed.
fn calculate_min_total_gas_cost(number_of_swaps: usize) -> GasCost {
    let number_of_swaps = number_of_swaps as u64;
    let acquire_lock_cost =
        SwapLockContract::GAS_COST_ACQUIRE_SWAP_LOCK + INTERNAL_GAS_COST_LOCK_ROUTE_CALLBACK;
    let cancel_locks_cost = (number_of_swaps + 1) * SwapLockContract::GAS_COST_CANCEL_LOCK
        + INTERNAL_GAS_COST_CANCEL_LOCK_ERROR_CALLBACK;
    let execute_lock_cost =
        // Cost of approving deposit at token.
        MPC20Contract::GAS_COST_APPROVE + INTERNAL_GAS_COST_APPROVE_CALLBACK +
            // Cost of depositing into swap.
            SwapContract::GAS_COST_DEPOSIT + INTERNAL_GAS_COST_DEPOSIT_CALLBACK +
            // Cost of executing the lock.
            SwapLockContract::GAS_COST_EXECUTE_LOCK + INTERNAL_GAS_COST_RECEIVE_OUTPUT_AMOUNT_CALLBACK +
            // Cost of withdrawing the tokens.
            SwapContract::GAS_COST_WITHDRAW + INTERNAL_GAS_COST_EXECUTE_ROUTE_CALLBACK;

    let total_acquire_cost =
        // Cost of starting chain
        INTERNAL_GAS_COST_START_LOCK_CHAIN_CALLBACK +
            // Cost of acquiring a lock, (#swaps + 1) time.
            (number_of_swaps + 1) * acquire_lock_cost;

    let total_execute_cost =
        // Cost of executing a lock, #swaps times.
        number_of_swaps * execute_lock_cost +
            // Finally transfer the tokens to the user at the end token.
            MPC20Contract::GAS_COST_TRANSFER;

    // We either pay for execution, or cancelling locks, but never both.
    max(
        total_acquire_cost + total_execute_cost,
        total_acquire_cost + cancel_locks_cost,
    )
}
