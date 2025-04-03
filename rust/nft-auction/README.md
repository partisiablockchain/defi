# NFT Auction contract

Auction contract that allows deployer to sell an NFT. Both NFT and bids are
escrowed.

## Usage

Usage is started with a `transfer` calls to the token and NFT contracts with
callbacks ensuring that the transfers were successful.
If a bid is not the current highest bid the transferred bidding tokens can
be claimed during any phase.

The auction has a set `duration`. After this duration the auction no longer accepts bids and can
be executed by anyone. Once `execute` has been called the contract adds the NFT as a claim for
the auction winner and the winning bid amount as a claim for the contract owner.

In the bidding phase any account can call `bid` on the auction which makes a token `transfer`
from the bidder to the contract. Once the transfer is done the contract updates its
highest bidder accordingly.

The contract owner also has the ability to `cancel` the contract during the bidding phase.

If `cancel` is called the highest bid is taken out of escrow such that the highest bidder can
claim it again. The same is done for the NFT which the contract owner
then can claim.
