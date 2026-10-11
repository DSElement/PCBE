package exchange.protocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** The Java monitor serializes complete request/response exchanges on this socket. */
public final class ExchangeConnection implements AutoCloseable {
    private final Socket socket = new Socket();
    private final Wire wire;
    private final Wire.Response welcome;

    public ExchangeConnection(String host, int port) throws IOException {
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30000);
            wire = new Wire(socket);
            welcome = wire.readResponse();
            if (!welcome.ok()) { throw new IOException(welcome.header()); }
        } catch (IOException | RuntimeException exception) {
            socket.close();
            throw exception;
        }
    }

    public Wire.Response welcome() { return welcome; }

    public synchronized Wire.Response request(String command) throws IOException {
        try {
            if (socket.isClosed()) { throw new IOException("Client connection is closed."); }
            wire.sendCommand(command);
            return wire.readResponse();
        } catch (IOException exception) {
            // A timed-out/partial response makes framing uncertain. Never reuse that stream.
            close();
            throw exception;
        }
    }

    @Override
    public void close() {
        // Deliberately outside the request monitor: shutdown must unblock a pending read.
        try { socket.close(); } catch (IOException ignored) { }
    }
}
