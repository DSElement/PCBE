package stockexchange.exchange;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public record OrderSubmission(UUID orderId, CompletableFuture<Void> matchingTask) {
    public OrderSubmission {
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID must not be null");
        }
        if (matchingTask == null) {
            throw new IllegalArgumentException("Matching task must not be null");
        }
    }
}
