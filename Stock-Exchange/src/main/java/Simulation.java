import stockexchange.exchange.Exchange;
import stockexchange.exchange.OrderSubmission;
import stockexchange.models.Company;
import stockexchange.models.Order;
import stockexchange.models.OrderSide;
import stockexchange.models.Trade;
import stockexchange.traders.Trader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class Simulation {
    private record OrderRequest(
            Trader trader,
            String symbol,
            OrderSide side,
            int price,
            int quantity) {
    }

    public static void main(String[] args) throws Exception {
        Exchange exchange = new Exchange(4);
        exchange.registerCompany(new Company("TSLA"));
        exchange.registerCompany(new Company("AAPL"));

        Trader alice = new Trader(exchange);
        Trader bob = new Trader(exchange);
        Trader carol = new Trader(exchange);
        Trader dan = new Trader(exchange);
        Trader eve = new Trader(exchange);
        Trader frank = new Trader(exchange);

        printHeader();

        System.out.println("\n[1] Se adauga oferta initiala pentru TSLA");
        submitAndWait(exchange, alice, "TSLA", OrderSide.SELL, 100, 8);
        submitAndWait(exchange, bob, "TSLA", OrderSide.SELL, 100, 5);
        printMarket(exchange, "TSLA");

        System.out.println("\n[2] Mai multi cumparatori trimit ordine TSLA simultan");
        List<OrderSubmission> tslaBuys = submitConcurrently(exchange, List.of(
                new OrderRequest(carol, "TSLA", OrderSide.BUY, 100, 10),
                new OrderRequest(dan, "TSLA", OrderSide.BUY, 100, 4),
                new OrderRequest(eve, "TSLA", OrderSide.BUY, 99, 2)));
        waitForAll(tslaBuys);
        printMarket(exchange, "TSLA");

        System.out.println("\n[3] Se tranzactioneaza AAPL");
        submitAndWait(exchange, eve, "AAPL", OrderSide.SELL, 200, 12);
        submitAndWait(exchange, frank, "AAPL", OrderSide.BUY, 200, 5);

        System.out.println("\n[4] Un ordin AAPL este modificat");
        OrderSubmission unmatchedBuy = exchange.submitOrder(
                frank.getId(), "AAPL", OrderSide.BUY, 205, 4);
        unmatchedBuy.matchingTask().join();
        System.out.println("Ordin BUY 4 AAPL @ 205 nu are contraparte.");

        Order orderToModify = exchange.getOrdersSnapshot("AAPL").stream()
                .filter(order -> order.getId().equals(unmatchedBuy.orderId()))
                .findFirst()
                .orElseThrow();

        exchange.modifyOrder(
                frank.getId(), "AAPL", orderToModify.getId(), 200, 4).join();
        printMarket(exchange, "AAPL");

        System.out.println("\n[5] Raport final");
        printTrades(exchange);

        int tslaQuantity = tradedQuantity(exchange, "TSLA");
        int aaplQuantity = tradedQuantity(exchange, "AAPL");
        System.out.println("Total TSLA tranzactionat: " + tslaQuantity);
        System.out.println("Total AAPL tranzactionat: " + aaplQuantity);

        if (tslaQuantity != 13) {
            throw new AssertionError(
                    "TSLA: trebuiau tranzactionate exact 13 actiuni, dar au fost "
                            + tslaQuantity);
        }
        if (aaplQuantity != 9) {
            throw new AssertionError(
                    "AAPL: trebuiau tranzactionate exact 9 actiuni, dar au fost "
                            + aaplQuantity);
        }

        System.out.println("\nVERIFICARE REUSITA");
        System.out.println("- TSLA: oferta 13, tranzactionat 13");
        System.out.println("- AAPL: oferta 12, tranzactionat 9, ramase 3");
        System.out.println("- Ordinele la preturi diferite au ramas neexecutate");
        System.out.println("- Ordinele concurente nu au consumat aceeasi actiune de doua ori");
        System.exit(0);
    }

    private static OrderSubmission submitAndWait(
            Exchange exchange,
            Trader trader,
            String symbol,
            OrderSide side,
            int price,
            int quantity) {
        OrderSubmission submission = exchange.submitOrder(
                trader.getId(), symbol, side, price, quantity);
        submission.matchingTask().join();
        return submission;
    }

    private static List<OrderSubmission> submitConcurrently(
            Exchange exchange,
            List<OrderRequest> requests) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        List<OrderSubmission> submissions = Collections.synchronizedList(new ArrayList<>());
        List<Thread> submitters = new ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            OrderRequest request = requests.get(i);
            Thread submitter = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    submissions.add(exchange.submitOrder(
                            request.trader().getId(),
                            request.symbol(),
                            request.side(),
                            request.price(),
                            request.quantity()));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }, "submitter-" + (i + 1));
            submitters.add(submitter);
            submitter.start();
        }

        ready.await();
        start.countDown();
        for (Thread submitter : submitters) {
            submitter.join();
        }
        return submissions;
    }

    private static void waitForAll(List<OrderSubmission> submissions) {
        for (OrderSubmission submission : submissions) {
            submission.matchingTask().join();
        }
    }

    private static int tradedQuantity(Exchange exchange, String symbol) {
        return exchange.getTradesSnapshot().stream()
                .filter(trade -> trade.getCompany().getStockSymbol().equals(symbol))
                .mapToInt(Trade::getQuantity)
                .sum();
    }

    private static void printHeader() {
        System.out.println("==============================================");
        System.out.println("       MINI STOCK EXCHANGE SIMULATION");
        System.out.println("==============================================");
        System.out.println("Worker threads: 4");
        System.out.println("Companii: TSLA, AAPL");
    }

    private static void printMarket(Exchange exchange, String symbol) {
        System.out.println("Order book " + symbol + ":");
        List<Order> orders = exchange.getOrdersSnapshot(symbol);
        if (orders.isEmpty()) {
            System.out.println("  (gol)");
            return;
        }
        for (Order order : orders) {
            System.out.println("  " + order.getSide()
                    + " " + order.getRemainingQuantity()
                    + " @ " + order.getPrice());
        }
    }

    private static void printTrades(Exchange exchange) {
        for (Trade trade : exchange.getTradesSnapshot()) {
            System.out.println("  " + trade.getCompany().getStockSymbol()
                    + ": " + trade.getQuantity()
                    + " actiuni @ " + trade.getPrice()
                    + " [" + trade.getExecutedAt() + "]");
        }
    }
}
