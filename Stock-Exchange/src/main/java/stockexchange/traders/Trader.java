package stockexchange.traders;

import stockexchange.exchange.Exchange;
import stockexchange.exchange.OrderSubmission;
import stockexchange.models.Company;
import stockexchange.models.Order;
import stockexchange.models.OrderSide;
import stockexchange.models.Trade;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Trader {
    private final UUID id;
    private final Exchange exchange;
    private final Queue<String> notifications = new ConcurrentLinkedQueue<>();

    public Trader(Exchange exchange) {
        if (exchange == null) {
            throw new IllegalArgumentException("Exchange must not be null");
        }
        this.id = UUID.randomUUID();
        this.exchange = exchange;
    }

    public UUID getId() {
        return id;
    }

    /** Returns the accepted order ID, or null if the request was rejected. */
    public UUID publishOrder(String stockSymbol, OrderSide side, int price, int quantity) {
        OrderSubmission submission;
        try {
            submission = exchange.submitOrder(id, stockSymbol, side, price, quantity);
        } catch (IllegalArgumentException e) {
            notifications.add("Order rejected: " + e.getMessage());
            return null;
        }

        notifications.add("Order accepted: " + submission.orderId() + " for " + stockSymbol);
        observeMatching(submission.orderId(), submission.matchingTask());
        return submission.orderId();
    }

    /** Returns whether the modification was accepted; matching continues asynchronously. */
    public boolean modifyOrder(String stockSymbol, UUID orderId, int price, int remainingQuantity) {
        CompletableFuture<Void> matchingTask;
        try {
            matchingTask = exchange.modifyOrder(id, stockSymbol, orderId, price, remainingQuantity);
        } catch (IllegalArgumentException | IllegalStateException e) {
            notifications.add("Modification rejected for order " + orderId + ": " + e.getMessage());
            return false;
        }

        notifications.add("Order updated: " + orderId);
        observeMatching(orderId, matchingTask);
        return true;
    }

    public List<Company> getCompanies() {
        return exchange.getCompaniesSnapshot();
    }

    public List<Order> getMarket() {
        return exchange.getCompaniesSnapshot().stream()
                .flatMap(company -> exchange.getOrdersSnapshot(company.getStockSymbol()).stream())
                .toList();
    }

    public List<Order> getMarket(String stockSymbol) {
        return exchange.getOrdersSnapshot(stockSymbol);
    }

    public List<Trade> getMarketHistory() {
        return exchange.getTradesSnapshot();
    }

    public List<Order> getActiveOrders() {
        return getMarket().stream()
                .filter(order -> id.equals(order.getTraderId()))
                .filter(order -> order.getRemainingQuantity() > 0)
                .toList();
    }

    public List<String> getNotifications() {
        return List.copyOf(notifications);
    }

    /** Removes the oldest notification, or returns null if none are pending. */
    public String pollNotification() {
        return notifications.poll();
    }

    private void observeMatching(UUID orderId, CompletableFuture<Void> matchingTask) {
        matchingTask.whenComplete((unused, failure) -> {
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                notifications.add("Matching failed for order " + orderId + ": " + cause.getMessage());
            }
        });
    }
}
