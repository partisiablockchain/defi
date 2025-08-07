//! Testing of the priority queue implementation.
use double_auction_order_matching::*;
use proptest::prelude::*;

fn id_from_raw(r: u64) -> LimitOrderId {
    LimitOrderId { raw_id: r }
}

/// Priorities created with `cheap_early` are sorted by price in ascending order
/// primarily, and by id in ascending order secondarily.
#[test]
fn cheap_early_examples() {
    let pri_0_0 = Priority::cheap_early(0, id_from_raw(0));
    let pri_0_1 = Priority::cheap_early(0, id_from_raw(1));
    let pri_0_9 = Priority::cheap_early(0, id_from_raw(9));
    let pri_1_0 = Priority::cheap_early(1, id_from_raw(0));
    let pri_256_0 = Priority::cheap_early(256, id_from_raw(0));
    let pri_max_1 = Priority::cheap_early(u64::MAX, id_from_raw(1));

    assert!(pri_0_0.key < pri_0_1.key);
    assert!(pri_0_1.key < pri_0_9.key);
    assert!(pri_0_1.key < pri_1_0.key);
    assert!(pri_0_1.key < pri_max_1.key);
    assert!(pri_1_0.key < pri_256_0.key);
}

/// Priorities created with `expensive_early` are sorted by price in descending order
/// primarily, and by id in ascending order secondarily.
#[test]
fn expensive_early_examples() {
    let pri_0_0 = Priority::expensive_early(0, id_from_raw(0));
    let pri_0_1 = Priority::expensive_early(0, id_from_raw(1));
    let pri_0_9 = Priority::expensive_early(0, id_from_raw(9));
    let pri_1_0 = Priority::expensive_early(1, id_from_raw(0));
    let pri_256_0 = Priority::expensive_early(256, id_from_raw(0));
    let pri_max_1 = Priority::expensive_early(u64::MAX, id_from_raw(1));
    let pri_max_3 = Priority::expensive_early(u64::MAX, id_from_raw(3));

    assert!(pri_0_0.key < pri_0_1.key);
    assert!(pri_0_1.key < pri_0_9.key);
    assert!(pri_1_0.key < pri_0_1.key);
    assert!(pri_256_0.key < pri_1_0.key);
    assert!(pri_max_1.key < pri_0_1.key);
    assert!(pri_max_1.key < pri_max_3.key);
}

proptest! {
    /// Priorities created with `cheap_early` are sorted by price in ascending order primarily.
    #[test]
    fn cheap_early_price(
        price1 in any::<u64>(),
        price2 in any::<u64>(),
        id in any::<u64>(),
    ) {
        prop_assume!(price1 < price2);

        let cheap_early_pri1 = Priority::cheap_early(price1, id_from_raw(id));
        let cheap_early_pri2 = Priority::cheap_early(price2, id_from_raw(id));

        assert!(cheap_early_pri1.key < cheap_early_pri2.key);
    }

    /// Priorities created with `cheap_early` are sorted by id in ascending order secondarily.
    #[test]
    fn cheap_early_id(
        price in any::<u64>(),
        id1 in any::<u64>(),
        id2 in any::<u64>(),
    ) {
        prop_assume!(id1 < id2);

        let cheap_early_pri1 = Priority::cheap_early(price, id_from_raw(id1));
        let cheap_early_pri2 = Priority::cheap_early(price, id_from_raw(id2));

        assert!(cheap_early_pri1.key < cheap_early_pri2.key);
    }

    /// Priorities created with `expensive_early` are sorted by prices in descending order primarily.
    #[test]
    fn expensive_early_price(
        price1 in any::<u64>(),
        price2 in any::<u64>(),
        id in any::<u64>(),
    ) {
        prop_assume!(price1 < price2);

        let expensive_early_pri1 = Priority::expensive_early(price1, id_from_raw(id));
        let expensive_early_pri2 = Priority::expensive_early(price2, id_from_raw(id));

        assert!(expensive_early_pri1.key > expensive_early_pri2.key);
    }

    /// Priorities created with `expensive_early` are sorted by id in ascending order secondarily.
    #[test]
    fn expensive_early_id(
        price in any::<u64>(),
        id1 in any::<u64>(),
        id2 in any::<u64>(),
    ) {
        prop_assume!(id1 < id2);

        let expensive_early_pri1 = Priority::expensive_early(price, id_from_raw(id1));
        let expensive_early_pri2 = Priority::expensive_early(price, id_from_raw(id2));

        assert!(expensive_early_pri1.key < expensive_early_pri2.key);
    }
}
