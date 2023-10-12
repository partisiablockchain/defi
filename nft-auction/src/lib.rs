#![doc = include_str!("../README.md")]
#![allow(unused_variables)]

#[macro_use]
extern crate pbc_contract_codegen;

use create_type_spec_derive::CreateTypeSpec;
use pbc_contract_common::address::{Address, AddressType, Shortname};
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::EventGroup;
use pbc_contract_common::sorted_vec_map::SortedVecMap;
use read_write_rpc_derive::{ReadRPC, WriteRPC};
use read_write_state_derive::ReadWriteState;

/// Bids in the NFT auction.
///
/// ### Fields:
///
/// * `bidder`: [`Address`], the address of the bidder.
///
/// * `amount`: [`u128`], the bid amount.
#[derive(ReadRPC, WriteRPC, ReadWriteState, CreateTypeSpec)]
#[cfg_attr(test, derive(PartialEq, Eq, Clone, Debug))]
pub struct Bid {
    bidder: Address,
    amount: u128,
}

/// Claims used by the contract's claim-map.
///
/// ### Fields:
///
/// * `tokens_for_bidding`: [`u128`], The claimable tokens for bidding.
///
/// * `nft_for_sale`: [`Option<Address>`], The claimable nft for sale.
#[derive(ReadWriteState, CreateTypeSpec)]
#[cfg_attr(test, derive(PartialEq, Eq, Clone, Debug))]
pub struct Claim {
    tokens_for_bidding: u128,
    nft_for_sale: Option<Address>,
}

/// Constants for the different phases of the contract.
type ContractStatus = u8;

/// Creation phase of the contract.
pub const CREATION: ContractStatus = 0;
/// Bidding phase of the contract.
pub const BIDDING: ContractStatus = 1;
/// Ended phase of the contract.
pub const ENDED: ContractStatus = 2;
/// Cancelled phase of the contract.
pub const CANCELLED: ContractStatus = 3;

/// Token contract actions
#[inline]
fn token_contract_transfer() -> Shortname {
    Shortname::from_u32(0x01)
}

#[inline]
fn transfer_from() -> Shortname {
    Shortname::from_u32(0x03)
}

/// Custom struct for the state of the contract.
///
/// The "state" attribute is attached.
///
/// ### Fields:
///
/// * `contract_owner`: [`Address`], the owner of the contract as well as the person selling tokens.
///
/// * `end_time`: [`i64`], the end time in millis UTC.
///
/// * `nft_for_sale`: [`Address`], the address of the NFT sold by the contract.
///
/// * `token_for_bidding`: [`Address`], the address of the token used for bids.
///
/// * `highest_bidder`: [`Bid`], the current highest `Bid`.
///
/// * `reserve_price`: [`u128`], the reserve price (minimum cost of the tokens for sale).
///
/// * `min_increment`: [`u128`], the minimum increment of each bid.
///
/// * `claim_map`: [`SortedVecMap<Address, Claim>`], the map of all claimable tokens and/or claimable NFT.
///
/// * `status`: [`u8`], the status of the contract.
#[state]
#[cfg_attr(test, derive(Clone, PartialEq, Eq, Debug))]
pub struct NftAuctionContractState {
    contract_owner: Address,
    end_time_millis: i64,
    nft_for_sale_address: Address,
    nft_for_sale_id: u128,
    token_for_bidding: Address,
    highest_bidder: Bid,
    reserve_price: u128,
    min_increment: u128,
    claim_map: SortedVecMap<Address, Claim>,
    status: ContractStatus,
}

impl NftAuctionContractState {
    /// Add a claim to the `claim_map` of the contract.
    ///
    /// ### Parameters:
    ///
    /// * `bidder`: The [`Address`] of the bidder.
    ///
    /// * `additional_claim`: The additional [`Claim`] that the `bidder` can claim.
    ///
    fn add_to_claim_map(&mut self, bidder: Address, additional_claim: Claim) {
        if !self.claim_map.contains_key(&bidder) {
            self.claim_map.insert(
                bidder,
                Claim {
                    tokens_for_bidding: 0,
                    nft_for_sale: None,
                },
            );
        }

        let value = self.claim_map.get_mut(&bidder).unwrap();
        value.tokens_for_bidding += additional_claim.tokens_for_bidding;
        value.nft_for_sale = additional_claim.nft_for_sale;
    }
}

