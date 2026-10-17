package stockexchange.exchange;

import stockexchange.models.Company;
import stockexchange.models.Order;
import stockexchange.models.Trade;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Exchange {
    private final ConcurrentMap<String, OrderBook> orderBooks = new ConcurrentHashMap<>();
    private final OrderMatcher orderMatcher = new OrderMatcher();
    private final ExecutorService matchingWorkers;

    public Exchange() {
        this(Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    public Exchange(int workerCount) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("Worker count must be positive");
        }
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

    public Future<?> submitOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order must not be null");
        }
        OrderBook orderBook = getRegisteredOrderBook(order.getCompany().getStockSymbol());
        orderBook.addOrder(order);
        return matchingWorkers.submit(() -> orderMatcher.match(orderBook, order));
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
