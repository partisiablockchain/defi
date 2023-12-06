# Token contract example

This is an example token smart contract.

The contract has a constant total supply of tokens.
The total supply is initialized together with the contract.

Any token owner can then `transfer` tokens to other accounts, or `approve` other accounts to use their tokens.
If a Alice has been approved tokens from Bob, then Alice can use `transfer_from` to use Bob's tokens.

The contract will use the new MPC-20 V2 format not yet specified. You can find the old MPC-20 format [here](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-20-token-contract.html)

The contract is inspired by the ERC20 token contract:
<https://github.com/ethereum/EIPs/blob/master/EIPS/eip-20.md>