/// Initial function to bootstrap the contracts state.
///
/// ### Parameters:
///
/// * `ctx`: [`ContractContext`], initial context.
///
/// * `nft_for_sale`: [`Address`], the address of the NFT for sale.
///
/// * `nft_for_sale_id`: [`u128`], the id of the NFT for sale.
///
/// * `token_for_bidding`: [`Address`], the address of the token used for bidding.
///
/// * `reserve_price`: [`u128`], the reserve price (minimum cost of the NFT for sale).
///
/// * `min_increment`: [`u128`], the minimum increment of each bid.
///
/// * `auction_duration_hours`: [`u32`], the duration of the auction in hours, from the auction is started by the contract owner.
///
/// ### Returns:
///
/// The new state object of type [`NftAuctionContractState`] with the initial state being
/// [`CREATION`].
#[init]
pub fn initialize(
    ctx: ContractContext,
    nft_for_sale_address: Address,
    nft_for_sale_id: u128,
    token_for_bidding: Address,
    reserve_price: u128,
    min_increment: u128,
    auction_duration_hours: u32,
) -> (NftAuctionContractState, Vec<EventGroup>) {
    if nft_for_sale_address.address_type != AddressType::PublicContract {
        panic!("Tried to create a contract selling a non publicContract NFT");
    }
    if token_for_bidding.address_type != AddressType::PublicContract {
        panic!("Tried to create a contract buying a non publicContract token");
    }
    let duration_millis = i64::from(auction_duration_hours) * 60 * 60 * 1000;
    let end_time_millis = ctx.block_production_time + duration_millis;
    let state = NftAuctionContractState {
        contract_owner: ctx.sender,
        end_time_millis,
        nft_for_sale_address,
        nft_for_sale_id,
        token_for_bidding,
        highest_bidder: Bid {
            bidder: ctx.sender,
            amount: 0,
        },
        reserve_price,
        min_increment,
        claim_map: SortedVecMap::new(),
        status: CREATION,
    };

    (state, vec![])
}

/// Action for starting the contract. The function throws an error if the caller isn't the `contract_owner`
/// or the contracts `status` isn't `STARTING`.
/// The contract is started by creating a transfer event from the `contract_owner`
/// to the contract of the tokens being sold as well as a callback to `start_callback`.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`], the context for the action call.
///
/// * `state`: [`NftAuctionContractState`], the current state of the contract.
///
/// ### Returns
///
/// The unchanged state object of type [`NftAuctionContractState`].
#[action(shortname = 0x01)]
pub fn start(
    context: ContractContext,
    state: NftAuctionContractState,
) -> (NftAuctionContractState, Vec<EventGroup>) {
    if context.sender != state.contract_owner {
        panic!("Start can only be called by the creator of the contract");
    }
    if state.status != CREATION {
        panic!("Start should only be called while setting up the contract");
    }
    // Create transfer event to contract for the token_for_sale
    // transfer should callback to start_callback (1)

    // Builder
    let mut event_group = EventGroup::builder();

    event_group.with_callback(SHORTNAME_START_CALLBACK).done();

    event_group
        .call(state.nft_for_sale_address, transfer_from())
        .argument(context.sender)
        .argument(context.contract_address)
        .argument(state.nft_for_sale_id)
        .done();

    (state, vec![event_group.build()])
}

/// Callback for starting the contract. If the transfer event was successful the `status`
/// is updated to `BIDDING`. If the transfer event failed the callback panics.
///
/// ### Parameters:
///
/// * `ctx`: [`ContractContext`], the contractContext for the callback.
///
/// * `callback_ctx`: [`CallbackContext`], the callbackContext.
///
/// * `state`: [`NftAuctionContractState`], the current state of the contract.
///
/// ### Returns
///
/// The new state object of type [`NftAuctionContractState`].
#[callback(shortname = 0x02)]
pub fn start_callback(
    ctx: ContractContext,
    callback_ctx: CallbackContext,
    state: NftAuctionContractState,
) -> (NftAuctionContractState, Vec<EventGroup>) {
    let mut new_state = state;
    if !callback_ctx.success {
        panic!("Transfer event did not succeed for start");
    }
    new_state.status = BIDDING;
    (new_state, vec![])
}

