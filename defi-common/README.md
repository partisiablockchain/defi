# DeFi Common

Contains common functionality for Partisia DeFi contracts.

### Deploy
Provides contract deployment. Used for example by `dex-swap-factory` to repeatedly deploy new swap contracts.

### Interact MPC20
Used to create and interact with [MPC20 Token Contracts](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-20-token-contract.html). Used for example by `liquidity-swap` as the token contract.

### Permission
Provides a permission system for who is allowed to interact with a contract. Used for example in `dex-swap-factory`, to specify who can change deployed swap contracts.

### Token Balances
Provides a data structure for tracking pairwise token balances. Does not actually store the balances. Used for example by `liquidity-swap` to keep track of swap balances internally, while the actualy tokens are at their respective contracts.


### Math
Contains generic math operations which may be useful across multiple contracts.
