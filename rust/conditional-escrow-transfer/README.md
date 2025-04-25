# Conditional Escrow Transfer

Smart Contract to facilitate conditional transfer of funds. The contract acts as
a trustee in a value transaction with predetermined conditions.

## Usage

Conditional Escrow Transfer allows a sender to put tokens into an escrow contract which a receiver can receive when a condition has been fulfilled.
The escrow transfer contract handles a specific token type.

The sender can place tokens into escrow specifying the receiver, an approver, and a deadline. 
They can claim the tokens when the deadline is met and the condition is not fulfilled.

The approver can signal fulfilment of the condition. The condition itself is not part of the
contract.

Possible outcomes:

1. The receiver can claim the tokens when the condition has been fulfilled.
2. The sender can claim the tokens when the deadline is met and the condition is not fulfilled.
