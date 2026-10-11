package exchange.market;

import exchange.config.Settings;
import exchange.model.Company;
import exchange.model.Side;
import exchange.model.MarketException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared exchange state. Lock hierarchy: books by symbol -> accounts by ID -> history.
 * Order fields belong to their book lock; account fields belong to their account lock.
 * Concurrent registries safely publish objects but do NOT protect their mutable fields.
 */
public final class Market implements AutoCloseable {
    private final Settings settings;
    private final EnumMap<Company, OrderBook> books = new EnumMap<>(Company.class);
    private final ConcurrentHashMap<Long, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong accountIds = new AtomicLong();
    private final AtomicLong orderIds = new AtomicLong();
    private final AtomicLong tradeIds = new AtomicLong();
    private final AtomicLong priorities = new AtomicLong();
    private final EventHistory history = new EventHistory();

    public Market(Settings settings) {
        this.settings = settings;
        for (Company company : Company.values()) { books.put(company, new OrderBook(company)); }
    }

    public Account connect() {
        Account account = new Account(accountIds.incrementAndGet(), settings);
        history.append(List.of(event("CONNECT", account.id, "new_session")));
        return account;
    }

    public List<String> place(Account account, Side side, Company company, long quantity, int price) {
        validateNumbers(quantity, price);
        OrderBook book = books.get(company);
        book.lock.writeLock().lock();
        List<Account> participants = List.of();
        try {
            List<Order> candidates = candidates(book, account, side, price);
            participants = participants(account, candidates);
            lockAccounts(participants);
            requireActive(account);
            if (account.openOrders >= settings.maxOrders()) {
                throw new MarketException("ORDER_LIMIT", "Cancel or modify an existing order; the open-order limit is " + settings.maxOrders() + ".");
            }
            validateResources(account, company, side, quantity, price, null);

            Order order = new Order(orderIds.incrementAndGet(), account, company, side,
                    price, quantity, priorities.incrementAndGet());
            reserveResources(order);
            account.openOrders++;
            orders.put(order.id, order);
            book.orders.add(order);
            List<EventHistory.Draft> events = new ArrayList<>();
            events.add(event("PLACED", account.id, details(order)));
            List<String> result = settle(book, order, candidates, events);
            history.append(events);
            result.addFirst(order.wire());
            return List.copyOf(result);
        } finally {
            unlockAccounts(participants);
            book.lock.writeLock().unlock();
        }
    }

    public List<String> modify(Account account, long orderId, long quantity, int price) {
        validateNumbers(quantity, price);
        Order order = findOrder(orderId);
        OrderBook book = books.get(order.company);
        book.lock.writeLock().lock();
        List<Account> participants = List.of();
        try {
            List<Order> candidates = candidates(book, account, order.side, price);
            participants = participants(account, candidates);
            lockAccounts(participants);
            requireOwnedOpen(account, order);
            if (order.price == price && order.remaining == quantity) {
                history.append(List.of(event("UNCHANGED", account.id, details(order))));
                return List.of("NOTICE Order unchanged; priority preserved.", order.wire());
            }
            validateResources(account, order.company, order.side, quantity, price, order);
            int oldPrice = order.price;
            long oldQuantity = order.remaining;
            long oldPriority = order.priority;
            releaseResources(order);
            book.orders.remove(order);
            order.price = price;
            order.remaining = quantity;
            order.priority = priorities.incrementAndGet();
            reserveResources(order);
            book.orders.add(order);
            List<EventHistory.Draft> events = new ArrayList<>();
            events.add(event("MODIFIED", account.id, details(order) + " old_price=" + oldPrice
                    + " old_remaining=" + oldQuantity + " old_priority=" + oldPriority));
            List<String> result = settle(book, order, candidates, events);
            history.append(events);
            result.addFirst(order.wire());
            return List.copyOf(result);
        } finally {
            unlockAccounts(participants);
            book.lock.writeLock().unlock();
        }
    }

    public List<String> cancel(Account account, long orderId) {
        Order order = findOrder(orderId);
        OrderBook book = books.get(order.company);
        book.lock.writeLock().lock();
        account.lock.lock();
        try {
            requireOwnedOpen(account, order);
            List<EventHistory.Draft> events = new ArrayList<>();
            cancelLocked(book, order, "CLIENT_REQUEST", events);
            history.append(events);
            return List.of(order.wire());
        } finally {
            account.lock.unlock();
            book.lock.writeLock().unlock();
        }
    }

