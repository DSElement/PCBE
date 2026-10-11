package exchange.bot;

import exchange.config.Settings;
import exchange.model.Company;
import exchange.model.Side;
import exchange.protocol.ExchangeConnection;
import exchange.protocol.Views;
import exchange.protocol.Wire;

import java.io.IOException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Terminal input and one worker share a connection. Only the worker updates strategy counters.
 * The control lock covers each cycle; stop acknowledges after an in-flight action finishes.
 * Timed condition waits release the lock, allowing commands without busy waiting.
 */
final class TradingBot implements AutoCloseable {
    private record Lane(Company company, Side side) { }
    private record Action(String command, boolean pricing, String reason) { }
    private record Price(int value, String reason) { }

    private final ExchangeConnection connection;
    private final Settings settings;
    private final ReentrantLock control = new ReentrantLock(true);
    private final Condition changed = control.newCondition();
    private final Thread worker;
    private final long clientId;
    private volatile boolean closing;
    private boolean paused;
    private int failures;
    private long observedFills;

    TradingBot(ExchangeConnection connection, Settings settings) {
        this.connection = connection;
        this.settings = settings;
        Views.Status status = Views.Status.from(connection.welcome());
        clientId = status.client();
        observedFills = status.fills();
        worker = new Thread(this::runTrading, "bot-trading-" + clientId);
        log("CONNECTED " + Views.summary(status));
    }

    void start() {
        worker.start();
        log("Commands: stop | resume | status | leave. Existing orders remain active while stopped.");
    }

    void stop() {
        control.lock();
        try {
            if (closing) { log("Connection ended; use leave to exit."); return; }
            paused = true;
            changed.signalAll();
            log("STOPPED: no new trading decisions; existing orders remain active and status checks continue.");
        } finally {
            control.unlock();
        }
    }

    void resume() {
        control.lock();
        try {
            if (closing) { log("Connection ended; use leave to exit."); return; }
            paused = false;
            changed.signalAll();
            log("RESUMED");
        } finally {
            control.unlock();
        }
    }

    void showStatus() {
        if (closing) { log("Connection ended; use leave to exit."); return; }
        try {
            Views.Status status = Views.Status.from(connection.request("STATUS"));
            log("STATUS " + Views.summary(status));
        } catch (IOException | RuntimeException exception) {
            fail(exception);
        }
    }

