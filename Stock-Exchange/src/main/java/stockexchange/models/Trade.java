package stockexchange.models;

import java.time.Instant;
import java.util.UUID;

public final class Trade {
    private final UUID id;
    private final UUID buyOrderId;
    private final UUID sellOrderId;
    private final Company company;
    private final int price;
    private final int quantity;
    private final Instant executedAt;

    public Trade(UUID id, UUID buyOrderId, UUID sellOrderId, Company company,
                 int price, int quantity, Instant executedAt) {
        if (id == null) {
            throw new IllegalArgumentException("Trade ID must not be null");
        }
        if (buyOrderId == null) {
            throw new IllegalArgumentException("Buy order ID must not be null");
        }
        if (sellOrderId == null) {
            throw new IllegalArgumentException("Sell order ID must not be null");
        }
        if (buyOrderId.equals(sellOrderId)) {
            throw new IllegalArgumentException("Buy and sell order IDs must be different");
        }
        if (company == null) {
            throw new IllegalArgumentException("Company must not be null");
        }
        if (price <= 0) {
            throw new IllegalArgumentException("Price must be positive");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (executedAt == null) {
            throw new IllegalArgumentException("Execution time must not be null");
        }

        this.id = id;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.company = company;
        this.price = price;
        this.quantity = quantity;
        this.executedAt = executedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBuyOrderId() {
        return buyOrderId;
    }

    public UUID getSellOrderId() {
        return sellOrderId;
    }

    public Company getCompany() {
        return company;
    }

    public int getPrice() {
        return price;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }
}
