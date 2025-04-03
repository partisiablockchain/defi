# Router

Routing contract that utilizes the swap lock functionality of the [Liquidity
Swap Lock Contract](../liquidity-swap-lock/README.md) to perform swaps between
different [Tokens](../token/README.md), that might not have a direct swap
contract between them.

## How does it work
A swap between two tokens, A, D, without a direct swap contract: A -/> D, can be performed if there exist intermediary swaps,
that connect the two tokens (a swap route): A -> B -> C -> D, by way of swap contracts AB, BC and CD.
The router contract handles communication with and between the swap contracts of the route,
and ensures that swaps are performed atomically, i.e. either:
- The entire A -> B -> C -> D swap happens, taking the user's A tokens and producing D tokens exclusively.
- No swaps occur, such that the user retains all their A tokens, and gain no other tokens.

### Phases
From start to finish the swap happens in 4 distinct phases:
#### Request
- User approves the router at token A.
- User requests router to route the swap, with specific swap contracts.
- The router validates the route. See [valid route](#valid-route)
- The router takes control of the user tokens, so user cannot interfere in the
  swap process.

#### Locking

After a route has been validated, the router acquires all locks on the route:
- The router tries to acquire a lock at the next swap contract.
   * If successful, the swap contract returns the output amount of the lock
   * If unsuccessful, the router cancels all acquired locks and aborts
- Repeat lock acquisition until all locks are acquired

Lock acquisition is done iteratively with a callback between every acquisition, to use the lock output
as input to the next lock acquisition. This is done to prevent leftover tokens when executing locks.

#### Execution
After lock acquisition is finished, execution of the locks start:
- Router approves next swap contract at the current input swap token. (see [execution approval amount](#execution-approval-amount) for more details on the amount approved.)
- Router deposits current input token to swap contract.
- Router executes the current lock.
- Router withdraws the current output token from the swap contract.
- Set the output token as the new input token, and repeat.

These steps are done with a callback between every interaction, to guarantee correct ordering of the events,
since approval must happen before depositing, which must happen before execution.

Additionally, when withdrawing, [`wait_for_callback`](../defi-common/src/interact_swap.rs) is used,
to ensure tokens have been withdrawn before continuing the execution of the next lock.

#### Withdrawal
After all locks have been executed, the swap is finished. However, the router still needs to transfer
the tokens back to the user, as we took control of the tokens in the [locking phase](#locking).
- Transfer final output tokens to user.

## Usage of the router
To execute a swap route, the user must first approve the swap contract at the first token (e.g. A).
The user can then invoke the router with a list of swap contracts to go through.
As explained above, the router then handles all communication between swap contracts, and either
returns an error to the user, and no swaps are made, or performs all the desired swaps.

The swap contracts given by the user must be in order of the intended swaps, with the first swap being
the leftmost swap contract in the list.

## Guarantees
The following is a list of guarantees provided by the router, when performing a route-swap

### Atomicity of swap
As explained above, the whole swap chain is atomic, either being performed fully, or not at all.
This is guaranteed by utilizing the swap-locks provided by the swap contracts. By acquiring a swap-lock
the router is guaranteed to be able to execute a token swap, with a specific exchange rate between tokens.
Thus, if locks are acquired along the whole swap route, are swaps are guaranteed to be able to be executed.

If any lock acquisition fails along the route, the router is *not* guaranteed to be able to execute the swaps,
which is handled by aborting lock acquisition and cancelling all acquired locks, matching the outcome as if nothing
had been done.

Furthermore, since we are relying on the guarantee of the swap-locks, to ensure their correct behaviour
the router will only use swap contracts that are known beforehand, and comes from a trusted source. In this case
the trusted source is our [swap factory](...)

### Valid route
Before trying to communicate with swap contracts and acquire locks, the router checks that
the route defined by the user-provided swap contracts is valid, i.e. that output and input tokens match
at every swap contract, and the final swap output matches the intended output token.

### Desired output amount
The lock functionality of each swap contract guarantees that we receive the intended output token amount
at every swap. If the last acquired lock guarantees an output greater than or equal to the user-desired
output amount, we are guaranteed that the user will receive at least their desired amount of output tokens in the end.


## Execution Approval amount
When executing a swap along a route, the router must approve the swap contract at the relevant token contract,
to be able to deposit tokens into the swap contract. According to the [MPC20 Specification](https://partisiablockchain.gitlab.io/documentation/smart-contracts/integration/mpc-20-token-contract.html)
for token contracts, the `approve` action will override any existing allowance.
If the router is executing multiple routes at the same time, where the same token is involved, approvals can clash,
and deposits fail.

To solve this, we assume that the sum of approval amounts for any immediate
clashing swaps is less than the maximum possible token amount (TokenAmount::MAX). We can then
approve the maximum possible token amount to avoid any failures. This will leave
trace approvals for the swap contracts, on behalf of the router, at the token contracts.
