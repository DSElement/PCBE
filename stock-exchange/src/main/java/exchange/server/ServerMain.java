package exchange.server;

import exchange.config.Arguments;
import exchange.config.Settings;

import java.util.Set;

public final class ServerMain {
    public static void main(String[] args) {
        try {
            Arguments options = new Arguments(args, Set.of("--bind", "--port", "--config"));
            if (options.help()) {
                System.out.println("java -jar stock-exchange.jar server [--bind 127.0.0.1] [--port 5000] [--config path]");
                return;
            }
            Settings settings = options.settings();
            ExchangeServer server = new ExchangeServer(settings,
                    options.get("--bind", settings.bind()), options.port(settings.port()));
            Thread shutdown = new Thread(server::close, "server-shutdown");
            try {
                Runtime.getRuntime().addShutdownHook(shutdown);
                server.serve();
            } finally {
                server.close();
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException ignored) { /* JVM shutdown is already underway. */ }
            }
        } catch (Exception exception) {
            System.err.println("Cannot run server: " + exception.getMessage());
            System.exit(1);
        }
    }
}