/// Action for bidding on the auction. The function always makes a transfer event
/// to the token for bidding contract. On callback `bid_callback` is called to actually update
/// the state.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`], the context for the action call.
///
/// * `state`: [`NftAuctionContractState`], the current state of the contract.
///
/// * `bid_amount`: [`u128`], the amount of tokens in the bid.
///
/// ### Returns
///
/// The unchanged state object of type [`NftAuctionContractState`].
#[action(shortname = 0x03)]
pub fn bid(
    context: ContractContext,
    state: NftAuctionContractState,
    bid_amount: u128,
) -> (NftAuctionContractState, Vec<EventGroup>) {
    // Potential new bid, create the transfer event
    // transfer(auctionContract, bid_amount)

    let bid: Bid = Bid {
        bidder: context.sender,
        amount: bid_amount,
    };

    let mut event_group = EventGroup::builder();
    event_group
        .call(state.token_for_bidding, transfer_from())
        .argument(context.sender)
        .argument(context.contract_address)
        .argument(bid_amount)
        .done();
    event_group
        .with_callback(SHORTNAME_BID_CALLBACK)
        .argument(bid)
        .done();
    (state, vec![event_group.build()])
}

/// Callback from bidding. If the transfer event was successful the `bid` will be compared
/// to the current highest bid and the claim map is updated accordingly.
/// If the transfer event fails the state is unchanged.
///
/// ### Parameters:
///
/// * `ctx`: [`ContractContext`], the contractContext for the callback.
///
/// * `callback_ctx`: [`CallbackContext`], the callbackContext.
///
/// * `state`: [`NftAuctionContractState`], the current state of the contract.
///
/// * `bid`: [`Bid`], the bid containing information as to who the bidder was and which
/// amount was bid.
///
/// ### Returns
///
/// The new state object of type [`NftAuctionContractState`].
#[callback(shortname = 0x04)]
pub fn bid_callback(
    ctx: ContractContext,
    callback_ctx: CallbackContext,
    state: NftAuctionContractState,
    bid: Bid,
) -> (NftAuctionContractState, Vec<EventGroup>) {
    let mut new_state = state;
    if !callback_ctx.success {
        panic!("Transfer event did not succeed for bid");
    } else if new_state.status != BIDDING
        || ctx.block_production_time >= new_state.end_time_millis
        || bid.amount < new_state.highest_bidder.amount + new_state.min_increment
        || bid.amount < new_state.reserve_price
    {
        // transfer succeeded, since we are no longer accepting bids we add
        // this to the claim map so the sender can get his money back
        // if the bid was too small we also add it to the claim map
        new_state.add_to_claim_map(
            bid.bidder,
            Claim {
                tokens_for_bidding: bid.amount,
                nft_for_sale: None as Option<Address>,
            },
        );
    } else {
        // bidding phase and a new highest bid
        let prev_highest_bidder = new_state.highest_bidder;
        // update highest bidder
        new_state.highest_bidder = bid;
        // move previous highest bidders coin into the claim map
        new_state.add_to_claim_map(
            prev_highest_bidder.bidder,
            Claim {
                tokens_for_bidding: prev_highest_bidder.amount,
                nft_for_sale: None as Option<Address>,
            },
        );
    }
    (new_state, vec![])
}

