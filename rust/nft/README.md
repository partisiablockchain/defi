# NFT v1 (MPC-721-v1)

**Has been superseded by the [NFT v2 contract](../nft-v2), which
is significantly cheaper and is better documented.**

Example contact for non-fungible tokens (NFTs).
Provides basic functionality to track and transfer NFTs, using a mint method for creating new bindings of NFTs to accounts.

## Implementation: 

Inspired by the ERC721 NFT contract with extensions for Metadata and Burnable\
[https://github.com/ethereum/EIPs/blob/master/EIPS/eip-721.md](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-721.md), and follows the MPC-721 standard contract interface. You can read more about this standard here:  [https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-721-nft-contract.html](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-721-nft-contract.html)

## Usage:

An NFT is identified via an [`u128`] tokenID.
Any token owner can `transfer` their tokens to other accounts, or `approve` other accounts
to transfer their tokens.
If Alice has been approved an NFT from Bob, then Alice can use `transfer_from` to transfer Bob's tokens.

Each token can only be approved to a single account.
Any token owner can also make another account an operator of their tokens using `set_approval_for_all`.

An operator is approved to manage all NFTs owned by the owner, this includes setting approval on each token and transfer.

