package it.uniparthenope.reti.pub.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.uniparthenope.reti.pub.common.CommandLineArgs;
import it.uniparthenope.reti.pub.common.Menu;
import it.uniparthenope.reti.pub.common.Message;
import it.uniparthenope.reti.pub.common.SocketLine;
import it.uniparthenope.reti.pub.common.WebAssets;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class WaiterServer {
    private static final int DEFAULT_PORT = 6000;
    private static final int DEFAULT_DASHBOARD_PORT = 7200;
    private static final int DEFAULT_PUB_PORT = 5000;
    private static final String DEFAULT_PUB_HOST = "localhost";
    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int port;
    private final String pubHost;
    private final int pubPort;
    private final int dashboardPort;
    private final Map<Integer, ClientSession> activeTables = new ConcurrentHashMap<>();
    private final AtomicInteger clientCounter = new AtomicInteger(0);

    private WaiterServer(int port, String pubHost, int pubPort, int dashboardPort) {
        this.port = port;
        this.pubHost = pubHost;
        this.pubPort = pubPort;
        this.dashboardPort = dashboardPort;
    }

    public static void main(String[] args) throws IOException {
        CommandLineArgs parsedArgs = CommandLineArgs.parse(args);
        int port = parsedArgs.getInt("port", DEFAULT_PORT);
        String pubHost = parsedArgs.get("pub-host", DEFAULT_PUB_HOST);
        int pubPort = parsedArgs.getInt("pub-port", DEFAULT_PUB_PORT);
        int dashboardPort = parsedArgs.getInt("dashboard-port", DEFAULT_DASHBOARD_PORT);

        new WaiterServer(port, pubHost, pubPort, dashboardPort).start();
    }

    private void start() throws IOException {
        log("Cameriere avviato sulla porta " + port + " collegato al pub " + pubHost + ":" + pubPort);
        startDashboard();
        log("Dashboard cameriere disponibile su http://localhost:" + dashboardPort);

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
            case "STATUS":
                return requestPub(Message.builder("STATUS").build());
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
            activeTables.put(session.table, session);
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
        activeTables.remove(releasedTable);

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
        activeTables.remove(session.table);
        session.clear();
    }

    private void startDashboard() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(dashboardPort), 0);
        server.createContext("/", this::handleDashboardIndex);
        server.createContext("/api/state", this::handleDashboardState);
        server.createContext(WebAssets.HERO_PATH, WebAssets::sendHeroImage);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private void handleDashboardIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
            sendText(exchange, 404, "Risorsa non trovata");
            return;
        }

        sendHtml(exchange, dashboardHtml());
    }

    private void handleDashboardState(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"ok\":false,\"message\":\"Metodo non consentito\"}");
            return;
        }

        sendJson(exchange, 200, waiterStateJson());
    }

    private String waiterStateJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"ok\":true,\"tables\":[");

        int index = 0;
        for (Map.Entry<Integer, ClientSession> entry : activeTables.entrySet()) {
            if (index++ > 0) {
                builder.append(",");
            }
            ClientSession session = entry.getValue();
            builder.append("{")
                    .append("\"table\":").append(entry.getKey()).append(",")
                    .append("\"customer\":\"").append(jsonEscape(session.customerName)).append("\"")
                    .append("}");
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
                + "<title>Cameriere - Gestione Pub</title>"
                + "<link rel=\"icon\" href=\"" + WebAssets.faviconDataUri() + "\">"
                + "<style>"
                + ":root{--bg:#f4f2ed;--ink:#202622;--muted:#65716c;--line:#d8d3c7;--green:#2f6f5e;--blue:#405b84;--coral:#b95d3f;--white:#fff;--soft:#eef2ec;}"
                + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font-family:Arial,Helvetica,sans-serif;letter-spacing:0}.top{min-height:190px;background:linear-gradient(90deg,rgba(24,35,30,.9),rgba(24,35,30,.55)),url('" + WebAssets.HERO_PATH + "') center/cover;color:#fff;padding:24px 26px;display:flex;align-items:flex-end;justify-content:space-between;gap:16px;flex-wrap:wrap}.top h1{margin:0;font-size:30px}.top p{margin:7px 0 0;color:#e8eee9}.pill{background:rgba(255,255,255,.12);border:1px solid rgba(255,255,255,.35);border-radius:6px;padding:9px 12px;color:#ffdfad;font-weight:700}.wrap{max-width:780px;margin:0 auto;padding:22px;display:grid;grid-template-columns:1fr;gap:18px}.panel{background:#fff;border:1px solid var(--line);border-radius:8px;padding:16px}.panel h2{margin:0 0 14px;font-size:18px}.switcher{display:grid;grid-template-columns:48px minmax(0,1fr) 48px;gap:10px;align-items:stretch}.arrow{background:var(--blue);font-size:22px;padding:0}.current-table{border:1px solid var(--line);border-radius:8px;padding:14px 16px;background:#eef6f0;min-height:70px;display:flex;justify-content:center;flex-direction:column}.current-table strong{display:block;font-size:20px}.current-table span{display:block;color:var(--muted);font-size:14px;margin-top:5px}.field{display:flex;flex-direction:column;gap:6px;margin-bottom:12px}label{font-size:13px;font-weight:700}select{width:100%;border:1px solid #c9ccc2;border-radius:6px;padding:10px;font-size:15px}button{width:100%;min-height:44px;border:0;border-radius:6px;background:var(--coral);color:#fff;font-size:15px;font-weight:700;cursor:pointer}button:disabled{background:#aaa;cursor:not-allowed}.notice{border-left:4px solid var(--blue);background:#f6f8fb;padding:14px;line-height:1.45;overflow-wrap:anywhere;margin-top:14px}.empty{border:1px dashed var(--line);border-radius:8px;padding:20px;color:var(--muted);text-align:center}"
                + "@media(max-width:860px){.top{align-items:flex-start;flex-direction:column}.wrap{grid-template-columns:1fr;padding:16px}}@media(max-width:520px){.top{padding:18px 14px}.top h1{font-size:25px}.wrap{padding:12px}.panel{padding:14px}}"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<header class=\"top\"><div><h1>Banco Cameriere</h1><p>Controllo dei tavoli attivi. Gli ordini restano nel flusso cliente e passano comunque dal cameriere.</p></div><div class=\"pill\" id=\"count\">0 tavoli occupati</div></header>"
                + "<main class=\"wrap\"><section class=\"panel\"><h2>Tavolo corrente</h2><div class=\"field\"><label for=\"tableSelect\">Tavoli attivi</label><select id=\"tableSelect\"></select></div><div class=\"switcher\"><button class=\"arrow\" id=\"prevTable\" title=\"Tavolo precedente\">&#8592;</button><div id=\"currentTable\" class=\"current-table\"></div><button class=\"arrow\" id=\"nextTable\" title=\"Tavolo successivo\">&#8594;</button></div><div class=\"notice\" id=\"notice\">Selezionare un tavolo.</div></section></main>"
                + "<script>"
                + "let selectedTable=null;let state={tables:[]};const $=id=>document.getElementById(id);function esc(v){return String(v||'').replace(/[&<>\\\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]));}"
                + "function selectedIndex(){return state.tables.findIndex(t=>t.table===selectedTable);}"
                + "function selectTable(table,showMessage){selectedTable=table;if(showMessage&&table)$('notice').textContent='Tavolo '+table+' selezionato.';draw();}"
                + "function moveTable(delta){if(!state.tables.length)return;let idx=selectedIndex();if(idx<0)idx=0;idx=(idx+delta+state.tables.length)%state.tables.length;selectTable(state.tables[idx].table,true);}"
                + "function draw(){ $('count').textContent=state.tables.length+' tavoli occupati';const tableSelect=$('tableSelect');tableSelect.innerHTML=state.tables.length?state.tables.map(t=>'<option value=\"'+t.table+'\">Tavolo '+t.table+' - '+esc(t.customer)+'</option>').join(''):'<option value=\"\">Nessun tavolo attivo</option>';if(selectedTable)tableSelect.value=String(selectedTable);const current=state.tables.find(t=>t.table===selectedTable);$('currentTable').innerHTML=current?'<strong>Tavolo '+current.table+'</strong><span>'+esc(current.customer)+'</span>':'<div class=\"empty\">Nessun tavolo selezionato.</div>';$('prevTable').disabled=!state.tables.length;$('nextTable').disabled=!state.tables.length;tableSelect.disabled=!state.tables.length;}"
                + "async function refresh(){const res=await fetch('/api/state');state=await res.json();state.tables.sort((a,b)=>a.table-b.table);if(selectedTable&&!state.tables.some(t=>t.table===selectedTable))selectedTable=null;if(!selectedTable&&state.tables.length)selectedTable=state.tables[0].table;draw();}"
                + "$('tableSelect').addEventListener('change',e=>{const table=parseInt(e.target.value,10);if(!Number.isNaN(table))selectTable(table,true);});"
                + "$('prevTable').onclick=()=>moveTable(-1);$('nextTable').onclick=()=>moveTable(1);"
                + "refresh();setInterval(refresh,1500);"
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

    private String jsonEscape(String value) {
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
