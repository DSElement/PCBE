package stockexchange.exchange;

import stockexchange.models.Order;

/** Deliberately incorrect: locks the two orders in whatever order they arrive, which can deadlock. */
public class UnorderedOrderMatcher extends OrderMatcher {
    private static final long LOCK_WINDOW_MILLIS = 25;

    @Override
    void executeTrade(OrderBook orderBook, Order order, Order candidate, int expectedPrice) {
        // Intentional time window: reading an order's price takes its lock, so the opposite
        // matching pass must finish reading this pair before either pass (meaning the other
        // run of OrderMatcher.match()) starts locking it.
        pause(LOCK_WINDOW_MILLIS);
        order.lock();
        try {
            // Intentional time window: lets the opposite pass lock the candidate first, so each
            // thread ends up holding one order while waiting for the other.
            pause(LOCK_WINDOW_MILLIS);
            candidate.lock();
            try {
                settleTrade(orderBook, order, candidate, expectedPrice);
            } finally {
                candidate.unlock();
            }
        } finally {
            order.unlock();
        }
    }
}
