# Conditional Escrow Transfer

Contract to facilitate conditional transfer of funds. The contract acts as
a trustee in a value transaction with predetermined conditions.

## Usage

Conditional Escrow Transfer allows a sender to put tokens into an escrow contract which a receiver can receive when a condition has been fulfilled.
The escrow transfer contract handles a specific token type.

A sender can place tokens into escrow specifying the receiver and an approver that signals
condition fulfilment and a deadline.

The approver can signal fulfilment of the condition. The condition itself is not part of the
contract, only the signalling of the fulfilment of the condition.

The receiver can claim the tokens when the condition has been fulfilled.
The sender can claim the tokens when the deadline is met and the condition is not fulfilled.