    private void runTrading() {
        control.lock();
        try {
            while (!closing) {
                Views.Status status = Views.Status.from(connection.request("STATUS"));
                observe(status);
                if (!paused) { act(status); }
                long delay = random().nextLong(settings.botDelayMin(), (long) settings.botDelayMax() + 1);
                long remaining = TimeUnit.MILLISECONDS.toNanos(delay);
                boolean wasPaused = paused;
                // Recheck state after every wakeup; paused bots still poll on the next cycle.
                while (!closing && paused == wasPaused && remaining > 0) {
                    remaining = changed.awaitNanos(remaining);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {
            failLocked(exception);
        } finally {
            control.unlock();
        }
    }

    private void act(Views.Status status) throws IOException {
        List<Views.Order> market = Views.marketOrders(connection.request("MARKET"));
        Action action;
        if (failures >= settings.botFallbackAttempts()) {
            action = exactMatch(status, market);
            if (action == null) {
                // Release a reservation that could prevent a later exact match.
                action = cancellation(status, "fallback: free resources for a future exact match");
                if (action == null) {
                    log("WAIT: exact-match fallback active; no affordable eligible opposite order.");
                    return;
                }
            }
        } else {
            int roll = random().nextInt(100);
            if (roll < 60) {
                action = placement(status, market);
                if (action == null) { action = modification(status, market); }
                if (action == null) { action = cancellation(status, "placement unavailable"); }
            } else if (roll < 85) {
                action = modification(status, market);
                if (action == null) { action = placement(status, market); }
                if (action == null) { action = cancellation(status, "modification unavailable"); }
            } else {
                action = cancellation(status, "random cancellation");
                if (action == null) { action = placement(status, market); }
                if (action == null) { action = modification(status, market); }
            }
            if (action == null) { log("WAIT: no feasible action. " + Views.summary(status)); return; }
        }

        log("ACTION " + action.command() + " (" + action.reason() + ")");
        Wire.Response response = connection.request(action.command());
        Views.Status updated = Views.Status.from(response);
        boolean observed = observe(updated);
        boolean immediateFill = response.rows().stream().anyMatch(row -> row.startsWith("TRADE "));
        boolean unchanged = response.rows().stream().anyMatch(row -> row.startsWith("NOTICE Order unchanged"));
        if (immediateFill || observed) {
            failures = 0;
        } else if (action.pricing() && !unchanged) {
            // Rejections caused by stale snapshots count as unsuccessful attempts too.
            if (failures < Integer.MAX_VALUE) { failures++; }
        }
        log(response.header() + " | unsuccessful pricing attempts=" + failures);
        for (String row : response.rows()) {
            if (row.startsWith("TRADE ") || row.startsWith("NOTICE ")) { log(row); }
        }
        log(Views.summary(updated));
    }

    private Action placement(Views.Status status, List<Views.Order> market) {
        if (status.openCount() >= status.rules().maxOrders()) { return null; }
        List<Lane> lanes = new ArrayList<>();
        for (Company company : Company.values()) {
            for (Side side : Side.values()) { lanes.add(new Lane(company, side)); }
        }
        Collections.shuffle(lanes, random());
        for (Lane lane : lanes) {
            Price price = price(status, market, lane.company(), lane.side());
            long quantity = quantity(status, lane.company(), lane.side(), price.value(), null, Long.MAX_VALUE);
            if (quantity > 0) {
                return new Action(lane.side() + " " + lane.company() + " " + quantity + " " + price.value(), true, price.reason());
            }
        }
        return null;
    }

    private Action modification(Views.Status status, List<Views.Order> market) {
        List<Views.Order> own = shuffled(status.orders());
        for (Views.Order order : own) {
            Price price = price(status, market, order.company(), order.side());
            long quantity = quantity(status, order.company(), order.side(), price.value(), order, Long.MAX_VALUE);
            if (quantity > 0 && (price.value() != order.price() || quantity != order.remaining())) {
                return new Action("MODIFY " + order.id() + " " + quantity + " " + price.value(), true, price.reason());
            }
        }
        return null;
    }

    private Action cancellation(Views.Status status, String reason) {
        if (status.orders().isEmpty()) { return null; }
        Views.Order order = status.orders().get(random().nextInt(status.orders().size()));
        return new Action("CANCEL " + order.id(), false, reason);
    }

    private Action exactMatch(Views.Status status, List<Views.Order> market) {
        for (Views.Order opposite : shuffled(market)) {
            if (opposite.client() == clientId) { continue; }
            Side side = opposite.side() == Side.BUY ? Side.SELL : Side.BUY;
            if (status.openCount() < status.rules().maxOrders()) {
                long quantity = quantity(status, opposite.company(), side, opposite.price(), null, opposite.remaining());
                if (quantity > 0) {
                    return new Action(side + " " + opposite.company() + " " + quantity + " " + opposite.price(),
                            true, "three-failure fallback: exact match");
                }
            }
            for (Views.Order own : shuffled(status.orders())) {
                if (own.company() != opposite.company() || own.side() != side) { continue; }
                long quantity = quantity(status, own.company(), side, opposite.price(), own, opposite.remaining());
                if (quantity > 0 && (own.price() != opposite.price() || own.remaining() != quantity)) {
                    return new Action("MODIFY " + own.id() + " " + quantity + " " + opposite.price(),
                            true, "three-failure fallback: exact-match reprice");
                }
            }
        }
        return null;
    }

    private Price price(Views.Status status, List<Views.Order> market, Company company, Side side) {
        Comparator<Views.Order> byPrice = Comparator.comparingInt(Views.Order::price);
        if (side == Side.SELL) { byPrice = byPrice.reversed(); }
        Views.Order opposite = market.stream().filter(order -> order.company() == company
                        && order.side() != side && order.client() != clientId)
                .min(byPrice).orElse(null);
        int min = status.rules().minPrice(), max = status.rules().maxPrice();
        if (opposite == null) {
            int value = (int) random().nextLong(min, (long) max + 1);
            return new Price(value, "no opposite quote: random permitted price");
        }
        int roll = random().nextInt(100);
        if (roll >= 60 && roll < 90) { return new Price(opposite.price(), "exact quote match"); }
        int offset = random().nextInt(1, 4);
        boolean cautious = roll < 60;
        int direction = side == Side.BUY ? -1 : 1;
        if (!cautious) { direction = -direction; }
        long value = (long) opposite.price() + direction * offset;
        return new Price((int) Math.max(min, Math.min(max, value)), cautious ? "cautious quote offset" : "rare aggressive quote offset (exact matching still applies)");
    }

    private long quantity(Views.Status status, Company company, Side side, int price,
                          Views.Order replaced, long maximum) {
        long available;
        if (side == Side.BUY) {
            long reusable = replaced == null ? 0 : replaced.remaining() * replaced.price();
            available = (status.availableCash() + reusable) / price;
        } else {
            available = status.holdings().get(company).available() + (replaced == null ? 0 : replaced.remaining());
        }
        long limit = Math.min(settings.botQuantityMax(), Math.min(available, maximum));
        return limit < 1 ? 0 : random().nextLong(1, limit + 1);
    }

    private boolean observe(Views.Status status) {
        if (status.fills() <= observedFills) { return false; }
        log("FILL observed " + (status.fills() - observedFills) + " new transaction(s). " + Views.summary(status));
        observedFills = status.fills();
        failures = 0;
        return true;
    }

    private void fail(Exception exception) {
        control.lock();
        try { if (!closing) { failLocked(exception); } }
        finally { control.unlock(); }
    }

    private void failLocked(Exception exception) {
        closing = true;
        changed.signalAll();
        connection.close();
        log("CONNECTION ENDED: " + exception.getMessage() + ". Type leave or press Ctrl+C to exit.");
    }

    private static ThreadLocalRandom random() { return ThreadLocalRandom.current(); }

    private static <T> List<T> shuffled(List<T> items) {
        List<T> copy = new ArrayList<>(items);
        Collections.shuffle(copy, random());
        return copy;
    }

    private void log(String message) {
        System.out.println("[" + LocalTime.now().withNano(0) + " bot " + clientId + "] " + message);
    }

    @Override
    public void close() {
        boolean leave;
        control.lock();
        try {
            leave = !closing;
            closing = true;
            changed.signalAll();
        } finally {
            control.unlock();
        }
        try {
            if (leave) { log(connection.request("LEAVE").header()); }
        } catch (IOException exception) {
            log("Connection closed; the server cleans up the session on disconnect.");
        } finally {
            connection.close();
            worker.interrupt();
        }
        try {
            worker.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
