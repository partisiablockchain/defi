# Prediction Market Token Splitter

This contract performs token splitting for
the [prediction market use case](https://en.wikipedia.org/wiki/Prediction_market). The token splitter has an internal balance, that users can deposit tokens into 
and withdraw tokens from. These tokens can be converted to true and false tokens, that you than transfer based on which 
outcome you want to bet on. Once the event has settled, users can then withdraw either the true or false tokens, 
based on the outcome of the event. For the event to be settled, an arbitrator account needs to settle the event.

This is done by being able to split the original token into its related true and false tokens.
When the event is settled and has either the true or false outcome, either the true or false tokens, 
respectively, can then be converted back into the original token.

From the balance of each account, you can split your tokens and join them back together using the `split` and `join` 
actions, respectively. This can be done only when the token splitter is active, which can be set using the `prepare` 
action. Note that the chosen arbitrator can only settle the event once, and in general that each deployed contract can only 
be used for one event. After the event has been settled, the users can then redeem their true or false tokens from 
their balance, using the `redeem` action.
