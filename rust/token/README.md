# MPC-20 Token Contract

Standard [MPC-20-v1 token
contract](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-20-token-contract.html),
that provides the standard methods ([`transfer`], [`transfer_from`]), and a few
extensions ([`bulk_transfer`], [`approve_relative`]).

The total supply is initialized with the contract, is assigned to the
initializing user, and remains constant afterwards. Burns are not explicitly
supported.

**This contract uses an inefficient storage system, and have been superceeded by
the `token-v2` contract; prefer that instead.**

## Background

A token contract is a smart contract that provides a simple currency (token)
that can be [`transfer`]red between users, and is a basic building block of the
Decentralized Finance Eco-System. Functionality have standardized on a few
basic operations, initially described for the Ethereum VM compatible
blockchains as [ERC-20 token
contract](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-20.md). This
standard have been ported to Partisia Blockchain as the [MPC-20 token contract
standard](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-20-token-contract.html)
