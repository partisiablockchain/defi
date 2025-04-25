# NFT v2 (MPC-721-v2)

Example of an NFT (Non-Fungible Token) smart contract for Partisia
Blockchain, implementing the MPC-721-v2 standard.

## Background, NFT

An NFT is a unique identifier managed by an NFT contract, that can be
transferred between accounts on the blockchain. NFTs can be used in much the
same way as [MPC-20 tokens](../token-v2) can, but NFTs represent specific
instances of an object (non-fungible like a physical book; there are many like
it, but the one sitting on your bookshelf is yours and has a history), whereas
[tokens](../token-v2) are interchangeable (fungible; like money in a bank
account).

NFTs are often associated with specific artworks, which are publicly
accessible by a unique link stored in the contract; artwork is rarely stored
on-chain.

Some NFT contracts also manage additional attributes associated with each NFT,
for example their history of ownership. This functionality is not implemented
by `nft-v2`.

## Implementation

This example follows the mpc-721-v2 standard contract interface. You can read more about this standard here:  [https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-721-nft-contract.html](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-721-nft-contract.html)

The contract is inspired by the ERC721 NFT contract with extensions for Metadata and Burnable\
[https://github.com/ethereum/EIPs/blob/master/EIPS/eip-721.md](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-721.md)

## Extensions

The contract is meant as a jumping off point to making your own NFTs. Here are
some ideas:

- NFT attributes: Track anything you want! This can include ownership history,
  rarity, game stats, etc.
- On-chain generation: Partisia Blockchain REAL/ZK allows for true randomness.
  Generate your attributes on-chain and store your images off-chain.
- User-requested minting: With on-chain generation your can allow your users to
  mint their own NFTs. Then you can limit them to a certain amount, or let them
  run amok.
