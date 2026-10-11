package exchange.client;

import exchange.config.Arguments;
import exchange.config.Settings;
import exchange.model.Company;
import exchange.protocol.ExchangeConnection;
import exchange.protocol.Wire;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class ClientMain {
    public static void main(String[] args) {
        try {
            Arguments options = new Arguments(args, Set.of("--host", "--port", "--config"));
            if (options.help()) {
                System.out.println("java -jar stock-exchange.jar client [--host 127.0.0.1] [--port 5000] [--config path]");
                return;
            }
            Settings settings = options.settings();
            try (ExchangeConnection connection = new ExchangeConnection(options.get("--host", "127.0.0.1"), options.port(settings.port()))) {
                Thread shutdown = new Thread(connection::close, "client-shutdown");
                Runtime.getRuntime().addShutdownHook(shutdown);
                try {
                    interact(connection);
                } finally {
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                    catch (IllegalStateException ignored) { }
                }
            }
        } catch (Exception exception) {
            System.err.println("Client ended: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static void interact(ExchangeConnection connection) throws IOException {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        ConsoleView.print(connection.welcome());
        while (true) {
            System.out.println("\n1 Status | 2 Market | 3 Full history | 4 Buy | 5 Sell | 6 Modify | 7 Cancel | 0 Leave");
            System.out.println("You can also enter a protocol command directly.");
            String choice = prompt(input, "> ");
            String command;
            if (choice == null) {
                command = "LEAVE";
            } else {
                command = switch (choice.trim()) {
                    case "0" -> "LEAVE";
                    case "1" -> "STATUS";
                    case "2" -> "MARKET";
                    case "3" -> "HISTORY";
                    case "4", "5" -> placement(input, choice.trim().equals("4") ? "BUY" : "SELL");
                    case "6" -> modification(input);
                    case "7" -> {
                        String id = prompt(input, "Order ID: ");
                        yield id == null ? "LEAVE" : "CANCEL " + id.trim();
                    }
                    default -> choice.trim();
                };
            }
            Wire.Response response = connection.request(command);
            ConsoleView.print(response);
            if (response.header().equals("OK LEAVE")) { return; }
        }
    }

    private static String placement(BufferedReader input, String side) throws IOException {
        String company = prompt(input, "Company: NVDA (NVIDIA), AAPL (Apple), MSFT (Microsoft): ");
        if (company == null) { return "LEAVE"; }
        String quantity = prompt(input, "Quantity (whole shares): ");
        if (quantity == null) { return "LEAVE"; }
        String price = prompt(input, "Price per share (integer): ");
        return price == null ? "LEAVE" : side + " " + company.trim() + " " + quantity.trim() + " " + price.trim();
    }

    private static String modification(BufferedReader input) throws IOException {
        String id = prompt(input, "Order ID: ");
        if (id == null) { return "LEAVE"; }
        String quantity = prompt(input, "New remaining quantity (whole shares): ");
        if (quantity == null) { return "LEAVE"; }
        String price = prompt(input, "New price per share (integer): ");
        return price == null ? "LEAVE" : "MODIFY " + id.trim() + " " + quantity.trim() + " " + price.trim();
    }

    private static String prompt(BufferedReader input, String text) throws IOException {
        System.out.print(text);
        System.out.flush();
        return input.readLine();
    }
}
