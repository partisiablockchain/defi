<div align="center">

# MOCCA - MPC On-Chain Custody Advanced

[Announcement](https://partisiablockchain.com/mocca-unveiled-in-davos/)

</div>

Decentralized Autonomous Organization capable of managing [MPC-20 tokens](../token-v2) through the
use of conditional proposals.

## Usage

Escrow a chosen token to the MOCCA contract, where the funds are managed through proposals, where each proposal goes
through a approval process defined by a criteria.

The first iteration of MOCCA has one type of criteria, a voting criteria.
The proposal must therefore pass in a voting, before it can be executed.

Each member of a committee has an assigned weight in a vote. This means that a members vote can impact a vote more
than another member's vote. A committee also has a threshold for a proposal to be approved.
The threshold is set for the committee and cannot be changed.

Users can create and execute proposals, where each proposal is voted on by the current committee. There are currently
two kinds of proposal, transfer and new committee. Everyone can make a proposal and execute it, when the proposal has
enough "Yes" votes, where the combined weight is greater than or equal to the threshold.

A transfer proposal is a transfer of an amount of tokens in escrow to a specified receiver. The transfer proposal can
then be voted on. The execution of the proposal, attempts to transfer the tokens. If the transfer fails because of a
lack of funds, then the proposal can be executed again, when funds become available.

A new committee proposal is to replace the current committee. The new committee can have different voters, and the
weight of their votes can also be different. A new threshold for that committee is stated in the proposal.
