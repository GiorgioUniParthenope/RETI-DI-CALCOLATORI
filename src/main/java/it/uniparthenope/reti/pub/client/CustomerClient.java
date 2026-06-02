package it.uniparthenope.reti.pub.client;

import it.uniparthenope.reti.pub.common.CommandLineArgs;
import it.uniparthenope.reti.pub.common.Menu;
import it.uniparthenope.reti.pub.common.Message;
import it.uniparthenope.reti.pub.common.SocketLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public final class CustomerClient {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6000;
    private static final String DEFAULT_NAME = "Cliente";

    private final String host;
    private final int port;
    private final String customerName;

    private Integer assignedTable;

    private CustomerClient(String host, int port, String customerName) {
        this.host = host;
        this.port = port;
        this.customerName = customerName;
    }

    public static void main(String[] args) throws IOException {
        CommandLineArgs parsedArgs = CommandLineArgs.parse(args);
        String host = parsedArgs.get("host", DEFAULT_HOST);
        int port = parsedArgs.getInt("port", DEFAULT_PORT);
        String customerName = parsedArgs.get("name", DEFAULT_NAME);
        String item = parsedArgs.get("item", "");

        CustomerClient client = new CustomerClient(host, port, customerName);
        if (item.trim().isEmpty() || parsedArgs.has("interactive")) {
            client.runInteractive();
        } else {
            client.runAutomatic(item);
        }
    }

    private void runAutomatic(String item) throws IOException {
        // Modalita' utile per dimostrazioni rapide e test da terminale.
        try (Socket socket = new Socket(host, port);
             BufferedReader reader = SocketLine.reader(socket);
             PrintWriter writer = SocketLine.writer(socket)) {

            readWelcome(reader);
            Message enterResponse = sendAndReceive(writer, reader, Message.builder("ENTER")
                    .put("customer", customerName)
                    .build());
            printResponse(enterResponse);

            if (!"SEAT_GRANTED".equals(enterResponse.getCommand())) {
                return;
            }

            assignedTable = enterResponse.requireInt("table");
            Message menuResponse = sendAndReceive(writer, reader, Message.builder("MENU_REQUEST")
                    .put("table", assignedTable)
                    .build());
            printResponse(menuResponse);

            Message orderResponse = sendAndReceive(writer, reader, Message.builder("ORDER")
                    .put("table", assignedTable)
                    .put("item", item)
                    .build());
            printResponse(orderResponse);

            Message exitResponse = sendAndReceive(writer, reader, Message.builder("EXIT")
                    .put("table", assignedTable)
                    .build());
            printResponse(exitResponse);
        }
    }

    private void runInteractive() throws IOException {
        try (Socket socket = new Socket(host, port);
             BufferedReader reader = SocketLine.reader(socket);
             PrintWriter writer = SocketLine.writer(socket);
             Scanner scanner = new Scanner(System.in)) {

            readWelcome(reader);
            printHelp();

            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    continue;
                }

                Message request;
                try {
                    request = buildInteractiveRequest(input);
                } catch (IllegalArgumentException ex) {
                    System.out.println(ex.getMessage());
                    continue;
                }

                Message response = sendAndReceive(writer, reader, request);
                printResponse(response);

                if ("SEAT_GRANTED".equals(response.getCommand())) {
                    assignedTable = response.requireInt("table");
                }

                if ("GOODBYE".equals(response.getCommand())) {
                    break;
                }
            }
        }
    }

    private Message buildInteractiveRequest(String input) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase();

        switch (command) {
            case "enter":
                return Message.builder("ENTER")
                        .put("customer", customerName)
                        .build();
            case "menu":
                return Message.builder("MENU_REQUEST")
                        .put("table", tableOrZero())
                        .build();
            case "order":
                if (parts.length < 2) {
                    throw new IllegalArgumentException("Indicare il codice del piatto, ad esempio: order PANINO");
                }
                return Message.builder("ORDER")
                        .put("table", tableOrZero())
                        .put("item", parts[1])
                        .build();
            case "exit":
                return Message.builder("EXIT")
                        .put("table", tableOrZero())
                        .build();
            case "help":
                printHelp();
                return Message.builder("PING").build();
            default:
                throw new IllegalArgumentException("Comando client non valido: " + input);
        }
    }

    private int tableOrZero() {
        return assignedTable == null ? 0 : assignedTable;
    }

    private void readWelcome(BufferedReader reader) throws IOException {
        Message welcome = SocketLine.receive(reader);
        if ("WELCOME".equals(welcome.getCommand())) {
            System.out.println(welcome.get("message"));
        }
    }

    private Message sendAndReceive(PrintWriter writer, BufferedReader reader, Message request) throws IOException {
        SocketLine.send(writer, request);
        return SocketLine.receive(reader);
    }

    private void printResponse(Message response) {
        switch (response.getCommand()) {
            case "SEAT_GRANTED":
                System.out.println("Ingresso accettato. Tavolo assegnato: " + response.get("table"));
                break;
            case "SEAT_DENIED":
                System.out.println("Ingresso rifiutato: " + response.get("reason"));
                break;
            case "MENU":
                System.out.print(Menu.formatForConsole(response.get("items")));
                break;
            case "ORDER_READY":
                System.out.println("Ordine pronto: " + response.get("item")
                        + " al tavolo " + response.get("table")
                        + " (" + response.get("preparationMs") + " ms)");
                break;
            case "ORDER_REJECTED":
                System.out.println("Ordine rifiutato: " + response.get("reason"));
                break;
            case "GOODBYE":
                System.out.println(response.get("message"));
                break;
            case "ERROR":
                System.out.println("Errore: " + response.get("message"));
                break;
            case "PONG":
                break;
            default:
                System.out.println(response.toLine());
                break;
        }
    }

    private void printHelp() {
        System.out.println("Comandi disponibili:");
        System.out.println(" enter          chiede al cameriere di entrare nel pub");
        System.out.println(" menu           richiede il menu");
        System.out.println(" order <codice> effettua un ordine, ad esempio order PANINO");
        System.out.println(" exit           esce dal pub e libera il tavolo");
        System.out.println(" help           mostra i comandi");
    }
}
