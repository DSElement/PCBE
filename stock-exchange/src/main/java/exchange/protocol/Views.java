package exchange.protocol;

import exchange.model.Company;
import exchange.model.Side;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Immutable wire snapshots: bot decisions never depend on server-owned mutable objects. */
public final class Views {
    public record Holding(long total, long reserved, long available) { }
    public record Rules(int minPrice, int maxPrice, int maxOrders) { }
    public record Order(long id, long client, Company company, Side side, int price,
                 long remaining, long executed, long priority, String state) {
        public boolean open() { return state.equals("OPEN") || state.equals("PARTIAL"); }
        public static Order parse(String[] fields) {
            return new Order(Long.parseLong(fields[1]), Long.parseLong(fields[2]),
                    Company.parse(fields[3]), Side.valueOf(fields[4]), Integer.parseInt(fields[5]),
                    Long.parseLong(fields[6]), Long.parseLong(fields[7]), Long.parseLong(fields[8]), fields[9]);
        }
    }
    public record Status(long client, String state, long cash, long reservedCash, long availableCash,
                  long fills, int openCount, Rules rules, Map<Company, Holding> holdings, List<Order> orders) {
        public Status {
            holdings = Map.copyOf(holdings);
            orders = List.copyOf(orders);
        }

        public static Status from(Wire.Response response) {
            long client = 0, cash = 0, reserved = 0, available = 0, fills = 0;
            int count = 0;
            String state = "UNKNOWN";
            Rules rules = null;
            Map<Company, Holding> holdings = new EnumMap<>(Company.class);
            List<Order> orders = new ArrayList<>();
            // Only ORDER rows after CLIENT belong to the account snapshot.
            for (String row : response.rows()) {
                String[] fields = row.split(" ");
                switch (fields[0]) {
                    case "CLIENT" -> {
                        client = Long.parseLong(fields[1]); state = fields[2];
                        orders.clear();
                    }
                    case "CASH" -> { cash = Long.parseLong(fields[1]); reserved = Long.parseLong(fields[2]); available = Long.parseLong(fields[3]); }
                    case "FILLS" -> fills = Long.parseLong(fields[1]);
                    case "RULES" -> rules = new Rules(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), Integer.parseInt(fields[3]));
                    case "OPEN_ORDERS" -> count = Integer.parseInt(fields[1]);
                    case "SHARES" -> holdings.put(Company.parse(fields[1]), new Holding(Long.parseLong(fields[2]), Long.parseLong(fields[3]), Long.parseLong(fields[4])));
                    case "ORDER" -> { if (client != 0) { orders.add(Order.parse(fields)); } }
                    default -> { }
                }
            }
            if (client == 0 || rules == null || holdings.size() != Company.values().length) {
                throw new IllegalArgumentException("Incomplete account response.");
            }
            long owner = client;
            return new Status(client, state, cash, reserved, available, fills, count, rules,
                    holdings, orders.stream().filter(order -> order.open() && order.client() == owner).toList());
        }
    }

    public static List<Order> marketOrders(Wire.Response response) {
        List<Order> orders = new ArrayList<>();
        for (String row : response.rows()) {
            if (row.startsWith("ORDER ")) { orders.add(Order.parse(row.split(" "))); }
        }
        return List.copyOf(orders);
    }

    public static String summary(Status status) {
        StringBuilder text = new StringBuilder("client=" + status.client() + " cash=" + status.cash()
                + " available=" + status.availableCash() + " reserved=" + status.reservedCash()
                + " orders=" + status.openCount() + "/" + status.rules().maxOrders() + " fills=" + status.fills());
        for (Company company : Company.LOCK_ORDER) {
            Holding holding = status.holdings().get(company);
            text.append(" ").append(company).append("=").append(holding.total())
                    .append("(available:").append(holding.available()).append(")");
        }
        return text.toString();
    }
}
