//! Computation file to implement the order matching logic.
//!
//! Mainly implemented in [`match_order`], which when given an [`Order`] will check all other
//! orders in the system, for any matching using [`is_a_match`], and return a [`Match`] for the
//! best match.
use pbc_zk::*;

type MatchAmountType = Sbi128;
type SecretSecretVarId = Sbi32;

/// Variable type for [`Order`] ZK variables.
pub const VARIABLE_TYPE_ORDER: u8 = 0u8;

/// Variable type for [`Match`] ZK variables.
pub const VARIABLE_TYPE_MATCH: u8 = 1u8;

/// Metadata for ZK variables.
#[repr(C)]
#[derive(read_write_state_derive::ReadWriteState, Debug, Clone)]
pub struct VarMetadata {
    /// Used to distinguish between [`Order`] and [`Match`] variables.
    pub variable_type: u8,
    /// Number of Token A owner of the [`Order`] variable possess.
    ///
    /// Invariant: Zero if variable type is [`VARIABLE_TYPE_MATCH`].
    pub deposit_amount_a: u128,
    /// Number of Token B owner of the [`Order`] variable possess.
    ///
    /// Invariant: Zero if variable type is [`VARIABLE_TYPE_MATCH`].
    pub deposit_amount_b: u128,
}

/// A secret order of some tokens for some other tokens.
///
/// The direction of the trade is based on [`Order::buy_a`]:
///
/// * When `buy_a == true`: An order to buy `amount_a` tokens of type A for `amount_b` or less tokens of type B.
/// * When `buy_a == false`: An order to buy `amount_b` tokens of type B for `amount_a` or less tokens of type A.
///
/// Thus two orders satisfy each other if they buy different tokens, and each order gets more of the
/// bought tokens than they specified. See [`crate`] documentation for further information on the
/// matching algorithm.
#[repr(C)]
#[derive(pbc_zk::SecretBinary, Clone, Copy, create_type_spec_derive::CreateTypeSpec)]
pub struct Order {
    /// Either the amount of bought or sold token A.
    pub amount_a: MatchAmountType,
    /// Either the amount of bought or sold token B.
    pub amount_b: MatchAmountType,
    /// Whether the order is buying or selling token A.
    pub buy_a: Sbu1,
}

/// A match of [`Order`] orders.
#[repr(C)]
#[derive(pbc_zk::SecretBinary)]
pub struct Match {
    /// The [`SecretVarId`] for the buyer's [`Order`] variable, as a secret-shared variable.
    pub order_id_buyer: SecretSecretVarId,
    /// The [`SecretVarId`] for the seller's [`Order`] variable, as a secret-shared variable.
    pub order_id_seller: SecretSecretVarId,
    /// The amount of token A's being traded
    pub amount_a: MatchAmountType,
    /// The amount of token B's being traded
    pub amount_b: MatchAmountType,
}

/// Keeps track of a potential match within the order matching logic.
#[repr(C)]
#[derive(pbc_zk::SecretBinary, Clone, Copy, create_type_spec_derive::CreateTypeSpec)]
pub struct PotentialMatch {
    /// The [`SecretVarId`] for the buyer's [`Order`] variable, as a secret-shared variable.
    pub order_id_buyer: SecretSecretVarId,
    /// The [`SecretVarId`] for the seller's [`Order`] variable, as a secret-shared variable.
    pub order_id_seller: SecretSecretVarId,
    /// The buyer's [`Order`] variable.
    pub buy_a: Order,
    /// The seller's [`Order`] variable.
    pub buy_b: Order,
}

/// Performs order matching, with the given [`SecretVarId`] as focus.
///
/// Returns a [`Match`] value.
#[allow(unused)]
#[zk_compute(shortname = 0x60)]
pub fn match_order(order_id: SecretVarId) -> Match {
    let new_order: Order = load_sbi::<Order>(order_id);
    let mut best_match_yet: Match = Match {
        order_id_buyer: Sbi32::from(0),
        order_id_seller: Sbi32::from(0),
        amount_a: Sbi128::from(0),
        amount_b: Sbi128::from(0),
    };
    for counterpart_id in secret_variable_ids() {
        let counterpart_metadata = load_metadata::<VarMetadata>(counterpart_id);
        let is_counterpart_variable = counterpart_metadata.variable_type == VARIABLE_TYPE_ORDER;
        if is_counterpart_variable {
            let counterpart: Order = load_sbi::<Order>(counterpart_id);

            let potential_match: PotentialMatch = if new_order.buy_a {
                PotentialMatch {
                    order_id_buyer: Sbi32::from(order_id.raw_id as i32),
                    order_id_seller: Sbi32::from(counterpart_id.raw_id as i32),
                    buy_a: new_order,
                    buy_b: counterpart,
                }
            } else {
                PotentialMatch {
                    order_id_seller: Sbi32::from(order_id.raw_id as i32),
                    order_id_buyer: Sbi32::from(counterpart_id.raw_id as i32),
                    buy_b: new_order,
                    buy_a: counterpart,
                }
            };

            if is_a_match(potential_match)
                && deposit_large_enough(order_id, new_order)
                && deposit_large_enough(counterpart_id, counterpart)
            {
                best_match_yet = Match {
                    order_id_buyer: potential_match.order_id_buyer,
                    order_id_seller: potential_match.order_id_seller,
                    amount_a: potential_match.buy_b.amount_a,
                    amount_b: potential_match.buy_a.amount_b,
                }
            }
        }
    }
    best_match_yet
}

fn deposit_large_enough(order_id: SecretVarId, order: Order) -> Sbu1 {
    let metadata = load_metadata::<VarMetadata>(order_id);
    if order.buy_a {
        order.amount_b <= Sbi128::from(metadata.deposit_amount_b as i128)
    } else {
        order.amount_a <= Sbi128::from(metadata.deposit_amount_a as i128)
    }
}

/// Determines whether the given [`PotentialMatch`] is a match fitting the requirements.
fn is_a_match(potential_match: PotentialMatch) -> Sbu1 {
    // Not a match when both parts are buying / selling
    let order_swap: Sbu1 = potential_match.buy_b.buy_a != potential_match.buy_a.buy_a;

    // Not a match if ratio is bad
    order_swap && orders_satisfies_the_other(potential_match)
}

/// Determines whether the [`PotentialMatch`] satisfies the given purchase ratio.
fn orders_satisfies_the_other(potential_match: PotentialMatch) -> Sbu1 {
    potential_match.buy_b.amount_a >= potential_match.buy_a.amount_a
        && potential_match.buy_a.amount_b >= potential_match.buy_b.amount_b
}
