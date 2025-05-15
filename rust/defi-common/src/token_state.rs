//! Mini-library providing commonly used methods for [MPC-20 token
//! contracts](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-20-token-contract.html).
//!
//! The extension trait [`AbstractTokenState`] allows contracts to easily implement transfer
//! functionality.
//!
//! These defi contracts use [`AbstractTokenState`]:
//!
//! - `token`
//! - `token_v2`

use std::ops::Add;

use pbc_contract_common::address::Address;

/// Type for tracking token amounts.
pub type TokenAmount = u128;

/// Extension trait that defines an abstract contract state for easy implementation of [MPC-20
/// token
/// contracts](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-20-token-contract.html).
///
/// Different tokens implement different underlying data-structures, but the operations that can be
/// done on MPC-20 token contracts are
/// [well-defined](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-20-token-contract.html)
/// .  This trait allows for easy implementation of the [`transfer()`](`AbstractTokenState::transfer`), [`transfer_from()`](`AbstractTokenState::transfer_from`) and
/// [`update_allowance_relative`](`AbstractTokenState::update_allowance_relative`) operations, for any implementations that provide the following
/// methods:
///
/// - [`get_symbol`](`AbstractTokenState::get_symbol`): For error messages.
/// - [`balance_of`](`AbstractTokenState::balance_of`): Get the balance of the specified user.
/// - [`update_balance`](`AbstractTokenState::update_balance`): Replaces the balance of the specified user. Should rarely be called
///   directly.
/// - [`allowance`](`AbstractTokenState::allowance`): Gets the allowance for the specified users.
/// - [`update_allowance`](`AbstractTokenState::update_allowance`): Replaces the allowance of the specified users.
pub trait AbstractTokenState {
    /// Gets the symbol string of the contract. Used for error messages.
    fn get_symbol(&self) -> &str;

    /// Gets the balance of the specified address.
    ///
    /// ### Parameters:
    ///
    /// * `owner`: The [`Address`] to query the balance of.
    ///
    /// ### Returns:
    ///
    /// An [`u64`] representing the amount owned by the passed address.
    fn balance_of(&self, owner: &Address) -> TokenAmount;

    /// Replaces the balance of the specified address.
    ///
    /// ### Parameters:
    ///
    /// * `owner`: The [`Address`] to replace the balance of.
    ///
    /// * `amount`: The amount of tokens to set the balance to.
    ///
    /// ### Safety
    ///
    /// This is a low level operation, and should rarely be called directly from contract
    /// invocations, as it allows for minting or burning tokens. A single call in the contract
    /// `#[init]` might be appropriate for minting initial balances.
    ///
    /// Contracts should prefer [`AbstractTokenState::transfer`], as it guarantees that the token amount will remain
    /// constant.
    fn update_balance(&mut self, owner: Address, amount: TokenAmount);

    /// Gets the amount of tokens that a user is allowed to spend on behalf of another user.
    ///
    /// ### Parameters:
    ///
    /// * `owner`: [`Address`] The address which owns the tokens.
    ///
    /// * `spender`: [`Address`] The address which will spend the tokens.
    ///
    /// ### Returns:
    ///
    /// A [`u64`] specifying the amount whicher `spender` is still allowed to withdraw from `owner`.
    fn allowance(&self, owner: &Address, spender: &Address) -> TokenAmount;

    /// Replaces the allowance of the given users, overwriting `owner`'s allowance for `spender` to `amount`.
    ///
    /// ## Parameters
    ///
    /// * `owner`: [`Address`] The address which owns the tokens.
    ///
    /// * `spender`: [`Address`] The address which will spend the tokens.
    ///
    /// * `amount`: The amount of tokens to set the allowance to.
    fn update_allowance(&mut self, owner: Address, spender: Address, amount: TokenAmount);

