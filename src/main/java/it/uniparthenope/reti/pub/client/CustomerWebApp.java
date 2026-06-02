package it.uniparthenope.reti.pub.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.uniparthenope.reti.pub.common.CommandLineArgs;
import it.uniparthenope.reti.pub.common.Message;
import it.uniparthenope.reti.pub.common.SocketLine;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class CustomerWebApp {
    private static final int DEFAULT_HTTP_PORT = 7000;
    private static final int DEFAULT_WAITER_PORT = 6000;
    private static final String DEFAULT_WAITER_HOST = "localhost";
    private static final String DEFAULT_NAME = "Giorgio";
    private static final DateTimeFormatter LOG_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int httpPort;
    private final String defaultWaiterHost;
    private final int defaultWaiterPort;
    private final String defaultCustomerName;
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();

    private CustomerWebApp(
            int httpPort,
            String defaultWaiterHost,
            int defaultWaiterPort,
            String defaultCustomerName
    ) {
        this.httpPort = httpPort;
        this.defaultWaiterHost = defaultWaiterHost;
        this.defaultWaiterPort = defaultWaiterPort;
        this.defaultCustomerName = defaultCustomerName;
    }

    public static void main(String[] args) throws IOException {
        CommandLineArgs parsedArgs = CommandLineArgs.parse(args);
        int httpPort = parsedArgs.getInt("port", DEFAULT_HTTP_PORT);
        String waiterHost = parsedArgs.get("waiter-host", DEFAULT_WAITER_HOST);
        int waiterPort = parsedArgs.getInt("waiter-port", DEFAULT_WAITER_PORT);
        String customerName = parsedArgs.get("name", DEFAULT_NAME);

        new CustomerWebApp(httpPort, waiterHost, waiterPort, customerName).start();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/enter", this::handleEnter);
        server.createContext("/api/menu", this::handleMenu);
        server.createContext("/api/order", this::handleOrder);
        server.createContext("/api/exit", this::handleExit);
        server.createContext("/api/health", this::handleHealth);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        log("Webapp cliente avviata su http://localhost:" + httpPort);
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
            sendText(exchange, 404, "Risorsa non trovata");
            return;
        }

        sendHtml(exchange, htmlPage());
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, "{"
                + "\"ok\":true,"
                + "\"activeSessions\":" + sessions.size()
                + "}");
    }

    private void handleEnter(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }

        Map<String, String> form = readForm(exchange);
        String oldSessionId = form.get("sessionId");
        closeSession(oldSessionId);

        String host = valueOrDefault(form.get("host"), defaultWaiterHost);
        int port = parseInt(form.get("port"), defaultWaiterPort);
        String customerName = valueOrDefault(form.get("name"), defaultCustomerName);
        String sessionId = UUID.randomUUID().toString();
        BrowserSession session = null;

        try {
            session = BrowserSession.connect(host, port, customerName);
            sessions.put(sessionId, session);

            Message enterResponse = session.sendAndReceive(Message.builder("ENTER")
                    .put("customer", customerName)
                    .build());

            if ("SEAT_GRANTED".equals(enterResponse.getCommand())) {
                session.assignedTable = enterResponse.requireInt("table");
            } else {
                session.close();
                sessions.remove(sessionId);
            }

            sendJson(exchange, 200, actionJson(true, sessionId, session, enterResponse, null));
        } catch (Exception ex) {
            if (session != null) {
                session.close();
            }
            sessions.remove(sessionId);
            sendJson(exchange, 200, errorJson(rootMessage(ex)));
        }
    }

    private void handleMenu(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }

        Map<String, String> form = readForm(exchange);
        String sessionId = form.get("sessionId");
        BrowserSession session = sessionById(sessionId);
        if (session == null) {
            sendJson(exchange, 200, errorJson("Sessione non attiva"));
            return;
        }

        try {
            Message response = session.sendAndReceive(Message.builder("MENU_REQUEST")
                    .put("table", tableOrZero(session))
                    .build());
            List<MenuItem> items = "MENU".equals(response.getCommand())
                    ? parseMenu(response.get("items"))
                    : new ArrayList<>();
            sendJson(exchange, 200, actionJson(true, sessionId, session, response, items));
        } catch (Exception ex) {
            sendJson(exchange, 200, errorJson(rootMessage(ex)));
        }
    }

    private void handleOrder(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }

        Map<String, String> form = readForm(exchange);
        BrowserSession session = sessionById(form.get("sessionId"));
        if (session == null) {
            sendJson(exchange, 200, errorJson("Sessione non attiva"));
            return;
        }

        String item = valueOrDefault(form.get("item"), "");
        if (item.isEmpty()) {
            sendJson(exchange, 200, errorJson("Selezionare un piatto"));
            return;
        }

        try {
            Message response = session.sendAndReceive(Message.builder("ORDER")
                    .put("table", tableOrZero(session))
                    .put("item", item)
                    .build());
            sendJson(exchange, 200, actionJson(true, form.get("sessionId"), session, response, null));
        } catch (Exception ex) {
            sendJson(exchange, 200, errorJson(rootMessage(ex)));
        }
    }

    private void handleExit(HttpExchange exchange) throws IOException {
        if (!requirePost(exchange)) {
            return;
        }

        Map<String, String> form = readForm(exchange);
        String sessionId = form.get("sessionId");
        BrowserSession session = sessionById(sessionId);
        if (session == null) {
            sendJson(exchange, 200, "{"
                    + "\"ok\":true,"
                    + "\"command\":\"GOODBYE\","
                    + "\"message\":\"Sessione gia' chiusa\","
                    + "\"connected\":false"
                    + "}");
            return;
        }

        try {
            Message response = session.sendAndReceive(Message.builder("EXIT")
                    .put("table", tableOrZero(session))
                    .build());
            session.assignedTable = null;
            session.close();
            sessions.remove(sessionId);
            sendJson(exchange, 200, actionJson(true, sessionId, session, response, null));
        } catch (Exception ex) {
            sendJson(exchange, 200, errorJson(rootMessage(ex)));
        } finally {
            closeSession(sessionId);
        }
    }

    private boolean requirePost(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, errorJson("Metodo non consentito"));
            return false;
        }
        return true;
    }

    private BrowserSession sessionById(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        return sessions.get(sessionId);
    }

    private void closeSession(String sessionId) {
        BrowserSession session = sessionById(sessionId);
        if (session != null) {
            session.close();
            sessions.remove(sessionId);
        }
    }

    private int tableOrZero(BrowserSession session) {
        return session.assignedTable == null ? 0 : session.assignedTable;
    }

    private Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String body = readRequestBody(exchange.getRequestBody());
        Map<String, String> values = new LinkedHashMap<>();

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

    private List<MenuItem> parseMenu(String protocolMenu) {
        List<MenuItem> items = new ArrayList<>();
        if (protocolMenu == null || protocolMenu.trim().isEmpty()) {
            return items;
        }

        String[] entries = protocolMenu.split(";");
        for (String entry : entries) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 2) {
                items.add(new MenuItem(parts[0], parts[1]));
            }
        }

        return items;
    }

    private String actionJson(
            boolean ok,
            String sessionId,
            BrowserSession session,
            Message response,
            List<MenuItem> menuItems
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("{");
        builder.append("\"ok\":").append(ok).append(",");
        builder.append("\"sessionId\":\"").append(jsonEscape(sessionId)).append("\",");
        builder.append("\"command\":\"").append(jsonEscape(response.getCommand())).append("\",");
        builder.append("\"connected\":").append(session != null && session.isConnected()).append(",");
        builder.append("\"customer\":\"").append(jsonEscape(session == null ? "" : session.customerName)).append("\",");
        builder.append("\"table\":").append(session == null || session.assignedTable == null ? "null" : session.assignedTable).append(",");
        builder.append("\"message\":\"").append(jsonEscape(responseMessage(response))).append("\",");
        builder.append("\"raw\":\"").append(jsonEscape(response.toLine())).append("\"");

        if (menuItems != null) {
            builder.append(",\"items\":[");
            for (int index = 0; index < menuItems.size(); index++) {
                if (index > 0) {
                    builder.append(",");
                }
                MenuItem item = menuItems.get(index);
                builder.append("{")
                        .append("\"code\":\"").append(jsonEscape(item.code)).append("\",")
                        .append("\"label\":\"").append(jsonEscape(item.label)).append("\"")
                        .append("}");
            }
            builder.append("]");
        }

        builder.append("}");
        return builder.toString();
    }

    private String responseMessage(Message response) {
        switch (response.getCommand()) {
            case "SEAT_GRANTED":
                return "Ingresso accettato. Tavolo " + response.get("table") + " assegnato.";
            case "SEAT_DENIED":
                return "Ingresso rifiutato: " + response.get("reason");
            case "MENU":
                return "Menu caricato.";
            case "ORDER_READY":
                return "Ordine pronto: " + response.get("item") + " al tavolo " + response.get("table")
                        + " (" + response.get("preparationMs") + " ms).";
            case "ORDER_REJECTED":
                return "Ordine rifiutato: " + response.get("reason");
            case "GOODBYE":
                return valueOrDefault(response.get("message"), "Sessione chiusa");
            case "ERROR":
                return "Errore: " + response.get("message");
            default:
                return response.toLine();
        }
    }

    private String errorJson(String message) {
        return "{"
                + "\"ok\":false,"
                + "\"connected\":false,"
                + "\"message\":\"" + jsonEscape(message) + "\""
                + "}";
    }

    private String htmlPage() {
        return "<!doctype html>"
                + "<html lang=\"it\">"
                + "<head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Gestione Pub</title>"
                + "<style>"
                + ":root{--bg:#f5f6f1;--ink:#1f2824;--muted:#66736e;--line:#d8d9d2;--green:#2f6f5e;--coral:#b95d3f;--blue:#465b8a;--white:#fff;--soft:#eef1ea;}"
                + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font-family:Arial,Helvetica,sans-serif;letter-spacing:0;}"
                + ".top{background:#314c40;color:var(--white);padding:22px 24px;display:flex;align-items:center;justify-content:space-between;gap:16px;}"
                + ".brand h1{margin:0;font-size:28px;line-height:1.1}.brand p{margin:6px 0 0;color:#e5ece5;font-size:14px}.pill{border:1px solid rgba(255,255,255,.35);padding:8px 12px;border-radius:6px;color:#ffe4bd;font-weight:700;white-space:nowrap;}"
                + ".shell{max-width:1120px;margin:0 auto;padding:22px;display:grid;grid-template-columns:minmax(300px,420px) minmax(0,1fr);gap:18px;}"
                + ".panel{background:var(--white);border:1px solid var(--line);border-radius:8px;padding:16px;}.panel h2{font-size:18px;margin:0 0 14px;}"
                + ".form{display:grid;grid-template-columns:1fr 96px;gap:12px}.field{display:flex;flex-direction:column;gap:6px}.wide{grid-column:1/-1}label{font-size:13px;font-weight:700;color:#35423d}input,select{width:100%;border:1px solid #c9ccc2;border-radius:6px;padding:10px 11px;font-size:15px;background:#fff;color:var(--ink);}"
                + ".actions{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:14px}button{border:0;border-radius:6px;padding:11px 12px;font-size:15px;font-weight:700;color:#fff;cursor:pointer}button:disabled{background:#a7aaa4!important;cursor:not-allowed}.primary{background:var(--green)}.secondary{background:var(--blue)}.order{background:var(--coral)}.neutral{background:#6f7370}"
                + ".status{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.metric{background:var(--soft);border:1px solid var(--line);border-radius:8px;padding:14px}.metric span{display:block;color:var(--muted);font-size:12px;font-weight:700;text-transform:uppercase}.metric strong{display:block;margin-top:6px;font-size:20px;word-break:break-word}"
                + ".menu-list{margin-top:14px;display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:10px}.menu-item{border:1px solid var(--line);border-radius:8px;padding:12px;background:#fff}.menu-item strong{display:block}.menu-item span{display:block;color:var(--muted);font-size:13px;margin-top:4px}"
                + ".row{display:grid;grid-template-columns:1fr;gap:18px}.notice{min-height:56px;border-left:4px solid var(--green);background:#f7faf6;padding:14px;color:#33423c;font-size:16px}.progress{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}.step{border:1px solid var(--line);background:#fff;border-radius:8px;padding:12px;text-align:center;color:var(--muted);font-weight:700}.step.active{border-color:var(--green);background:#eef6f0;color:#244d3e}"
                + "@media(max-width:820px){.top{align-items:flex-start;flex-direction:column}.shell{grid-template-columns:1fr;padding:14px}.status{grid-template-columns:1fr}.actions{grid-template-columns:1fr}.form{grid-template-columns:1fr}}"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<header class=\"top\"><div class=\"brand\"><h1>Gestione Pub</h1><p>Client web locale per la simulazione con socket TCP</p></div><div class=\"pill\" id=\"headerStatus\">Non connesso</div></header>"
                + "<main class=\"shell\">"
                + "<section class=\"panel\"><h2>Sessione cliente</h2>"
                + "<div class=\"form\">"
                + "<div class=\"field\"><label for=\"host\">Cameriere</label><input id=\"host\" value=\"" + htmlEscape(defaultWaiterHost) + "\"></div>"
                + "<div class=\"field\"><label for=\"port\">Porta</label><input id=\"port\" value=\"" + defaultWaiterPort + "\" inputmode=\"numeric\"></div>"
                + "<div class=\"field wide\"><label for=\"name\">Cliente</label><input id=\"name\" value=\"" + htmlEscape(defaultCustomerName) + "\"></div>"
                + "<div class=\"field wide\"><label for=\"item\">Piatto</label><select id=\"item\" disabled><option value=\"\">Menu non caricato</option></select></div>"
                + "</div>"
                + "<div class=\"actions\">"
                + "<button class=\"primary\" id=\"enterBtn\">Entra</button>"
                + "<button class=\"secondary\" id=\"menuBtn\" disabled>Menu</button>"
                + "<button class=\"order\" id=\"orderBtn\" disabled>Ordina</button>"
                + "<button class=\"neutral\" id=\"exitBtn\" disabled>Esci</button>"
                + "</div>"
                + "</section>"
                + "<section class=\"row\">"
                + "<div class=\"status\">"
                + "<div class=\"metric\"><span>Connessione</span><strong id=\"connectionValue\">Chiusa</strong></div>"
                + "<div class=\"metric\"><span>Tavolo</span><strong id=\"tableValue\">-</strong></div>"
                + "<div class=\"metric\"><span>Ordine</span><strong id=\"orderValue\">-</strong></div>"
                + "</div>"
                + "<div class=\"panel\"><h2>Esito</h2><div class=\"notice\" id=\"notice\">Inserisci il nome cliente e premi Entra.</div><div class=\"menu-list\" id=\"menuList\"></div></div>"
                + "<div class=\"panel\"><h2>Percorso</h2><div class=\"progress\"><div class=\"step active\" data-step=\"enter\">Ingresso</div><div class=\"step\" data-step=\"menu\">Menu</div><div class=\"step\" data-step=\"order\">Ordine</div><div class=\"step\" data-step=\"exit\">Uscita</div></div></div>"
                + "</section>"
                + "</main>"
                + "<script>"
                + "let sessionId='';let connected=false;let hasMenu=false;"
                + "const $=id=>document.getElementById(id);"
                + "function params(obj){const p=new URLSearchParams();Object.keys(obj).forEach(k=>p.append(k,obj[k]||''));return p;}"
                + "function setStep(step){document.querySelectorAll('.step').forEach(el=>el.classList.toggle('active',el.dataset.step===step));}"
                + "function setBusy(b){['enterBtn','menuBtn','orderBtn','exitBtn'].forEach(id=>$(id).disabled=b);if(!b)updateButtons();}"
                + "function updateButtons(){ $('enterBtn').disabled=connected; $('menuBtn').disabled=!connected; $('orderBtn').disabled=!connected||!hasMenu; $('exitBtn').disabled=!connected; $('item').disabled=!hasMenu; }"
                + "function clearMenu(){hasMenu=false;$('item').innerHTML='<option value=\"\">Menu non caricato</option>';$('menuList').innerHTML='';}"
                + "function renderState(data){if(data.sessionId)sessionId=data.sessionId;if(typeof data.connected==='boolean')connected=data.connected;if(data.command==='SEAT_GRANTED'||!connected){clearMenu();$('orderValue').textContent='-';}if(data.command==='SEAT_GRANTED')setStep('menu');if(data.command==='MENU')setStep('order');if(data.command==='ORDER_READY')setStep('exit');if(data.command==='GOODBYE')setStep('enter');$('headerStatus').textContent=connected?'Connesso':'Non connesso';$('connectionValue').textContent=connected?'Aperta':'Chiusa';$('tableValue').textContent=data.table?data.table:'-';$('notice').textContent=data.message||'';if(data.command==='ORDER_READY')$('orderValue').textContent=data.message;updateButtons();}"
                + "function renderMenu(items){const list=$('menuList');const select=$('item');list.innerHTML='';select.innerHTML='';if(!items||!items.length){select.innerHTML='<option value=\"\">Menu non disponibile</option>';hasMenu=false;updateButtons();return;}items.forEach(item=>{const opt=document.createElement('option');opt.value=item.code;opt.textContent=item.code+' - '+item.label;select.appendChild(opt);const div=document.createElement('div');div.className='menu-item';div.innerHTML='<strong>'+item.code+'</strong><span>'+item.label+'</span>';list.appendChild(div);});hasMenu=true;updateButtons();}"
                + "async function post(path,body,waiting){setBusy(true);if(waiting)$('notice').textContent=waiting;try{const res=await fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:params(body)});const data=await res.json();if(!data.ok){$('notice').textContent=data.message||'Errore';return data;}renderState(data);if(data.items)renderMenu(data.items);return data;}finally{setBusy(false);}}"
                + "$('enterBtn').onclick=()=>post('/api/enter',{sessionId,host:$('host').value,port:$('port').value,name:$('name').value},'Richiesta ingresso in corso...');"
                + "$('menuBtn').onclick=()=>post('/api/menu',{sessionId},'Caricamento menu...');"
                + "$('orderBtn').onclick=()=>post('/api/order',{sessionId,item:$('item').value},'Ordine inviato al pub. Attendere la preparazione.');"
                + "$('exitBtn').onclick=async()=>{const data=await post('/api/exit',{sessionId});connected=false;hasMenu=false;sessionId='';$('item').innerHTML='<option value=\"\">Menu non caricato</option>';$('menuList').innerHTML='';$('tableValue').textContent='-';$('orderValue').textContent='-';updateButtons();};"
                + "updateButtons();"
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

    private String valueOrDefault(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(valueOrDefault(value, String.valueOf(defaultValue)));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("Codifica UTF-8 non disponibile", ex);
        }
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

    private String htmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String rootMessage(Exception ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void log(String message) {
        System.out.println("[" + LOG_FORMAT.format(LocalDateTime.now()) + "] " + message);
    }

    private static final class BrowserSession {
        private final String customerName;
        private final Socket socket;
        private final BufferedReader reader;
        private final PrintWriter writer;
        private Integer assignedTable;

        private BrowserSession(String customerName, Socket socket, BufferedReader reader, PrintWriter writer) {
            this.customerName = customerName;
            this.socket = socket;
            this.reader = reader;
            this.writer = writer;
        }

        private static BrowserSession connect(String host, int port, String customerName) throws IOException {
            Socket socket = new Socket(host, port);
            BufferedReader reader = SocketLine.reader(socket);
            PrintWriter writer = SocketLine.writer(socket);
            SocketLine.receive(reader);
            return new BrowserSession(customerName, socket, reader, writer);
        }

        private synchronized Message sendAndReceive(Message message) throws IOException {
            SocketLine.send(writer, message);
            return SocketLine.receive(reader);
        }

        private boolean isConnected() {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }

        private void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // La chiusura della socket non richiede ulteriori azioni.
            }
        }
    }

    private static final class MenuItem {
        private final String code;
        private final String label;

        private MenuItem(String code, String label) {
            this.code = code;
            this.label = label;
        }
    }
}
