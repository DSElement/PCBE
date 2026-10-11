package exchange.bot;

import exchange.config.Arguments;
import exchange.config.Settings;
import exchange.protocol.ExchangeConnection;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

public final class BotMain {
    public static void main(String[] args) {
        try {
            Arguments options = new Arguments(args, Set.of("--host", "--port", "--config"));
            if (options.help()) {
                System.out.println("java -jar stock-exchange.jar bot [--host 127.0.0.1] [--port 5000] [--config path]");
                return;
            }
            Settings settings = options.settings();
            try (ExchangeConnection connection = new ExchangeConnection(options.get("--host", "127.0.0.1"), options.port(settings.port()))) {
                TradingBot bot = new TradingBot(connection, settings);
                Thread shutdown = new Thread(connection::close, "bot-shutdown");
                try {
                    Runtime.getRuntime().addShutdownHook(shutdown);
                    bot.start();
                    BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    String command;
                    while ((command = input.readLine()) != null) {
                        switch (command.trim().toLowerCase(Locale.ROOT)) {
                            case "stop" -> bot.stop();
                            case "resume" -> bot.resume();
                            case "status" -> bot.showStatus();
                            case "leave" -> { return; }
                            case "" -> { }
                            default -> System.out.println("Use stop, resume, status, or leave.");
                        }
                    }
                } finally {
                    bot.close();
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                    catch (IllegalStateException ignored) { }
                }
            }
        } catch (Exception exception) {
            System.err.println("Bot ended: " + exception.getMessage());
            System.exit(1);
        }
    }
}
