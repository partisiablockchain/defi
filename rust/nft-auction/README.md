# NFT Auction

Smart contract auction that allows deployer to sell an NFT (Non-Fungible Token). Both NFT and bids are
escrowed.

## Usage

NFT auction is initialised with `transfer` calls to the token and NFT contracts with
callbacks, ensuring that the transfers were successful.
If a bid is not the current highest bid the transferred bidding tokens can
be claimed during any phase.

The auction has a set `duration`. After this duration the auction no longer accepts bids and can
be executed by anyone. Once `execute` has been called, the NFT is added as a claim for
the auction winner and the winning bid amount as a claim for the contract owner.

In the bidding phase any account can call `bid` on the auction which makes a token `transfer`
from the bidder to the contract. Once the transfer is done the contract updates its
highest bidder accordingly.

The contract owner also has the ability to `cancel` the auction during the bidding phase.

If `cancel` is called, the highest bid is taken out of escrow such that the highest bidder can
claim it again. The same is done for the NFT which the contract owner can claim.
