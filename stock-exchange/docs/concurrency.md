# Concurrency in the exchange

The server is the authority for balances, reservations, orders, trades, and history. Each connected socket has a handler running in a cached `ExecutorService`. Handlers call the same `Market` object, so requests from different clients can overlap.

## Primitives and responsibilities

| Primitive | Where | Purpose |
| --- | --- | --- |
| Fair `ReentrantReadWriteLock` | Each `OrderBook` | Multiple readers can inspect a book; one writer changes orders, trades, and last price atomically. |
| Fair `ReentrantLock` | Each `Account` | Protects cash, shares, reservations, open-order count, fill count, and active state across companies. |
| Fair `ReentrantReadWriteLock` | `EventHistory` | Appends complete event batches and assigns their sequence; readers receive consistent history snapshots. |
| `ExecutorService` | `ExchangeServer` | Runs socket handlers concurrently and waits for them during shutdown. |
| `AtomicLong` | `Market` | Allocates unique account, order, trade, and acceptance IDs across handlers. |
| `ConcurrentHashMap` | Order registry and server socket set | Publishes order references safely and tracks sockets while handlers join and leave. Mutable order fields still need the book lock. |
| `BlockingQueue` | History producer/console consumer | Handlers enqueue events; the logger blocks on `take()` and prints independently. |
| `Condition` with timed waits | Bot control lock | Releases the lock during cycle delays, wakes on stop/resume/leave, and avoids busy waiting. |
| Java monitor (`synchronized`) | `ExchangeConnection.request` | Serializes a complete command and response when terminal input and the bot worker share a socket. |
| `AtomicBoolean` | Server shutdown | Ensures only one caller performs shutdown. |
| `volatile` | Bot closing flag | Makes connection termination visible to terminal input without taking the control lock. |

Fair locks reduce barging by later requests. They do not make scheduling deterministic or define FIFO trading priority; the list of orders defines trading priority.

## Lock hierarchy

Acquire server locks in this order, releasing them in reverse:

1. Company books, sorted by symbol: **AAPL → MSFT → NVDA**.
2. Participating accounts, sorted by account ID.
3. General history.

Single-company operations take only that company's write lock. A handler never acquires a book while already holding an account or history lock. The history consumer never acquires books or accounts. This prevents circular waits.

Different companies can be updated concurrently when their participating accounts do not overlap. Trading in different companies through the same account is serialized by its account lock, preventing the account from spending the same cash twice.

There is no separate global market mutex. Public market state is the combination of the company books: `snapshot()` takes all book read locks, and personal `status()` additionally takes the account lock. Immutable response strings are copied before locks are released. Network output happens afterward.

## An atomic trade

For placement or modification, `Market`:

1. Locks the company's book for writing and gathers eligible opposite orders in FIFO order.
2. Locks the initiating and candidate accounts in ID order.
3. Validates account state, ownership where applicable, order cap, and resources. Modification validation includes the old reservation before releasing it.
4. Reserves resources and adds the incoming order to the FIFO tail. A real modification removes its previous list entry before appending it again.
5. Walks the candidate list directly. For each match, transfers cash and shares, consumes both reservations, updates remaining/filled quantities and account counts, and records a trade. It stops when the incoming order is filled.
6. Appends the operation and all its fill events as one history batch, then releases locks.

The book and account locks span every fill of that operation. Cancellation or modification of those orders waits until settlement finishes. An observer sees balances and orders before or after the operation, never halfway through it.

Active orders use a plain `ArrayList`: append order is FIFO priority, and completed/cancelled entries are removed. The concurrent ID registry retains closed orders so a later modification can report `ORDER_CLOSED`. Small classroom balances use direct arithmetic, with checked multiplication for buy reservation validation; no projected balance maps or separate execution plan are needed.

## Logging and snapshots

History is recorded synchronously under its write lock before a mutation releases its book/account locks. Sequence numbers and queue insertion use that same lock, keeping console output in history order even when companies trade concurrently. The queue is unbounded, so producers do not wait for console printing.

Public snapshots lock all books for reading. Personal snapshots add the account lock. History has its own read lock and returns a copy. A mutation response and its following personal status are separate snapshots: another client can trade in between, so the protocol parser keeps result orders separate from status orders.

The event history includes thread names, letting the server console show which concurrent handler performed each operation. There are no extra tracing threads, artificial delays, or persisted log files.

## Bot threads

A bot has terminal input on the main thread and one background worker. The worker alone updates strategy counters. Every cycle requests status and observes fills, then trades if active. While paused, it still checks status at the configured interval.

The fair control lock covers the cycle. Stop acquires that lock after any in-flight action and sets `paused`; its acknowledgement means no further trading decisions will start until resume. The worker's timed `Condition.awaitNanos()` releases the lock, allowing terminal commands to change state. Its loop rechecks closing, pause state, and time remaining after wakeups, including spurious wakeups.

Manual `status` requests print the response without updating strategy counters. The connection monitor serializes socket exchanges. No code keeps the connection monitor while acquiring the control lock, avoiding an inverted dependency. Socket `close()` deliberately does not use that monitor: closing must unblock a worker waiting for network input.

## Shutdown

Leaving or losing a connection acquires all book write locks and the account lock, cancels its outstanding orders, releases reservations, and records disconnect once. A second cleanup call sees the inactive account and returns.

Server shutdown closes the listener, stops accepting handler tasks, and closes tracked sockets to unblock readers. It waits for handlers to finish their account cleanup before closing history. History then enqueues a stop marker after the final event and joins the logger, draining console output in order.

Bot leave signals closing, sends `LEAVE`, closes the socket, and joins the worker. The JVM shutdown hook closes the socket directly to unblock network I/O. Client requests use a five-second connect timeout and a thirty-second response timeout; a failed response closes the connection so incomplete framing is never reused.