    public void disconnect(Account account, String reason) {
        lockAllBooksForWrite();
        account.lock.lock();
        try {
            if (!account.active) { return; }
            List<EventHistory.Draft> events = new ArrayList<>();
            for (Company company : Company.LOCK_ORDER) {
                OrderBook book = books.get(company);
                for (Order order : new ArrayList<>(book.orders)) {
                    if (order.owner == account) { cancelLocked(book, order, "DISCONNECT", events); }
                }
            }
            account.active = false;
            events.add(event("DISCONNECT", account.id, "reason=" + reason));
            history.append(events);
        } finally {
            account.lock.unlock();
            unlockAllBooksForWrite();
        }
    }

    public List<String> status(Account account) {
        // All book read locks make the own-order list and balances one consistent snapshot.
        lockAllBooksForRead();
        account.lock.lock();
        try {
            List<String> rows = new ArrayList<>();
            rows.add("CLIENT " + account.id + " " + (account.active ? "ACTIVE" : "CLOSED"));
            rows.add("CASH " + account.cash + " " + account.reservedCash + " " + (account.cash - account.reservedCash));
            rows.add(rules());
            rows.add("FILLS " + account.fillCount);
            rows.add("OPEN_ORDERS " + account.openOrders);
            for (Company company : Company.LOCK_ORDER) {
                long total = account.shares.get(company);
                long reserved = account.reservedShares.get(company);
                rows.add("SHARES " + company + " " + total + " " + reserved + " " + (total - reserved));
                for (Order order : books.get(company).orders) {
                    if (order.owner == account) { rows.add(order.wire()); }
                }
            }
            return List.copyOf(rows);
        } finally {
            account.lock.unlock();
            unlockAllBooksForRead();
        }
    }

    public List<String> snapshot() {
        lockAllBooksForRead();
        try {
            List<String> rows = new ArrayList<>();
            List<Trade> trades = new ArrayList<>();
            rows.add(rules());
            for (Company company : Company.LOCK_ORDER) {
                OrderBook book = books.get(company);
                rows.add("COMPANY " + company + " " + company.displayName + " "
                        + (book.lastPrice == null ? "NONE" : book.lastPrice));
                for (Order order : book.orders) { rows.add(order.wire()); }
                trades.addAll(book.trades);
            }
            trades.stream().sorted(Comparator.comparingLong(Trade::id)).forEach(trade -> rows.add(trade.wire()));
            return List.copyOf(rows);
        } finally {
            unlockAllBooksForRead();
        }
    }

    public List<String> history() { return history.snapshot(); }

    public void reject(Account account, String command, String code) {
        history.append(List.of(event("REJECTED", account.id, "code=" + code + " command=" + command)));
    }

    public void failure(Account account, String message) {
        history.append(List.of(event("ERROR", account.id, message)));
    }

    private String rules() {
        return "RULES " + settings.priceMin() + " " + settings.priceMax() + " " + settings.maxOrders();
    }

    private List<Order> candidates(OrderBook book, Account account, Side side, int price) {
        return book.orders.stream().filter(order -> order.side != side
                && order.price == price && order.owner != account).toList();
    }

    private List<Account> participants(Account initiator, List<Order> candidates) {
        Map<Long, Account> distinct = new TreeMap<>();
        distinct.put(initiator.id, initiator);
        for (Order order : candidates) { distinct.put(order.owner.id, order.owner); }
        return List.copyOf(distinct.values());
    }

    private static void lockAccounts(List<Account> participants) {
        for (Account account : participants) { account.lock.lock(); }
    }

    private static void unlockAccounts(List<Account> participants) {
        for (int index = participants.size() - 1; index >= 0; index--) {
            participants.get(index).lock.unlock();
        }
    }

    private void lockAllBooksForRead() {
        for (Company company : Company.LOCK_ORDER) { books.get(company).lock.readLock().lock(); }
    }

    private void unlockAllBooksForRead() {
        for (Company company : Company.LOCK_ORDER.reversed()) { books.get(company).lock.readLock().unlock(); }
    }

    private void lockAllBooksForWrite() {
        for (Company company : Company.LOCK_ORDER) { books.get(company).lock.writeLock().lock(); }
    }

    private void unlockAllBooksForWrite() {
        for (Company company : Company.LOCK_ORDER.reversed()) { books.get(company).lock.writeLock().unlock(); }
    }

    private void validateNumbers(long quantity, int price) {
        if (quantity <= 0) { throw new MarketException("INVALID_QUANTITY", "Quantity must be a positive whole number; use CANCEL to remove an order."); }
        if (price < settings.priceMin() || price > settings.priceMax()) {
            throw new MarketException("INVALID_PRICE", "Price must be an integer from " + settings.priceMin() + " to " + settings.priceMax() + ".");
        }
    }

