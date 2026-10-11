package exchange.market;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Authoritative synchronous history plus asynchronous console output.
 * The history lock is LAST in the server lock hierarchy. The consumer never
 * acquires a book/account lock, so slow printing cannot hold up settlement.
 */
final class EventHistory implements AutoCloseable {
    record Draft(String type, long clientId, String details) { }
    record Event(long sequence, Instant time, String type, long clientId, String details) {
        String wire() {
            return "EVENT " + sequence + " " + time + " " + type + " " + clientId + " " + details;
        }
    }

    private static final Event STOP = new Event(-1, Instant.EPOCH, "STOP", 0, "");
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final List<Event> events = new ArrayList<>();
    private final BlockingQueue<Event> output = new LinkedBlockingQueue<>();
    private final Thread logger;
    private long nextSequence = 1;
    private boolean closed;

    EventHistory() {
        logger = new Thread(this::printEvents, "event-logger");
        logger.setDaemon(true);
        logger.start();
    }

    void append(List<Draft> batch) {
        lock.writeLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("History is closed.");
            }
            for (Draft draft : batch) {
                Event event = new Event(nextSequence++, Instant.now(), draft.type(),
                        draft.clientId(), oneLine(draft.details()));
                events.add(event);
                // Unbounded queue: add never waits for the console consumer.
                // Enqueue under the history lock to preserve event sequence order.
                output.add(event);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    List<String> snapshot() {
        lock.readLock().lock();
        try {
            return events.stream().map(Event::wire).toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void printEvents() {
        try {
            while (true) {
                Event event = output.take(); // Blocks without spinning when there is no work.
                if (event == STOP) {
                    return;
                }
                System.out.println(event.wire());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    static String oneLine(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            if (closed) { return; }
            closed = true;
            output.add(STOP); // All preceding events drain before the consumer exits.
        } finally {
            lock.writeLock().unlock();
        }
        try {
            logger.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
