# Concurrent stock exchange

Java 25 coursework demonstrating concurrency in a TCP stock exchange. One server owns the shared market; human clients and bots connect from separate terminals. Companies are **NVIDIA (NVDA), Apple (AAPL), and Microsoft (MSFT)**, with fictional integer prices.

## Build and run

Requires JDK 25 and Maven. Check `java -version` and `mvn -version`; both must use Java 25. Maven uses `JAVA_HOME` when set. Open a new terminal after changing system environment variables. In PowerShell, you can select the installed JDK for Maven with `$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'`.

From this folder:

```shell
mvn clean package
java -jar target/stock-exchange.jar server
```

Open another terminal for a human client:

```shell
java -jar target/stock-exchange.jar client
```

For simulated traders, open a few more terminals and run this command in each:

```shell
java -jar target/stock-exchange.jar bot
```

Each bot is an independent client with its own account. Server startup creates an empty market; connecting traders supply its orders. Use `server --help`, `client --help`, or `bot --help` after the JAR name for role options. Rebuild after changing source code. There are no runtime libraries or tests; Maven skips test compilation and execution.

Default endpoint is `127.0.0.1:5000`. To connect across computers:

```shell
java -jar target/stock-exchange.jar server --bind 0.0.0.0 --port 5000
java -jar target/stock-exchange.jar client --host <server-LAN-address> --port 5000
java -jar target/stock-exchange.jar bot --host <server-LAN-address> --port 5000
```

The server machine must permit connections on that port.

## Configuration

Defaults are bundled from [src/main/resources/config.properties](src/main/resources/config.properties). They work when the JAR is launched from another directory. A `config.properties` in the working directory overrides the bundled file; `--config path/to/custom.properties` selects a required external file. Network arguments override configuration. Restart a process to reload its settings; rebuild to change bundled defaults.

| Setting | Default |
| --- | --- |
| Starting cash per account | 200–1,000, inclusive |
| Starting shares per company | 0–10, inclusive |
| Permitted price per share | 20–50, inclusive |
| Maximum open orders per account | 5 across all companies and sides |
| Bot cycle delay | 1–3 seconds |
| Bot order quantity | 1–3 shares |
| Bot exact-match fallback threshold | 3 unsuccessful pricing attempts |

## Trading rules

- A connection receives a new client ID and random cash and shares. Reconnecting creates a new account.
- A buy reserves cash for its entire remaining quantity; a sell reserves shares. Available resources exclude existing reservations.
- Prices and quantities are positive integers within the configured rules. Quantities and balances use `long`; buy quantity multiplied by price is checked for overflow before changing state.
- Trades require the same company and **exactly equal prices**, excluding self-trades. A buy at 31 does not match a sell at 30. Eligible orders match in FIFO acceptance order.
- Each trade fills the smaller remaining quantity. One incoming order can fill several opposite orders atomically. Partial orders retain the unused reservation and an open-order slot.
- New orders at the account's cap are rejected, even if they could fill immediately. Modification remains available.
- Modification retains ID, company, side, and cumulative filled quantity, replacing the **remaining quantity** and price. It can reuse the old reservation. Failed validation leaves the old order intact.
- A real modification moves the order to the FIFO tail. An unchanged request preserves priority. Filled and cancelled orders cannot be changed.
- Cancellation, `LEAVE`, lost connections, and server shutdown release reservations and cancel outstanding orders.

`MARKET` shows companies, open orders, and all completed trades. A company's displayed price is its last trade price, or `NONE` before its first trade. `HISTORY` contains every accepted order, modification, cancellation, fill, trade, rejection, and connection event for the server run. History is kept in memory and printed in sequence on the server console.

## Terminal controls

The human client offers `1 Status`, `2 Market`, `3 History`, `4 Buy`, `5 Sell`, `6 Modify`, `7 Cancel`, and `0 Leave`. You can also enter protocol commands directly.

Bots accept `stop`, `resume`, `status`, and `leave`. Stop waits for any current action to finish. Existing orders stay active while paused, and the worker continues checking status for fills. Resume wakes the worker immediately. Leave cancels its remaining orders through the server.

Each active bot cycle chooses placement/modification/cancellation with probabilities **60%/25%/15%**, trying feasible alternatives when its first choice is unavailable. Prices favor cautious offsets (60%), exact quotes (30%), and aggressive offsets (10%); matching still requires equality. With no opposite quote, the bot chooses a random permitted price. Three unsuccessful pricing attempts switch it to exact matching, including modifying existing orders to reuse reservations. It may cancel to free resources when no exact match is affordable. Any observed fill resets the attempt count.

## Source layout

```text
src/main/java/exchange/
  Main.java       executable JAR role dispatcher
  server/         TCP listener, handlers, shutdown
  market/         accounts, orders, FIFO books, settlement, history
  client/         human terminal menu and formatting
  bot/            trading decisions and pause/resume worker
  protocol/       socket framing, connection, immutable snapshots
  model/          Company, Side, MarketException
  config/         properties and command-line options
src/main/resources/config.properties
docs/concurrency.md
pom.xml
```

The lock hierarchy and other primitives are explained in [docs/concurrency.md](docs/concurrency.md).

## Wire protocol

UTF-8 TCP: one command per line, followed by an `OK <operation>` or `ERROR <code> <message>` header, data rows, and `END`. Command length is limited to 4,096 characters after reading the line. Connection immediately sends `OK CONNECT` and the account status.

```text
STATUS
MARKET
HISTORY
BUY NVDA 2 30
SELL AAPL 1 25
MODIFY 7 3 31
CANCEL 7
LEAVE
```

Data rows:

```text
CLIENT <id> <ACTIVE|CLOSED>
CASH <total> <reserved> <available>
RULES <min-price> <max-price> <max-open-orders>
FILLS <account-trade-count>
OPEN_ORDERS <count>
SHARES <symbol> <total> <reserved> <available>
COMPANY <symbol> <name> <last-price|NONE>
ORDER <id> <owner> <symbol> <side> <price> <remaining> <executed> <priority> <state>
TRADE <id> <symbol> <price> <quantity> <buyer> <seller> <buy-order> <sell-order> <UTC-time>
EVENT <sequence> <UTC-time> <type> <client-id> <details>
NOTICE <message>
```

Order states are `OPEN`, `PARTIAL`, `FILLED`, and `CANCELLED`. Placement, modification, and cancellation return their result rows before a fresh account snapshot. `ORDER` rows preceding `CLIENT` describe the command result; rows after it describe the later account status. Other clients may trade between those snapshots.
