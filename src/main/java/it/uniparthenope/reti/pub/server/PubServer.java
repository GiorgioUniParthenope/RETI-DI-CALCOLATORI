package it.uniparthenope.reti.pub.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.uniparthenope.reti.pub.common.CommandLineArgs;
import it.uniparthenope.reti.pub.common.Menu;
import it.uniparthenope.reti.pub.common.Message;
import it.uniparthenope.reti.pub.common.SocketLine;
import it.uniparthenope.reti.pub.common.WebAssets;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class PubServer {
    private static final int DEFAULT_PORT = 5000;
    private static final int DEFAULT_DASHBOARD_PORT = 7100;
    private static final int DEFAULT_TABLES = 5;
    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int port;
    private final PubState state;
    private final PubDashboard dashboard;
    private final int dashboardPort;
    private final AtomicInteger connectionCounter = new AtomicInteger(0);

    private PubServer(int port, int tableCount, int dashboardPort) {
        this.port = port;
        this.dashboardPort = dashboardPort;
        this.dashboard = new PubDashboard(tableCount);
        this.state = new PubState(tableCount, dashboard);
    }

    public static void main(String[] args) throws IOException {
        CommandLineArgs parsedArgs = CommandLineArgs.parse(args);
        int port = parsedArgs.getInt("port", DEFAULT_PORT);
        int tableCount = parsedArgs.getInt("tables", DEFAULT_TABLES);
        int dashboardPort = parsedArgs.getInt("dashboard-port", DEFAULT_DASHBOARD_PORT);

        new PubServer(port, tableCount, dashboardPort).start();
    }

    private void start() throws IOException {
        log("Pub avviato sulla porta " + port + " con " + state.capacity() + " tavoli");
        dashboard.start(dashboardPort);
        log("Dashboard pub disponibile su http://localhost:" + dashboardPort);

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
        PendingOrder pendingOrder = dashboard.orderPreparing(table, normalizedItem);
        // Il tempo casuale simula la preparazione reale senza bloccare gli altri thread.
        pendingOrder.awaitCompletion(preparationMs);
        log("Ordine pronto tavolo " + table + ": " + normalizedItem);
        dashboard.orderReady(table, normalizedItem, preparationMs, pendingOrder.completedManually());

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

    private static final class PubDashboard {
        private final Map<Integer, TableReceipt> receipts = new LinkedHashMap<>();
        private final Map<String, PendingOrder> pendingOrders = new HashMap<>();
        private final AtomicInteger orderCounter = new AtomicInteger(0);
        private final int tableCount;

        private PubDashboard(int tableCount) {
            this.tableCount = tableCount;
            for (int table = 1; table <= tableCount; table++) {
                receipts.put(table, TableReceipt.empty(table));
            }
        }

        private void start(int port) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", this::handleIndex);
            server.createContext("/api/state", this::handleState);
            server.createContext("/api/complete", this::handleComplete);
            server.createContext(WebAssets.HERO_PATH, WebAssets::sendHeroImage);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }

        private void handleIndex(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
                sendText(exchange, 404, "Risorsa non trovata");
                return;
            }

            sendHtml(exchange, dashboardHtml());
        }

        private void handleState(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"ok\":false,\"message\":\"Metodo non consentito\"}");
                return;
            }

            sendJson(exchange, 200, stateJson());
        }

        private void handleComplete(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"ok\":false,\"message\":\"Metodo non consentito\"}");
                return;
            }

            Map<String, String> form = readForm(exchange);
            String orderId = form.get("orderId");
            PendingOrder pendingOrder;

            synchronized (this) {
                pendingOrder = pendingOrders.get(orderId);
            }

            if (pendingOrder == null) {
                sendJson(exchange, 200, "{\"ok\":false,\"message\":\"Ordine non trovato o gia' evaso\"}");
                return;
            }

            pendingOrder.completeManually();
            sendJson(exchange, 200, "{\"ok\":true,\"message\":\"Ordine evaso\"}");
        }

        private synchronized void customerSeated(int table, String customerName) {
            TableReceipt receipt = new TableReceipt(table, customerName);
            receipt.status = "Cliente al tavolo";
            receipt.add("Ingresso", "Cliente " + customerName + " assegnato al tavolo");
            receipts.put(table, receipt);
        }

        private synchronized PendingOrder orderPreparing(int table, String item) {
            TableReceipt receipt = receiptFor(table);
            String orderId = "ORD-" + orderCounter.incrementAndGet();
            PendingOrder pendingOrder = new PendingOrder();
            pendingOrders.put(orderId, pendingOrder);
            receipt.status = "Ordine in preparazione";
            receipt.pendingOrderId = orderId;
            receipt.add("Ordine", item + " in preparazione");
            return pendingOrder;
        }

        private synchronized void orderReady(int table, String item, int preparationMs, boolean manual) {
            TableReceipt receipt = receiptFor(table);
            pendingOrders.remove(receipt.pendingOrderId);
            receipt.pendingOrderId = "";
            receipt.status = "Ordine pronto";
            String suffix = manual ? " evaso manualmente dal pub" : " pronto dopo " + preparationMs + " ms";
            receipt.add("Cucina", item + suffix);
        }

        private synchronized void tableReleased(int table) {
            TableReceipt receipt = receiptFor(table);
            receipt.status = "Tavolo liberato";
            receipt.occupied = false;
            receipt.add("Uscita", "Tavolo liberato");
        }

        private TableReceipt receiptFor(int table) {
            if (!receipts.containsKey(table)) {
                receipts.put(table, TableReceipt.empty(table));
            }
            return receipts.get(table);
        }

        private synchronized String stateJson() {
            StringBuilder builder = new StringBuilder();
            builder.append("{\"ok\":true,\"tableCount\":").append(tableCount).append(",\"tables\":[");

            int index = 0;
            for (TableReceipt receipt : receipts.values()) {
                if (index++ > 0) {
                    builder.append(",");
                }

                builder.append("{")
                        .append("\"table\":").append(receipt.table).append(",")
                        .append("\"occupied\":").append(receipt.occupied).append(",")
                        .append("\"customer\":\"").append(jsonEscape(receipt.customerName)).append("\",")
                        .append("\"status\":\"").append(jsonEscape(receipt.status)).append("\",")
                        .append("\"pendingOrderId\":\"").append(jsonEscape(receipt.pendingOrderId)).append("\",")
                        .append("\"lines\":[");

                for (int lineIndex = 0; lineIndex < receipt.lines.size(); lineIndex++) {
                    if (lineIndex > 0) {
                        builder.append(",");
                    }
                    ReceiptLine line = receipt.lines.get(lineIndex);
                    builder.append("{")
                            .append("\"time\":\"").append(jsonEscape(line.time)).append("\",")
                            .append("\"title\":\"").append(jsonEscape(line.title)).append("\",")
                            .append("\"text\":\"").append(jsonEscape(line.text)).append("\"")
                            .append("}");
                }

                builder.append("]}");
            }

            builder.append("]}");
            return builder.toString();
        }

        private String dashboardHtml() {
            return "<!doctype html>"
                    + "<html lang=\"it\">"
                    + "<head>"
                    + "<meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                    + "<title>Dashboard Pub</title>"
                    + "<link rel=\"icon\" href=\"" + WebAssets.faviconDataUri() + "\">"
                    + "<style>"
                    + ":root{--bg:#f3f1ea;--ink:#20231f;--muted:#73756d;--green:#315f4c;--line:#d9d3c4;--paper:#fffdf6;--ready:#b95d3f;--wait:#465b8a;}"
                    + "*{box-sizing:border-box}html{min-height:100%}body{min-height:100vh;margin:0;background:var(--bg);color:var(--ink);font-family:Arial,Helvetica,sans-serif;letter-spacing:0;}"
                    + ".top{min-height:190px;background:linear-gradient(90deg,rgba(24,35,30,.9),rgba(24,35,30,.58)),url('" + WebAssets.HERO_PATH + "') center/cover;color:#fff;padding:24px 26px;display:flex;justify-content:space-between;align-items:flex-end;gap:16px;flex-wrap:wrap}.top>div:first-child{min-width:0}.top h1{margin:0;font-size:30px;line-height:1.15;overflow-wrap:anywhere}.top p{margin:6px 0 0;color:#e6ece5;line-height:1.35}.clock{background:rgba(255,255,255,.12);border:1px solid rgba(255,255,255,.35);border-radius:6px;padding:9px 12px;color:#ffdfad;font-weight:700;white-space:nowrap}"
                    + ".wrap{width:100%;max-width:1180px;margin:0 auto;padding:22px}.summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:18px}.metric{min-width:0;background:#fff;border:1px solid var(--line);border-radius:8px;padding:14px}.metric span{display:block;color:var(--muted);font-size:12px;font-weight:700;text-transform:uppercase}.metric strong{font-size:25px;margin-top:4px;display:block;overflow-wrap:anywhere}"
                    + ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:16px}.receipt{min-width:0;background:var(--paper);border:1px solid var(--line);border-radius:8px;box-shadow:0 2px 0 rgba(0,0,0,.05);padding:16px;min-height:230px}.receipt.active{border-color:#8da892}.receipt h2{margin:0;font-size:20px;line-height:1.2}.receipt .head{display:flex;justify-content:space-between;gap:10px;align-items:flex-start;border-bottom:1px dashed #bbb3a2;padding-bottom:10px;margin-bottom:10px}.status{max-width:100%;font-size:12px;font-weight:700;border-radius:20px;padding:6px 9px;background:#ece9dd;color:#4a4d46;overflow-wrap:anywhere}.active .status{background:#e3efe6;color:#23523f}.line{min-width:0;display:grid;grid-template-columns:54px minmax(0,1fr);gap:10px;padding:8px 0;border-bottom:1px dashed #ddd5c5}.line:last-child{border-bottom:0}.time{color:var(--muted);font-size:12px}.line strong{display:block;font-size:13px;overflow-wrap:anywhere}.line span{display:block;font-size:14px;line-height:1.35;margin-top:2px;overflow-wrap:anywhere}.empty{color:var(--muted);padding:20px 0}.customer{color:var(--muted);font-size:13px;margin-top:4px;overflow-wrap:anywhere}.complete{width:100%;margin-top:12px;min-height:42px;border:0;border-radius:6px;background:var(--ready);color:#fff;font-weight:700;cursor:pointer}.complete:disabled{background:#aaa;cursor:not-allowed}"
                    + "@media(max-width:760px){.top{align-items:flex-start;flex-direction:column;padding:18px 14px}.clock{white-space:normal}.wrap{padding:14px}.summary{grid-template-columns:1fr}.grid{grid-template-columns:1fr}.receipt{padding:14px}}"
                    + "@media(max-width:420px){.receipt .head{flex-direction:column}.status{align-self:flex-start}.line{grid-template-columns:1fr;gap:4px}.metric strong{font-size:22px}}"
                    + "</style>"
                    + "</head>"
                    + "<body>"
                    + "<header class=\"top\"><div><h1>Dashboard Pub</h1><p>Scontrini operativi aggiornati in tempo reale</p></div><div class=\"clock\" id=\"clock\">--:--:--</div></header>"
                    + "<main class=\"wrap\">"
                    + "<section class=\"summary\">"
                    + "<div class=\"metric\"><span>Tavoli</span><strong id=\"tables\">-</strong></div>"
                    + "<div class=\"metric\"><span>Occupati</span><strong id=\"occupied\">-</strong></div>"
                    + "<div class=\"metric\"><span>Ordini registrati</span><strong id=\"orders\">-</strong></div>"
                    + "</section>"
                    + "<section class=\"grid\" id=\"receipts\"></section>"
                    + "</main>"
                    + "<script>"
                    + "const $=id=>document.getElementById(id);"
                    + "function esc(v){return String(v||'').replace(/[&<>\\\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]));}"
                    + "function draw(data){$('tables').textContent=data.tableCount;let occupied=0,orders=0;const cards=data.tables.map(t=>{if(t.occupied)occupied++;orders+=t.lines.filter(l=>l.title==='Ordine').length;const lines=t.lines.length?t.lines.map(l=>'<div class=\"line\"><div class=\"time\">'+esc(l.time)+'</div><div><strong>'+esc(l.title)+'</strong><span>'+esc(l.text)+'</span></div></div>').join(''):'<div class=\"empty\">Nessuna operazione registrata.</div>';const action=t.pendingOrderId?'<button class=\"complete\" data-order=\"'+esc(t.pendingOrderId)+'\">Evadi ordine</button>':'';return '<article class=\"receipt '+(t.occupied?'active':'')+'\"><div class=\"head\"><div><h2>Tavolo '+t.table+'</h2><div class=\"customer\">'+(t.customer?esc(t.customer):'Libero')+'</div></div><div class=\"status\">'+esc(t.status)+'</div></div>'+lines+action+'</article>';}).join('');$('occupied').textContent=occupied;$('orders').textContent=orders;$('receipts').innerHTML=cards;}"
                    + "async function completeOrder(orderId){const body=new URLSearchParams();body.append('orderId',orderId);await fetch('/api/complete',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body});refresh();}"
                    + "$('receipts').addEventListener('click',e=>{const b=e.target.closest('button[data-order]');if(b){b.disabled=true;completeOrder(b.dataset.order);}});"
                    + "async function refresh(){try{const res=await fetch('/api/state');draw(await res.json());}catch(e){}}"
                    + "function tick(){$('clock').textContent=new Date().toLocaleTimeString();}"
                    + "tick();refresh();setInterval(tick,1000);setInterval(refresh,1000);"
                    + "</script>"
                    + "</body></html>";
        }

        private void sendHtml(HttpExchange exchange, String body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            send(exchange, 200, body);
        }

        private void sendText(HttpExchange exchange, int status, String body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            send(exchange, status, body);
        }

        private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            send(exchange, status, body);
        }

        private void send(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private Map<String, String> readForm(HttpExchange exchange) throws IOException {
            String body = readRequestBody(exchange.getRequestBody());
            Map<String, String> values = new HashMap<>();

            if (body.trim().isEmpty()) {
                return values;
            }

            String[] pairs = body.split("&");
            for (String pair : pairs) {
                int separator = pair.indexOf('=');
                if (separator < 0) {
                    values.put(decode(pair), "");
                } else {
                    values.put(
                            decode(pair.substring(0, separator)),
                            decode(pair.substring(separator + 1))
                    );
                }
            }

            return values;
        }

        private String readRequestBody(InputStream inputStream) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;

            while ((count = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }

            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }

        private String decode(String value) {
            try {
                return URLDecoder.decode(value, "UTF-8");
            } catch (UnsupportedEncodingException ex) {
                throw new IllegalStateException("Codifica UTF-8 non disponibile", ex);
            }
        }
    }

    private static final class TableReceipt {
        private final int table;
        private final List<ReceiptLine> lines = new ArrayList<>();
        private String customerName;
        private String status;
        private String pendingOrderId = "";
        private boolean occupied;

        private TableReceipt(int table, String customerName) {
            this.table = table;
            this.customerName = customerName;
            this.status = "Libero";
            this.occupied = true;
        }

        private static TableReceipt empty(int table) {
            TableReceipt receipt = new TableReceipt(table, "");
            receipt.occupied = false;
            receipt.status = "Libero";
            return receipt;
        }

        private void add(String title, String text) {
            lines.add(new ReceiptLine(LOG_FORMAT.format(LocalDateTime.now()), title, text));
        }
    }

    private static final class PendingOrder {
        private boolean completed;
        private boolean completedManually;

        private synchronized void awaitCompletion(int timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            long remaining = timeoutMs;

            while (!completed && remaining > 0) {
                wait(remaining);
                remaining = deadline - System.currentTimeMillis();
            }
            completed = true;
        }

        private synchronized void completeManually() {
            completed = true;
            completedManually = true;
            notifyAll();
        }

        private synchronized boolean completedManually() {
            return completedManually;
        }
    }

    private static final class ReceiptLine {
        private final String time;
        private final String title;
        private final String text;

        private ReceiptLine(String time, String title, String text) {
            this.time = time;
            this.title = title;
            this.text = text;
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    builder.append(current);
                    break;
            }
        }
        return builder.toString();
    }

    private static final class PubState {
        private final Queue<Integer> availableTables = new ArrayDeque<>();
        private final Map<Integer, String> occupiedTables = new HashMap<>();
        private final PubDashboard dashboard;
        private final int capacity;

        private PubState(int tableCount, PubDashboard dashboard) {
            if (tableCount <= 0) {
                throw new IllegalArgumentException("Il numero di tavoli deve essere positivo");
            }

            this.capacity = tableCount;
            this.dashboard = dashboard;
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
            dashboard.customerSeated(table, customerName);

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
            dashboard.tableReleased(table);

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
