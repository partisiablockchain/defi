#![doc = include_str!("../README.md")]

#[macro_use]
extern crate pbc_contract_codegen;

use create_type_spec_derive::CreateTypeSpec;
use pbc_contract_common::address::Address;
use pbc_contract_common::avl_tree_map::AvlTreeMap;
use pbc_contract_common::context::{CallbackContext, ContractContext};
use pbc_contract_common::events::EventGroup;
use pbc_contract_common::sorted_vec_map::SortedVecMap;
use read_write_rpc_derive::ReadWriteRPC;
use read_write_state_derive::ReadWriteState;

use defi_common::interact_mpc20::MPC20Contract;

use crate::ProposalType::NewCriteria;
use crate::VoteResult::{Approved, Denied};

/// The types of allowed votes.
#[derive(CreateTypeSpec, ReadWriteState, ReadWriteRPC)]
pub enum Vote {
    /// The `Yes` vote.
    #[discriminant(0)]
    Yes {},
    /// The `No` vote.
    #[discriminant(1)]
    No {},
}

/// The criteria for a proposal to be approved, defined by the list of allowed voters and the threshold for approval.
#[derive(CreateTypeSpec, ReadWriteState, ReadWriteRPC, Clone)]
pub struct Criteria {
    /// The list of allowed voters.
    voters: Vec<Voter>,
    /// The threshold for approval of a proposal.
    threshold: u32,
}

/// An allowed voter, with the address to identify them, and the weight of their vote.
#[derive(CreateTypeSpec, ReadWriteState, ReadWriteRPC, Clone)]
pub struct Voter {
    /// The blockchain address the voters sends transactions with.
    address: Address,
    /// The weight of the voters vote in a poll.
    weight: u32,
}

impl Criteria {
    /// Compute the total weight of votes, by summing the weights for all registered voters.
    ///
    /// ### Returns
    ///
    /// The total weights of votes.
    ///
    fn get_total_vote_weight(&self) -> u32 {
        self.voters.iter().map(|voter| voter.weight).sum()
    }

    /// Get the weight of the voter's vote.
    ///
    /// ### Parameters
    ///
    /// * `address`: [`Address`], the address of the voter.
    ///
    /// ### Returns
    ///
    /// The weight of the voter's vote.
    ///
    fn get_vote_weight(&self, address: &Address) -> u32 {
        self.voters
            .iter()
            .filter(|voter| voter.address == *address)
            .map(|voter| voter.weight)
            .last()
            .unwrap()
    }

    /// Check if a given address is in the list of registered voters.
    ///
    /// ### Parameters
    ///
    /// * `address`: [`Address`], the address to check for.
    ///
    /// ### Returns
    ///
    /// True, if the address is in the list of voters, else False.
    ///
    fn allowed_voter(&self, address: &Address) -> bool {
        self.voters
            .iter()
            .any(|voter: &Voter| voter.address == *address)
    }

    /// Check the validity of a criteria, to avoid deadlock of the contract.
    fn check_validity(&self) {
        let total_weight = self.get_total_vote_weight();

        if self.voters.is_empty() {
            panic!("Cannot use a criteria without assigned voters.")
        }
        if self.threshold > total_weight {
            panic!("Threshold cannot be larger then the total weight of votes.")
        }
    }
}

/// The result af a vote on a proposal.
#[derive(CreateTypeSpec, ReadWriteState, PartialEq, Clone)]
pub enum VoteResult {
    /// The proposal was denied.
    #[discriminant(0)]
    Denied {},
    /// The proposal was approved.
    #[discriminant(1)]
    Approved {},
}

/// A proposal, that must be voted on the take effect.
#[derive(CreateTypeSpec, ReadWriteState)]
pub struct Proposal {
    /// The type of action that will happen if the proposal is approved.
    proposal_type: ProposalType,
    /// The result of the vote on the proposal.
    result: Option<VoteResult>,
    /// The current votes on the proposal.
    votes: SortedVecMap<Address, Vote>,
}

impl Proposal {
    /// Create a new Proposal.
    ///
    /// ### Parameters
    ///
    /// * `proposal_type`: [`ProposalType`]
    ///
    /// ### Returns
    ///
    /// The newly created Proposal.
    ///
    pub fn new(proposal_type: ProposalType) -> Proposal {
        Proposal {
            proposal_type,
            result: None,
            votes: SortedVecMap::new(),
        }
    }
}

/// The types of proposals that can be voted on.
#[derive(CreateTypeSpec, ReadWriteState, ReadWriteRPC)]
pub enum ProposalType {
    /// `Transfer`
    #[discriminant(0)]
    Transfer {
        /// The amount to transfer.
        amount: u128,
        /// The address of the receiver.
        receiver: Address,
    },

    ///  `NewCriteria`
    #[discriminant(1)]
    NewCriteria {
        /// The proposed criteria to replace the old one.
        new_criteria: Criteria,
    },
}

