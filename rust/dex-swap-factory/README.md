
# DEX: Swap Factory and Directory

Smart contract implementing swap factory and directory. Decentralized exchange example contract for Partisia Blockchain.

Deployed swap factories contains a directory of all swap contracts deployed
with the factory, and allows for the creation of new contracts between pairs.

## Usage:

- [`deploy_swap_contract`]: Creates a new swap contract between two tokens ([`TokenPair`]), and adds it to [the directory](SwapFactoryState::swap_contracts).
- [`update_swap_binary`]: Replaces the [contract binary](SwapFactoryState::swap_contract_binary) with a new version.
  Does not automatically update deployed contracts.
- [`delist_swap_contract`]: Removes given contracts from [Swap Directory](SwapFactoryState::swap_contracts).

The [Swap Directory](SwapFactoryState::swap_contracts) can be used to determine
which swap contracts the directory maintainers are confident in, but it does
not expose every relevant detail for selecting swap contracts. Liquidity amount
and current exchange rate must be queried from the relevant swap contracts.

