//! Submodule handles upgrade logic for the smart contract.

use crate::{
    LiquidStakingState, LiquidTokenState, PendingUnlock, PendingUnlockId, INITIAL_PENDING_UNLOCK_ID,
};
use create_type_spec_derive::CreateTypeSpec;
use pbc_contract_codegen::upgrade_is_allowed;
use pbc_contract_common::address::Address;
use pbc_contract_common::avl_tree_map::AvlTreeMap;
use pbc_contract_common::context::ContractContext;
use pbc_contract_common::upgrade::ContractHashes;
use read_write_state_derive::ReadWriteState;

/// An unlock request waiting to be redeemed.
///
/// Old version of the `PendingUnlock` structure.
#[derive(ReadWriteState, CreateTypeSpec)]
pub struct PendingUnlockV1 {
    liquid_amount: u128,
    stake_token_amount: u128,
    created_at: u64,
    cooldown_ends_at: u64,
    expires_at: u64,
}

/// Liquid Staking contract compatible state.
///
/// Old version of the `LiquidStakingState` structure.
#[derive(ReadWriteState, CreateTypeSpec)]
pub struct LiquidStakingStateV1 {
    token_for_staking: Address,
    stake_token_balance: u128,
    staking_responsible: Address,
    administrator: Address,
    total_pool_stake_token: u128,
    total_pool_liquid: u128,
    liquid_token_state: LiquidTokenState,
    pending_unlocks: AvlTreeMap<Address, Vec<PendingUnlockV1>>,
    buy_in_tokens: AvlTreeMap<Address, u128>,
    length_of_cooldown_period: u64,
    length_of_redeem_period: u64,
    amount_of_buy_in_locked_stake_tokens: u128,
    buy_in_percentage: u128,
    buy_in_enabled: bool,
}

/// Determines whether the contract is upgradable in the current context.
///
/// This contract allows the [`LiquidStakingState::administrator`] to upgrade the contract at any time.
///
/// # Parameters:
///
/// * `context`: The context for the action call.
/// * `state`: The current state of the contract.
/// * `current_contract_hashes`: Hashes of the contract currently running this code.
/// * `new_contract_hashes`: Hashes of the contract being upgraded to.
/// * `new_contract_rpc`: RPC for the contract upgrade, if needed.
///
#[upgrade_is_allowed]
pub fn is_upgrade_allowed(
    context: ContractContext,
    state: LiquidStakingState,
    _current_contract_hashes: ContractHashes,
    _new_contract_hashes: ContractHashes,
    _new_contract_rpc: Vec<u8>,
) -> bool {
    context.sender == state.administrator
}

/// Upgrades from the [`PendingUnlockV1`] to [`PendingUnlock`].
fn upgrade_pending_unlock_from_v1_to_current(
    v1: PendingUnlockV1,
    id: PendingUnlockId,
) -> PendingUnlock {
    PendingUnlock {
        id,
        liquid_amount: v1.liquid_amount,
        stake_token_amount: v1.stake_token_amount,
        created_at: v1.created_at,
        cooldown_ends_at: v1.cooldown_ends_at,
        expires_at: v1.expires_at,
    }
}

/// Upgrades from the [`LiquidStakingStateV1`] to [`LiquidStakingState`].
///
/// # Parameters:
///
/// * `context`: The context for the action call.
/// * `state`: The current state of the contract.
#[upgrade]
pub fn upgrade_state_from_v1_to_current(
    _context: ContractContext,
    state: LiquidStakingStateV1,
) -> LiquidStakingState {
    let mut pending_unlocks = AvlTreeMap::new();
    let mut pending_unlock_id_counter = INITIAL_PENDING_UNLOCK_ID;
    for (user, user_pending_unlocks_v1) in state.pending_unlocks.iter() {
        let mut user_pending_unlocks = vec![];
        for pending_unlock_v1 in user_pending_unlocks_v1 {
            user_pending_unlocks.push(upgrade_pending_unlock_from_v1_to_current(
                pending_unlock_v1,
                pending_unlock_id_counter,
            ));
            pending_unlock_id_counter += 1;
        }
        pending_unlocks.insert(user, user_pending_unlocks);
    }

    LiquidStakingState {
        token_for_staking: state.token_for_staking,
        stake_token_balance: state.stake_token_balance,
        staking_responsible: state.staking_responsible,
        administrator: state.administrator,
        total_pool_stake_token: state.total_pool_stake_token,
        total_pool_liquid: state.total_pool_liquid,
        liquid_token_state: state.liquid_token_state,
        pending_unlocks,
        buy_in_tokens: state.buy_in_tokens,
        length_of_cooldown_period: state.length_of_cooldown_period,
        length_of_redeem_period: state.length_of_redeem_period,
        amount_of_buy_in_locked_stake_tokens: state.amount_of_buy_in_locked_stake_tokens,
        buy_in_percentage: state.buy_in_percentage,
        buy_in_enabled: state.buy_in_enabled,
        pending_unlock_id_counter,
    }
}
