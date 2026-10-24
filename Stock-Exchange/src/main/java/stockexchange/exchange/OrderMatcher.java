package stockexchange.exchange;

import stockexchange.models.Order;
import stockexchange.models.OrderSide;
import stockexchange.models.Trade;

import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class OrderMatcher {
    private final Queue<Trade> trades = new ConcurrentLinkedQueue<>();

    void match(OrderBook orderBook, Order order) {
        if (orderBook.findOrder(order.getId()) != order) {
            return;
        }

        for (Order candidate : orderBook.getOrdersSnapshot()) {
            if (order.getRemainingQuantity() == 0) {
                return;
            }
            if (candidate.getSide() == order.getSide()) {
                continue;
            }

            int expectedPrice = order.getPrice();
            if (candidate.getPrice() == expectedPrice) {
                executeTrade(orderBook, order, candidate, expectedPrice);
            }
        }
    }

    List<Trade> getTradesSnapshot() {
        return List.copyOf(trades);
    }

    void executeTrade(OrderBook orderBook, Order order, Order candidate, int expectedPrice) {
        // Lock ordering by id is only safe if ids are unique; OrderBook.addOrder enforces this.
        if (order.getId().equals(candidate.getId())) {
            throw new IllegalStateException("Two different orders share the id " + order.getId());
        }

        Order first = order.getId().compareTo(candidate.getId()) < 0 ? order : candidate;
        Order second = first == order ? candidate : order;

        first.lock();
        try {
            second.lock();
            try {
                settleTrade(orderBook, order, candidate, expectedPrice);
            } finally {
                second.unlock();
            }
        } finally {
            first.unlock();
        }
    }

    void settleTrade(OrderBook orderBook, Order order, Order candidate, int expectedPrice) {
        if (order.getRemainingQuantity() == 0
                || candidate.getRemainingQuantity() == 0
                || order.getPrice() != expectedPrice
                || candidate.getPrice() != expectedPrice) {
            return;
        }

        Order buy = order.getSide() == OrderSide.BUY ? order : candidate;
        Order sell = order.getSide() == OrderSide.SELL ? order : candidate;
        int quantity = Math.min(buy.getRemainingQuantity(), sell.getRemainingQuantity());
        Trade trade = new Trade(UUID.randomUUID(), buy.getId(), sell.getId(),
                orderBook.getCompany(), expectedPrice, quantity, Instant.now());

        beforeReducingQuantities();
        buy.reduceQuantity(quantity);
        sell.reduceQuantity(quantity);
        trades.add(trade);

        if (buy.getRemainingQuantity() == 0) {
            orderBook.removeFilledOrder(buy);
        }
        if (sell.getRemainingQuantity() == 0) {
            orderBook.removeFilledOrder(sell);
        }
    }

    /** Hook between reading the quantities and applying the trade; the correct matcher does nothing. */
    void beforeReducingQuantities() {
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
