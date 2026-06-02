package it.uniparthenope.reti.pub.common;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class SocketLine {
    private SocketLine() {
    }

    public static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    public static PrintWriter writer(Socket socket) throws IOException {
        return new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
    }

    public static Message receive(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new EOFException("Connessione chiusa dal peer");
        }
        return Message.parse(line);
    }

    public static void send(PrintWriter writer, Message message) {
        writer.println(message.toLine());
    }
}
