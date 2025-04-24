# Liquidity Swap Lock

Smart contract implementing Automated Market Maker with support for short-termed Option contract-likes.

Extension of the [liquidity swap contract](../liquidity-swap/README.md) with
the ability to create liquidity locks; short lived guarentees for specific
exchange rates, similar to a [Option
contract](https://en.wikipedia.org/wiki/Option_%28finance%29). Each lock guarentees
that the user is able to exercise the lock to acquire a specific number of
A tokens for a specific number of B tokens (or the other direction).

This functionality is based on that described in [Automated Market Makers for
Cross-chain DeFi and Sharded Blockchains](https://arxiv.org/abs/2309.14290),
but modified to address certain issues that were found in that paper. Addresses
issues include:

- The paper describes the use of a single "virtual" liquidity pool (in contrast
  to the liquidity pool of known assets), for tracking the exchange rate to
  offer for new locks, while other locks are valid. This "virtual" liquidity
  pool could be manipulated into an invalid state whereby it allowed an
  attacker to extract most of the liquidity in the pools.
- Above issue has been rectified in this contract by using two "virtual"
  liquidity pools instead, which track the best exchange rate that the contract
  can offer in each direction. This guarentees that the exchange rate is always
  favorable for the liquidity providers, though it may become less attractive
  for the users.

The locking mechanism allows users to acquire a lock on a swap, for e.g. Token
A -> Token B, at the liquidity pool state (of A and B) when the lock was
requested. A user can then later execute the lock-swap, swapping at the
exchange rate based on the liquidity pool state when the lock was acquired, and
not the current state.

The main purpose of this protocol is to allow users to efficiently perform
swaps cross-chain and on sharded blockchains. Specifically, users might want to
perform swap-chains, e.g. A -> B -> C -> D, if no direct swap is available
between the held and desired token. The user would like some guarantee on the
exchange rate for A -> D, but this rate may change if the state of the C ->
D liquidity pool changes while the users is performing the A -> B or B ->
C swap. Acquiring locks allows the user to guarantee a desired exchange rate,
which can then be executed if all locks are acquired. If some locks are
acquired, but not all, and the user wants to abort, the acquired locks and
simply be cancelled, and the user performs no swap.

## Exchange Rate Quote Giving

Given the set of locks that have been given, and the current liquidity, how do
we determine what exchange rate to assign to a new lock? Remember that locks
can be cancelled, and new ones can be created after this lock.

The quote giving strategy should provide competitive prices, but it should
ensure that the prices aren't so good as to bankrupt the swap contract (aka.
the contract should not be leaking liquidity.)

This contract specifically uses a pessimistic strategy whereby it maintains
variables for tracking the most A or B tokens it can receive (assuming that the
only locks that are executed are those in the direction of A/B); it does not
track the minimum amount of the other token it will receive.

The use of the maximum received concept implies that the contract will produce
a spread, that will grow as more locks are created, and more tokens are locked.

## Invariants

Desired properties:

- Liquidity providers are guarenteed to be able to withdraw at least as much
  liquidity as they provided, measured as the product of the liquidity pools.
  Note that liquidity is not the same as value: As the exchange rate of the
  underlying tokens change, it is possible that the value -- measured compared
  to a reference asset, like USD -- of the liquidity pools shrink. The value
  withdraw can thus be lower than the the value deposited. This is commonly
  called [Impermanent
  Loss](https://www.kraken.com/learn/what-is-impermanent-loss).
- Any user with permission can create a lock with any amount of input token.
- Any user that owns a lock can exercise it at any time, as long as they
  possess the required amount of input tokens.

We define the estimated realized liquidity as the factual liquidity added to
the sum of the execution of the existing locks: `estliq = liq + sum(locks)`.

- The `estliq` liquidity constant must not be decreasing when _acquiring locks_
  or _executing locks_. From this we can also conclude that the same holds for
  _instant swaps_.
