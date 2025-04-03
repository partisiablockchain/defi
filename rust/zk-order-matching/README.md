# ZK Order Matching

Order matching contract using secret-sharing to hide orders until a match have
been found.

Operates a similar deposit mechanism to liquidity swap, requiring
users to deposit some amount of tokens before being able to place orders.

User flow:

- [`deposit`]: Deposit some amount of tokens.
- [`place_order`]: Place a secret-shared order. The order will be appended to
  the worklist.
- At some point the worklist will reach the order, after which it will be
  attempted matched. If no matches were available, it will remain in the state.
- If anyone inputs a matching order at some point it will be resolved.
- [`withdraw`]: Withdraw tokens from the order-matching contract.

## Order matching algorithm

Orders are matched in both directions. The matching algorithm is very simple,
and will match two orders whenever these orders are buying different tokens,
and each order is getting a satisfying deal. Both parts will always pay the
entire sum they promised, but they might get more than they requested. There is
no support for partial matching, and there is no consideration take to exchange
rates. There is no distinction between maker and taker in this model, nor is
there a difference between the tokens.

Example: Say we are buying 10 A for 20 B. Following orders could be matching
with this order:

- Selling 10 A for 20 B. Exact match. Both gain precisely what they asked for.
- Selling 10 A for 18 B. Inexact match: Whoever we matched with will gain 2 B more than expected.
- Selling 11 A for 20 B. Inexact match: We will gain 1 A more than expected.
- Selling 11 A for 18 B. Inexact match: Both parts gain slightly more than they expected.

## Invariants

- Cannot create order match contract between the same token.
- Deposits are checked with the token contracts.
- Withdraws can only be done when no orders are placed.
- Withdraws are communicated to the token contracts.
- Orders by users without balances are quickly ignored.
- Cancelling orders waits for the completion of the current computation.
- Whenever orders are compatible we can guarentee that the users have enough
  deposit to execute them.
- When two orders are compatible, they are matched, and executed.

