package stockexchange.exchange;

import stockexchange.models.Company;
import stockexchange.models.Order;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

class OrderBook {
    private final Company company;
    private final ConcurrentMap<UUID, Order> orders = new ConcurrentHashMap<>();

    OrderBook(Company company) {
        this.company = company;
    }

    Company getCompany() {
        return company;
    }

    /** Receives a fresh order constructed by Exchange for this company. */
    void addOrder(Order order) {
        if (orders.putIfAbsent(order.getId(), order) != null) {
            throw new IllegalArgumentException("An order with this ID is already registered");
        }
    }

    Order findOrder(UUID orderId) {
        return orders.get(orderId);
    }

    /** Caller holds the order lock and has verified that its remaining quantity is zero. */
    void removeFilledOrder(Order order) {
        orders.remove(order.getId(), order);
    }

    List<Order> getOrdersSnapshot() {
        return List.copyOf(orders.values());
    }
}
