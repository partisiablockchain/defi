//! Shared functionality for contracts involving (locked) liquidity swaps.

use crate::token_balances::TokenAmount;
use create_type_spec_derive::CreateTypeSpec;
use read_write_rpc_derive::ReadWriteRPC;
use read_write_state_derive::ReadWriteState;

/// Id of a liquidity-lock.
#[derive(
    Copy, Clone, Debug, Ord, PartialOrd, Eq, PartialEq, ReadWriteRPC, ReadWriteState, CreateTypeSpec,
)]
pub struct LiquidityLockId {
    raw_id: u128,
}

impl LiquidityLockId {
    /// Creates a new LiquidityLockId with the initial id.
    pub fn initial_id() -> Self {
        LiquidityLockId { raw_id: 0 }
    }

    /// Returns a new [`LiquidityLockId`], which comes next after `self`.
    pub fn next(&self) -> Self {
        LiquidityLockId {
            raw_id: self.raw_id + 1,
        }
    }
}

/// Information about a lock acquired on a swap-contract.
#[derive(ReadWriteRPC)]
pub struct AcquiredLiquidityLockInformation {
    /// Id of the lock, used when executing or cancelling the lock.
    pub lock_id: LiquidityLockId,
    /// How many output tokens are received if the lock is executed.
    pub amount_out: TokenAmount,
}

/// Calculates how many of the output token you can get for `swap_amount_in` given an exchange fee in per mille. <br>
/// In other words, calculates how much the input token amount, minus the fee, is worth in the output token currency. <br>
/// This calculation is derived from section 3.1.2 of [UniSwap v1 whitepaper](https://github.com/runtimeverification/verified-smart-contracts/blob/uniswap/uniswap/x-y-k.pdf)
///
/// ### Parameters:
///
/// * `pool_token_in`: [`TokenAmount`] - The token pool matching the token of `swap_amount_in`.
///
/// * `pool_token_out`: [`TokenAmount`] - The output token pool.
///
/// * `swap_amount_in`: [`TokenAmount`] - The amount being swapped.
///
/// * `swap_fee_per_mille`: [`u16`] - The fee to take out of swapped to amount. Must be in [`ALLOWED_FEE_PER_MILLE`].
///
/// # Returns
/// The amount received after swapping. [`TokenAmount`]
pub fn calculate_swap_to_amount(
    pool_token_in: TokenAmount,
    pool_token_out: TokenAmount,
    swap_amount_in: TokenAmount,
    swap_fee_per_mille: u16,
) -> TokenAmount {
    let remainder_ratio = (1000 - swap_fee_per_mille) as TokenAmount;
    (remainder_ratio * swap_amount_in * pool_token_out)
        / (1000 * pool_token_in + remainder_ratio * swap_amount_in)
}
