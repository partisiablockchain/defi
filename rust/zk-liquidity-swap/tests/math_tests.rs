//! Testing of the mathematical formulas of the contract.
use proptest::prelude::*;
use zk_liquidity_swap::{calculate_swap_to_amount, TokenSwapAmount};

proptest! {
    #[test]
    fn calculate_swap_to_amount_must_not_crash(
        pool_input_token in any::<u64>(),
        pool_output_token in any::<u64>(),
        amount_in in any::<TokenSwapAmount>(),
    ) {
        let swap_constant = TokenSwapAmount::from(pool_input_token) * TokenSwapAmount::from(pool_output_token);
        let _ = calculate_swap_to_amount(
            pool_input_token.into(),
            pool_output_token.into(),
            swap_constant,
            amount_in,
        );
    }
}
