package exchange.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class Arguments {
    private final Map<String, String> values = new HashMap<>();

    public Arguments(String[] args, Set<String> allowed) {
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if (option.equals("--help")) {
                values.put(option, "true");
                continue;
            }
            if (!allowed.contains(option) || index + 1 == args.length) {
                throw new IllegalArgumentException("Unknown option or missing value: " + option);
            }
            if (values.putIfAbsent(option, args[++index]) != null) {
                throw new IllegalArgumentException("Repeated option: " + option);
            }
        }
    }

    public boolean help() { return values.containsKey("--help"); }
    public String get(String key, String fallback) { return values.getOrDefault(key, fallback); }

    public int port(int fallback) {
        int port = Integer.parseInt(get("--port", Integer.toString(fallback)));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
        return port;
    }

    public Settings settings() throws IOException {
        return Settings.load(Path.of(get("--config", "config.properties")),
                values.containsKey("--config"));
    }
}
