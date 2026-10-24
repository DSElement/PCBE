package stockexchange.exchange;

import stockexchange.models.Order;

/** Deliberately incorrect: trades without locking the orders, so check and update are not atomic. */
public class UnlockedOrderMatcher extends OrderMatcher {
    private static final long UPDATE_WINDOW_MILLIS = 10;

    @Override
    void executeTrade(OrderBook orderBook, Order order, Order candidate, int expectedPrice) {
        settleTrade(orderBook, order, candidate, expectedPrice);
    }

    @Override
    void beforeReducingQuantities() {
        // Intentional time window: another thread can trade against the same orders after the
        // quantities were read, making this trade work with stale quantities.
        pause(UPDATE_WINDOW_MILLIS);
    }
}
