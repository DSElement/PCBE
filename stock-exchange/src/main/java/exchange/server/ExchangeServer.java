package exchange.server;

import exchange.config.Settings;
import exchange.market.Account;
import exchange.market.Market;
import exchange.model.Company;
import exchange.model.Side;
import exchange.model.MarketException;
import exchange.protocol.Wire;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class ExchangeServer implements AutoCloseable {
    private final Market market;
    private final ServerSocket listener;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService handlers;

    ExchangeServer(Settings settings, String bind, int port) throws IOException {
        listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(bind, port));
        } catch (IOException | RuntimeException exception) {
            listener.close();
            throw exception;
        }
        market = new Market(settings);
        AtomicLong threadIds = new AtomicLong();
        handlers = Executors.newCachedThreadPool(task ->
                new Thread(task, "client-handler-" + threadIds.incrementAndGet()));
    }

    void serve() throws IOException {
        System.out.println("Stock exchange listening on " + listener.getLocalSocketAddress());
        System.out.println("Companies: NVIDIA (NVDA), Apple (AAPL), Microsoft (MSFT).");
        System.out.println("Full run history follows. Ctrl+C shuts down the server.");
        while (!closed.get()) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException exception) {
                if (closed.get()) { return; }
                throw exception;
            }
            sockets.add(socket);
            try {
                socket.setTcpNoDelay(true);
                handlers.execute(() -> handle(socket));
            } catch (IOException | RejectedExecutionException exception) {
                sockets.remove(socket);
                closeSocket(socket);
                if (!closed.get()) { throw exception; }
            }
        }
    }

    private void handle(Socket socket) {
        Account account = null;
        String reason = "CONNECTION_CLOSED";
        try (socket) {
            Wire wire = new Wire(socket);
            account = market.connect();
            wire.sendResponse("OK CONNECT", market.status(account));
            String raw;
            while (!closed.get() && (raw = wire.readCommand()) != null) {
                String command = raw.trim();
                try {
                    String[] tokens = command.isEmpty() ? new String[0] : command.split("\\s+");
                    if (tokens.length == 0) { throw new MarketException("BAD_COMMAND", "Enter a command."); }
                    String operation = tokens[0].toUpperCase(Locale.ROOT);
                    List<String> rows = execute(account, operation, tokens);
                    wire.sendResponse("OK " + operation, rows);
                    if (operation.equals("LEAVE")) { reason = "LEAVE"; return; }
                } catch (MarketException exception) {
                    market.reject(account, command, exception.code);
                    wire.sendResponse("ERROR " + exception.code + " " + exception.getMessage(), market.status(account));
                } catch (NumberFormatException exception) {
                    market.reject(account, command, "INVALID_NUMBER");
                    wire.sendResponse("ERROR INVALID_NUMBER Use integers within the supported numeric range.", market.status(account));
                }
            }
            if (closed.get()) { reason = "SERVER_SHUTDOWN"; }
        } catch (IOException exception) {
            reason = closed.get() ? "SERVER_SHUTDOWN" : "CONNECTION_LOST";
            if (account != null && !closed.get()) {
                market.failure(account, "network=" + exception.getMessage());
            }
        } catch (RuntimeException exception) {
            reason = "HANDLER_ERROR";
            if (account != null) { market.failure(account, exception.toString()); }
            System.err.println("Handler error: " + exception);
        } finally {
            try {
                if (account != null) { market.disconnect(account, reason); }
            } finally {
                sockets.remove(socket);
            }
        }
    }

    private List<String> execute(Account account, String operation, String[] tokens) {
        List<String> result;
        switch (operation) {
            case "STATUS" -> { arity(tokens, 1); return market.status(account); }
            case "MARKET" -> { arity(tokens, 1); return market.snapshot(); }
            case "HISTORY" -> { arity(tokens, 1); return market.history(); }
            case "BUY", "SELL" -> {
                arity(tokens, 4);
                result = market.place(account, Side.valueOf(operation), Company.parse(tokens[1]),
                        Long.parseLong(tokens[2]), Integer.parseInt(tokens[3]));
            }
            case "MODIFY" -> {
                arity(tokens, 4);
                result = market.modify(account, Long.parseLong(tokens[1]), Long.parseLong(tokens[2]), Integer.parseInt(tokens[3]));
            }
            case "CANCEL" -> { arity(tokens, 2); result = market.cancel(account, Long.parseLong(tokens[1])); }
            case "LEAVE" -> { arity(tokens, 1); market.disconnect(account, "LEAVE"); return market.status(account); }
            default -> throw new MarketException("BAD_COMMAND", "Use STATUS, MARKET, HISTORY, BUY, SELL, MODIFY, CANCEL, or LEAVE.");
        }
        List<String> rows = new ArrayList<>(result);
        // Market mutation has released its locks before a status snapshot or socket write.
        rows.addAll(market.status(account));
        return List.copyOf(rows);
    }

    private static void arity(String[] tokens, int count) {
        if (tokens.length != count) { throw new MarketException("BAD_ARGUMENTS", "Wrong number of arguments for this command."); }
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) { return; }
        try { listener.close(); } catch (IOException ignored) { }
        handlers.shutdown();
        sockets.forEach(ExchangeServer::closeSocket);
        boolean interrupted = false;
        // No handler performs network I/O while holding market locks. Closing sockets
        // releases idle readers; wait for all cancellation producers before closing history.
        while (!handlers.isTerminated()) {
            try {
                if (!handlers.awaitTermination(1, TimeUnit.SECONDS)) {
                    sockets.forEach(ExchangeServer::closeSocket);
                }
            } catch (InterruptedException exception) {
                interrupted = true;
                handlers.shutdownNow();
            }
        }
        market.close();
        if (interrupted) { Thread.currentThread().interrupt(); }
    }
}
