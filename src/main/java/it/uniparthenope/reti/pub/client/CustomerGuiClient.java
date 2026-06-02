package it.uniparthenope.reti.pub.client;

import it.uniparthenope.reti.pub.common.CommandLineArgs;
import it.uniparthenope.reti.pub.common.Menu;
import it.uniparthenope.reti.pub.common.Message;
import it.uniparthenope.reti.pub.common.SocketLine;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public final class CustomerGuiClient {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6000;
    private static final String DEFAULT_NAME = "Giorgio";

    private final JFrame frame = new JFrame("Gestione Pub - Cliente");
    private final JTextField hostField = new JTextField(DEFAULT_HOST, 14);
    private final JTextField portField = new JTextField(String.valueOf(DEFAULT_PORT), 6);
    private final JTextField nameField = new JTextField(DEFAULT_NAME, 14);
    private final JLabel connectionLabel = new JLabel("Non connesso");
    private final JLabel tableLabel = new JLabel("Tavolo: -");
    private final JComboBox<String> menuCombo = new JComboBox<>();
    private final JTextArea logArea = new JTextArea();

    private final JButton connectButton = new JButton("Connetti");
    private final JButton enterButton = new JButton("Entra");
    private final JButton menuButton = new JButton("Menu");
    private final JButton orderButton = new JButton("Ordina");
    private final JButton exitButton = new JButton("Esci");

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private Integer assignedTable;
    private boolean busy;

    public static void main(String[] args) {
        CommandLineArgs parsedArgs = CommandLineArgs.parse(args);

        SwingUtilities.invokeLater(() -> {
            setLookAndFeel();
            CustomerGuiClient gui = new CustomerGuiClient();
            gui.hostField.setText(parsedArgs.get("host", DEFAULT_HOST));
            gui.portField.setText(String.valueOf(parsedArgs.getInt("port", DEFAULT_PORT)));
            gui.nameField.setText(parsedArgs.get("name", DEFAULT_NAME));
            gui.show();
        });
    }

    private CustomerGuiClient() {
        buildInterface();
        bindActions();
        updateButtonState();
    }

    private static void setLookAndFeel() {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (Exception ignored) {
            // Se Nimbus non e' disponibile viene usato il look and feel predefinito.
        }
    }

    private void show() {
        frame.setMinimumSize(new Dimension(820, 560));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void buildInterface() {
        Color background = new Color(246, 247, 241);
        Color header = new Color(52, 75, 65);
        Color accent = new Color(178, 92, 58);
        Color panelBorder = new Color(213, 214, 204);

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(background);

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(header);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));

        JLabel title = new JLabel("Gestione Pub");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));

        JLabel subtitle = new JLabel("Client grafico per ordinazioni tramite socket TCP");
        subtitle.setForeground(new Color(229, 235, 225));
        subtitle.setFont(subtitle.getFont().deriveFont(14f));

        JPanel titleBox = new JPanel(new BorderLayout(0, 4));
        titleBox.setOpaque(false);
        titleBox.add(title, BorderLayout.NORTH);
        titleBox.add(subtitle, BorderLayout.SOUTH);

        JPanel statusBox = new JPanel(new GridBagLayout());
        statusBox.setOpaque(false);
        connectionLabel.setForeground(Color.WHITE);
        tableLabel.setForeground(new Color(255, 226, 176));
        tableLabel.setFont(tableLabel.getFont().deriveFont(Font.BOLD));
        statusBox.add(connectionLabel);
        statusBox.add(tableLabel);

        headerPanel.add(titleBox, BorderLayout.WEST);
        headerPanel.add(statusBox, BorderLayout.EAST);

        JPanel mainPanel = new JPanel(new BorderLayout(18, 18));
        mainPanel.setBackground(background);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(18, 22, 22, 22));

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBackground(Color.WHITE);
        formPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(panelBorder),
                BorderFactory.createEmptyBorder(16, 16, 16, 16)
        ));

        addFormRow(formPanel, 0, "Host", hostField);
        addFormRow(formPanel, 1, "Porta", portField);
        addFormRow(formPanel, 2, "Nome", nameField);
        addFormRow(formPanel, 3, "Ordine", menuCombo);

        JPanel commandPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        commandPanel.setOpaque(false);
        commandPanel.add(connectButton);
        commandPanel.add(enterButton);
        commandPanel.add(menuButton);
        commandPanel.add(orderButton);
        commandPanel.add(exitButton);

        GridBagConstraints commandConstraints = new GridBagConstraints();
        commandConstraints.gridx = 0;
        commandConstraints.gridy = 4;
        commandConstraints.gridwidth = 2;
        commandConstraints.anchor = GridBagConstraints.WEST;
        commandConstraints.insets = new Insets(12, 0, 0, 0);
        formPanel.add(commandPanel, commandConstraints);

        styleButton(connectButton, accent, Color.WHITE);
        styleButton(enterButton, new Color(73, 126, 106), Color.WHITE);
        styleButton(menuButton, new Color(91, 102, 140), Color.WHITE);
        styleButton(orderButton, new Color(178, 92, 58), Color.WHITE);
        styleButton(exitButton, new Color(115, 115, 115), Color.WHITE);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        logArea.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createLineBorder(panelBorder));

        mainPanel.add(formPanel, BorderLayout.NORTH);
        mainPanel.add(logScroll, BorderLayout.CENTER);

        frame.add(headerPanel, BorderLayout.NORTH);
        frame.add(mainPanel, BorderLayout.CENTER);
    }

    private void addFormRow(JPanel panel, int row, String label, java.awt.Component component) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = new Insets(6, 0, 6, 12);

        JLabel fieldLabel = new JLabel(label);
        fieldLabel.setFont(fieldLabel.getFont().deriveFont(Font.BOLD));
        panel.add(fieldLabel, labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1.0;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = new Insets(6, 0, 6, 0);
        panel.add(component, fieldConstraints);
    }

    private void styleButton(JButton button, Color background, Color foreground) {
        button.setBackground(background);
        button.setForeground(foreground);
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD));
        button.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
    }

    private void bindActions() {
        connectButton.addActionListener(event -> {
            if (isConnected()) {
                disconnect();
            } else {
                runNetworkTask("Connessione al cameriere", this::connect);
            }
        });

        enterButton.addActionListener(event -> runNetworkTask("Richiesta ingresso", this::enterPub));
        menuButton.addActionListener(event -> runNetworkTask("Richiesta menu", this::requestMenu));
        orderButton.addActionListener(event -> runNetworkTask("Invio ordine", this::sendOrder));
        exitButton.addActionListener(event -> runNetworkTask("Uscita dal pub", this::exitPub));

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                disconnect();
                frame.dispose();
            }
        });
    }

    private void connect() throws IOException {
        String host = textOf(hostField).trim();
        int port = Integer.parseInt(textOf(portField).trim());

        socket = new Socket(host, port);
        reader = SocketLine.reader(socket);
        writer = SocketLine.writer(socket);

        Message welcome = SocketLine.receive(reader);
        appendLog("< " + welcome.toLine());
        setConnectionText("Connesso a " + host + ":" + port);
    }

    private void enterPub() throws IOException {
        String customerName = textOf(nameField).trim();
        if (customerName.isEmpty()) {
            throw new IllegalArgumentException("Inserire il nome del cliente");
        }

        Message response = sendAndReceive(Message.builder("ENTER")
                .put("customer", customerName)
                .build());

        if ("SEAT_GRANTED".equals(response.getCommand())) {
            assignedTable = response.requireInt("table");
            setTableText("Tavolo: " + assignedTable);
        }

        handleResponse(response);
    }

    private void requestMenu() throws IOException {
        requireTable();
        Message response = sendAndReceive(Message.builder("MENU_REQUEST")
                .put("table", assignedTable)
                .build());

        if ("MENU".equals(response.getCommand())) {
            fillMenu(response.get("items"));
        }

        handleResponse(response);
    }

    private void sendOrder() throws IOException {
        requireTable();
        Object selectedItem = selectedMenuItem();
        if (selectedItem == null || selectedItem.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("Richiedere il menu e selezionare un piatto");
        }

        Message response = sendAndReceive(Message.builder("ORDER")
                .put("table", assignedTable)
                .put("item", selectedItem.toString())
                .build());
        handleResponse(response);
    }

    private void exitPub() throws IOException {
        Message response = sendAndReceive(Message.builder("EXIT")
                .put("table", assignedTable == null ? 0 : assignedTable)
                .build());
        handleResponse(response);
        assignedTable = null;
        setTableText("Tavolo: -");
        closeConnection();
        setConnectionText("Non connesso");
    }

    private Message sendAndReceive(Message request) throws IOException {
        if (!isConnected()) {
            throw new IllegalStateException("Connessione non attiva");
        }

        appendLog("> " + request.toLine());
        SocketLine.send(writer, request);
        Message response = SocketLine.receive(reader);
        appendLog("< " + response.toLine());
        return response;
    }

    private void handleResponse(Message response) {
        switch (response.getCommand()) {
            case "SEAT_GRANTED":
                appendLog("Ingresso accettato. Tavolo assegnato: " + response.get("table"));
                break;
            case "SEAT_DENIED":
                appendLog("Ingresso rifiutato: " + response.get("reason"));
                break;
            case "MENU":
                appendLog("Menu ricevuto.");
                appendLog(Menu.formatForConsole(response.get("items")).trim());
                break;
            case "ORDER_READY":
                appendLog("Ordine pronto: " + response.get("item")
                        + " al tavolo " + response.get("table")
                        + " (" + response.get("preparationMs") + " ms)");
                break;
            case "ORDER_REJECTED":
                appendLog("Ordine rifiutato: " + response.get("reason"));
                break;
            case "GOODBYE":
                appendLog(response.get("message"));
                break;
            case "ERROR":
                appendLog("Errore: " + response.get("message"));
                break;
            default:
                appendLog(response.toLine());
                break;
        }
    }

    private void fillMenu(String protocolMenu) {
        List<String> itemCodes = new ArrayList<>();
        String[] entries = protocolMenu.split(";");

        for (String entry : entries) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2) {
                itemCodes.add(parts[0]);
            }
        }

        runOnUiAndWait(() -> {
            menuCombo.removeAllItems();
            for (String itemCode : itemCodes) {
                menuCombo.addItem(itemCode);
            }
        });
    }

    private void requireTable() {
        if (assignedTable == null) {
            throw new IllegalStateException("Entrare nel pub prima di continuare");
        }
    }

    private void runNetworkTask(String description, NetworkAction action) {
        busy = true;
        updateButtonState();
        appendLog(description + "...");

        SwingWorker<Void, Void> worker = new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                action.execute();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    appendLog("Errore: " + rootMessage(ex));
                    if (!isConnected()) {
                        connectionLabel.setText("Non connesso");
                    }
                } finally {
                    busy = false;
                    updateButtonState();
                }
            }
        };
        worker.execute();
    }

    private void updateButtonState() {
        boolean connected = isConnected();
        connectButton.setText(connected ? "Disconnetti" : "Connetti");

        hostField.setEnabled(!connected && !busy);
        portField.setEnabled(!connected && !busy);
        nameField.setEnabled(!connected && !busy);

        connectButton.setEnabled(!busy);
        enterButton.setEnabled(connected && assignedTable == null && !busy);
        menuButton.setEnabled(connected && assignedTable != null && !busy);
        orderButton.setEnabled(connected && assignedTable != null && menuCombo.getItemCount() > 0 && !busy);
        exitButton.setEnabled(connected && !busy);
    }

    private boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private void disconnect() {
        closeConnection();
        assignedTable = null;
        runOnUi(() -> menuCombo.removeAllItems());
        setConnectionText("Non connesso");
        setTableText("Tavolo: -");
        updateButtonState();
        appendLog("Connessione chiusa.");
    }

    private void closeConnection() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // La chiusura della socket non richiede ulteriori azioni lato client.
        } finally {
            socket = null;
            reader = null;
            writer = null;
        }
    }

    private String textOf(JTextField textField) {
        final String[] value = new String[1];
        runOnUiAndWait(() -> value[0] = textField.getText());
        return value[0];
    }

    private Object selectedMenuItem() {
        final Object[] value = new Object[1];
        runOnUiAndWait(() -> value[0] = menuCombo.getSelectedItem());
        return value[0];
    }

    private void setConnectionText(String text) {
        runOnUi(() -> connectionLabel.setText(text));
    }

    private void setTableText(String text) {
        runOnUi(() -> tableLabel.setText(text));
    }

    private void runOnUi(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    private void runOnUiAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }

        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception ex) {
            throw new IllegalStateException("Aggiornamento interfaccia non riuscito", ex);
        }
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private String rootMessage(Exception ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private interface NetworkAction {
        void execute() throws Exception;
    }
}
