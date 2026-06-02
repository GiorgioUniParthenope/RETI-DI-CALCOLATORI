package it.uniparthenope.reti.pub.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.uniparthenope.reti.pub.common.CommandLineArgs;
import it.uniparthenope.reti.pub.common.Message;
import it.uniparthenope.reti.pub.common.SocketLine;
import it.uniparthenope.reti.pub.common.WebAssets;

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
        server.createContext(WebAssets.HERO_PATH, WebAssets::sendHeroImage);
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
                + "<title>Sala Clienti - Gestione Pub</title>"
                + "<link rel=\"icon\" href=\"" + WebAssets.faviconDataUri() + "\">"
                + "<style>"
                + ":root{--bg:#f4f2ed;--ink:#202622;--muted:#66726d;--line:#d9d5ca;--green:#2f6f5e;--coral:#b95d3f;--blue:#405b84;--white:#fff;--soft:#eef2ec;--paper:#fffdf8;}"
                + "*{box-sizing:border-box}html{min-height:100%}body{min-height:100vh;margin:0;background:var(--bg);color:var(--ink);font-family:Arial,Helvetica,sans-serif;letter-spacing:0;}"
                + ".top{min-height:210px;background:linear-gradient(90deg,rgba(25,38,33,.88),rgba(25,38,33,.55)),url('" + WebAssets.HERO_PATH + "') center/cover;color:var(--white);padding:28px;display:flex;align-items:flex-end;justify-content:space-between;gap:18px;flex-wrap:wrap;}"
                + ".brand{min-width:0;max-width:760px}.brand h1{margin:0;font-size:34px;line-height:1.08;overflow-wrap:anywhere}.brand p{margin:8px 0 0;color:#eef4ee;font-size:15px;line-height:1.45}.pill{background:rgba(255,255,255,.12);border:1px solid rgba(255,255,255,.35);padding:9px 12px;border-radius:6px;color:#ffdfad;font-weight:700;white-space:nowrap;}"
                + ".shell{width:100%;max-width:1180px;margin:0 auto;padding:22px;display:grid;grid-template-columns:minmax(280px,380px) minmax(0,1fr);gap:18px;align-items:start;}"
                + ".panel{min-width:0;background:var(--white);border:1px solid var(--line);border-radius:8px;padding:16px;box-shadow:0 1px 0 rgba(0,0,0,.03)}.panel h2{font-size:18px;line-height:1.25;margin:0 0 14px;}"
                + ".form{display:grid;grid-template-columns:minmax(0,1fr) minmax(80px,96px);gap:12px}.field{min-width:0;display:flex;flex-direction:column;gap:6px}.wide{grid-column:1/-1}label{font-size:13px;font-weight:700;color:#35423d}input,select{min-width:0;width:100%;border:1px solid #c9ccc2;border-radius:6px;padding:10px 11px;font-size:15px;background:#fff;color:var(--ink);}"
                + ".actions{display:grid;grid-template-columns:1fr;gap:10px;margin-top:14px}button{min-height:44px;border:0;border-radius:6px;padding:11px 12px;font-size:15px;font-weight:700;color:#fff;cursor:pointer;overflow-wrap:anywhere}button:disabled{background:#a7aaa4!important;cursor:not-allowed}.primary{background:var(--green)}.secondary{background:var(--blue)}.order{background:var(--coral)}.neutral{background:#6f7370}"
                + ".summary{display:grid;grid-template-columns:repeat(auto-fit,minmax(145px,1fr));gap:12px;margin-bottom:14px}.metric{min-width:0;background:var(--soft);border:1px solid var(--line);border-radius:8px;padding:14px}.metric span{display:block;color:var(--muted);font-size:12px;font-weight:700;text-transform:uppercase}.metric strong{display:block;margin-top:6px;font-size:20px;overflow-wrap:anywhere}"
                + ".notice{min-height:54px;border-left:4px solid var(--green);background:#f7faf6;padding:14px;color:#33423c;font-size:16px;line-height:1.45;overflow-wrap:anywhere}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:14px;margin-top:14px}.client-card{min-width:0;background:var(--paper);border:1px solid var(--line);border-radius:8px;padding:15px}.client-card h3{margin:0;font-size:18px}.client-meta{color:var(--muted);font-size:13px;margin-top:4px}.client-card .message{margin:12px 0;padding:10px;border-left:4px solid var(--blue);background:#f6f8fb;color:#33425c;line-height:1.4}.card-actions{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;margin-top:10px}.empty{border:1px dashed var(--line);background:#fff;padding:22px;border-radius:8px;color:var(--muted);text-align:center}"
                + "@media(max-width:900px){.top{align-items:flex-start;flex-direction:column;justify-content:flex-end}.shell{grid-template-columns:1fr;padding:16px}.pill{white-space:normal}.cards{grid-template-columns:repeat(auto-fit,minmax(230px,1fr))}}"
                + "@media(max-width:560px){.top{min-height:180px;padding:18px 14px}.brand h1{font-size:26px}.shell{padding:12px;gap:12px}.panel{padding:14px}.form{grid-template-columns:1fr}.summary{grid-template-columns:1fr}.cards{grid-template-columns:1fr}.card-actions{grid-template-columns:1fr}.metric strong{font-size:18px}}"
                + "</style>"
                + "</head>"
                + "<body>"
                + "<header class=\"top\"><div class=\"brand\"><h1>Sala Clienti</h1><p>Gestione di piu' clienti su tavoli diversi tramite cameriere e socket TCP.</p></div><div class=\"pill\" id=\"headerStatus\">0 clienti attivi</div></header>"
                + "<main class=\"shell\">"
                + "<section class=\"panel\"><h2>Nuovo cliente</h2>"
                + "<div class=\"form\">"
                + "<div class=\"field\"><label for=\"host\">Cameriere</label><input id=\"host\" value=\"" + htmlEscape(defaultWaiterHost) + "\"></div>"
                + "<div class=\"field\"><label for=\"port\">Porta</label><input id=\"port\" value=\"" + defaultWaiterPort + "\" inputmode=\"numeric\"></div>"
                + "<div class=\"field wide\"><label for=\"name\">Cliente</label><input id=\"name\" value=\"" + htmlEscape(defaultCustomerName) + "\"></div>"
                + "</div>"
                + "<div class=\"actions\"><button class=\"primary\" id=\"enterBtn\">Fai entrare cliente</button></div>"
                + "</section>"
                + "<section class=\"panel\">"
                + "<h2>Clienti ai tavoli</h2>"
                + "<div class=\"summary\"><div class=\"metric\"><span>Clienti attivi</span><strong id=\"activeCount\">0</strong></div><div class=\"metric\"><span>Tavoli occupati</span><strong id=\"tableCount\">0</strong></div><div class=\"metric\"><span>Ultimo esito</span><strong id=\"lastResult\">-</strong></div></div>"
                + "<div class=\"notice\" id=\"notice\">Aggiungi uno o piu' clienti. Ogni cliente ricevera' un tavolo disponibile.</div>"
                + "<div class=\"cards\" id=\"clientCards\"><div class=\"empty\">Nessun cliente presente.</div></div>"
                + "</section>"
                + "</main>"
                + "<script>"
                + "const sessions={};let busy=false;"
                + "const $=id=>document.getElementById(id);"
                + "const cards=$('clientCards');"
                + "function params(obj){const p=new URLSearchParams();Object.keys(obj).forEach(k=>p.append(k,obj[k]||''));return p;}"
                + "function esc(v){return String(v||'').replace(/[&<>\\\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]));}"
                + "function setBusy(v){busy=v;$('enterBtn').disabled=v;document.querySelectorAll('[data-action]').forEach(b=>b.disabled=v);}"
                + "async function post(path,body,waiting){setBusy(true);if(waiting)$('notice').textContent=waiting;try{const res=await fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body:params(body)});return await res.json();}finally{setBusy(false);}}"
                + "function render(){const list=Object.values(sessions);$('activeCount').textContent=list.length;$('tableCount').textContent=list.length;$('headerStatus').textContent=list.length+' clienti attivi';if(!list.length){cards.innerHTML='<div class=\"empty\">Nessun cliente presente.</div>';return;}cards.innerHTML=list.map(s=>{const options=s.items.length?s.items.map(i=>'<option value=\"'+esc(i.code)+'\">'+esc(i.code)+' - '+esc(i.label)+'</option>').join(''):'<option value=\"\">Carica menu</option>';return '<article class=\"client-card\"><h3>'+esc(s.customer)+'</h3><div class=\"client-meta\">Tavolo '+esc(s.table)+'</div><div class=\"message\">'+esc(s.message||'Cliente seduto. Richiedere il menu.')+'</div><label>Piatto</label><select id=\"item-'+esc(s.id)+'\" '+(!s.items.length?'disabled':'')+'>'+options+'</select><div class=\"card-actions\"><button class=\"secondary\" data-action=\"menu\" data-id=\"'+esc(s.id)+'\">Menu</button><button class=\"order\" data-action=\"order\" data-id=\"'+esc(s.id)+'\" '+(!s.items.length?'disabled':'')+'>Ordina</button><button class=\"neutral\" data-action=\"exit\" data-id=\"'+esc(s.id)+'\">Esci</button></div></article>';}).join('');}"
                + "async function enterClient(){const data=await post('/api/enter',{host:$('host').value,port:$('port').value,name:$('name').value},'Richiesta ingresso in corso...');$('lastResult').textContent=data.command||'-';$('notice').textContent=data.message||'';if(data.ok&&data.command==='SEAT_GRANTED'){sessions[data.sessionId]={id:data.sessionId,customer:data.customer,table:data.table,items:[],message:data.message};render();}}"
                + "async function loadMenu(id){const s=sessions[id];if(!s)return;const data=await post('/api/menu',{sessionId:id},'Caricamento menu tavolo '+s.table+'...');$('lastResult').textContent=data.command||'-';s.message=data.message||s.message;if(data.items)s.items=data.items;$('notice').textContent=data.message||'';render();}"
                + "async function sendOrder(id){const s=sessions[id];if(!s)return;const item=$('item-'+id).value;if(!item){$('notice').textContent='Selezionare un piatto.';return;}s.message='Ordine inviato. Attendere la preparazione.';render();const data=await post('/api/order',{sessionId:id,item:item},'Ordine tavolo '+s.table+' inviato al pub...');$('lastResult').textContent=data.command||'-';s.message=data.message||s.message;$('notice').textContent=data.message||'';render();}"
                + "async function exitClient(id){const s=sessions[id];if(!s)return;const data=await post('/api/exit',{sessionId:id},'Uscita tavolo '+s.table+'...');$('lastResult').textContent=data.command||'-';$('notice').textContent=data.message||'Cliente uscito.';delete sessions[id];render();}"
                + "$('enterBtn').onclick=enterClient;"
                + "cards.addEventListener('click',e=>{const b=e.target.closest('button[data-action]');if(!b||busy)return;const id=b.dataset.id;if(b.dataset.action==='menu')loadMenu(id);if(b.dataset.action==='order')sendOrder(id);if(b.dataset.action==='exit')exitClient(id);});"
                + "render();"
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
