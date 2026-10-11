package exchange.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record Settings(String bind, int port, int cashMin, int cashMax,
                       int sharesMin, int sharesMax, int priceMin, int priceMax,
                       int maxOrders, int botDelayMin, int botDelayMax,
                       int botQuantityMax, int botFallbackAttempts) {
    public static Settings load(Path path, boolean required) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } else if (required) {
            throw new IOException("Configuration file does not exist: " + path);
        } else {
            try (InputStream defaults = Settings.class.getResourceAsStream("/config.properties")) {
                if (defaults == null) { throw new IOException("Bundled configuration is missing."); }
                try (Reader reader = new InputStreamReader(defaults, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
        }
        return new Settings(properties.getProperty("server.bind", "127.0.0.1"),
                number(properties, "server.port", 5000),
                number(properties, "initial.cash.min", 200),
                number(properties, "initial.cash.max", 1000),
                number(properties, "initial.shares.min", 0),
                number(properties, "initial.shares.max", 10),
                number(properties, "price.min", 20),
                number(properties, "price.max", 50),
                number(properties, "orders.max", 5),
                number(properties, "bot.delay.min.ms", 1000),
                number(properties, "bot.delay.max.ms", 3000),
                number(properties, "bot.quantity.max", 3),
                number(properties, "bot.fallback.attempts", 3));
    }

    public Settings {
        if (bind.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Set a bind address and a port between 1 and 65535.");
        }
        range("cash", cashMin, cashMax, 0);
        range("shares", sharesMin, sharesMax, 0);
        range("price", priceMin, priceMax, 1);
        range("bot delay", botDelayMin, botDelayMax, 1);
        if (maxOrders < 1 || botQuantityMax < 1 || botFallbackAttempts < 1) {
            throw new IllegalArgumentException("Order limit, bot quantity, and fallback threshold must be positive.");
        }
    }

    private static int number(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Setting " + key + " must be an integer.");
        }
    }

    private static void range(String label, int min, int max, int floor) {
        if (min < floor || max < min) {
            throw new IllegalArgumentException("Invalid " + label + " range.");
        }
    }
}
