package stockexchange.exchange;

import stockexchange.models.Company;
import stockexchange.models.Order;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class OrderBook {
    private final Company company;
    private final ConcurrentMap<UUID, Order> orders = new ConcurrentHashMap<>();

    public OrderBook(Company company) {
        if (company == null) {
            throw new IllegalArgumentException("Company must not be null");
        }
        this.company = company;
    }

    public Company getCompany() {
        return company;
    }

    public void addOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order must not be null");
        }
        if (!company.getStockSymbol().equals(order.getCompany().getStockSymbol())) {
            throw new IllegalArgumentException("Order must belong to this order book's company");
        }
        if (orders.putIfAbsent(order.getId(), order) != null) {
            throw new IllegalArgumentException("An order with this ID is already registered");
        }
    }

    public Order findOrder(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID must not be null");
        }
        return orders.get(orderId);
    }

    public List<Order> getOrdersSnapshot() {
        return List.copyOf(orders.values());
    }
}
