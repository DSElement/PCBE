package exchange.model;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public enum Company {
    NVDA("NVIDIA"), AAPL("Apple"), MSFT("Microsoft");

    public static final List<Company> LOCK_ORDER = Arrays.stream(values())
            .sorted(Comparator.comparing(Company::name)).toList();
    public final String displayName;

    Company(String displayName) {
        this.displayName = displayName;
    }

    public static Company parse(String symbol) {
        try {
            return valueOf(symbol.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new MarketException("UNKNOWN_COMPANY", "Use NVDA, AAPL, or MSFT.");
        }
    }
}