/// Action for claiming tokens and/or the NFT. Can be called at any time during the auction. Only the highest
/// bidder and the owner of the contract cannot get their escrowed tokens.
/// If there is any available tokens for the sender in the claim map the contract creates
/// appropriate transfer calls for the token for bidding. Likewise if there is an NFT for the sender in the claim map,
/// the contract creates the appropriate transfer calls for the NFT. The entry in
/// the claim map is then set to 0 for the token for bidding and the NFT for sale is set to none.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`], the context for the action call.
///
/// * `state`: [`NftAuctionContractState`], the current state of the contract.
///
/// ### Returns
///
/// The new state object of type [`NftAuctionContractState`].
#[action(shortname = 0x05)]
pub fn claim(
    context: ContractContext,
    state: NftAuctionContractState,
) -> (NftAuctionContractState, Vec<EventGroup>) {
    let mut new_state = state;
    let opt_claimable = new_state.claim_map.get(&context.sender);
    match opt_claimable {
        None => (new_state, vec![]),
        Some(claimable) => {
            let mut event_group = EventGroup::builder();
            if claimable.tokens_for_bidding > 0 {
                event_group
                    .call(new_state.token_for_bidding, token_contract_transfer())
                    .argument(context.sender)
                    .argument(claimable.tokens_for_bidding)
                    .done();
            }
            if claimable.nft_for_sale.is_some() {
                event_group
                    .call(new_state.nft_for_sale_address, transfer_from())
                    .argument(context.contract_address)
                    .argument(context.sender)
                    .argument(new_state.nft_for_sale_id)
                    .done();
            }
            new_state.claim_map.insert(
                context.sender,
                Claim {
                    tokens_for_bidding: 0,
                    nft_for_sale: None as Option<Address>,
                },
            );
            (new_state, vec![event_group.build()])
        }
    }
}

/// Action for executing the auction. Panics if the block time is earlier than the contracts
/// end time or if the current status is not `BIDDING`. When the contract is executed the status
/// is changed to `ENDED`, and the highest bidder will be able to claim the sold tokens.
/// Similarly the contract owner is able to claim the amount of bidding tokens that the highest
/// bidder bid.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`], the context for the action call.
///
/// * `state`: [`NftAuctionContractState`], the current state of the contract.
///
/// ### Returns
///
/// The new state object of type [`NftAuctionContractState`].
#[action(shortname = 0x06)]
pub fn execute(
    context: ContractContext,
    state: NftAuctionContractState,
) -> (NftAuctionContractState, Vec<EventGroup>) {
    let mut new_state = state;
    if context.block_production_time < new_state.end_time_millis {
        panic!("Tried to execute the auction before auction end block time");
    } else if new_state.status != BIDDING {
        panic!("Tried to execute the auction when the status isn't Bidding");
    } else {
        new_state.status = ENDED;
        new_state.add_to_claim_map(
            new_state.contract_owner,
            Claim {
                tokens_for_bidding: new_state.highest_bidder.amount,
                nft_for_sale: None as Option<Address>,
            },
        );
        new_state.add_to_claim_map(
            new_state.highest_bidder.bidder,
            Claim {
                tokens_for_bidding: 0,
                nft_for_sale: Some(new_state.nft_for_sale_address),
            },
        );
        (new_state, vec![])
    }
}

/// Action for cancelling the auction. Panics if the caller is not the contract owner, the
/// block time is later than the contracts end time, or if the status is not `BIDDING`.
/// When the contract is cancelled the status is changed to `CANCELLED`, and the highest bidder
/// will be able to claim the amount of tokens he bid. Similarly the contract owner is
/// able to claim the tokens previously for sale.
///
/// ### Parameters:
///
/// * `context`: [`ContractContext`], the context for the action call.
///
/// * `state`: [`NftAuctionContractState`], the current state of the contract.
///
/// ### Returns
///
/// The new state object of type [`NftAuctionContractState`].
#[action(shortname = 0x07)]
pub fn cancel(
    context: ContractContext,
    state: NftAuctionContractState,
) -> (NftAuctionContractState, Vec<EventGroup>) {
    let mut new_state = state;
    if context.sender != new_state.contract_owner {
        panic!("Only the contract owner can cancel the auction");
    } else if context.block_production_time >= new_state.end_time_millis {
        panic!("Tried to cancel the auction after auction end block time");
    } else if new_state.status != BIDDING {
        panic!("Tried to cancel the auction when the status isn't Bidding");
    } else {
        new_state.status = CANCELLED;
        new_state.add_to_claim_map(
            new_state.highest_bidder.bidder,
            Claim {
                tokens_for_bidding: new_state.highest_bidder.amount,
                nft_for_sale: None as Option<Address>,
            },
        );
        new_state.add_to_claim_map(
            new_state.contract_owner,
            Claim {
                tokens_for_bidding: 0,
                nft_for_sale: Some(new_state.nft_for_sale_address),
            },
        );
        (new_state, vec![])
    }
}
