# Liquidity Swap Lock
This contract builds upon the default [liquidity swap contract](../liquidity-swap/README.md), by implementing
locks on the swaps, as described in [Automated Market Makers for Cross-chain DeFi and Sharded Blockchains](https://arxiv.org/abs/2309.14290).

The locking mechanism allows users to acquire a lock on a swap, for e.g. Token A -> Token B,
at the liquidity pool state (of A and B) when the lock was requested. A user can then later
execute the lock-swap, swapping at the exchange rate based on the liquidity pool state when the lock was acquired,
and not the current state.

The locking mechanism essentially works by having an actual state of the liquidity pools, which reflect actual balances,
while a virtual pool reflects balances as if the locks had been executed. When acquiring new locks,
or performing instant-swaps, the exchange rate will be the minimum exchange rate of the actual and virtual pool state.

The main purpose of this protocol is to allow users to efficiently perform swaps cross-chain and on sharded blockchains.
Specifically, users might want to perform swap-chains, e.g. A -> B -> C -> D, if no direct swap is available
between the held and desired token. The user would like some guarantee on the exchange rate for A -> D, but this rate
may change if the state of the C -> D liquidity pool changes while the users is performing the A -> B or B -> C swap.
Acquiring locks allows the user to guarantee a desired exchange rate, which can then be executed if all locks are acquired.
If some locks are acquired, but not all, and the user wants to abort, the acquired locks and simply be cancelled,
and the user performs no swap.