    private void validateResources(Account account, Company company, Side side, long quantity, int price, Order old) {
        try {
            if (side == Side.BUY) {
                long required = Math.multiplyExact(quantity, (long) price);
                long reusable = old == null ? 0 : Math.multiplyExact(old.remaining, (long) old.price);
                if (required > account.cash - account.reservedCash + reusable) {
                    throw new MarketException("INSUFFICIENT_CASH", "Not enough available cash for the remaining quantity.");
                }
            } else {
                long reusable = old == null ? 0 : old.remaining;
                if (quantity > account.shares.get(company) - account.reservedShares.get(company) + reusable) {
                    throw new MarketException("INSUFFICIENT_SHARES", "Not enough available shares of " + company + ".");
                }
            }
        } catch (ArithmeticException exception) {
            throw new MarketException("NUMERIC_OVERFLOW", "Quantity times price is too large.");
        }
    }

    private List<String> settle(OrderBook book, Order incoming, List<Order> candidates,
                                List<EventHistory.Draft> events) {
        List<String> result = new ArrayList<>();
        for (Order opposite : candidates) {
            if (incoming.remaining == 0) { break; }
            Order buy = incoming.side == Side.BUY ? incoming : opposite;
            Order sell = incoming.side == Side.SELL ? incoming : opposite;
            long quantity = Math.min(incoming.remaining, opposite.remaining);
            long cost = quantity * incoming.price; // Covered by the buyer reservation.
            buy.owner.cash -= cost;
            buy.owner.reservedCash -= cost;
            sell.owner.cash += cost;
            buy.owner.shares.merge(book.company, quantity, Long::sum);
            sell.owner.shares.merge(book.company, -quantity, Long::sum);
            sell.owner.reservedShares.merge(book.company, -quantity, Long::sum);
            buy.remaining -= quantity;
            sell.remaining -= quantity;
            buy.executed += quantity;
            sell.executed += quantity;
            buy.owner.fillCount++;
            sell.owner.fillCount++;
            Trade trade = new Trade(tradeIds.incrementAndGet(), book.company, incoming.price,
                    quantity, buy.owner.id, sell.owner.id, buy.id, sell.id, Instant.now());
            book.trades.add(trade);
            book.lastPrice = incoming.price;
            result.add(trade.wire());
            events.add(event("TRADE", incoming.owner.id, "trade=" + trade.id() + " company=" + book.company
                    + " price=" + incoming.price + " quantity=" + quantity + " buyer=" + buy.owner.id
                    + " seller=" + sell.owner.id + " buy_order=" + buy.id + " sell_order=" + sell.id));
            for (Order order : List.of(buy, sell)) {
                events.add(event("FILL", order.owner.id, details(order) + " trade=" + trade.id() + " quantity=" + quantity));
                if (!order.open()) {
                    book.orders.remove(order);
                    order.owner.openOrders--;
                    events.add(event("FILLED", order.owner.id, details(order)));
                }
            }
        }
        return result;
    }

    private void reserveResources(Order order) {
        if (order.side == Side.BUY) {
            order.owner.reservedCash += order.remaining * order.price;
        } else {
            order.owner.reservedShares.merge(order.company, order.remaining, Long::sum);
        }
    }

    private void releaseResources(Order order) {
        if (order.side == Side.BUY) {
            order.owner.reservedCash -= order.remaining * order.price;
        } else {
            order.owner.reservedShares.merge(order.company, -order.remaining, Long::sum);
        }
    }

    private void cancelLocked(OrderBook book, Order order, String reason, List<EventHistory.Draft> events) {
        releaseResources(order);
        book.orders.remove(order);
        order.owner.openOrders--;
        order.cancelled = true;
        events.add(event("CANCELLED", order.owner.id, details(order) + " reason=" + reason));
    }

    private Order findOrder(long id) {
        Order order = orders.get(id);
        if (order == null) { throw new MarketException("UNKNOWN_ORDER", "No order has ID " + id + "."); }
        return order;
    }

    private static void requireActive(Account account) {
        if (!account.active) { throw new MarketException("ACCOUNT_CLOSED", "This market session has ended."); }
    }

    private static void requireOwnedOpen(Account account, Order order) {
        requireActive(account);
        if (order.owner != account) { throw new MarketException("NOT_YOUR_ORDER", "Only the order owner can change it."); }
        if (!order.open()) { throw new MarketException("ORDER_CLOSED", "Order " + order.id + " is already " + order.state() + "."); }
    }

    private static String details(Order order) {
        return "order=" + order.id + " company=" + order.company + " side=" + order.side
                + " price=" + order.price + " remaining=" + order.remaining + " executed=" + order.executed
                + " priority=" + order.priority + " state=" + order.state();
    }

    private static EventHistory.Draft event(String type, long account, String detail) {
        return new EventHistory.Draft(type, account, "thread=" + Thread.currentThread().getName() + " " + detail);
    }

    @Override
    public void close() { history.close(); }
}