/// The MOCCA contract state, that holds the current proposals, the criteria for approval and the amount the contract
/// current has in escrow.
///
/// ### Fields:
///
/// * `criteria`: [`Criteria`], the current approval criteria.
///
/// * `token_contract`: [`Address`], the related contracts transfers can be made from.
///
/// * `amount_of_tokens`: [`u128`], the current amount of tokens in escrow by the MOCCA contract.
///
/// * `next_proposal_id`: [`u32`], the id of the next proposal.
///
/// * `proposal`: [`AvlTreeMap<u32, Proposal>`], the proposals that is currently voted on or already executed.
///
#[state]
pub struct MoccaState {
    criteria: Criteria,
    token_contract: Address,
    amount_of_tokens: u128,
    next_proposal_id: u32,
    proposal: AvlTreeMap<u32, Proposal>,
}

impl MoccaState {
    /// Check if the current votes for a proposal is enough to make a conclusive decision.
    ///
    /// ### Parameters
    ///
    ///  *  `votes`: [`&SortedVecMap<Address, Vote>`], the current votes for the proposal.
    ///
    /// ### Returns
    ///
    /// `Approve`, if the weight of the `Yes` votes is greater than or equal to the threshold.
    /// `Denied`, if the weight of the `No` votes is greater than the threshold.
    ///  Panics if the vote is inconclusive with the current amount of votes.
    ///
    fn check_result(&self, votes: &SortedVecMap<Address, Vote>) -> VoteResult {
        let mut yes_vote_weight = 0;
        let mut no_vote_weight = 0;

        for (address, vote) in votes.iter() {
            if self.criteria.allowed_voter(address) {
                match vote {
                    Vote::Yes {} => yes_vote_weight += self.criteria.get_vote_weight(address),
                    Vote::No {} => no_vote_weight += self.criteria.get_vote_weight(address),
                }
            }
        }

        if yes_vote_weight >= self.criteria.threshold {
            Approved {}
        } else if no_vote_weight > self.criteria.get_total_vote_weight() - self.criteria.threshold {
            Denied {}
        } else {
            panic!("The voting was not conclusive.")
        }
    }
}

/// Initialize a new MOCCA contract with a initial criteria for voting.
///
/// ### Parameters
///
///   * `context`: [`ContractContext`] - the contract context containing sender and chain information.
///
///   * `criteria`: [`Criteria`],
///
///   * `token_contract`: [`Address`],
///
/// ### Returns
///
/// The initial state of the MOCCA contact.
///
#[init]
pub fn initialize(
    _ctx: ContractContext,
    criteria: Criteria,
    token_contract: Address,
) -> MoccaState {
    criteria.check_validity();

    MoccaState {
        criteria,
        token_contract,
        amount_of_tokens: 0,
        next_proposal_id: 0,
        proposal: AvlTreeMap::new(),
    }
}

/// Escrow a given amount of tokens in the MOCCA contract.
///
/// ### Parameters
///
///   * `context`: [`ContractContext`], the contract context containing sender and chain information.
///
///   * `state`: [`MoccaState`], the current state of the MOCCA contract.
///
///   * `amount`: [`u128`], the amount to send from the sender of the action to the MOCCA contract.
///
/// ### Returns
///
/// The state unchanged, and a transfer event, to transfer the amount of tokens to the MOCCA contract.
///
#[action(shortname = 0x01)]
pub fn escrow(
    context: ContractContext,
    state: MoccaState,
    amount: u128,
) -> (MoccaState, Vec<EventGroup>) {
    let mut event_builder = EventGroup::builder();
    MPC20Contract::at_address(state.token_contract).transfer_from(
        &mut event_builder,
        &context.sender,
        &context.contract_address,
        amount,
    );
    event_builder
        .with_callback(SHORTNAME_ESCROW_CALLBACK)
        .argument(amount)
        .done();

    let events = event_builder.build();
    (state, vec![events])
}

/// Send a new proposal for voting. The proposal can either be transfer of tokens or a new criteria to use.
///
/// ### Parameters
///
///   * `context`: [`ContractContext`], the contract context containing sender and chain information.
///
///   * `state`: [`MoccaState`], the current state of the MOCCA contract.
///
///   * `proposal_type`: [`ProposalType`],
///
/// ### Returns
///
/// The state with the proposal added.
///
#[action(shortname = 0x02)]
pub fn propose(
    _ctx: ContractContext,
    mut state: MoccaState,
    proposal_type: ProposalType,
) -> MoccaState {
    if let NewCriteria { ref new_criteria } = proposal_type {
        new_criteria.check_validity()
    }
    let proposal = Proposal::new(proposal_type);
    state.proposal.insert(state.next_proposal_id, proposal);
    state.next_proposal_id += 1;
    state
}

