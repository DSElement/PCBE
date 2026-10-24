# Stock Exchange

A concurrent stock exchange simulated in Java. Sellers and buyers are threads that trade shares of several companies through a shared exchange. The project also contains two deliberately incorrect matchers that reproduce a deadlock and a race condition, next to the correct one.

## Requirements covered

- Sellers offer a number of shares at a price per share; buyers request a number of shares at a price.
- When an offer and a request have the same price, `min(offer, request)` shares are traded.
- Everyone can read the open orders and the transaction history.
- An order can be modified at any time, unless a transaction involving it is in progress.
- The market is simulated: buyers and sellers are threads running a pricing algorithm over the common information (order book and trade history).

## Requirements

- JDK 25 (set in `pom.xml`)
- Maven

## Build and run

```
mvn compile
java -cp target/classes Simulation
java -cp target/classes DeadlockSimulation
java -cp target/classes RaceConditionSimulation
```

Each simulation exits with code 0 when it shows what it is meant to show, and 1 otherwise.

## Design

| Class | Role |
|---|---|
| `Exchange` | Entry point. Holds one `OrderBook` per company and a fixed pool of matching workers. Submitting or modifying an order schedules a matching pass on the pool. |
| `OrderBook` | The open orders of one company, keyed by order id. Rejects duplicate ids. |
| `OrderMatcher` | Matches an order against the opposite side at an equal price, records `Trade`s, and removes filled orders. |
| `Order` | An order with its own lock. Its getters and `reduceQuantity` lock internally; `lock()`/`unlock()` let the matcher hold it across several steps. |
| `Trader` | A participant's view of the exchange: publish and modify orders, read the market and the trade history, and receive notifications (including failed matching passes). |

### How a trade stays correct

A trade reads and changes two orders, and other threads may be trading or modifying the same orders. `OrderMatcher.executeTrade` therefore:

1. Locks both orders, always in the same order (by order id), so two threads can never each hold one order while waiting for the other.
2. Re-checks, under both locks, that both orders still have quantity left and still have the expected price.
3. Reduces both quantities, records the trade, and removes filled orders from the book.

Order modification (`Order.updateTerms`) takes the same lock, so a modification waits for a transaction in progress. A modification replaces the remaining quantity with the given value, so callers should treat it as a replacement of the order's terms.

Prices must be exactly equal to trade; there is no price crossing.

## Simulations

All three simulations use the same engine, `MarketSimulation`. Only the matcher and the amount of contention differ.

`MarketSimulation` creates one thread per trader (no thread pool for traders). Each trader repeats a number of rounds:

- Most rounds it publishes an order. Buyers aim at the lowest ask and sellers at the highest bid; otherwise they quote near the last traded price of that company.
- Some rounds it reprices one of its own active orders toward the market.

After the traders finish and the matching passes drain, `findViolations()` checks:

- no matching pass failed;
- every unmodified order balances: initial quantity minus traded quantity equals what is left in the book;
- no buy and sell order are left unmatched at the same price.

### `Simulation` (correct)

Uses `OrderMatcher` with two companies. Expected result: all checks pass.

### `DeadlockSimulation`

Uses `UnorderedOrderMatcher`, which locks `order` then `candidate` without a fixed order. When a buy pass and a sell pass trade the same pair at the same time, each holds one order and waits for the other. The simulation detects this with `ThreadMXBean.findDeadlockedThreads()` and prints the threads involved. It retries up to five times because the interleaving is not guaranteed.

### `RaceConditionSimulation`

Uses `UnlockedOrderMatcher`, which trades without locking the orders. Two passes can then work from the same stale quantity, which makes matching fail or leaves an order's quantity reduced with no trade recorded. Expected result: violations are reported.

### Time windows in the wrong matchers

The two wrong matchers contain short, commented pauses (`pause(...)`). They widen the window in which the bad interleaving can happen, so the bug appears reliably instead of once in a long run. Without them, the same bugs exist but are much harder to hit.

### Why the results look repetitive

Each trader's `Random` is seeded (`SEED + trader index` in `MarketSimulation`), so which company a trader picks and how large its orders are is almost identical on every run. Thread timing adds only some noise on top.

That is why the final order books look alike between runs. With the default seed (42) one company ends up with leftover buy orders and the other with a mix; with other seeds the lopsided company and side change. Try a different `SEED` to see a different market. The checks pass or fail independently of the seed.

## Project layout

```
src/main/java
  Simulation.java, DeadlockSimulation.java, RaceConditionSimulation.java
  MarketSimulation.java                      shared simulation engine
  stockexchange/
    exchange/   Exchange, OrderBook, OrderMatcher,
                UnorderedOrderMatcher, UnlockedOrderMatcher, OrderSubmission
    models/     Company, Order, OrderSide, Trade
    traders/    Trader
```
