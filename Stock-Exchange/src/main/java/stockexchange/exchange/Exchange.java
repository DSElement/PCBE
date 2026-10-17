package stockexchange.exchange;

import stockexchange.models.Company;
import stockexchange.models.Order;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Exchange {
    private final ConcurrentMap<String, OrderBook> orderBooks = new ConcurrentHashMap<>();

    public void registerCompany(Company company) {
        if (company == null) {
            throw new IllegalArgumentException("Company must not be null");
        }
        if (orderBooks.putIfAbsent(company.getStockSymbol(), new OrderBook(company)) != null) {
            throw new IllegalArgumentException("A company with this stock symbol is already registered");
        }
    }

    public void submitOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order must not be null");
        }
        getRegisteredOrderBook(order.getCompany().getStockSymbol()).addOrder(order);
    }

    public List<Company> getCompaniesSnapshot() {
        return orderBooks.values().stream()
                .map(OrderBook::getCompany)
                .toList();
    }

    public List<Order> getOrdersSnapshot(String stockSymbol) {
        return getRegisteredOrderBook(stockSymbol).getOrdersSnapshot();
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
