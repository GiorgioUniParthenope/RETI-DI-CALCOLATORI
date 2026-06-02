package it.uniparthenope.reti.pub.server;

import it.uniparthenope.reti.pub.common.CommandLineArgs;
import it.uniparthenope.reti.pub.common.Menu;
import it.uniparthenope.reti.pub.common.Message;
import it.uniparthenope.reti.pub.common.SocketLine;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public final class PubServer {
    private static final int DEFAULT_PORT = 5000;
    private static final int DEFAULT_TABLES = 5;
    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int port;
    private final PubState state;
    private final AtomicInteger connectionCounter = new AtomicInteger(0);

    private PubServer(int port, int tableCount) {
        this.port = port;
        this.state = new PubState(tableCount);
    }

    public static void main(String[] args) throws IOException {
        CommandLineArgs parsedArgs = CommandLineArgs.parse(args);
        int port = parsedArgs.getInt("port", DEFAULT_PORT);
        int tableCount = parsedArgs.getInt("tables", DEFAULT_TABLES);

        new PubServer(port, tableCount).start();
    }

    private void start() throws IOException {
        log("Pub avviato sulla porta " + port + " con " + state.capacity() + " tavoli");

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket socket = serverSocket.accept();
                int connectionId = connectionCounter.incrementAndGet();
                // Ogni richiesta del cameriere viene servita in un thread dedicato.
                Thread handler = new Thread(
                        () -> handleConnection(socket, connectionId),
                        "pub-handler-" + connectionId
                );
                handler.start();
            }
        }
    }

    private void handleConnection(Socket socket, int connectionId) {
        try (Socket currentSocket = socket;
             BufferedReader reader = SocketLine.reader(currentSocket);
             PrintWriter writer = SocketLine.writer(currentSocket)) {

            Message request = SocketLine.receive(reader);
            Message response = handleRequest(request);
            SocketLine.send(writer, response);
        } catch (EOFException ex) {
            log("Connessione " + connectionId + " chiusa prima della richiesta");
        } catch (Exception ex) {
            log("Errore sulla connessione " + connectionId + ": " + ex.getMessage());
        }
    }

    private Message handleRequest(Message request) throws InterruptedException {
        switch (request.getCommand()) {
            case "SEAT_REQUEST":
                return state.assignTable(request.require("customer"));
            case "PREPARE_ORDER":
                return prepareOrder(request.requireInt("table"), request.require("item"));
            case "LEAVE":
                return state.releaseTable(request.requireInt("table"));
            case "STATUS":
                return state.status();
            case "PING":
                return Message.builder("PONG").build();
            default:
                return error("Comando non riconosciuto dal pub: " + request.getCommand());
        }
    }

    private Message prepareOrder(int table, String itemCode) throws InterruptedException {
        String normalizedItem = Menu.normalize(itemCode);

        if (!state.isOccupied(table)) {
            return Message.builder("ORDER_REJECTED")
                    .put("reason", "Tavolo non occupato")
                    .build();
        }

        if (!Menu.contains(normalizedItem)) {
            return Message.builder("ORDER_REJECTED")
                    .put("reason", "Piatto non presente nel menu")
                    .build();
        }

        int preparationMs = ThreadLocalRandom.current().nextInt(1200, 3501);
        log("Preparazione ordine tavolo " + table + ": " + normalizedItem);
        // Il tempo casuale simula la preparazione reale senza bloccare gli altri thread.
        Thread.sleep(preparationMs);
        log("Ordine pronto tavolo " + table + ": " + normalizedItem);

        return Message.builder("ORDER_READY")
                .put("table", table)
                .put("item", normalizedItem)
                .put("preparationMs", preparationMs)
                .build();
    }

    private static Message error(String message) {
        return Message.builder("ERROR").put("message", message).build();
    }

    private static void log(String message) {
        System.out.println("[" + LOG_FORMAT.format(LocalDateTime.now()) + "] " + message);
    }

    private static final class PubState {
        private final Queue<Integer> availableTables = new ArrayDeque<>();
        private final Map<Integer, String> occupiedTables = new HashMap<>();
        private final int capacity;

        private PubState(int tableCount) {
            if (tableCount <= 0) {
                throw new IllegalArgumentException("Il numero di tavoli deve essere positivo");
            }

            this.capacity = tableCount;
            for (int table = 1; table <= tableCount; table++) {
                availableTables.add(table);
            }
        }

        private synchronized int capacity() {
            return capacity;
        }

        private synchronized Message assignTable(String customerName) {
            // Il lock dell'oggetto impedisce che due thread assegnino lo stesso tavolo.
            if (availableTables.isEmpty()) {
                return Message.builder("SEAT_DENIED")
                        .put("reason", "Nessun tavolo disponibile")
                        .build();
            }

            int table = availableTables.remove();
            occupiedTables.put(table, customerName);

            return Message.builder("SEAT_GRANTED")
                    .put("table", table)
                    .put("availableTables", availableTables.size())
                    .build();
        }

        private synchronized Message releaseTable(int table) {
            if (!occupiedTables.containsKey(table)) {
                return Message.builder("LEAVE_REJECTED")
                        .put("reason", "Tavolo non occupato")
                        .build();
            }

            occupiedTables.remove(table);
            availableTables.add(table);

            return Message.builder("LEAVE_OK")
                    .put("table", table)
                    .put("availableTables", availableTables.size())
                    .build();
        }

        private synchronized boolean isOccupied(int table) {
            return occupiedTables.containsKey(table);
        }

        private synchronized Message status() {
            return Message.builder("STATUS_OK")
                    .put("capacity", capacity)
                    .put("occupiedTables", occupiedTables.size())
                    .put("availableTables", availableTables.size())
                    .build();
        }
    }
}
