package exchange.market;

import exchange.model.Company;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** A write lock makes an order and all its fills one atomic operation. */
final class OrderBook {
    final Company company;
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    final List<Order> orders = new ArrayList<>(); // FIFO acceptance order; modifications append again.
    final List<Trade> trades = new ArrayList<>();
    Integer lastPrice;

    OrderBook(Company company) { this.company = company; }
}
