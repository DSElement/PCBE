package stockexchange.models;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

public class Order {
    private final UUID id;
    private final UUID traderId;
    private final Company company;
    private final OrderSide side;
    private final ReentrantLock lock = new ReentrantLock();
    private int price;
    private int remainingQuantity;

    public Order(UUID id, UUID traderId, Company company, OrderSide side,
                 int price, int remainingQuantity) {
        if (id == null) {
            throw new IllegalArgumentException("Order ID must not be null");
        }
        if (traderId == null) {
            throw new IllegalArgumentException("Trader ID must not be null");
        }
        if (company == null) {
            throw new IllegalArgumentException("Company must not be null");
        }
        if (side == null) {
            throw new IllegalArgumentException("Order side must not be null");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        if (remainingQuantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        this.id = id;
        this.traderId = traderId;
        this.company = company;
        this.side = side;
        this.price = price;
        this.remainingQuantity = remainingQuantity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTraderId() {
        return traderId;
    }

    public Company getCompany() {
        return company;
    }

    public OrderSide getSide() {
        return side;
    }

    public int getPrice() {
        lock.lock();
        try {
            return price;
        } finally {
            lock.unlock();
        }
    }

    public int getRemainingQuantity() {
        lock.lock();
        try {
            return remainingQuantity;
        } finally {
            lock.unlock();
        }
    }

    public void updateTerms(int price, int remainingQuantity) {
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        if (remainingQuantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        lock.lock();
        try {
            if (this.remainingQuantity == 0) {
                throw new IllegalStateException("A fully filled order cannot be modified");
            }
            this.price = price;
            this.remainingQuantity = remainingQuantity;
        } finally {
            lock.unlock();
        }
    }

    public void reduceQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }

        lock.lock();
        try {
            if (quantity > remainingQuantity) {
                throw new IllegalArgumentException("Quantity must not exceed the remaining quantity");
            }
            remainingQuantity -= quantity;
        } finally {
            lock.unlock();
        }
    }

    public void lock() {
        lock.lock();
    }

    public void unlock() {
        lock.unlock();
    }
}