    /// Updates the allowance of the given users, by adding `delta` to `owner`'s allowance for `spender`.
    ///
    /// * If `owner` does not currently have any allowance, a new entry is created, with `delta` as the initial amount.
    /// * If `delta` is negative, the allowance is lowered.
    /// * Panics if adding `delta` would overflow, or the allowed balance would become negative.
    ///
    /// ## Parameters
    ///
    /// * `owner`: [`Address`] The address which owns the tokens.
    ///
    /// * `spender`: [`Address`] The address which will spend the tokens.
    ///
    /// * `delta`: The amount of tokens to update with.
    fn update_allowance_relative(&mut self, owner: Address, spender: Address, delta: i128) {
        let existing_allowance = self.allowance(&owner, &spender);
        let new_allowance = existing_allowance
            .checked_add_signed(delta)
            .expect("Allowance would become negative.");
        self.update_allowance(owner, spender, new_allowance);
    }

    /// Transfers `amount` of tokens to address `to` from the caller.
    ///
    /// The [`AbstractTokenState::transfer`] fails if the message caller's account balance does not have enough tokens to spend.
    ///
    /// If the sender's account goes to 0, the sender's address is removed from state.
    ///
    /// ### Parameters:
    ///
    /// * `sender`: [`Address`], the sender of the transaction.
    ///
    /// * `to`: [`Address`], the address to transfer to.
    ///
    /// * `transfer_amount`: [`TokenAmount`], amount to transfer.
    fn transfer(&mut self, sender: Address, to: Address, transfer_amount: TokenAmount) {
        // Subtract from sender
        let sender_balance = self.balance_of(&sender);
        match sender_balance.checked_sub(transfer_amount) {
            Some(new_sender_balance) => self.update_balance(sender, new_sender_balance),
            None => panic_on_insufficient_funds_for_transfer(
                sender_balance,
                transfer_amount,
                self.get_symbol(),
            ),
        }

        // Add to receiver
        let receiver_balance = self.balance_of(&to);
        self.update_balance(to, receiver_balance.add(transfer_amount));
    }

    /// Transfers `amount` of tokens from address `from` to address `to`.
    ///
    /// This requires that the sender is allowed to do the transfer by the `from`
    /// account through [`AbstractTokenState::update_allowance`].
    ///
    /// The [`AbstractTokenState::transfer_from`] fails if the message caller's account
    /// balance does not have enough tokens to spend, or if the tokens were not approved.
    ///
    /// ### Parameters:
    ///
    /// * `sender`: [`Address`], the sender of the transaction.
    ///
    /// * `from`: [`Address`], the address to transfer from.
    ///
    /// * `to`: [`Address`], the address to transfer to.
    ///
    /// * `transfer_amount`: [`TokenAmount`], amount to transfer.
    fn transfer_from(
        &mut self,
        sender: Address,
        from: Address,
        to: Address,
        transfer_amount: TokenAmount,
    ) {
        let sender_allowance = self.allowance(&from, &sender);
        match sender_allowance.checked_sub(transfer_amount) {
            Some(new_sender_allowance) => self.update_allowance(from, sender, new_sender_allowance),
            None => panic_on_insufficient_funds_for_transfer_from(
                sender_allowance,
                transfer_amount,
                self.get_symbol(),
            ),
        }
        self.transfer(from, to, transfer_amount)
    }
}

/// Fails with a standardized error message for insufficient funds.
fn panic_on_insufficient_funds_for_transfer_from(
    allowance: u128,
    transfer_amount: u128,
    symbol: &str,
) -> ! {
    panic!(
        "Insufficient {} allowance for transfer_from! Allowed {}, but trying to transfer {} (in minimal units)",
        symbol, allowance, transfer_amount
    );
}

/// Fails with a standardized error message for insufficient funds.
fn panic_on_insufficient_funds_for_transfer(
    in_possession: u128,
    transfer_amount: u128,
    symbol: &str,
) -> ! {
    panic!(
        "Insufficient {} tokens for transfer! Have {}, but trying to transfer {} (in minimal units)",
        symbol, in_possession, transfer_amount
    );
}
