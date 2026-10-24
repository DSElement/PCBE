import stockexchange.exchange.Exchange;
import stockexchange.exchange.OrderMatcher;
import stockexchange.models.Company;
import stockexchange.models.Order;
import stockexchange.models.OrderSide;
import stockexchange.models.Trade;
import stockexchange.traders.Trader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class MarketSimulation {
    private static final int MATCHING_WORKERS = 4;
    private static final int MAX_QUANTITY = 10;
    private static final int MAX_PAUSE_MILLIS = 3;
    private static final long SEED = 42;

    private final Exchange exchange;
    private final List<String> symbols;
    private final int rounds;
    private final int basePrice;
    private final int priceSpread;
    private final List<Trader> traders = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final Map<UUID, Integer> initialQuantities = new ConcurrentHashMap<>();
    private final Set<UUID> modifiedOrders = ConcurrentHashMap.newKeySet();

    public MarketSimulation(OrderMatcher matcher, List<String> symbols, int sellers, int buyers,
                            int rounds, int basePrice, int priceSpread) {
        this.exchange = new Exchange(MATCHING_WORKERS, matcher);
        this.symbols = symbols;
        this.rounds = rounds;
        this.basePrice = basePrice;
        this.priceSpread = priceSpread;

        symbols.forEach(symbol -> exchange.registerCompany(new Company(symbol)));
        for (int i = 0; i < sellers; i++) {
            addTrader(OrderSide.SELL, "seller-" + (i + 1));
        }
        for (int i = 0; i < buyers; i++) {
            addTrader(OrderSide.BUY, "buyer-" + (i + 1));
        }
    }

    /** Returns whether all traders and all matching passes finished before the timeout. */
    public boolean run(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            thread.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
        }

        boolean tradersFinished = threads.stream().noneMatch(Thread::isAlive);
        boolean matchingFinished = exchange.shutdownAndAwait(
                Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        return tradersFinished && matchingFinished;
    }

    /** Only safe once run() returned true: it reads orders, which blocks if a lock is stuck. */
    public List<String> findViolations() {
        List<String> violations = new ArrayList<>();

        for (Trader trader : traders) {
            for (String notification : trader.getNotifications()) {
                if (notification.startsWith("Matching failed")) {
                    violations.add(notification);
                }
            }
        }

        Map<UUID, Integer> tradedQuantities = new HashMap<>();
        for (Trade trade : exchange.getTradesSnapshot()) {
            tradedQuantities.merge(trade.getBuyOrderId(), trade.getQuantity(), Integer::sum);
            tradedQuantities.merge(trade.getSellOrderId(), trade.getQuantity(), Integer::sum);
        }

        Map<UUID, Order> remainingOrders = new HashMap<>();
        for (String symbol : symbols) {
            for (Order order : exchange.getOrdersSnapshot(symbol)) {
                remainingOrders.put(order.getId(), order);
            }
        }

        // A modification replaces the remaining quantity, so only unmodified orders must balance.
        initialQuantities.forEach((orderId, initialQuantity) -> {
            if (modifiedOrders.contains(orderId)) {
                return;
            }
            Order order = remainingOrders.get(orderId);
            int remaining = order == null ? 0 : order.getRemainingQuantity();
            int traded = tradedQuantities.getOrDefault(orderId, 0);
            if (initialQuantity - traded != remaining) {
                violations.add("Order " + orderId + ": initial " + initialQuantity
                        + ", traded " + traded + ", remaining " + remaining);
            }
        });

        for (String symbol : symbols) {
            violations.addAll(findUnmatchedPairs(symbol));
        }
        return violations;
    }

    public void printReport() {
        List<Trade> trades = exchange.getTradesSnapshot();
        for (String symbol : symbols) {
            System.out.println("Order book " + symbol + ":");
            List<Order> orders = exchange.getOrdersSnapshot(symbol);
            if (orders.isEmpty()) {
                System.out.println("  (empty)");
            }
            for (Order order : orders) {
                System.out.println("  " + order.getSide() + " " + order.getRemainingQuantity()
                        + " @ " + order.getPrice());
            }

            List<Trade> symbolTrades = trades.stream()
                    .filter(trade -> trade.getCompany().getStockSymbol().equals(symbol))
                    .toList();
            int quantity = symbolTrades.stream().mapToInt(Trade::getQuantity).sum();
            System.out.println("Traded " + symbol + ": " + quantity
                    + " shares in " + symbolTrades.size() + " trades");
        }
    }

    private void addTrader(OrderSide side, String name) {
        Trader trader = new Trader(exchange);
        Random random = new Random(SEED + threads.size());
        Thread thread = new Thread(() -> trade(trader, side, random), name);
        thread.setDaemon(true);
        traders.add(trader);
        threads.add(thread);
    }

    private void trade(Trader trader, OrderSide side, Random random) {
        try {
            for (int round = 0; round < rounds; round++) {
                if (random.nextInt(5) == 0) {
                    reprice(trader, random);
                } else {
                    publish(trader, side, random);
                }
                Thread.sleep(random.nextInt(MAX_PAUSE_MILLIS + 1));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void publish(Trader trader, OrderSide side, Random random) {
        String symbol = symbols.get(random.nextInt(symbols.size()));
        int price = choosePrice(trader, symbol, side, random);
        int quantity = 1 + random.nextInt(MAX_QUANTITY);
        UUID orderId = trader.publishOrder(symbol, side, price, quantity);
        if (orderId != null) {
            initialQuantities.put(orderId, quantity);
        }
    }

    private void reprice(Trader trader, Random random) {
        List<Order> activeOrders = trader.getActiveOrders();
        if (activeOrders.isEmpty()) {
            return;
        }
        Order order = activeOrders.get(random.nextInt(activeOrders.size()));
        String symbol = order.getCompany().getStockSymbol();
        int price = choosePrice(trader, symbol, order.getSide(), random);
        int quantity = order.getRemainingQuantity();
        if (quantity == 0) {
            return;
        }
        modifiedOrders.add(order.getId());
        trader.modifyOrder(symbol, order.getId(), price, quantity);
    }

    // Buyers take the lowest ask and sellers the highest bid, mostly; otherwise they quote near the last trade.
    private int choosePrice(Trader trader, String symbol, OrderSide side, Random random) {
        var counterPrices = trader.getMarket(symbol).stream()
                .filter(order -> order.getSide() != side && order.getRemainingQuantity() > 0)
                .mapToInt(Order::getPrice);
        var bestCounterPrice = side == OrderSide.BUY
                ? counterPrices.min()
                : counterPrices.max();
        if (bestCounterPrice.isPresent() && random.nextInt(3) != 0) {
            return bestCounterPrice.getAsInt();
        }

        int lastPrice = trader.getMarketHistory().stream()
                .filter(trade -> trade.getCompany().getStockSymbol().equals(symbol))
                .reduce((first, second) -> second)
                .map(Trade::getPrice)
                .orElse(basePrice);
        int offset = random.nextInt(2 * priceSpread + 1) - priceSpread;
        return Math.max(1, lastPrice + offset);
    }

    private List<String> findUnmatchedPairs(String symbol) {
        List<Order> orders = exchange.getOrdersSnapshot(symbol).stream()
                .filter(order -> order.getRemainingQuantity() > 0)
                .toList();
        Set<Integer> bidPrices = new HashSet<>();
        for (Order order : orders) {
            if (order.getSide() == OrderSide.BUY) {
                bidPrices.add(order.getPrice());
            }
        }

        List<String> violations = new ArrayList<>();
        for (Order order : orders) {
            if (order.getSide() == OrderSide.SELL && bidPrices.contains(order.getPrice())) {
                violations.add("Sell order " + order.getId() + " at price " + order.getPrice()
                        + " has an unfilled buy order at the same price");
            }
        }
        return violations;
    }
}
