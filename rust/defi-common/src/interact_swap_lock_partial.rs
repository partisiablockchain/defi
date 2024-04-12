//! # Liquidity Swap Lock invocation helper
//!
//! Mini-library for creating interactions with Liquidity Swap Lock contracts.
//! Such contracts possesses the same interactions as [regular swap contracts](`crate::interact_swap`),
//! but with additional interactions for locking liquidity swaps.
//!
//! Only the additional interactions are described and implemented here.
//!
//! Assumes that the target contract possesses actions where the shortname and arguments matches
//! the following:
//!
//! ```ignore
//! #[action(shortname=0x07)] acquire_swap_lock(token_in: Address, amount_in: TokenAmount, amount_out_minimum: TokenAmount);
//! #[action(shortname=0x08)] execute_lock_swap(lock_id: LiquidityLockId);
//! #[action(shortname=0x09)] cancel_lock(lock_id: LiquidityLockId);
//! ```

use crate::liquidity_util::LiquidityLockId;
use crate::token_balances::TokenAmount;
use pbc_contract_common::address::Address;
use pbc_contract_common::events::{EventGroupBuilder, GasCost};
use pbc_contract_common::shortname::Shortname;

/// Represents an individual swap contract with support for locks, on the blockchain
pub struct SwapLockContract {
    swap_address: Address,
}

impl SwapLockContract {
    /// Shortname of the [`SwapLockContract::acquire_swap_lock`] invocation
    const SHORTNAME_ACQUIRE_SWAP_LOCK: Shortname = Shortname::from_u32(0x07);
    /// Shortname of the [`SwapLockContract::execute_lock_swap`] invocation
    const SHORTNAME_EXECUTE_SWAP_LOCK: Shortname = Shortname::from_u32(0x08);
    /// Shortname of the [`SwapLockContract::cancel_lock`] invocation
    const SHORTNAME_CANCEL_LOCK: Shortname = Shortname::from_u32(0x09);

    /// Gas amount sufficient for [`SwapLockContract::acquire_swap_lock`] invocation.
    ///
    /// Guarantees that the invocation does not fail due to insufficient gas.
    pub const GAS_COST_ACQUIRE_SWAP_LOCK: GasCost = 2500;

    /// Gas amount sufficient for [`SwapLockContract::execute_lock_swap`] invocation.
    ///
    /// Guarantees that the invocation does not fail due to insufficient gas.
    pub const GAS_COST_EXECUTE_LOCK: GasCost = 2500;

    /// Gas amount sufficient for [`SwapLockContract::cancel_lock`] invocation.
    ///
    /// Guarantees that the invocation does not fail due to insufficient gas.
    pub const GAS_COST_CANCEL_LOCK: GasCost = 2500;

    /// Create new swap lock contract representation for the given `swap_address`.
    pub fn at_address(swap_address: Address) -> Self {
        Self { swap_address }
    }

    /// Create an interaction with the `self` swap lock contract, for acquiring a lock
    /// on a swap of `amount_in` of `token_in`, which should result in `amount_out_minimum` tokens.
    ///
    /// The owner of the lock is the sender of the invocation.
    pub fn acquire_swap_lock(
        &self,
        event_group_builder: &mut EventGroupBuilder,
        token_in: &Address,
        amount_in: TokenAmount,
        amount_out_minimum: TokenAmount,
    ) {
        event_group_builder
            .call(self.swap_address, Self::SHORTNAME_ACQUIRE_SWAP_LOCK)
            .argument(*token_in)
            .argument(amount_in)
            .argument(amount_out_minimum)
            .with_cost(Self::GAS_COST_ACQUIRE_SWAP_LOCK)
            .done();
    }

    /// Create an interaction with the `self` swap lock contract, for executing a previously
    /// acquired lock with id `lock_id`.
    pub fn execute_lock_swap(
        &self,
        event_group_builder: &mut EventGroupBuilder,
        lock_id: LiquidityLockId,
    ) {
        event_group_builder
            .call(self.swap_address, Self::SHORTNAME_EXECUTE_SWAP_LOCK)
            .argument(lock_id)
            .with_cost(Self::GAS_COST_EXECUTE_LOCK)
            .done();
    }

    /// Create an interaction with the `self` swap lock contract, for cancelling a previously
    /// acquired lock with id `lock_id`.
    pub fn cancel_lock(
        &self,
        event_group_builder: &mut EventGroupBuilder,
        lock_id: LiquidityLockId,
    ) {
        event_group_builder
            .call(self.swap_address, Self::SHORTNAME_CANCEL_LOCK)
            .argument(lock_id)
            .with_cost(Self::GAS_COST_CANCEL_LOCK)
            .done();
    }
}
