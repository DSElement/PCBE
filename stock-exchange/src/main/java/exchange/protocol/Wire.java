package exchange.protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** UTF-8, one command line, then a complete response ending in END. */
public final class Wire {
    static final int MAX_COMMAND_LENGTH = 4096;
    public record Response(String header, List<String> rows) {
        public Response { rows = List.copyOf(rows); }
        public boolean ok() { return header.startsWith("OK "); }
    }

    private final BufferedReader reader;
    private final BufferedWriter writer;

    public Wire(Socket socket) throws IOException {
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    public String readCommand() throws IOException {
        String command = reader.readLine();
        if (command != null && command.length() > MAX_COMMAND_LENGTH) {
            throw new IOException("Command exceeded " + MAX_COMMAND_LENGTH + " characters.");
        }
        return command;
    }

    public void sendCommand(String command) throws IOException {
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0 || command.length() > MAX_COMMAND_LENGTH) {
            throw new IllegalArgumentException("Send one command of at most " + MAX_COMMAND_LENGTH + " characters.");
        }
        writer.write(command);
        writer.newLine();
        writer.flush();
    }

    public void sendResponse(String header, List<String> rows) throws IOException {
        writer.write(header.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '));
        writer.newLine();
        for (String row : rows) {
            writer.write(row);
            writer.newLine();
        }
        writer.write("END");
        writer.newLine();
        writer.flush();
    }

    public Response readResponse() throws IOException {
        String header = reader.readLine();
        if (header == null) { throw new EOFException("Server closed the connection."); }
        if (!header.startsWith("OK ") && !header.startsWith("ERROR ")) {
            throw new IOException("Unexpected response header: " + header);
        }
        List<String> rows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.equals("END")) { return new Response(header, rows); }
            rows.add(line);
        }
        throw new EOFException("Server closed an incomplete response.");
    }
}
