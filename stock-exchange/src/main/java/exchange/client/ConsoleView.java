package exchange.client;

import exchange.model.Company;
import exchange.protocol.Views;
import exchange.protocol.Wire;

import java.util.List;

final class ConsoleView {
    static void print(Wire.Response response) {
        System.out.println();
        System.out.println(response.header());
        boolean hasStatus = response.rows().stream().anyMatch(row -> row.startsWith("CLIENT "));
        if (hasStatus) {
            Views.Status status = Views.Status.from(response);
            System.out.println("Client " + status.client() + " (" + status.state() + ")");
            System.out.printf("Cash: total=%d  reserved=%d  available=%d%n", status.cash(), status.reservedCash(), status.availableCash());
            System.out.printf("Open orders: %d/%d   Executed trades: %d   Price range: %d-%d%n",
                    status.openCount(), status.rules().maxOrders(), status.fills(), status.rules().minPrice(), status.rules().maxPrice());
            System.out.printf("%-12s %12s %12s %12s%n", "Shares", "Total", "Reserved", "Available");
            for (Company company : Company.LOCK_ORDER) {
                Views.Holding holding = status.holdings().get(company);
                System.out.printf("%-12s %12d %12d %12d%n", company, holding.total(), holding.reserved(), holding.available());
            }
            printOrders(status.orders());
        } else if (response.header().equals("OK MARKET")) {
            System.out.println("Companies (prices are fictional integer values):");
            for (String row : response.rows()) {
                if (row.startsWith("COMPANY ")) {
                    String[] fields = row.split(" ");
                    System.out.println("  " + fields[2] + " (" + fields[1] + "): "
                            + (fields[3].equals("NONE") ? "no trades yet" : "last traded price " + fields[3]));
                }
            }
            printOrders(Views.marketOrders(response));
        }

        boolean tradeHeading = false;
        boolean beforeStatus = true;
        for (String row : response.rows()) {
            if (row.startsWith("CLIENT ")) { beforeStatus = false; }
            if (row.startsWith("ORDER ") && hasStatus && beforeStatus) {
                Views.Order order = Views.Order.parse(row.split(" "));
                System.out.printf("Order result: #%d %s %s price=%d remaining=%d filled=%d state=%s%n",
                        order.id(), order.side(), order.company(), order.price(), order.remaining(), order.executed(), order.state());
            } else if (row.startsWith("TRADE ")) {
                if (!tradeHeading) {
                    System.out.println("Transactions:");
                    System.out.printf("%-8s %-6s %7s %8s %8s %8s %10s %10s %s%n",
                            "ID", "Stock", "Price", "Quantity", "Buyer", "Seller", "Buy order", "Sell order", "Time (UTC)");
                    tradeHeading = true;
                }
                String[] fields = row.split(" ");
                System.out.printf("%-8s %-6s %7s %8s %8s %8s %10s %10s %s%n",
                        fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7], fields[8], fields[9]);
            } else if (row.startsWith("EVENT ")) {
                System.out.println(row);
            } else if (row.startsWith("NOTICE ")) {
                System.out.println(row.substring(7));
            }
        }
        if (response.header().equals("OK HISTORY") && response.rows().isEmpty()) {
            System.out.println("No events yet.");
        }
        if (response.header().equals("OK MARKET") && !tradeHeading) { System.out.println("No completed transactions yet."); }
    }

    private static void printOrders(List<Views.Order> orders) {
        System.out.println("Open orders:");
        if (orders.isEmpty()) { System.out.println("  None."); return; }
        System.out.printf("%-8s %-8s %-6s %-5s %7s %10s %10s %10s %s%n",
                "ID", "Client", "Stock", "Side", "Price", "Remaining", "Filled", "Priority", "State");
        orders.forEach(order -> System.out.printf("%-8d %-8d %-6s %-5s %7d %10d %10d %10d %s%n",
                order.id(), order.client(), order.company(), order.side(), order.price(), order.remaining(),
                order.executed(), order.priority(), order.state()));
    }
}
