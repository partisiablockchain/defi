//! Upgrade logic from this version of the contract.

use crate::LiquidStakingState;
use pbc_contract_codegen::upgrade_is_allowed;
use pbc_contract_common::context::ContractContext;
use pbc_contract_common::upgrade::ContractHashes;

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

/// Upgrades the liquid staking contract v1 to itself.
///
/// # Parameters:
///
/// * `context`: The context for the action call.
/// * `state`: The current state of the contract.
#[upgrade]
pub fn upgrade_self(_context: ContractContext, state: LiquidStakingState) -> LiquidStakingState {
    state
}
