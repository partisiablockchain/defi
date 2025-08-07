# Double Auction Order Matching

This contract implements a mechanism for [order matching](https://en.wikipedia.org/wiki/Order_matching_system). This is
done by initializing the contract with a currency token and an asset token that the limit orders trade on, according
to a given exchange rate quota. 

Placing limit orders works by first checking if other limit orders meeting your ask or bid have been placed, and then 
meeting those orders until your order is fully met. If it cannot be fully met or not at all, the order is placed on 
the contract for others to meet.

The quota is given as how many asset tokens you can buy for one currency token, and are
given on initialization by the price of each token. The limit orders can be placed using the `submit_bid` and 
`submit_ask` actions.

When limit orders are placed, you also provide an ID used for cancelling the order. This can be done using the 
`cancel_limit_order` action, as long as the order has not yet been met. When they are met, your balance on the 
contract is withdrawn from/deposited to corresponding the amount placed, times the agreed upon price and the quota.
