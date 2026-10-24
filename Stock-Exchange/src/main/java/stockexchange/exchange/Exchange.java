package stockexchange.exchange;

import stockexchange.models.Company;
import stockexchange.models.Order;
import stockexchange.models.OrderSide;
import stockexchange.models.Trade;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Exchange {
    private final ConcurrentMap<String, OrderBook> orderBooks = new ConcurrentHashMap<>();
    private final OrderMatcher orderMatcher;
    private final ExecutorService matchingWorkers;

    public Exchange() {
        this(Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    public Exchange(int workerCount) {
        this(workerCount, new OrderMatcher());
    }

    public Exchange(int workerCount, OrderMatcher orderMatcher) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("Worker count must be positive");
        }
        if (orderMatcher == null) {
            throw new IllegalArgumentException("Order matcher must not be null");
        }
        this.orderMatcher = orderMatcher;
        matchingWorkers = Executors.newFixedThreadPool(workerCount);
    }

    public void registerCompany(Company company) {
        if (company == null) {
            throw new IllegalArgumentException("Company must not be null");
        }
        if (orderBooks.putIfAbsent(company.getStockSymbol(), new OrderBook(company)) != null) {
            throw new IllegalArgumentException("A company with this stock symbol is already registered");
        }
    }

    /** Returns the generated order ID and a handle to its asynchronous matching pass. */
    public OrderSubmission submitOrder(UUID traderId, String stockSymbol, OrderSide side,
                                       int price, int quantity) {
        OrderBook orderBook = getRegisteredOrderBook(stockSymbol);
        Order order = new Order(traderId, orderBook.getCompany(), side, price, quantity);
        orderBook.addOrder(order);
        CompletableFuture<Void> matchingTask = CompletableFuture.runAsync(
                () -> orderMatcher.match(orderBook, order), matchingWorkers);
        return new OrderSubmission(order.getId(), matchingTask);
    }

    /** Updates the remaining quantity and price, then schedules another matching pass. */
    public CompletableFuture<Void> modifyOrder(UUID traderId, String stockSymbol, UUID orderId,
                                               int price, int remainingQuantity) {
        if (traderId == null) {
            throw new IllegalArgumentException("Trader ID must not be null");
        }
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID must not be null");
        }

        OrderBook orderBook = getRegisteredOrderBook(stockSymbol);
        Order order = orderBook.findOrder(orderId);
        if (order == null) {
            throw new IllegalStateException("Order is not active or does not exist");
        }
        if (!order.getTraderId().equals(traderId)) {
            throw new IllegalArgumentException("Only the order's owner can modify it");
        }

        order.updateTerms(price, remainingQuantity);

        return CompletableFuture.runAsync(() -> orderMatcher.match(orderBook, order), matchingWorkers);
    }

    /** Stops accepting matching work and waits for the queued matching passes to finish. */
    public boolean shutdownAndAwait(long timeout, TimeUnit unit) throws InterruptedException {
        matchingWorkers.shutdown();
        return matchingWorkers.awaitTermination(timeout, unit);
    }

    public List<Company> getCompaniesSnapshot() {
        return orderBooks.values().stream()
                .map(OrderBook::getCompany)
                .toList();
    }

    public List<Order> getOrdersSnapshot(String stockSymbol) {
        return getRegisteredOrderBook(stockSymbol).getOrdersSnapshot();
    }

    public List<Trade> getTradesSnapshot() {
        return orderMatcher.getTradesSnapshot();
    }

    private OrderBook getRegisteredOrderBook(String stockSymbol) {
        if (stockSymbol == null || stockSymbol.isBlank()) {
            throw new IllegalArgumentException("Stock symbol must not be null or blank");
        }
        OrderBook orderBook = orderBooks.get(stockSymbol);
        if (orderBook == null) {
            throw new IllegalArgumentException("Company is not registered: " + stockSymbol);
        }
        return orderBook;
    }
}
