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
import java.util.concurrent.atomic.AtomicInteger;

public final class WaiterServer {
    private static final int DEFAULT_PORT = 6000;
    private static final int DEFAULT_PUB_PORT = 5000;
    private static final String DEFAULT_PUB_HOST = "localhost";
    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int port;
    private final String pubHost;
    private final int pubPort;
    private final AtomicInteger clientCounter = new AtomicInteger(0);

    private WaiterServer(int port, String pubHost, int pubPort) {
        this.port = port;
        this.pubHost = pubHost;
        this.pubPort = pubPort;
    }

    public static void main(String[] args) throws IOException {
        CommandLineArgs parsedArgs = CommandLineArgs.parse(args);
        int port = parsedArgs.getInt("port", DEFAULT_PORT);
        String pubHost = parsedArgs.get("pub-host", DEFAULT_PUB_HOST);
        int pubPort = parsedArgs.getInt("pub-port", DEFAULT_PUB_PORT);

        new WaiterServer(port, pubHost, pubPort).start();
    }

    private void start() throws IOException {
        log("Cameriere avviato sulla porta " + port + " collegato al pub " + pubHost + ":" + pubPort);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                int clientId = clientCounter.incrementAndGet();
                // Ogni cliente ha una sessione indipendente gestita da un thread.
                Thread handler = new Thread(
                        () -> handleClient(clientSocket, clientId),
                        "waiter-client-" + clientId
                );
                handler.start();
            }
        }
    }

    private void handleClient(Socket socket, int clientId) {
        ClientSession session = new ClientSession();

        try (Socket currentSocket = socket;
             BufferedReader reader = SocketLine.reader(currentSocket);
             PrintWriter writer = SocketLine.writer(currentSocket)) {

            SocketLine.send(writer, Message.builder("WELCOME")
                    .put("message", "Connessione con il cameriere stabilita")
                    .build());

            while (true) {
                Message request = SocketLine.receive(reader);
                Message response = handleCustomerRequest(request, session);
                SocketLine.send(writer, response);

                if ("GOODBYE".equals(response.getCommand())) {
                    break;
                }
            }
        } catch (EOFException ex) {
            releaseTableIfNeeded(session);
            log("Cliente " + clientId + " disconnesso");
        } catch (Exception ex) {
            releaseTableIfNeeded(session);
            log("Errore con cliente " + clientId + ": " + ex.getMessage());
        }
    }

    private Message handleCustomerRequest(Message request, ClientSession session) {
        switch (request.getCommand()) {
            case "ENTER":
                return enterPub(request, session);
            case "MENU_REQUEST":
                return sendMenu(session);
            case "ORDER":
                return sendOrderToPub(request, session);
            case "EXIT":
                return exitPub(session);
            case "PING":
                return Message.builder("PONG").build();
            default:
                return error("Comando non riconosciuto dal cameriere: " + request.getCommand());
        }
    }

    private Message enterPub(Message request, ClientSession session) {
        if (session.hasTable()) {
            return error("Il cliente ha gia' un tavolo assegnato");
        }

        String customerName = request.require("customer");
        Message pubResponse = requestPub(Message.builder("SEAT_REQUEST")
                .put("customer", customerName)
                .build());

        if ("SEAT_GRANTED".equals(pubResponse.getCommand())) {
            session.customerName = customerName;
            session.table = pubResponse.requireInt("table");
            log("Cliente " + customerName + " assegnato al tavolo " + session.table);
        }

        return pubResponse;
    }

    private Message sendMenu(ClientSession session) {
        if (!session.hasTable()) {
            return error("Il cliente deve prima entrare nel pub");
        }

        return Message.builder("MENU")
                .put("table", session.table)
                .put("items", Menu.formatForProtocol())
                .build();
    }

    private Message sendOrderToPub(Message request, ClientSession session) {
        if (!session.hasTable()) {
            return error("Il cliente non ha un tavolo assegnato");
        }

        int requestedTable = request.requireInt("table");
        if (requestedTable != session.table) {
            return Message.builder("ORDER_REJECTED")
                    .put("reason", "Il tavolo indicato non appartiene al cliente")
                    .build();
        }

        String item = Menu.normalize(request.require("item"));
        if (!Menu.contains(item)) {
            return Message.builder("ORDER_REJECTED")
                    .put("reason", "Piatto non presente nel menu")
                    .build();
        }

        log("Invio ordine al pub: tavolo " + session.table + ", piatto " + item);
        return requestPub(Message.builder("PREPARE_ORDER")
                .put("table", session.table)
                .put("item", item)
                .build());
    }

    private Message exitPub(ClientSession session) {
        if (!session.hasTable()) {
            return Message.builder("GOODBYE")
                    .put("message", "Connessione chiusa")
                    .build();
        }

        Message pubResponse = requestPub(Message.builder("LEAVE")
                .put("table", session.table)
                .build());

        int releasedTable = session.table;
        session.clear();

        if ("LEAVE_OK".equals(pubResponse.getCommand())) {
            log("Tavolo " + releasedTable + " liberato");
            return Message.builder("GOODBYE")
                    .put("message", "Cliente uscito dal pub")
                    .build();
        }

        return pubResponse;
    }

    private void releaseTableIfNeeded(ClientSession session) {
        if (!session.hasTable()) {
            return;
        }

        requestPub(Message.builder("LEAVE")
                .put("table", session.table)
                .build());
        String customer = session.customerName == null ? "cliente" : session.customerName;
        log("Tavolo " + session.table + " liberato dopo disconnessione di " + customer);
        session.clear();
    }

    private Message requestPub(Message message) {
        // Il cameriere apre una connessione breve verso il pub per ogni richiesta.
        try (Socket socket = new Socket(pubHost, pubPort);
             BufferedReader reader = SocketLine.reader(socket);
             PrintWriter writer = SocketLine.writer(socket)) {

            SocketLine.send(writer, message);
            return SocketLine.receive(reader);
        } catch (Exception ex) {
            return error("Pub non raggiungibile: " + ex.getMessage());
        }
    }

    private static Message error(String message) {
        return Message.builder("ERROR").put("message", message).build();
    }

    private static void log(String message) {
        System.out.println("[" + LOG_FORMAT.format(LocalDateTime.now()) + "] " + message);
    }

    private static final class ClientSession {
        private String customerName;
        private Integer table;

        private boolean hasTable() {
            return table != null;
        }

        private void clear() {
            customerName = null;
            table = null;
        }
    }
}
