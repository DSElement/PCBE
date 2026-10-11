package exchange.market;

import exchange.model.Company;
import exchange.model.Side;

/** Mutable order fields are protected by the company book lock. */
final class Order {
    final long id;
    final Account owner;
    final Company company;
    final Side side;
    int price;
    long remaining;
    long executed;
    long priority;
    boolean cancelled;

    Order(long id, Account owner, Company company, Side side, int price, long quantity, long priority) {
        this.id = id;
        this.owner = owner;
        this.company = company;
        this.side = side;
        this.price = price;
        remaining = quantity;
        this.priority = priority;
    }

    boolean open() { return !cancelled && remaining > 0; }
    String state() { return cancelled ? "CANCELLED" : remaining == 0 ? "FILLED" : executed > 0 ? "PARTIAL" : "OPEN"; }
    String wire() {
        return "ORDER " + id + " " + owner.id + " " + company + " " + side + " "
                + price + " " + remaining + " " + executed + " " + priority + " " + state();
    }
}
