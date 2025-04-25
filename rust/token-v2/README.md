# MPC-20 Token v2

Smart contract implementing standard [MPC-20-v2 token
contract](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-20-token-contract.html),
that provides the standard methods ([`transfer`], [`transfer_from`]), and a few
extensions ([`bulk_transfer`], [`approve_relative`]).

The total supply is initialized with the contract, is assigned to the
initializing user, and remains constant afterward. Burns are not explicitly
supported.

## Background

A token contract is a smart contract that provides a simple currency (token)
that can be [`transfer`]red between users, and is a basic building block of the
Decentralized Finance Eco-System. Functionality have standardized on a few
basic operations, initially described for the Ethereum VM compatible
blockchains as [ERC-20 token
contract](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-20.md). This
standard have been ported to Partisia Blockchain as the [MPC-20 token contract
standard](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-20-token-contract.html)
