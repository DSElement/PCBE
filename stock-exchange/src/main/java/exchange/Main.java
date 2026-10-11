package exchange;

import exchange.bot.BotMain;
import exchange.client.ClientMain;
import exchange.server.ServerMain;
import java.util.Arrays;

/** One executable JAR for the three terminal roles. */
public final class Main {
    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
            System.out.println("Usage: java -jar stock-exchange.jar <server|client|bot> [options]");
            System.out.println("Use <role> --help for its options.");
            return;
        }
        String[] options = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "server" -> ServerMain.main(options);
            case "client" -> ClientMain.main(options);
            case "bot" -> BotMain.main(options);
            default -> {
                System.err.println("Unknown role: " + args[0] + ". Use server, client, or bot.");
                System.exit(1);
            }
        }
    }
}
