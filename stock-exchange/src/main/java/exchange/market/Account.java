package exchange.market;

import exchange.config.Settings;
import exchange.model.Company;
import java.util.EnumMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/** Account balances and reservations are protected by lock. */
public final class Account {
    final long id;
    final ReentrantLock lock = new ReentrantLock(true);
    final EnumMap<Company, Long> shares = new EnumMap<>(Company.class);
    final EnumMap<Company, Long> reservedShares = new EnumMap<>(Company.class);
    long cash;
    long reservedCash;
    long fillCount;
    int openOrders;
    boolean active = true;

    Account(long id, Settings settings) {
        this.id = id;
        cash = ThreadLocalRandom.current().nextLong(settings.cashMin(), (long) settings.cashMax() + 1);
        for (Company company : Company.values()) {
            shares.put(company, ThreadLocalRandom.current().nextLong(settings.sharesMin(), (long) settings.sharesMax() + 1));
            reservedShares.put(company, 0L);
        }
    }

    public long id() { return id; }
}