/// Vote on a specific proposal.
///
/// ### Parameters
///
///   * `context`: [`ContractContext`], the contract context containing sender and chain information.
///
///   * `state`: [`MoccaState`], the current state of the MOCCA contract.
///
///   * `proposal_id`: [`u32`], the proposal the vote is for.
///
///   * `vote`: [`Vote`], a `Yes` or `No` vote.
///
/// ### Returns
///
/// The state with the vote registered in the list of votes in the proposal.
///
#[action(shortname = 0x03)]
pub fn vote(
    ctx: ContractContext,
    mut state: MoccaState,
    proposal_id: u32,
    vote: Vote,
) -> MoccaState {
    if !state.criteria.allowed_voter(&ctx.sender) {
        panic!("Only addresses registered in the criteria as voters can vote.")
    }

    let mut voting_on = state
        .proposal
        .get(&proposal_id)
        .unwrap_or_else(|| panic!("No proposal with id {}", proposal_id));

    if voting_on.result.is_some() {
        panic!("Cannot vote on proposal that has been executed.")
    }

    voting_on.votes.insert(ctx.sender, vote);
    state.proposal.insert(proposal_id, voting_on);

    state
}

/// Execute a proposal.
///
/// ### Parameters
///
///   * `context`: [`ContractContext`], the contract context containing sender and chain information.
///
///   * `state`: [`MoccaState`], the current state of the MOCCA contract.
///
///   * `proposal_id`: [`u32`],
///
/// ### Returns
///
/// The state with the result of the proposal is set.
/// If the proposal was `Approved`, then in the case of a transfer proposal, a transfer event to the transfer contract
/// and a callback is registered. In the case of a new criteria, the new criteria replaces the old in the state.
///
#[action(shortname = 0x04)]
pub fn execute(
    _ctx: ContractContext,
    mut state: MoccaState,
    proposal_id: u32,
) -> (MoccaState, Vec<EventGroup>) {
    let mut proposal = state
        .proposal
        .get(&proposal_id)
        .unwrap_or_else(|| panic!("No proposal with id {}", proposal_id));
    if proposal.result.is_some() {
        panic!("The proposal has already been executed.");
    }

    let result: VoteResult = state.check_result(&proposal.votes);
    let mut event_builder = EventGroup::builder();

    proposal.result = Some(result.clone());

    if result == (Approved {}) {
        match proposal.proposal_type {
            ProposalType::Transfer {
                ref amount,
                ref receiver,
            } => {
                MPC20Contract::at_address(state.token_contract).transfer(
                    &mut event_builder,
                    receiver,
                    *amount,
                );
                event_builder
                    .with_callback(SHORTNAME_TRANSFER_CALLBACK)
                    .argument(proposal_id)
                    .argument(*amount)
                    .done();
            }
            ProposalType::NewCriteria { ref new_criteria } => {
                state.criteria = new_criteria.clone();
            }
        }
    }

    state.proposal.insert(proposal_id, proposal);

    (state, vec![event_builder.build()])
}

/// Check if the transfer of tokens from an escrow action was successful.
///
/// ### Parameters
///
///   * `context`: [`ContractContext`], the contract context containing sender and chain information.
///
///   * `callback_ctx`: [`CallbackContext`], the callback context.
///
///   * `state`: [`MoccaState`], the current state of the contract.
///
///   * `amount`: [`u128`], the amount of tokens sent.
///
/// ### Returns
///
/// If the transfer was a success, then the amount sent is added to the states total amount and the state is returned.
///
#[callback(shortname = 0x01)]
pub fn escrow_callback(
    ctx: ContractContext,
    callback_ctx: CallbackContext,
    mut state: MoccaState,
    amount: u128,
) -> (MoccaState, Vec<EventGroup>) {
    if callback_ctx.success {
        state.amount_of_tokens += amount;
    } else {
        panic!("Could not escrow {} tokens, from {}.", amount, ctx.sender)
    }

    (state, vec![])
}

/// Check if the transfer of tokens from a proposal transfer was a success.
///
/// ### Parameters
///
///   * `_ctx`: [`ContractContext`], the contract context containing sender and chain information.
///
///   * `callback_ctx`: [`CallbackContext`], the callback context.
///
///   * `state`: [`MoccaState`], the current state of the contract.
///
///   * `proposal_id`: [`u32`], the id of the transfer proposal.
///
///   * `amount`: [`u128`], the amount of tokens, that was sent.
///
/// ### Returns
///
/// If the transfer was a success, then the amount is subtracted from the total amount, if the transfer failed, then
/// the proposal result is reset, so it can be executed again.
///
#[callback(shortname = 0x02)]
pub fn transfer_callback(
    _ctx: ContractContext,
    callback_ctx: CallbackContext,
    mut state: MoccaState,
    proposal_id: u32,
    amount: u128,
) -> (MoccaState, Vec<EventGroup>) {
    if !callback_ctx.success {
        let mut proposal = state.proposal.get(&proposal_id).unwrap();
        proposal.result = None;
        state.proposal.insert(proposal_id, proposal);
    } else {
        state.amount_of_tokens -= amount;
    }

    (state, vec![])
}
