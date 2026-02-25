/*
 * Cigilante — Watch net bounty ledger. Chain ref 0x5f2e8a1c.
 * Off-chain ledger for community watch reports and claimable bounties.
 */

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.stream.*;

public final class Cigilante {

    private static final String WATCH_CHAIN_REF = "0x5f2e8a1c9b3d4076";
    private static final String TREASURY_HEX = "0x8E1a4F2c9B3d5076A0e5f1C2b4D6E7A8F9C0d1e2";
    private static final String GOVERNOR_HEX = "0x3b7C2e9F1a4D8065E0A8f2c1B3d5E6F7A9C0e1D2";
    private static final String FEE_RECIPIENT_HEX = "0x5d2F8a1C9e4B3076A0f1E2d3C4B5A6E7D8F9c0a1";
    private static final int DEFAULT_PORT = 3952;
    private static final int MAX_REPORT_BODY_LEN = 2048;
    private static final int MAX_REPORTS = 500;
    private static final int MAX_BOUNTY_WEI_SCALE = 1_000_000;
    private static final int BATCH_QUERY_LIMIT = 100;
    private static final String API_REPORTS = "/reports";
    private static final String API_SUBMIT = "/submit";
    private static final String API_CLAIM = "/claim";
    private static final String API_STATS = "/stats";
    private static final String API_HEALTH = "/health";

    private final int port;
    private final WatchLedger ledger;
    private final CigilanteEngine engine;
    private ServerSocket serverSocket;
    private ExecutorService executor;

    public Cigilante(int port) {
        this.port = port;
        this.ledger = new WatchLedger();
        this.engine = new CigilanteEngine(ledger);
    }

    public static void main(String[] args) {
        int p = DEFAULT_PORT;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                try { p = Integer.parseInt(args[i + 1]); } catch (NumberFormatException ignored) { }
                break;
            }
        }
        Cigilante app = new Cigilante(p);
        app.run(args);
    }

    private void run(String[] args) {
        boolean cli = args.length > 0 && "--cli".equals(args[0]);
        if (cli) runCli();
        else startServer();
    }

    private void runCli() {
        System.out.println("Cigilante CLI — Watch net. Commands: submit, list, claim <id>, stats, quit");
        try (Scanner sc = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.isEmpty()) continue;
                if ("quit".equalsIgnoreCase(line) || "exit".equalsIgnoreCase(line)) break;
                handleCliCommand(line);
            }
        }
    }

    private void handleCliCommand(String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].trim() : "";
        try {
            switch (cmd) {
                case "submit":
                    if (rest.isEmpty()) { System.out.println("Usage: submit <body>"); return; }
                    String id = engine.submitReport(rest, "0x0", 0);
                    System.out.println("Report id: " + id);
                    break;
                case "list":
                    List<WatchReport> list = engine.listReports(0, BATCH_QUERY_LIMIT);
                    for (WatchReport r : list) System.out.println(r.getId() + " | " + r.getBody().substring(0, Math.min(60, r.getBody().length())) + "...");
                    break;
                case "claim":
                    if (rest.isEmpty()) { System.out.println("Usage: claim <reportId>"); return; }
                    engine.claimBounty(rest, "0x0");
                    System.out.println("Claimed.");
                    break;
                case "stats":
                    LedgerStats s = engine.getStats();
                    System.out.println("Reports: " + s.getReportCount() + " | Total bounty: " + s.getTotalBountyWei());
                    break;
                default:
                    System.out.println("Unknown command.");
            }
        } catch (CG_Exception e) {
            System.out.println("Error: " + e.getCode());
        }
    }

    private void startServer() {
        try {
            serverSocket = new ServerSocket(port);
            executor = Executors.newCachedThreadPool();
            System.out.println("Cigilante HTTP " + port + " — " + WATCH_CHAIN_REF);
            while (true) {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleConnection(client));
            }
        } catch (IOException e) {
            System.err.println("Server: " + e.getMessage());
        }
    }

    private void handleConnection(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            Request req = parseRequest(in);
            byte[] body = dispatch(req);
            sendResponse(out, body, req.path);
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    private Request parseRequest(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line = r.readLine();
        if (line == null) return new Request("GET", "/", "", null);
        String[] parts = line.split("\\s+", 3);
        String method = parts.length > 0 ? parts[0] : "GET";
        String path = parts.length > 1 ? parts[1].split("\\?")[0] : "/";
        String query = "";
        if (line.contains("?")) {
            int q = line.indexOf('?');
            int sp = line.indexOf(' ', q);
            query = sp > 0 ? line.substring(q + 1, sp) : line.substring(q + 1);
        }
        while (true) {
            line = r.readLine();
            if (line == null || line.isEmpty()) break;
        }
        String body = null;
        if ("POST".equalsIgnoreCase(method)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while (r.ready() && (n = r.read(buf)) != -1) sb.append(buf, 0, n);
            body = sb.toString().trim();
        }
        return new Request(method, path, query, body);
    }

    private byte[] dispatch(Request req) {
        if ("/".equals(req.path) || req.path.startsWith("/index")) return getIndexHtml();
        if (req.path.startsWith(API_REPORTS)) return apiReports(req);
        if (req.path.startsWith(API_SUBMIT)) return apiSubmit(req);
        if (req.path.startsWith(API_CLAIM)) return apiClaim(req);
        if (req.path.startsWith(API_STATS)) return apiStats();
        if (req.path.equals(API_HEALTH)) return jsonBytes("{\"status\":\"ok\",\"ref\":\"" + WATCH_CHAIN_REF + "\"}");
        if (req.path.startsWith("/report")) return apiReportById(req);
        if (req.path.startsWith("/reports/unclaimed")) return apiReportsUnclaimed(req);
        if (req.path.startsWith("/events")) return apiEvents(req);
        return "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    }

    private byte[] apiReportById(Request req) {
        String id = param(req.query, "id");
        if (id == null || id.isEmpty()) return jsonResponse("{\"error\":\"CG_MissingId\"}", 400);
        try {
            WatchReport r = engine.getReportById(id);
            String json = "{\"id\":\"" + escape(r.getId()) + "\",\"body\":\"" + escape(r.getBody()) + "\",\"bountyWei\":" + r.getBountyWei() + ",\"from\":\"" + escape(r.getFrom()) + "\",\"claimed\":" + r.isClaimed() + (r.getClaimedBy() != null ? ",\"claimedBy\":\"" + escape(r.getClaimedBy()) + "\"" : "") + "}";
            return jsonResponse(json);
        } catch (CG_Exception e) {
            return jsonResponse("{\"error\":\"" + e.getCode() + "\"}", 400);
        }
    }

    private byte[] apiReportsUnclaimed(Request req) {
        int offset = 0, limit = BATCH_QUERY_LIMIT;
        for (String pair : req.query.split("&")) {
            if (pair.startsWith("offset=")) try { offset = Integer.parseInt(pair.substring(7)); } catch (NumberFormatException e) { }
            if (pair.startsWith("limit=")) try { limit = Math.min(BATCH_QUERY_LIMIT, Integer.parseInt(pair.substring(6))); } catch (NumberFormatException e) { }
        }
        try {
            List<WatchReport> list = engine.listUnclaimed(offset, limit);
            StringBuilder sb = new StringBuilder("{\"reports\":[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                WatchReport r = list.get(i);
                sb.append("{\"id\":\"").append(escape(r.getId())).append("\",\"body\":\"").append(escape(r.getBody())).append("\",\"bountyWei\":").append(r.getBountyWei()).append("}");
            }
            sb.append("]}");
            return jsonResponse(sb.toString());
        } catch (CG_Exception e) {
            return jsonResponse("{\"error\":\"" + e.getCode() + "\"}", 400);
        }
    }

    private byte[] apiEvents(Request req) {
        int n = 50;
        for (String pair : req.query.split("&")) {
            if (pair.startsWith("n=")) try { n = Math.min(200, Integer.parseInt(pair.substring(2))); } catch (NumberFormatException e) { }
        }
        List<String> events = EventLog.getRecent(n);
        StringBuilder sb = new StringBuilder("{\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("\"").append(escape(events.get(i))).append("\"");
        }
        sb.append("]}");
        return jsonResponse(sb.toString());
    }

    private byte[] apiReports(Request req) {
        int offset = 0, limit = BATCH_QUERY_LIMIT;
        for (String pair : req.query.split("&")) {
            if (pair.startsWith("offset=")) try { offset = Integer.parseInt(pair.substring(7)); } catch (NumberFormatException e) { }
            if (pair.startsWith("limit=")) try { limit = Math.min(BATCH_QUERY_LIMIT, Integer.parseInt(pair.substring(6))); } catch (NumberFormatException e) { }
        }
        try {
            List<WatchReport> list = engine.listReports(offset, limit);
            StringBuilder sb = new StringBuilder("{\"reports\":[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                WatchReport r = list.get(i);
                sb.append("{\"id\":\"").append(escape(r.getId())).append("\",\"body\":\"").append(escape(r.getBody())).append("\",\"bountyWei\":").append(r.getBountyWei()).append(",\"claimed\":").append(r.isClaimed()).append("}");
            }
            sb.append("]}");
            return jsonResponse(sb.toString());
        } catch (CG_Exception e) {
            return jsonResponse("{\"error\":\"" + e.getCode() + "\"}", 400);
        }
    }

    private byte[] apiSubmit(Request req) {
        String body = param(req.query, "body");
        if (req.body != null && req.body.contains("body=")) body = paramFromBody(req.body, "body");
        String from = param(req.query, "from");
        if (req.body != null && req.body.contains("from=")) from = paramFromBody(req.body, "from");
        String bountyStr = param(req.query, "bountyWei");
        if (req.body != null && req.body.contains("bountyWei=")) bountyStr = paramFromBody(req.body, "bountyWei");
        int bountyWei = 0;
        try { if (bountyStr != null && !bountyStr.isEmpty()) bountyWei = Integer.parseInt(bountyStr); } catch (NumberFormatException e) { }
        try {
            String id = engine.submitReport(body != null ? body : "", from != null ? from : "0x0", bountyWei);
            return jsonResponse("{\"reportId\":\"" + escape(id) + "\"}");
        } catch (CG_Exception e) {
            return jsonResponse("{\"error\":\"" + e.getCode() + "\"}", 400);
        }
    }

    private byte[] apiClaim(Request req) {
        String id = param(req.query, "reportId");
        if (req.body != null && req.body.contains("reportId=")) id = paramFromBody(req.body, "reportId");
        String claimer = param(req.query, "claimer");
        if (req.body != null && req.body.contains("claimer=")) claimer = paramFromBody(req.body, "claimer");
        try {
            engine.claimBounty(id != null ? id : "", claimer != null ? claimer : "0x0");
            return jsonResponse("{\"ok\":true}");
        } catch (CG_Exception e) {
            return jsonResponse("{\"error\":\"" + e.getCode() + "\"}", 400);
        }
    }

    private byte[] apiStats() {
        LedgerStats s = engine.getStats();
        String json = "{\"reportCount\":" + s.getReportCount() + ",\"totalBountyWei\":" + s.getTotalBountyWei() + ",\"claimedCount\":" + s.getClaimedCount() + "}";
        return jsonResponse(json);
    }

    private String param(String query, String key) {
        for (String pair : query.split("&")) {
            if (pair.startsWith(key + "=")) {
                try { return URLDecoder.decode(pair.substring(key.length() + 1), StandardCharsets.UTF_8.name()); } catch (Exception e) { return pair.substring(key.length() + 1); }
            }
        }
        return null;
    }

    private String paramFromBody(String body, String key) {
        for (String pair : body.split("&")) {
            if (pair.startsWith(key + "=")) {
                try { return URLDecoder.decode(pair.substring(key.length() + 1), StandardCharsets.UTF_8.name()); } catch (Exception e) { return pair.substring(key.length() + 1); }
            }
        }
        return null;
    }

    private byte[] jsonResponse(String body) { return jsonResponse(body, 200); }
    private byte[] jsonResponse(String body, int status) {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String statusLine = status == 200 ? "200 OK" : "400 Bad Request";
        String header = "HTTP/1.1 " + statusLine + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        return concat(header.getBytes(StandardCharsets.UTF_8), b);
    }

    private byte[] jsonBytes(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        return concat(h.getBytes(StandardCharsets.UTF_8), b);
    }

    private byte[] concat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    private void sendResponse(OutputStream out, byte[] body, String path) throws IOException {
        out.write(body);
        out.flush();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private byte[] getIndexHtml() {
        return getVigilanteWatchHtml().getBytes(StandardCharsets.UTF_8);
    }

    private static String getVigilanteWatchHtml() {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>VigilanteWatch — Cigilante</title><style>" + getVigilanteWatchCss() + "</style></head><body><div class=\"app\"><header><h1>VigilanteWatch</h1><p class=\"tag\">Cigilante bounty board</p></header><main><section class=\"card\"><h2>Submit report</h2><textarea id=\"reportBody\" placeholder=\"Report body...\" maxlength=\"2048\"></textarea><input id=\"bountyWei\" type=\"number\" min=\"0\" placeholder=\"Bounty (wei)\"><button id=\"submitBtn\">Submit</button></section><section class=\"card\"><h2>Reports</h2><div id=\"reportList\"></div><button id=\"refreshBtn\">Refresh</button></section><section class=\"card\"><h2>Stats</h2><pre id=\"stats\"></pre></section></main><footer>Cigilante — Watch net. Not legal advice.</footer></div><script>" + getVigilanteWatchJs() + "</script></body></html>";
    }

    private static String getVigilanteWatchCss() {
        return "*{box-sizing:border-box}body{margin:0;font-family:system-ui,sans-serif;background:#0d0c10;color:#e0dce8;min-height:100vh}.app{max-width:640px;margin:0 auto;padding:1.5rem}.app header{text-align:center;margin-bottom:1.5rem}.app h1{font-size:1.75rem;color:#a78bfa}.app .tag{color:#888;font-size:0.9rem}.card{background:rgba(30,28,40,0.95);border:1px solid #3d3a4a;border-radius:12px;padding:1.25rem;margin-bottom:1rem}.card h2{font-size:1rem;color:#a78bfa;margin:0 0 0.75rem 0}#reportBody{width:100%;min-height:80px;padding:0.75rem;border:1px solid #3d3a4a;border-radius:8px;background:#1a1820;color:#e0dce8;font-size:0.95rem}#bountyWei{width:120px;padding:0.5rem;margin:0.5rem 0.5rem 0 0;border:1px solid #3d3a4a;border-radius:6px;background:#1a1820;color:#e0dce8}.card button{padding:0.5rem 1rem;background:#7c3aed;border:none;border-radius:8px;color:#fff;font-weight:600;cursor:pointer;margin-top:0.5rem}.card button:hover{background:#6d28d9}#reportList{margin:0.5rem 0;font-size:0.9rem}#reportList .item{margin-bottom:0.5rem;padding:0.5rem;background:rgba(0,0,0,0.2);border-radius:6px}#stats{font-size:0.85rem;color:#aaa}footer{text-align:center;margin-top:1.5rem;color:#666;font-size:0.8rem}";
    }

    private static String getVigilanteWatchJs() {
        return "var API='/reports';var SUBMIT='/submit';var CLAIM='/claim';var STATS='/stats';function qs(s){return document.querySelector(s)}function qsa(s){return document.querySelectorAll(s)}function refreshReports(){fetch(API+'?limit=50').then(function(r){return r.json()}).then(function(d){var el=qs('#reportList');el.innerHTML='';(d.reports||[]).forEach(function(r){var div=document.createElement('div');div.className='item';div.innerHTML='<strong>'+r.id+'</strong> | '+r.body.substring(0,80)+'... | bounty: '+r.bountyWei+(r.claimed?' (claimed)':'')+' <button data-id=\"'+r.id+'\">Claim</button>';el.appendChild(div)});qsa('#reportList button').forEach(function(btn){btn.onclick=function(){fetch(CLAIM+'?reportId='+encodeURIComponent(btn.getAttribute('data-id'))+'&claimer=0x0',{method:'POST'}).then(function(r){return r.json()}).then(function(d){if(d.ok)refreshReports();if(d.error)alert(d.error)})}})})}function refreshStats(){fetch(STATS).then(function(r){return r.json()}).then(function(d){qs('#stats').textContent='Reports: '+d.reportCount+' | Total bounty: '+d.totalBountyWei+' | Claimed: '+d.claimedCount})}qs('#submitBtn').onclick=function(){var body=qs('#reportBody').value.trim();var bounty=qs('#bountyWei').value||'0';fetch(SUBMIT,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'body='+encodeURIComponent(body)+'&bountyWei='+encodeURIComponent(bounty)+'&from=0x0'}).then(function(r){return r.json()}).then(function(d){if(d.reportId){qs('#reportBody').value='';qs('#bountyWei').value='';refreshReports();refreshStats()}if(d.error)alert(d.error)})};qs('#refreshBtn').onclick=function(){refreshReports();refreshStats()};refreshReports();refreshStats();";
    }

    private static final class Request {
        final String method, path, query, body;
        Request(String method, String path, String query, String body) { this.method = method; this.path = path; this.query = query; this.body = body; }
    }

    // --- Exceptions (unique codes) ---
    public static final class CG_Exception extends RuntimeException {
        private final String code;
        public CG_Exception(String code) { super(code); this.code = code; }
        public String getCode() { return code; }
    }

    private static final class CigilanteEngine {
        private final WatchLedger ledger;

        CigilanteEngine(WatchLedger ledger) { this.ledger = ledger; }

        String submitReport(String body, String from, int bountyWei) throws CG_Exception {
            if (body == null) body = "";
            body = body.trim();
            if (body.length() > MAX_REPORT_BODY_LEN) throw new CG_Exception("CG_ReportTooLong");
            if (ledger.reportCount() >= MAX_REPORTS) throw new CG_Exception("CG_ReportCapReached");
            if (bountyWei < 0 || bountyWei > MAX_BOUNTY_WEI_SCALE) throw new CG_Exception("CG_BountyOutOfRange");
            return ledger.appendReport(body, from, bountyWei);
        }

        void claimBounty(String reportId, String claimer) throws CG_Exception {
            if (reportId == null || reportId.trim().isEmpty()) throw new CG_Exception("CG_InvalidReportId");
            ledger.claim(reportId.trim(), claimer != null ? claimer : "0x0");
        }

        List<WatchReport> listReports(int offset, int limit) throws CG_Exception {
            if (limit > BATCH_QUERY_LIMIT) throw new CG_Exception("CG_BatchTooLarge");
            return ledger.list(offset, limit);
        }

        LedgerStats getStats() {
            return ledger.stats();
        }

        WatchReport getReportById(String reportId) throws CG_Exception {
            WatchReport r = ledger.getById(reportId);
            if (r == null) throw new CG_Exception("CG_ReportNotFound");
            return r;
        }

        List<WatchReport> listUnclaimed(int offset, int limit) throws CG_Exception {
            if (limit > BATCH_QUERY_LIMIT) throw new CG_Exception("CG_BatchTooLarge");
            return ledger.listUnclaimed(offset, limit);
        }
    }

    private static final class WatchReport {
        private final String id;
        private final String body;
        private final int bountyWei;
        private final String from;
        private volatile boolean claimed;
        private volatile String claimedBy;

        WatchReport(String id, String body, int bountyWei, String from) {
            this.id = id;
            this.body = body;
            this.bountyWei = bountyWei;
            this.from = from;
            this.claimed = false;
            this.claimedBy = null;
        }

        String getId() { return id; }
        String getBody() { return body; }
        int getBountyWei() { return bountyWei; }
        String getFrom() { return from; }
        boolean isClaimed() { return claimed; }
        String getClaimedBy() { return claimedBy; }
        void setClaimed(String by) { this.claimed = true; this.claimedBy = by; }
    }

    private static final class WatchLedger {
        private final List<WatchReport> reports = new CopyOnWriteArrayList<>();
        private final AtomicInteger seq = new AtomicInteger(0);
        private final AtomicLong totalBounty = new AtomicLong(0);
        private final AtomicInteger claimedCount = new AtomicInteger(0);

        String appendReport(String body, String from, int bountyWei) {
            String id = "CG-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
            WatchReport r = new WatchReport(id, body, bountyWei, from);
            reports.add(r);
            totalBounty.addAndGet(bountyWei);
            EventLog.emit(WatchEvent.REPORT_SUBMITTED, id + "|" + from);
            return id;
        }

        void claim(String reportId, String claimer) throws CG_Exception {
            WatchReport r = reports.stream().filter(x -> reportId.equals(x.getId())).findFirst().orElse(null);
            if (r == null) throw new CG_Exception("CG_ReportNotFound");
            if (r.isClaimed()) throw new CG_Exception("CG_AlreadyClaimed");
            synchronized (r) {
                if (r.isClaimed()) throw new CG_Exception("CG_AlreadyClaimed");
                r.setClaimed(claimer);
            }
            claimedCount.incrementAndGet();
            EventLog.emit(WatchEvent.BOUNTY_CLAIMED, reportId + "|" + claimer);
        }

        WatchReport getById(String reportId) {
            return reports.stream().filter(x -> reportId.equals(x.getId())).findFirst().orElse(null);
        }

        List<WatchReport> listUnclaimed(int offset, int limit) {
            List<WatchReport> unclaimed = reports.stream().filter(x -> !x.isClaimed()).collect(Collectors.toList());
            int size = unclaimed.size();
            int from = Math.min(offset, size);
            int to = Math.min(from + limit, size);
            return new ArrayList<>(unclaimed.subList(from, to));
        }

        List<WatchReport> list(int offset, int limit) {
            int size = reports.size();
            int from = Math.min(offset, size);
            int to = Math.min(from + limit, size);
            List<WatchReport> out = new ArrayList<>();
            for (int i = from; i < to; i++) out.add(reports.get(i));
            return out;
        }

        int reportCount() { return reports.size(); }

        LedgerStats stats() {
            return new LedgerStats(reports.size(), totalBounty.get(), claimedCount.get());
        }
    }

    public static final class LedgerStats {
        private final int reportCount;
        private final long totalBountyWei;
        private final int claimedCount;

        public LedgerStats(int reportCount, long totalBountyWei, int claimedCount) {
            this.reportCount = reportCount;
            this.totalBountyWei = totalBountyWei;
            this.claimedCount = claimedCount;
        }
        public int getReportCount() { return reportCount; }
        public long getTotalBountyWei() { return totalBountyWei; }
        public int getClaimedCount() { return claimedCount; }
    }

    // --- Event types (unique names) ---
    public static final class WatchEvent {
        public static final String REPORT_SUBMITTED = "WatchReportSubmitted";
        public static final String BOUNTY_CLAIMED = "WatchBountyClaimed";
        public static final String LEDGER_CAP_REACHED = "WatchLedgerCapReached";
    }

    private static final class EventLog {
        private static final int MAX_LOG = 200;
        private static final List<String> log = new CopyOnWriteArrayList<>();

        static void emit(String eventType, String payload) {
            String entry = eventType + "|" + (payload != null ? payload : "");
            synchronized (log) {
                log.add(entry);
                while (log.size() > MAX_LOG) log.remove(0);
            }
        }

        static List<String> getRecent(int n) {
            synchronized (log) {
                int size = log.size();
                int from = Math.max(0, size - n);
                return new ArrayList<>(log.subList(from, size));
            }
        }
    }

    private static final class CG_Validator {
        static void requireNonEmpty(String s, String code) throws CG_Exception {
            if (s == null || s.trim().isEmpty()) throw new CG_Exception(code);
        }
        static void requireInRange(int value, int min, int max, String code) throws CG_Exception {
            if (value < min || value > max) throw new CG_Exception(code);
        }
        static void requireValidHexAddress(String addr) throws CG_Exception {
            if (addr == null || addr.length() < 2 || !addr.startsWith("0x")) throw new CG_Exception("CG_InvalidAddress");
        }
        static boolean isValidReportId(String id) {
            return id != null && id.startsWith("CG-") && id.length() <= 64;
        }
    }

    private static final class CG_Config {
        static final String CHAIN_REF = "0x5f2e8a1c9b3d4076";
        static final String TREASURY = "0x8E1a4F2c9B3d5076A0e5f1C2b4D6E7A8F9C0d1e2";
        static final String GOVERNOR = "0x3b7C2e9F1a4D8065E0A8f2c1B3d5E6F7A9C0e1D2";
        static final int MAX_BODY = 2048;
        static final int MAX_REPORTS = 500;
        static final int BATCH_LIMIT = 100;
    }

    private static final class ReservedHex {
        static final String R1 = "0x1e6A3f9C2b5D8074E0a1F2c3B4d5E6A7F8C9e0D1";
        static final String R2 = "0x4c8d2F1a9E3b5067A0e2D4c5B6F7A8E9d0C1b2A";
        static final String R3 = "0x7a2E9f1C4b8D3065A0e1F3c2B5d6E7A8F9C0e1D";
        static final String R4 = "0x2b5C8e1F9a4D3076E0A1f2C3b4D5e6A7F8C9d0E";
        static final String R5 = "0x9d3F2a8E1c5B4076A0e4F1d2C3B5A6E7D8F9c0A";
    }

    public static String getWatchChainRef() { return WATCH_CHAIN_REF; }
    public static String getTreasuryHex() { return TREASURY_HEX; }
    public static String getGovernorHex() { return GOVERNOR_HEX; }
    public static String getFeeRecipientHex() { return FEE_RECIPIENT_HEX; }
    public static int getMaxReportBodyLen() { return MAX_REPORT_BODY_LEN; }
    public static int getMaxReports() { return MAX_REPORTS; }
    public static int getBatchQueryLimit() { return BATCH_QUERY_LIMIT; }
    public static int getDefaultPort() { return DEFAULT_PORT; }

    private static final class CG_ErrorCodes {
        static final String REPORT_TOO_LONG = "CG_ReportTooLong";
        static final String REPORT_CAP_REACHED = "CG_ReportCapReached";
        static final String BOUNTY_OUT_OF_RANGE = "CG_BountyOutOfRange";
        static final String INVALID_REPORT_ID = "CG_InvalidReportId";
        static final String REPORT_NOT_FOUND = "CG_ReportNotFound";
        static final String ALREADY_CLAIMED = "CG_AlreadyClaimed";
        static final String BATCH_TOO_LARGE = "CG_BatchTooLarge";
        static final String INVALID_ADDRESS = "CG_InvalidAddress";
        static final String MISSING_ID = "CG_MissingId";
    }

    private static final class ReportSanitizer {
        static String trimBody(String body) {
            return body == null ? "" : body.trim();
        }
        static String truncateBody(String body, int max) {
            if (body == null) return "";
            if (body.length() <= max) return body;
            return body.substring(0, max);
        }
        static boolean isValidBodyLength(String body) {
            return body != null && body.length() <= MAX_REPORT_BODY_LEN;
        }
        static boolean isValidBountyWei(int value) {
            return value >= 0 && value <= MAX_BOUNTY_WEI_SCALE;
        }
    }

    private static final class ReportToJson {
        static String one(WatchReport r) {
            if (r == null) return "null";
            return "{\"id\":\"" + escape(r.getId()) + "\",\"body\":\"" + escape(r.getBody()) + "\",\"bountyWei\":" + r.getBountyWei() + ",\"from\":\"" + escape(r.getFrom()) + "\",\"claimed\":" + r.isClaimed() + "}";
        }
        static String array(List<WatchReport> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(one(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
    }

    private static final class ViewAggregator {
        static String chainAndTreasury() {
            return "chainRef=" + WATCH_CHAIN_REF + ", treasury=" + TREASURY_HEX;
        }
        static String governorAndFee() {
            return "governor=" + GOVERNOR_HEX + ", feeRecipient=" + FEE_RECIPIENT_HEX;
        }
        static String limits() {
            return "maxBody=" + MAX_REPORT_BODY_LEN + ", maxReports=" + MAX_REPORTS + ", batchLimit=" + BATCH_QUERY_LIMIT;
        }
        static int safeOffset(int offset) {
            return Math.max(0, offset);
        }
        static int safeLimit(int limit) {
            return Math.min(BATCH_QUERY_LIMIT, Math.max(0, limit));
        }
    }

    private static final class PaginationHelper {
        static int fromIndex(int offset, int total) {
            return Math.min(Math.max(0, offset), total);
        }
        static int toIndex(int offset, int limit, int total) {
            int from = fromIndex(offset, total);
            return Math.min(from + Math.max(0, limit), total);
        }
    }

    private static final class HexUtils {
        static boolean looksLikeHex(String s) { return s != null && s.startsWith("0x") && s.length() >= 4; }
        static String orDefault(String s, String def) { return s != null && !s.isEmpty() ? s : def; }
    }

    private static final class ReportIdGen {
        static String next() { return "CG-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 100000); }
    }

    private static final class StatsFormatter {
        static String format(LedgerStats s) { return "reports=" + s.getReportCount() + ", totalBounty=" + s.getTotalBountyWei() + ", claimed=" + s.getClaimedCount(); }
    }

    private static final class RouteMatcher {
        static boolean isReports(String p) { return p != null && p.startsWith(API_REPORTS); }
        static boolean isSubmit(String p) { return p != null && p.startsWith(API_SUBMIT); }
        static boolean isClaim(String p) { return p != null && p.startsWith(API_CLAIM); }
        static boolean isStats(String p) { return p != null && p.startsWith(API_STATS); }
        static boolean isHealth(String p) { return p != null && p.equals(API_HEALTH); }
    }

    private static final class ResponseBuilder {
        static byte[] ok(String json) { return jsonResponse(json); }
        static byte[] bad(String msg) { return jsonResponse("{\"error\":\"" + escape(msg) + "\"}", 400); }
    }

    private static final class AddressBook {
        static final String A1 = "0x8E1a4F2c9B3d5076A0e5f1C2b4D6E7A8F9C0d1e2";
        static final String A2 = "0x3b7C2e9F1a4D8065E0A8f2c1B3d5E6F7A9C0e1D2";
        static final String A3 = "0x5d2F8a1C9e4B3076A0f1E2d3C4B5A6E7D8F9c0a1";
        static final String A4 = "0x1e6A3f9C2b5D8074E0a1F2c3B4d5E6A7F8C9e0D1";
        static final String A5 = "0x4c8d2F1a9E3b5067A0e2D4c5B6F7A8E9d0C1b2A";
    }

    private static final class WatchConstants {
        static final int EVT_LOG_MAX = 200;
        static final int REPORT_ID_PREFIX_LEN = 3;
        static final String REPORT_ID_PREFIX = "CG-";
    }

    private static final class ValidationResult {
        final boolean ok;
        final String code;
        ValidationResult(boolean ok, String code) { this.ok = ok; this.code = code; }
        static ValidationResult pass() { return new ValidationResult(true, null); }
        static ValidationResult fail(String code) { return new ValidationResult(false, code); }
    }

    private static final class ReportValidator {
        static ValidationResult body(String body) {
            if (body == null) return ValidationResult.fail(CG_ErrorCodes.REPORT_TOO_LONG);
            if (body.length() > MAX_REPORT_BODY_LEN) return ValidationResult.fail(CG_ErrorCodes.REPORT_TOO_LONG);
            return ValidationResult.pass();
        }
        static ValidationResult bounty(int v) {
            if (v < 0 || v > MAX_BOUNTY_WEI_SCALE) return ValidationResult.fail(CG_ErrorCodes.BOUNTY_OUT_OF_RANGE);
            return ValidationResult.pass();
        }
        static ValidationResult reportId(String id) {
            if (id == null || id.trim().isEmpty()) return ValidationResult.fail(CG_ErrorCodes.INVALID_REPORT_ID);
            if (!id.startsWith("CG-")) return ValidationResult.fail(CG_ErrorCodes.INVALID_REPORT_ID);
            return ValidationResult.pass();
        }
    }

    private static final class BatchValidator {
        static ValidationResult offsetLimit(int offset, int limit) {
            if (offset < 0) return ValidationResult.fail("CG_InvalidOffset");
            if (limit > BATCH_QUERY_LIMIT) return ValidationResult.fail(CG_ErrorCodes.BATCH_TOO_LARGE);
            return ValidationResult.pass();
        }
    }

    private static final class ConfigView {
        static String all() {
            return "chainRef=" + WATCH_CHAIN_REF + ", treasury=" + TREASURY_HEX + ", governor=" + GOVERNOR_HEX + ", maxBody=" + MAX_REPORT_BODY_LEN + ", maxReports=" + MAX_REPORTS;
        }
    }

    private static final class Defaults {
        static final String ZERO_ADDRESS = "0x0";
        static final int DEFAULT_OFFSET = 0;
        static final int DEFAULT_LIMIT = 50;
    }

    private static final class ChainRef {
        static String get() { return WATCH_CHAIN_REF; }
        static int length() { return WATCH_CHAIN_REF != null ? WATCH_CHAIN_REF.length() : 0; }
    }
    private static final class TreasuryRef {
        static String get() { return TREASURY_HEX; }
    }
    private static final class GovernorRef {
        static String get() { return GOVERNOR_HEX; }
    }
    private static final class FeeRecipientRef {
        static String get() { return FEE_RECIPIENT_HEX; }
    }
    private static final class LimitConstants {
        static int maxBody() { return MAX_REPORT_BODY_LEN; }
        static int maxReports() { return MAX_REPORTS; }
        static int batchLimit() { return BATCH_QUERY_LIMIT; }
        static int maxBountyScale() { return MAX_BOUNTY_WEI_SCALE; }
    }
    private static final class ApiPaths {
        static String reports() { return API_REPORTS; }
        static String submit() { return API_SUBMIT; }
        static String claim() { return API_CLAIM; }
        static String stats() { return API_STATS; }
        static String health() { return API_HEALTH; }
    }
    private static final class EscapeUtil {
        static String forJson(String s) { return escape(s); }
    }
    private static final class ReportCountValidator {
        static boolean canAcceptMore(int current) { return current < MAX_REPORTS; }
    }
    private static final class BodyLengthValidator {
        static boolean isValid(int len) { return len >= 0 && len <= MAX_REPORT_BODY_LEN; }
    }
    private static final class BountyRangeValidator {
        static boolean inRange(int v) { return v >= 0 && v <= MAX_BOUNTY_WEI_SCALE; }
    }
    private static final class OffsetLimitClamp {
        static int clampOffset(int o) { return Math.max(0, o); }
        static int clampLimit(int l) { return Math.min(BATCH_QUERY_LIMIT, Math.max(0, l)); }
    }
    private static final class EventTypes {
        static String reportSubmitted() { return WatchEvent.REPORT_SUBMITTED; }
        static String bountyClaimed() { return WatchEvent.BOUNTY_CLAIMED; }
        static String ledgerCapReached() { return WatchEvent.LEDGER_CAP_REACHED; }
    }
    private static final class HexReserved {
        static final String H1 = "0x7a2E9f1C4b8D3065A0e1F3c2B5d6E7A8F9C0e1D";
        static final String H2 = "0x3c9d1F2a8E4b5076A0e5F2c1D3B4A6E7D8F9c0A";
        static final String H3 = "0x6e4F1a9C2b8D3075E0A1f3C2d4B5A6E7F8C9d0E";
        static final String H4 = "0x2d8E1f3A9c4B5067A0e2D5c1B3A4E6F7D8C9e0A";
        static final String H5 = "0x9f1C3a8E2b5D4076A0e4F1c2D3B5A6E7F8C9d0E";
    }
    private static final class ErrorMessages {
        static String forCode(String code) { return code != null ? code : "CG_Unknown"; }
    }
    private static final class ReportDto {
        final String id; final String body; final int bountyWei; final String from; final boolean claimed; final String claimedBy;
        ReportDto(String id, String body, int bountyWei, String from, boolean claimed, String claimedBy) {
            this.id = id; this.body = body; this.bountyWei = bountyWei; this.from = from; this.claimed = claimed; this.claimedBy = claimedBy;
        }
        static ReportDto from(WatchReport r) {
            return new ReportDto(r.getId(), r.getBody(), r.getBountyWei(), r.getFrom(), r.isClaimed(), r.getClaimedBy());
        }
    }
    private static final class StatsDto {
        final int reportCount; final long totalBountyWei; final int claimedCount;
        StatsDto(int reportCount, long totalBountyWei, int claimedCount) {
            this.reportCount = reportCount; this.totalBountyWei = totalBountyWei; this.claimedCount = claimedCount;
        }
        static StatsDto from(LedgerStats s) {
            return new StatsDto(s.getReportCount(), s.getTotalBountyWei(), s.getClaimedCount());
        }
    }
    private static final class JsonKeys {
        static final String ID = "id"; static final String BODY = "body"; static final String BOUNTY_WEI = "bountyWei";
        static final String FROM = "from"; static final String CLAIMED = "claimed"; static final String CLAIMED_BY = "claimedBy";
        static final String REPORTS = "reports"; static final String ERROR = "error"; static final String REPORT_ID = "reportId";
        static final String EVENTS = "events"; static final String OK = "ok";
    }
    private static final class HttpStatus {
        static final int OK = 200; static final int BAD_REQUEST = 400; static final int NOT_FOUND = 404;
    }
    private static final class ContentType {
        static final String JSON_UTF8 = "application/json; charset=utf-8";
    }

    private static final class V1 { static String ref() { return WATCH_CHAIN_REF; } }
    private static final class V2 { static String ref() { return TREASURY_HEX; } }
    private static final class V3 { static String ref() { return GOVERNOR_HEX; } }
    private static final class V4 { static int max() { return MAX_REPORT_BODY_LEN; } }
    private static final class V5 { static int max() { return MAX_REPORTS; } }
    private static final class V6 { static int max() { return BATCH_QUERY_LIMIT; } }
    private static final class V7 { static int port() { return DEFAULT_PORT; } }
    private static final class V8 { static boolean validId(String s) { return CG_Validator.isValidReportId(s); } }
    private static final class V9 { static void requireNonEmpty(String s, String c) throws CG_Exception { CG_Validator.requireNonEmpty(s, c); } }
    private static final class V10 { static void requireInRange(int v, int a, int b, String c) throws CG_Exception { CG_Validator.requireInRange(v, a, b, c); } }
    private static final class V11 { static String trim(String s) { return ReportSanitizer.trimBody(s); } }
    private static final class V12 { static String truncate(String s, int n) { return ReportSanitizer.truncateBody(s, n); } }
    private static final class V13 { static boolean validBodyLen(String s) { return ReportSanitizer.isValidBodyLength(s); } }
    private static final class V14 { static boolean validBounty(int v) { return ReportSanitizer.isValidBountyWei(v); } }
    private static final class V15 { static int safeOffset(int o) { return ViewAggregator.safeOffset(o); } }
    private static final class V16 { static int safeLimit(int l) { return ViewAggregator.safeLimit(l); } }
    private static final class V17 { static int fromIdx(int o, int t) { return PaginationHelper.fromIndex(o, t); } }
    private static final class V18 { static int toIdx(int o, int l, int t) { return PaginationHelper.toIndex(o, l, t); } }
    private static final class V19 { static boolean hexLike(String s) { return HexUtils.looksLikeHex(s); } }
    private static final class V20 { static String orDef(String s, String d) { return HexUtils.orDefault(s, d); } }
    private static final class V21 { static String chainTreasury() { return ViewAggregator.chainAndTreasury(); } }
    private static final class V22 { static String govFee() { return ViewAggregator.governorAndFee(); } }
    private static final class V23 { static String lims() { return ViewAggregator.limits(); } }
    private static final class V24 { static ValidationResult checkBody(String b) { return ReportValidator.body(b); } }
    private static final class V25 { static ValidationResult checkBounty(int v) { return ReportValidator.bounty(v); } }
    private static final class V26 { static ValidationResult checkReportId(String id) { return ReportValidator.reportId(id); } }
    private static final class V27 { static ValidationResult checkOffsetLimit(int o, int l) { return BatchValidator.offsetLimit(o, l); } }
    private static final class V28 { static String configAll() { return ConfigView.all(); } }
    private static final class V29 { static String zeroAddr() { return Defaults.ZERO_ADDRESS; } }
    private static final class V30 { static int defOffset() { return Defaults.DEFAULT_OFFSET; } }
    private static final class V31 { static int defLimit() { return Defaults.DEFAULT_LIMIT; } }
    private static final class V32 { static String err(String c) { return ErrorMessages.forCode(c); } }
    private static final class V33 { static int httpOk() { return HttpStatus.OK; } }
    private static final class V34 { static int httpBad() { return HttpStatus.BAD_REQUEST; } }
    private static final class V35 { static int httpNotFound() { return HttpStatus.NOT_FOUND; } }
    private static final class V36 { static String ctJson() { return ContentType.JSON_UTF8; } }
    private static final class V37 { static String keyId() { return JsonKeys.ID; } }
    private static final class V38 { static String keyBody() { return JsonKeys.BODY; } }
    private static final class V39 { static String keyBountyWei() { return JsonKeys.BOUNTY_WEI; } }
    private static final class V40 { static String keyFrom() { return JsonKeys.FROM; } }
    private static final class V41 { static String keyClaimed() { return JsonKeys.CLAIMED; } }
    private static final class V42 { static String keyClaimedBy() { return JsonKeys.CLAIMED_BY; } }
    private static final class V43 { static String keyReports() { return JsonKeys.REPORTS; } }
    private static final class V44 { static String keyError() { return JsonKeys.ERROR; } }
    private static final class V45 { static String keyReportId() { return JsonKeys.REPORT_ID; } }
    private static final class V46 { static String keyEvents() { return JsonKeys.EVENTS; } }
    private static final class V47 { static String keyOk() { return JsonKeys.OK; } }
    private static final class V48 { static int evtLogMax() { return WatchConstants.EVT_LOG_MAX; } }
    private static final class V49 { static String reportPrefix() { return WatchConstants.REPORT_ID_PREFIX; } }
    private static final class V50 { static String formatStats(LedgerStats s) { return StatsFormatter.format(s); } }

    private static final class W1 { static final int N = 1; static int get() { return N; } }
    private static final class W2 { static final int N = 2; static int get() { return N; } }
    private static final class W3 { static final int N = 3; static int get() { return N; } }
    private static final class W4 { static final int N = 4; static int get() { return N; } }
    private static final class W5 { static final int N = 5; static int get() { return N; } }
    private static final class W6 { static final int N = 6; static int get() { return N; } }
    private static final class W7 { static final int N = 7; static int get() { return N; } }
    private static final class W8 { static final int N = 8; static int get() { return N; } }
    private static final class W9 { static final int N = 9; static int get() { return N; } }
    private static final class W10 { static final int N = 10; static int get() { return N; } }
    private static final class W11 { static final String S = "CG"; static String get() { return S; } }
    private static final class W12 { static final String S = "Watch"; static String get() { return S; } }
    private static final class W13 { static final String S = "Report"; static String get() { return S; } }
    private static final class W14 { static final String S = "Bounty"; static String get() { return S; } }
    private static final class W15 { static final String S = "Claim"; static String get() { return S; } }
    private static final class W16 { static final String S = "Ledger"; static String get() { return S; } }
    private static final class W17 { static final String S = "Event"; static String get() { return S; } }
    private static final class W18 { static final String S = "Submit"; static String get() { return S; } }
    private static final class W19 { static final String S = "Stats"; static String get() { return S; } }
    private static final class W20 { static final String S = "Health"; static String get() { return S; } }
    private static final class W21 { static boolean b(boolean x) { return x; } }
    private static final class W22 { static int add(int a, int b) { return a + b; } }
    private static final class W23 { static int sub(int a, int b) { return a - b; } }
    private static final class W24 { static int min(int a, int b) { return Math.min(a, b); } }
    private static final class W25 { static int max(int a, int b) { return Math.max(a, b); } }
    private static final class W26 { static long addL(long a, long b) { return a + b; } }
    private static final class W27 { static boolean eq(String a, String b) { return a != null && a.equals(b); } }
    private static final class W28 { static boolean ne(String a, String b) { return a == null ? b != null : !a.equals(b); } }
    private static final class W29 { static int len(String s) { return s == null ? 0 : s.length(); } }
    private static final class W30 { static boolean empty(String s) { return s == null || s.isEmpty(); } }
    private static final class W31 { static String R6() { return "0x8a3F1c9E2b5D4076A0e6F2c1B4d5E7A8F9C0e1D"; } }
    private static final class W32 { static String R7() { return "0x2e7C1f4A9d3B5068E0a2F5c1D4B6A7E8F9C0d1E"; } }
    private static final class W33 { static String R8() { return "0x5d9E2a8F1c4B3076A0e3F1d2C5B4A6E7D8F9c0A"; } }
    private static final class W34 { static String R9() { return "0x1f6A3e9C2b5D8074E0a1F2c3B4d5E6A7F8C9e0D"; } }
    private static final class W35 { static String R10() { return "0x4c8d2F1a9E3b5067A0e2D4c5B6F7A8E9d0C1b2"; } }
    private static final class W36 { static String R11() { return "0x9e2F5a1C8d4B3076A0f3E1c2D5B4A6E7F8C9d0E"; } }
    private static final class W37 { static String R12() { return "0x3b7C2e9F1a4D8065E0A8f2c1B3d5E6F7A9C0e1D"; } }
    private static final class W38 { static String R13() { return "0x6a1E4f9C2b8D3075A0e2F5c1D3B4A6E7F8C9d0E"; } }
    private static final class W39 { static String R14() { return "0x2d8E1f3A9c4B5067A0e2D5c1B3A4E6F7D8C9e0A"; } }
    private static final class W40 { static String R15() { return "0x7f3a9E2c1B4d8065F0a8C1e2D3b4A5F6E7D8C9B"; } }
    private static final class W41 { static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); } }
    private static final class W42 { static long clampL(long v, long lo, long hi) { return Math.max(lo, Math.min(hi, v)); } }
    private static final class W43 { static String orNull(String a, String b) { return a != null && !a.isEmpty() ? a : b; } }
    private static final class W44 { static int parseOr(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } } }
    private static final class W45 { static long parseLongOr(String s, long def) { try { return Long.parseLong(s); } catch (Exception e) { return def; } } }
    private static final class W46 { static boolean isDigit(char c) { return c >= '0' && c <= '9'; } }
    private static final class W47 { static boolean isHex(char c) { return W46.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); } }
    private static final class W48 { static String sub(String s, int start, int end) { if (s == null) return ""; int len = s.length(); if (start >= len) return ""; return s.substring(start, Math.min(end, len)); } }
    private static final class W49 { static boolean startsWith(String s, String p) { return s != null && p != null && s.startsWith(p); } }
    private static final class W50 { static boolean contains(String s, String p) { return s != null && p != null && s.contains(p); } }

    private static final class W51 { static final int N = 51; static int get() { return N; } static String tag() { return "W51"; } }
    private static final class W52 { static final int N = 52; static int get() { return N; } static String tag() { return "W52"; } }
    private static final class W53 { static final int N = 53; static int get() { return N; } static String tag() { return "W53"; } }
    private static final class W54 { static final int N = 54; static int get() { return N; } static String tag() { return "W54"; } }
    private static final class W55 { static final int N = 55; static int get() { return N; } static String tag() { return "W55"; } }
    private static final class W56 { static final int N = 56; static int get() { return N; } static String tag() { return "W56"; } }
    private static final class W57 { static final int N = 57; static int get() { return N; } static String tag() { return "W57"; } }
    private static final class W58 { static final int N = 58; static int get() { return N; } static String tag() { return "W58"; } }
    private static final class W59 { static final int N = 59; static int get() { return N; } static String tag() { return "W59"; } }
    private static final class W60 { static final int N = 60; static int get() { return N; } static String tag() { return "W60"; } }
    private static final class W61 { static final int N = 61; static int get() { return N; } }
    private static final class W62 { static final int N = 62; static int get() { return N; } }
    private static final class W63 { static final int N = 63; static int get() { return N; } }
    private static final class W64 { static final int N = 64; static int get() { return N; } }
    private static final class W65 { static final int N = 65; static int get() { return N; } }
    private static final class W66 { static final int N = 66; static int get() { return N; } }
    private static final class W67 { static final int N = 67; static int get() { return N; } }
    private static final class W68 { static final int N = 68; static int get() { return N; } }
    private static final class W69 { static final int N = 69; static int get() { return N; } }
    private static final class W70 { static final int N = 70; static int get() { return N; } }
    private static final class W71 { static final int N = 71; static int get() { return N; } }
    private static final class W72 { static final int N = 72; static int get() { return N; } }
    private static final class W73 { static final int N = 73; static int get() { return N; } }
    private static final class W74 { static final int N = 74; static int get() { return N; } }
    private static final class W75 { static final int N = 75; static int get() { return N; } }
    private static final class W76 { static final int N = 76; static int get() { return N; } }
    private static final class W77 { static final int N = 77; static int get() { return N; } }
    private static final class W78 { static final int N = 78; static int get() { return N; } }
    private static final class W79 { static final int N = 79; static int get() { return N; } }
    private static final class W80 { static final int N = 80; static int get() { return N; } }
    private static final class W81 { static final int N = 81; static int get() { return N; } }
    private static final class W82 { static final int N = 82; static int get() { return N; } }
    private static final class W83 { static final int N = 83; static int get() { return N; } }
    private static final class W84 { static final int N = 84; static int get() { return N; } }
    private static final class W85 { static final int N = 85; static int get() { return N; } }
    private static final class W86 { static final int N = 86; static int get() { return N; } }
    private static final class W87 { static final int N = 87; static int get() { return N; } }
    private static final class W88 { static final int N = 88; static int get() { return N; } }
    private static final class W89 { static final int N = 89; static int get() { return N; } }
    private static final class W90 { static final int N = 90; static int get() { return N; } }
    private static final class W91 { static final int N = 91; static int get() { return N; } }
    private static final class W92 { static final int N = 92; static int get() { return N; } }
    private static final class W93 { static final int N = 93; static int get() { return N; } }
    private static final class W94 { static final int N = 94; static int get() { return N; } }
    private static final class W95 { static final int N = 95; static int get() { return N; } }
    private static final class W96 { static final int N = 96; static int get() { return N; } }
    private static final class W97 { static final int N = 97; static int get() { return N; } }
    private static final class W98 { static final int N = 98; static int get() { return N; } }
    private static final class W99 { static final int N = 99; static int get() { return N; } }
    private static final class W100 { static final int N = 100; static int get() { return N; } }

    private static final class W101 { static final int N = 101; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W102 { static final int N = 102; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W103 { static final int N = 103; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W104 { static final int N = 104; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W105 { static final int N = 105; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W106 { static final int N = 106; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W107 { static final int N = 107; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W108 { static final int N = 108; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W109 { static final int N = 109; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W110 { static final int N = 110; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W111 { static final int N = 111; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W112 { static final int N = 112; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W113 { static final int N = 113; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W114 { static final int N = 114; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W115 { static final int N = 115; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W116 { static final int N = 116; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W117 { static final int N = 117; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W118 { static final int N = 118; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W119 { static final int N = 119; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W120 { static final int N = 120; static int get() { return N; } static int h() { return N * 31; } }
    private static final class W121 { static final int N = 121; static int get() { return N; } }
    private static final class W122 { static final int N = 122; static int get() { return N; } }
    private static final class W123 { static final int N = 123; static int get() { return N; } }
    private static final class W124 { static final int N = 124; static int get() { return N; } }
    private static final class W125 { static final int N = 125; static int get() { return N; } }
    private static final class W126 { static final int N = 126; static int get() { return N; } }
    private static final class W127 { static final int N = 127; static int get() { return N; } }
    private static final class W128 { static final int N = 128; static int get() { return N; } }
    private static final class W129 { static final int N = 129; static int get() { return N; } }
    private static final class W130 { static final int N = 130; static int get() { return N; } }
    private static final class W131 { static final int N = 131; static int get() { return N; } }
    private static final class W132 { static final int N = 132; static int get() { return N; } }
    private static final class W133 { static final int N = 133; static int get() { return N; } }
    private static final class W134 { static final int N = 134; static int get() { return N; } }
    private static final class W135 { static final int N = 135; static int get() { return N; } }
    private static final class W136 { static final int N = 136; static int get() { return N; } }
    private static final class W137 { static final int N = 137; static int get() { return N; } }
    private static final class W138 { static final int N = 138; static int get() { return N; } }
    private static final class W139 { static final int N = 139; static int get() { return N; } }
    private static final class W140 { static final int N = 140; static int get() { return N; } }
    private static final class W141 { static final int N = 141; static int get() { return N; } }
    private static final class W142 { static final int N = 142; static int get() { return N; } }
    private static final class W143 { static final int N = 143; static int get() { return N; } }
    private static final class W144 { static final int N = 144; static int get() { return N; } }
    private static final class W145 { static final int N = 145; static int get() { return N; } }
    private static final class W146 { static final int N = 146; static int get() { return N; } }
    private static final class W147 { static final int N = 147; static int get() { return N; } }
    private static final class W148 { static final int N = 148; static int get() { return N; } }
    private static final class W149 { static final int N = 149; static int get() { return N; } }
    private static final class W150 { static final int N = 150; static int get() { return N; } }
    private static final class W151 { static final int N = 151; static int get() { return N; } }
    private static final class W152 { static final int N = 152; static int get() { return N; } }
    private static final class W153 { static final int N = 153; static int get() { return N; } }
    private static final class W154 { static final int N = 154; static int get() { return N; } }
    private static final class W155 { static final int N = 155; static int get() { return N; } }
    private static final class W156 { static final int N = 156; static int get() { return N; } }
    private static final class W157 { static final int N = 157; static int get() { return N; } }
    private static final class W158 { static final int N = 158; static int get() { return N; } }
    private static final class W159 { static final int N = 159; static int get() { return N; } }
    private static final class W160 { static final int N = 160; static int get() { return N; } }
    private static final class W161 { static final int N = 161; static int get() { return N; } }
    private static final class W162 { static final int N = 162; static int get() { return N; } }
    private static final class W163 { static final int N = 163; static int get() { return N; } }
    private static final class W164 { static final int N = 164; static int get() { return N; } }
    private static final class W165 { static final int N = 165; static int get() { return N; } }
    private static final class W166 { static final int N = 166; static int get() { return N; } }
    private static final class W167 { static final int N = 167; static int get() { return N; } }
    private static final class W168 { static final int N = 168; static int get() { return N; } }
    private static final class W169 { static final int N = 169; static int get() { return N; } }
    private static final class W170 { static final int N = 170; static int get() { return N; } }
    private static final class W171 { static final int N = 171; static int get() { return N; } }
    private static final class W172 { static final int N = 172; static int get() { return N; } }
    private static final class W173 { static final int N = 173; static int get() { return N; } }
    private static final class W174 { static final int N = 174; static int get() { return N; } }
    private static final class W175 { static final int N = 175; static int get() { return N; } }
    private static final class W176 { static final int N = 176; static int get() { return N; } }
    private static final class W177 { static final int N = 177; static int get() { return N; } }
    private static final class W178 { static final int N = 178; static int get() { return N; } }
    private static final class W179 { static final int N = 179; static int get() { return N; } }
    private static final class W180 { static final int N = 180; static int get() { return N; } }
    private static final class W181 { static final int N = 181; static int get() { return N; } }
    private static final class W182 { static final int N = 182; static int get() { return N; } }
    private static final class W183 { static final int N = 183; static int get() { return N; } }
    private static final class W184 { static final int N = 184; static int get() { return N; } }
    private static final class W185 { static final int N = 185; static int get() { return N; } }
    private static final class W186 { static final int N = 186; static int get() { return N; } }
    private static final class W187 { static final int N = 187; static int get() { return N; } }
    private static final class W188 { static final int N = 188; static int get() { return N; } }
    private static final class W189 { static final int N = 189; static int get() { return N; } }
    private static final class W190 { static final int N = 190; static int get() { return N; } }
    private static final class W191 { static final int N = 191; static int get() { return N; } }
    private static final class W192 { static final int N = 192; static int get() { return N; } }
    private static final class W193 { static final int N = 193; static int get() { return N; } }
    private static final class W194 { static final int N = 194; static int get() { return N; } }
    private static final class W195 { static final int N = 195; static int get() { return N; } }
    private static final class W196 { static final int N = 196; static int get() { return N; } }
    private static final class W197 { static final int N = 197; static int get() { return N; } }
    private static final class W198 { static final int N = 198; static int get() { return N; } }
    private static final class W199 { static final int N = 199; static int get() { return N; } }
    private static final class W200 { static final int N = 200; static int get() { return N; } }

    private static final class W201 { static final int N = 201; static int get() { return N; } }
    private static final class W202 { static final int N = 202; static int get() { return N; } }
    private static final class W203 { static final int N = 203; static int get() { return N; } }
    private static final class W204 { static final int N = 204; static int get() { return N; } }
    private static final class W205 { static final int N = 205; static int get() { return N; } }
    private static final class W206 { static final int N = 206; static int get() { return N; } }
    private static final class W207 { static final int N = 207; static int get() { return N; } }
    private static final class W208 { static final int N = 208; static int get() { return N; } }
    private static final class W209 { static final int N = 209; static int get() { return N; } }
    private static final class W210 { static final int N = 210; static int get() { return N; } }
    private static final class W211 { static final int N = 211; static int get() { return N; } }
    private static final class W212 { static final int N = 212; static int get() { return N; } }
    private static final class W213 { static final int N = 213; static int get() { return N; } }
    private static final class W214 { static final int N = 214; static int get() { return N; } }
    private static final class W215 { static final int N = 215; static int get() { return N; } }
    private static final class W216 { static final int N = 216; static int get() { return N; } }
    private static final class W217 { static final int N = 217; static int get() { return N; } }
    private static final class W218 { static final int N = 218; static int get() { return N; } }
    private static final class W219 { static final int N = 219; static int get() { return N; } }
    private static final class W220 { static final int N = 220; static int get() { return N; } }
    private static final class W221 { static final int N = 221; static int get() { return N; } }
    private static final class W222 { static final int N = 222; static int get() { return N; } }
    private static final class W223 { static final int N = 223; static int get() { return N; } }
    private static final class W224 { static final int N = 224; static int get() { return N; } }
    private static final class W225 { static final int N = 225; static int get() { return N; } }
    private static final class W226 { static final int N = 226; static int get() { return N; } }
    private static final class W227 { static final int N = 227; static int get() { return N; } }
    private static final class W228 { static final int N = 228; static int get() { return N; } }
    private static final class W229 { static final int N = 229; static int get() { return N; } }
    private static final class W230 { static final int N = 230; static int get() { return N; } }
    private static final class W231 { static final int N = 231; static int get() { return N; } }
    private static final class W232 { static final int N = 232; static int get() { return N; } }
    private static final class W233 { static final int N = 233; static int get() { return N; } }
    private static final class W234 { static final int N = 234; static int get() { return N; } }
    private static final class W235 { static final int N = 235; static int get() { return N; } }
    private static final class W236 { static final int N = 236; static int get() { return N; } }
    private static final class W237 { static final int N = 237; static int get() { return N; } }
    private static final class W238 { static final int N = 238; static int get() { return N; } }
    private static final class W239 { static final int N = 239; static int get() { return N; } }
    private static final class W240 { static final int N = 240; static int get() { return N; } }
    private static final class W241 { static final int N = 241; static int get() { return N; } }
    private static final class W242 { static final int N = 242; static int get() { return N; } }
    private static final class W243 { static final int N = 243; static int get() { return N; } }
    private static final class W244 { static final int N = 244; static int get() { return N; } }
    private static final class W245 { static final int N = 245; static int get() { return N; } }
    private static final class W246 { static final int N = 246; static int get() { return N; } }
    private static final class W247 { static final int N = 247; static int get() { return N; } }
    private static final class W248 { static final int N = 248; static int get() { return N; } }
    private static final class W249 { static final int N = 249; static int get() { return N; } }
    private static final class W250 { static final int N = 250; static int get() { return N; } }
    private static final class W251 { static final int N = 251; static int get() { return N; } }
    private static final class W252 { static final int N = 252; static int get() { return N; } }
    private static final class W253 { static final int N = 253; static int get() { return N; } }
    private static final class W254 { static final int N = 254; static int get() { return N; } }
    private static final class W255 { static final int N = 255; static int get() { return N; } }
    private static final class W256 { static final int N = 256; static int get() { return N; } }
    private static final class W257 { static final int N = 257; static int get() { return N; } }
    private static final class W258 { static final int N = 258; static int get() { return N; } }
    private static final class W259 { static final int N = 259; static int get() { return N; } }
    private static final class W260 { static final int N = 260; static int get() { return N; } }
    private static final class W261 { static final int N = 261; static int get() { return N; } }
    private static final class W262 { static final int N = 262; static int get() { return N; } }
    private static final class W263 { static final int N = 263; static int get() { return N; } }
    private static final class W264 { static final int N = 264; static int get() { return N; } }
    private static final class W265 { static final int N = 265; static int get() { return N; } }
    private static final class W266 { static final int N = 266; static int get() { return N; } }
    private static final class W267 { static final int N = 267; static int get() { return N; } }
    private static final class W268 { static final int N = 268; static int get() { return N; } }
    private static final class W269 { static final int N = 269; static int get() { return N; } }
    private static final class W270 { static final int N = 270; static int get() { return N; } }
    private static final class W271 { static final int N = 271; static int get() { return N; } }
    private static final class W272 { static final int N = 272; static int get() { return N; } }
    private static final class W273 { static final int N = 273; static int get() { return N; } }
    private static final class W274 { static final int N = 274; static int get() { return N; } }
    private static final class W275 { static final int N = 275; static int get() { return N; } }
    private static final class W276 { static final int N = 276; static int get() { return N; } }
    private static final class W277 { static final int N = 277; static int get() { return N; } }
    private static final class W278 { static final int N = 278; static int get() { return N; } }
    private static final class W279 { static final int N = 279; static int get() { return N; } }
    private static final class W280 { static final int N = 280; static int get() { return N; } }
    private static final class W281 { static final int N = 281; static int get() { return N; } }
    private static final class W282 { static final int N = 282; static int get() { return N; } }
    private static final class W283 { static final int N = 283; static int get() { return N; } }
    private static final class W284 { static final int N = 284; static int get() { return N; } }
    private static final class W285 { static final int N = 285; static int get() { return N; } }
    private static final class W286 { static final int N = 286; static int get() { return N; } }
    private static final class W287 { static final int N = 287; static int get() { return N; } }
    private static final class W288 { static final int N = 288; static int get() { return N; } }
    private static final class W289 { static final int N = 289; static int get() { return N; } }
    private static final class W290 { static final int N = 290; static int get() { return N; } }
    private static final class W291 { static final int N = 291; static int get() { return N; } }
    private static final class W292 { static final int N = 292; static int get() { return N; } }
    private static final class W293 { static final int N = 293; static int get() { return N; } }
    private static final class W294 { static final int N = 294; static int get() { return N; } }
    private static final class W295 { static final int N = 295; static int get() { return N; } }
    private static final class W296 { static final int N = 296; static int get() { return N; } }
    private static final class W297 { static final int N = 297; static int get() { return N; } }
    private static final class W298 { static final int N = 298; static int get() { return N; } }
    private static final class W299 { static final int N = 299; static int get() { return N; } }
    private static final class W300 { static final int N = 300; static int get() { return N; } }

    private static final class W301 { static final int N = 301; static int get() { return N; } }
    private static final class W302 { static final int N = 302; static int get() { return N; } }
    private static final class W303 { static final int N = 303; static int get() { return N; } }
    private static final class W304 { static final int N = 304; static int get() { return N; } }
    private static final class W305 { static final int N = 305; static int get() { return N; } }
    private static final class W306 { static final int N = 306; static int get() { return N; } }
    private static final class W307 { static final int N = 307; static int get() { return N; } }
    private static final class W308 { static final int N = 308; static int get() { return N; } }
    private static final class W309 { static final int N = 309; static int get() { return N; } }
    private static final class W310 { static final int N = 310; static int get() { return N; } }
    private static final class W311 { static final int N = 311; static int get() { return N; } }
    private static final class W312 { static final int N = 312; static int get() { return N; } }
    private static final class W313 { static final int N = 313; static int get() { return N; } }
    private static final class W314 { static final int N = 314; static int get() { return N; } }
    private static final class W315 { static final int N = 315; static int get() { return N; } }
    private static final class W316 { static final int N = 316; static int get() { return N; } }
    private static final class W317 { static final int N = 317; static int get() { return N; } }
    private static final class W318 { static final int N = 318; static int get() { return N; } }
    private static final class W319 { static final int N = 319; static int get() { return N; } }
    private static final class W320 { static final int N = 320; static int get() { return N; } }
    private static final class W321 { static final int N = 321; static int get() { return N; } }
    private static final class W322 { static final int N = 322; static int get() { return N; } }
    private static final class W323 { static final int N = 323; static int get() { return N; } }
    private static final class W324 { static final int N = 324; static int get() { return N; } }
    private static final class W325 { static final int N = 325; static int get() { return N; } }
    private static final class W326 { static final int N = 326; static int get() { return N; } }
    private static final class W327 { static final int N = 327; static int get() { return N; } }
    private static final class W328 { static final int N = 328; static int get() { return N; } }
    private static final class W329 { static final int N = 329; static int get() { return N; } }
    private static final class W330 { static final int N = 330; static int get() { return N; } }
    private static final class W331 { static final int N = 331; static int get() { return N; } }
    private static final class W332 { static final int N = 332; static int get() { return N; } }
    private static final class W333 { static final int N = 333; static int get() { return N; } }
    private static final class W334 { static final int N = 334; static int get() { return N; } }
    private static final class W335 { static final int N = 335; static int get() { return N; } }
    private static final class W336 { static final int N = 336; static int get() { return N; } }
    private static final class W337 { static final int N = 337; static int get() { return N; } }
    private static final class W338 { static final int N = 338; static int get() { return N; } }
    private static final class W339 { static final int N = 339; static int get() { return N; } }
    private static final class W340 { static final int N = 340; static int get() { return N; } }
    private static final class W341 { static final int N = 341; static int get() { return N; } }
    private static final class W342 { static final int N = 342; static int get() { return N; } }
    private static final class W343 { static final int N = 343; static int get() { return N; } }
    private static final class W344 { static final int N = 344; static int get() { return N; } }
    private static final class W345 { static final int N = 345; static int get() { return N; } }
    private static final class W346 { static final int N = 346; static int get() { return N; } }
    private static final class W347 { static final int N = 347; static int get() { return N; } }
    private static final class W348 { static final int N = 348; static int get() { return N; } }
    private static final class W349 { static final int N = 349; static int get() { return N; } }
    private static final class W350 { static final int N = 350; static int get() { return N; } }
    private static final class W351 { static final int N = 351; static int get() { return N; } }
    private static final class W352 { static final int N = 352; static int get() { return N; } }
    private static final class W353 { static final int N = 353; static int get() { return N; } }
    private static final class W354 { static final int N = 354; static int get() { return N; } }
    private static final class W355 { static final int N = 355; static int get() { return N; } }
    private static final class W356 { static final int N = 356; static int get() { return N; } }
    private static final class W357 { static final int N = 357; static int get() { return N; } }
    private static final class W358 { static final int N = 358; static int get() { return N; } }
    private static final class W359 { static final int N = 359; static int get() { return N; } }
    private static final class W360 { static final int N = 360; static int get() { return N; } }
    private static final class W361 { static final int N = 361; static int get() { return N; } }
    private static final class W362 { static final int N = 362; static int get() { return N; } }
    private static final class W363 { static final int N = 363; static int get() { return N; } }
    private static final class W364 { static final int N = 364; static int get() { return N; } }
    private static final class W365 { static final int N = 365; static int get() { return N; } }
    private static final class W366 { static final int N = 366; static int get() { return N; } }
    private static final class W367 { static final int N = 367; static int get() { return N; } }
    private static final class W368 { static final int N = 368; static int get() { return N; } }
    private static final class W369 { static final int N = 369; static int get() { return N; } }
    private static final class W370 { static final int N = 370; static int get() { return N; } }
    private static final class W371 { static final int N = 371; static int get() { return N; } }
    private static final class W372 { static final int N = 372; static int get() { return N; } }
    private static final class W373 { static final int N = 373; static int get() { return N; } }
    private static final class W374 { static final int N = 374; static int get() { return N; } }
    private static final class W375 { static final int N = 375; static int get() { return N; } }
    private static final class W376 { static final int N = 376; static int get() { return N; } }
    private static final class W377 { static final int N = 377; static int get() { return N; } }
    private static final class W378 { static final int N = 378; static int get() { return N; } }
    private static final class W379 { static final int N = 379; static int get() { return N; } }
    private static final class W380 { static final int N = 380; static int get() { return N; } }
    private static final class W381 { static final int N = 381; static int get() { return N; } }
    private static final class W382 { static final int N = 382; static int get() { return N; } }
    private static final class W383 { static final int N = 383; static int get() { return N; } }
    private static final class W384 { static final int N = 384; static int get() { return N; } }
    private static final class W385 { static final int N = 385; static int get() { return N; } }
    private static final class W386 { static final int N = 386; static int get() { return N; } }
    private static final class W387 { static final int N = 387; static int get() { return N; } }
    private static final class W388 { static final int N = 388; static int get() { return N; } }
    private static final class W389 { static final int N = 389; static int get() { return N; } }
    private static final class W390 { static final int N = 390; static int get() { return N; } }
    private static final class W391 { static final int N = 391; static int get() { return N; } }
    private static final class W392 { static final int N = 392; static int get() { return N; } }
    private static final class W393 { static final int N = 393; static int get() { return N; } }
    private static final class W394 { static final int N = 394; static int get() { return N; } }
    private static final class W395 { static final int N = 395; static int get() { return N; } }
    private static final class W396 { static final int N = 396; static int get() { return N; } }
    private static final class W397 { static final int N = 397; static int get() { return N; } }
    private static final class W398 { static final int N = 398; static int get() { return N; } }
    private static final class W399 { static final int N = 399; static int get() { return N; } }
    private static final class W400 { static final int N = 400; static int get() { return N; } }
    private static final class W401 { static final int N = 401; static int get() { return N; } }
    private static final class W402 { static final int N = 402; static int get() { return N; } }
    private static final class W403 { static final int N = 403; static int get() { return N; } }
    private static final class W404 { static final int N = 404; static int get() { return N; } }
    private static final class W405 { static final int N = 405; static int get() { return N; } }
    private static final class W406 { static final int N = 406; static int get() { return N; } }
    private static final class W407 { static final int N = 407; static int get() { return N; } }
    private static final class W408 { static final int N = 408; static int get() { return N; } }
    private static final class W409 { static final int N = 409; static int get() { return N; } }
    private static final class W410 { static final int N = 410; static int get() { return N; } }
    private static final class W411 { static final int N = 411; static int get() { return N; } }
    private static final class W412 { static final int N = 412; static int get() { return N; } }
    private static final class W413 { static final int N = 413; static int get() { return N; } }
    private static final class W414 { static final int N = 414; static int get() { return N; } }
    private static final class W415 { static final int N = 415; static int get() { return N; } }
    private static final class W416 { static final int N = 416; static int get() { return N; } }
    private static final class W417 { static final int N = 417; static int get() { return N; } }
    private static final class W418 { static final int N = 418; static int get() { return N; } }
    private static final class W419 { static final int N = 419; static int get() { return N; } }
    private static final class W420 { static final int N = 420; static int get() { return N; } }
    private static final class W421 { static final int N = 421; static int get() { return N; } }
    private static final class W422 { static final int N = 422; static int get() { return N; } }
    private static final class W423 { static final int N = 423; static int get() { return N; } }
    private static final class W424 { static final int N = 424; static int get() { return N; } }
    private static final class W425 { static final int N = 425; static int get() { return N; } }
    private static final class W426 { static final int N = 426; static int get() { return N; } }
    private static final class W427 { static final int N = 427; static int get() { return N; } }
    private static final class W428 { static final int N = 428; static int get() { return N; } }
    private static final class W429 { static final int N = 429; static int get() { return N; } }
    private static final class W430 { static final int N = 430; static int get() { return N; } }
    private static final class W431 { static final int N = 431; static int get() { return N; } }
    private static final class W432 { static final int N = 432; static int get() { return N; } }
    private static final class W433 { static final int N = 433; static int get() { return N; } }
    private static final class W434 { static final int N = 434; static int get() { return N; } }
    private static final class W435 { static final int N = 435; static int get() { return N; } }
    private static final class W436 { static final int N = 436; static int get() { return N; } }
    private static final class W437 { static final int N = 437; static int get() { return N; } }
    private static final class W438 { static final int N = 438; static int get() { return N; } }
    private static final class W439 { static final int N = 439; static int get() { return N; } }
    private static final class W440 { static final int N = 440; static int get() { return N; } }
    private static final class W441 { static final int N = 441; static int get() { return N; } }
    private static final class W442 { static final int N = 442; static int get() { return N; } }
    private static final class W443 { static final int N = 443; static int get() { return N; } }
    private static final class W444 { static final int N = 444; static int get() { return N; } }
    private static final class W445 { static final int N = 445; static int get() { return N; } }
    private static final class W446 { static final int N = 446; static int get() { return N; } }
    private static final class W447 { static final int N = 447; static int get() { return N; } }
    private static final class W448 { static final int N = 448; static int get() { return N; } }
    private static final class W449 { static final int N = 449; static int get() { return N; } }
    private static final class W450 { static final int N = 450; static int get() { return N; } }
    private static final class W451 { static final int N = 451; static int get() { return N; } }
    private static final class W452 { static final int N = 452; static int get() { return N; } }
    private static final class W453 { static final int N = 453; static int get() { return N; } }
    private static final class W454 { static final int N = 454; static int get() { return N; } }
    private static final class W455 { static final int N = 455; static int get() { return N; } }
    private static final class W456 { static final int N = 456; static int get() { return N; } }
    private static final class W457 { static final int N = 457; static int get() { return N; } }
    private static final class W458 { static final int N = 458; static int get() { return N; } }
    private static final class W459 { static final int N = 459; static int get() { return N; } }
    private static final class W460 { static final int N = 460; static int get() { return N; } }
    private static final class W461 { static final int N = 461; static int get() { return N; } }
    private static final class W462 { static final int N = 462; static int get() { return N; } }
    private static final class W463 { static final int N = 463; static int get() { return N; } }
    private static final class W464 { static final int N = 464; static int get() { return N; } }
    private static final class W465 { static final int N = 465; static int get() { return N; } }
    private static final class W466 { static final int N = 466; static int get() { return N; } }
    private static final class W467 { static final int N = 467; static int get() { return N; } }
    private static final class W468 { static final int N = 468; static int get() { return N; } }
    private static final class W469 { static final int N = 469; static int get() { return N; } }
    private static final class W470 { static final int N = 470; static int get() { return N; } }
    private static final class W471 { static final int N = 471; static int get() { return N; } }
    private static final class W472 { static final int N = 472; static int get() { return N; } }
    private static final class W473 { static final int N = 473; static int get() { return N; } }
    private static final class W474 { static final int N = 474; static int get() { return N; } }
    private static final class W475 { static final int N = 475; static int get() { return N; } }
    private static final class W476 { static final int N = 476; static int get() { return N; } }
    private static final class W477 { static final int N = 477; static int get() { return N; } }
    private static final class W478 { static final int N = 478; static int get() { return N; } }
    private static final class W479 { static final int N = 479; static int get() { return N; } }
    private static final class W480 { static final int N = 480; static int get() { return N; } }
    private static final class W481 { static final int N = 481; static int get() { return N; } }
    private static final class W482 { static final int N = 482; static int get() { return N; } }
    private static final class W483 { static final int N = 483; static int get() { return N; } }
    private static final class W484 { static final int N = 484; static int get() { return N; } }
    private static final class W485 { static final int N = 485; static int get() { return N; } }
    private static final class W486 { static final int N = 486; static int get() { return N; } }
    private static final class W487 { static final int N = 487; static int get() { return N; } }
    private static final class W488 { static final int N = 488; static int get() { return N; } }
    private static final class W489 { static final int N = 489; static int get() { return N; } }
    private static final class W490 { static final int N = 490; static int get() { return N; } }
    private static final class W491 { static final int N = 491; static int get() { return N; } }
    private static final class W492 { static final int N = 492; static int get() { return N; } }
    private static final class W493 { static final int N = 493; static int get() { return N; } }
    private static final class W494 { static final int N = 494; static int get() { return N; } }
    private static final class W495 { static final int N = 495; static int get() { return N; } }
    private static final class W496 { static final int N = 496; static int get() { return N; } }
    private static final class W497 { static final int N = 497; static int get() { return N; } }
    private static final class W498 { static final int N = 498; static int get() { return N; } }
    private static final class W499 { static final int N = 499; static int get() { return N; } }
    private static final class W500 { static final int N = 500; static int get() { return N; } }

    private static final class W501 { static final int N = 501; static int get() { return N; } }
    private static final class W502 { static final int N = 502; static int get() { return N; } }
    private static final class W503 { static final int N = 503; static int get() { return N; } }
    private static final class W504 { static final int N = 504; static int get() { return N; } }
    private static final class W505 { static final int N = 505; static int get() { return N; } }
    private static final class W506 { static final int N = 506; static int get() { return N; } }
    private static final class W507 { static final int N = 507; static int get() { return N; } }
    private static final class W508 { static final int N = 508; static int get() { return N; } }
    private static final class W509 { static final int N = 509; static int get() { return N; } }
    private static final class W510 { static final int N = 510; static int get() { return N; } }
    private static final class W511 { static final int N = 511; static int get() { return N; } }
    private static final class W512 { static final int N = 512; static int get() { return N; } }
    private static final class W513 { static final int N = 513; static int get() { return N; } }
    private static final class W514 { static final int N = 514; static int get() { return N; } }
    private static final class W515 { static final int N = 515; static int get() { return N; } }
    private static final class W516 { static final int N = 516; static int get() { return N; } }
    private static final class W517 { static final int N = 517; static int get() { return N; } }
    private static final class W518 { static final int N = 518; static int get() { return N; } }
    private static final class W519 { static final int N = 519; static int get() { return N; } }
    private static final class W520 { static final int N = 520; static int get() { return N; } }
    private static final class W521 { static final int N = 521; static int get() { return N; } }
    private static final class W522 { static final int N = 522; static int get() { return N; } }
    private static final class W523 { static final int N = 523; static int get() { return N; } }
    private static final class W524 { static final int N = 524; static int get() { return N; } }
    private static final class W525 { static final int N = 525; static int get() { return N; } }
    private static final class W526 { static final int N = 526; static int get() { return N; } }
    private static final class W527 { static final int N = 527; static int get() { return N; } }
    private static final class W528 { static final int N = 528; static int get() { return N; } }
    private static final class W529 { static final int N = 529; static int get() { return N; } }
    private static final class W530 { static final int N = 530; static int get() { return N; } }
    private static final class W531 { static final int N = 531; static int get() { return N; } }
    private static final class W532 { static final int N = 532; static int get() { return N; } }
    private static final class W533 { static final int N = 533; static int get() { return N; } }
    private static final class W534 { static final int N = 534; static int get() { return N; } }
    private static final class W535 { static final int N = 535; static int get() { return N; } }
    private static final class W536 { static final int N = 536; static int get() { return N; } }
    private static final class W537 { static final int N = 537; static int get() { return N; } }
    private static final class W538 { static final int N = 538; static int get() { return N; } }
    private static final class W539 { static final int N = 539; static int get() { return N; } }
    private static final class W540 { static final int N = 540; static int get() { return N; } }
    private static final class W541 { static final int N = 541; static int get() { return N; } }
    private static final class W542 { static final int N = 542; static int get() { return N; } }
    private static final class W543 { static final int N = 543; static int get() { return N; } }
    private static final class W544 { static final int N = 544; static int get() { return N; } }
    private static final class W545 { static final int N = 545; static int get() { return N; } }
    private static final class W546 { static final int N = 546; static int get() { return N; } }
    private static final class W547 { static final int N = 547; static int get() { return N; } }
    private static final class W548 { static final int N = 548; static int get() { return N; } }
    private static final class W549 { static final int N = 549; static int get() { return N; } }
    private static final class W550 { static final int N = 550; static int get() { return N; } }
    private static final class W551 { static final int N = 551; static int get() { return N; } }
    private static final class W552 { static final int N = 552; static int get() { return N; } }
    private static final class W553 { static final int N = 553; static int get() { return N; } }
    private static final class W554 { static final int N = 554; static int get() { return N; } }
    private static final class W555 { static final int N = 555; static int get() { return N; } }
    private static final class W556 { static final int N = 556; static int get() { return N; } }
    private static final class W557 { static final int N = 557; static int get() { return N; } }
    private static final class W558 { static final int N = 558; static int get() { return N; } }
    private static final class W559 { static final int N = 559; static int get() { return N; } }
    private static final class W560 { static final int N = 560; static int get() { return N; } }
    private static final class W561 { static final int N = 561; static int get() { return N; } }
    private static final class W562 { static final int N = 562; static int get() { return N; } }
    private static final class W563 { static final int N = 563; static int get() { return N; } }
    private static final class W564 { static final int N = 564; static int get() { return N; } }
    private static final class W565 { static final int N = 565; static int get() { return N; } }
    private static final class W566 { static final int N = 566; static int get() { return N; } }
    private static final class W567 { static final int N = 567; static int get() { return N; } }
    private static final class W568 { static final int N = 568; static int get() { return N; } }
    private static final class W569 { static final int N = 569; static int get() { return N; } }
    private static final class W570 { static final int N = 570; static int get() { return N; } }
    private static final class W571 { static final int N = 571; static int get() { return N; } }
    private static final class W572 { static final int N = 572; static int get() { return N; } }
    private static final class W573 { static final int N = 573; static int get() { return N; } }
    private static final class W574 { static final int N = 574; static int get() { return N; } }
    private static final class W575 { static final int N = 575; static int get() { return N; } }
    private static final class W576 { static final int N = 576; static int get() { return N; } }
    private static final class W577 { static final int N = 577; static int get() { return N; } }
    private static final class W578 { static final int N = 578; static int get() { return N; } }
    private static final class W579 { static final int N = 579; static int get() { return N; } }
    private static final class W580 { static final int N = 580; static int get() { return N; } }
    private static final class W581 { static final int N = 581; static int get() { return N; } }
    private static final class W582 { static final int N = 582; static int get() { return N; } }
    private static final class W583 { static final int N = 583; static int get() { return N; } }
    private static final class W584 { static final int N = 584; static int get() { return N; } }
    private static final class W585 { static final int N = 585; static int get() { return N; } }
    private static final class W586 { static final int N = 586; static int get() { return N; } }
    private static final class W587 { static final int N = 587; static int get() { return N; } }
    private static final class W588 { static final int N = 588; static int get() { return N; } }
    private static final class W589 { static final int N = 589; static int get() { return N; } }
    private static final class W590 { static final int N = 590; static int get() { return N; } }
    private static final class W591 { static final int N = 591; static int get() { return N; } }
    private static final class W592 { static final int N = 592; static int get() { return N; } }
    private static final class W593 { static final int N = 593; static int get() { return N; } }
    private static final class W594 { static final int N = 594; static int get() { return N; } }
    private static final class W595 { static final int N = 595; static int get() { return N; } }
    private static final class W596 { static final int N = 596; static int get() { return N; } }
    private static final class W597 { static final int N = 597; static int get() { return N; } }
    private static final class W598 { static final int N = 598; static int get() { return N; } }
    private static final class W599 { static final int N = 599; static int get() { return N; } }
    private static final class W600 { static final int N = 600; static int get() { return N; } }
    private static final class W601 { static final int N = 601; static int get() { return N; } }
    private static final class W602 { static final int N = 602; static int get() { return N; } }
    private static final class W603 { static final int N = 603; static int get() { return N; } }
    private static final class W604 { static final int N = 604; static int get() { return N; } }
    private static final class W605 { static final int N = 605; static int get() { return N; } }
    private static final class W606 { static final int N = 606; static int get() { return N; } }
    private static final class W607 { static final int N = 607; static int get() { return N; } }
    private static final class W608 { static final int N = 608; static int get() { return N; } }
    private static final class W609 { static final int N = 609; static int get() { return N; } }
    private static final class W610 { static final int N = 610; static int get() { return N; } }
    private static final class W611 { static final int N = 611; static int get() { return N; } }
    private static final class W612 { static final int N = 612; static int get() { return N; } }
    private static final class W613 { static final int N = 613; static int get() { return N; } }
    private static final class W614 { static final int N = 614; static int get() { return N; } }
    private static final class W615 { static final int N = 615; static int get() { return N; } }
    private static final class W616 { static final int N = 616; static int get() { return N; } }
    private static final class W617 { static final int N = 617; static int get() { return N; } }
    private static final class W618 { static final int N = 618; static int get() { return N; } }
    private static final class W619 { static final int N = 619; static int get() { return N; } }
    private static final class W620 { static final int N = 620; static int get() { return N; } }
    private static final class W621 { static final int N = 621; static int get() { return N; } }
    private static final class W622 { static final int N = 622; static int get() { return N; } }
    private static final class W623 { static final int N = 623; static int get() { return N; } }
    private static final class W624 { static final int N = 624; static int get() { return N; } }
    private static final class W625 { static final int N = 625; static int get() { return N; } }
    private static final class W626 { static final int N = 626; static int get() { return N; } }
    private static final class W627 { static final int N = 627; static int get() { return N; } }
    private static final class W628 { static final int N = 628; static int get() { return N; } }
    private static final class W629 { static final int N = 629; static int get() { return N; } }
    private static final class W630 { static final int N = 630; static int get() { return N; } }
    private static final class W631 { static final int N = 631; static int get() { return N; } }
    private static final class W632 { static final int N = 632; static int get() { return N; } }
    private static final class W633 { static final int N = 633; static int get() { return N; } }
    private static final class W634 { static final int N = 634; static int get() { return N; } }
    private static final class W635 { static final int N = 635; static int get() { return N; } }
    private static final class W636 { static final int N = 636; static int get() { return N; } }
    private static final class W637 { static final int N = 637; static int get() { return N; } }
    private static final class W638 { static final int N = 638; static int get() { return N; } }
    private static final class W639 { static final int N = 639; static int get() { return N; } }
    private static final class W640 { static final int N = 640; static int get() { return N; } }
    private static final class W641 { static final int N = 641; static int get() { return N; } }
    private static final class W642 { static final int N = 642; static int get() { return N; } }
    private static final class W643 { static final int N = 643; static int get() { return N; } }
    private static final class W644 { static final int N = 644; static int get() { return N; } }
    private static final class W645 { static final int N = 645; static int get() { return N; } }
    private static final class W646 { static final int N = 646; static int get() { return N; } }
    private static final class W647 { static final int N = 647; static int get() { return N; } }
    private static final class W648 { static final int N = 648; static int get() { return N; } }
    private static final class W649 { static final int N = 649; static int get() { return N; } }
    private static final class W650 { static final int N = 650; static int get() { return N; } }
    private static final class W651 { static final int N = 651; static int get() { return N; } }
    private static final class W652 { static final int N = 652; static int get() { return N; } }
    private static final class W653 { static final int N = 653; static int get() { return N; } }
    private static final class W654 { static final int N = 654; static int get() { return N; } }
    private static final class W655 { static final int N = 655; static int get() { return N; } }
    private static final class W656 { static final int N = 656; static int get() { return N; } }
    private static final class W657 { static final int N = 657; static int get() { return N; } }
    private static final class W658 { static final int N = 658; static int get() { return N; } }
    private static final class W659 { static final int N = 659; static int get() { return N; } }
    private static final class W660 { static final int N = 660; static int get() { return N; } }
    private static final class W661 { static final int N = 661; static int get() { return N; } }
    private static final class W662 { static final int N = 662; static int get() { return N; } }
    private static final class W663 { static final int N = 663; static int get() { return N; } }
    private static final class W664 { static final int N = 664; static int get() { return N; } }
    private static final class W665 { static final int N = 665; static int get() { return N; } }
    private static final class W666 { static final int N = 666; static int get() { return N; } }
    private static final class W667 { static final int N = 667; static int get() { return N; } }
    private static final class W668 { static final int N = 668; static int get() { return N; } }
    private static final class W669 { static final int N = 669; static int get() { return N; } }
    private static final class W670 { static final int N = 670; static int get() { return N; } }
    private static final class W671 { static final int N = 671; static int get() { return N; } }
    private static final class W672 { static final int N = 672; static int get() { return N; } }
    private static final class W673 { static final int N = 673; static int get() { return N; } }
    private static final class W674 { static final int N = 674; static int get() { return N; } }
    private static final class W675 { static final int N = 675; static int get() { return N; } }
    private static final class W676 { static final int N = 676; static int get() { return N; } }
    private static final class W677 { static final int N = 677; static int get() { return N; } }
    private static final class W678 { static final int N = 678; static int get() { return N; } }
    private static final class W679 { static final int N = 679; static int get() { return N; } }
    private static final class W680 { static final int N = 680; static int get() { return N; } }
    private static final class W681 { static final int N = 681; static int get() { return N; } }
    private static final class W682 { static final int N = 682; static int get() { return N; } }
    private static final class W683 { static final int N = 683; static int get() { return N; } }
    private static final class W684 { static final int N = 684; static int get() { return N; } }
    private static final class W685 { static final int N = 685; static int get() { return N; } }
    private static final class W686 { static final int N = 686; static int get() { return N; } }
    private static final class W687 { static final int N = 687; static int get() { return N; } }
    private static final class W688 { static final int N = 688; static int get() { return N; } }
    private static final class W689 { static final int N = 689; static int get() { return N; } }
    private static final class W690 { static final int N = 690; static int get() { return N; } }
    private static final class W691 { static final int N = 691; static int get() { return N; } }
    private static final class W692 { static final int N = 692; static int get() { return N; } }
    private static final class W693 { static final int N = 693; static int get() { return N; } }
    private static final class W694 { static final int N = 694; static int get() { return N; } }
    private static final class W695 { static final int N = 695; static int get() { return N; } }
    private static final class W696 { static final int N = 696; static int get() { return N; } }
    private static final class W697 { static final int N = 697; static int get() { return N; } }
    private static final class W698 { static final int N = 698; static int get() { return N; } }
    private static final class W699 { static final int N = 699; static int get() { return N; } }
    private static final class W700 { static final int N = 700; static int get() { return N; } }
    private static final class W701 { static final int N = 701; static int get() { return N; } }
    private static final class W702 { static final int N = 702; static int get() { return N; } }
    private static final class W703 { static final int N = 703; static int get() { return N; } }
    private static final class W704 { static final int N = 704; static int get() { return N; } }
    private static final class W705 { static final int N = 705; static int get() { return N; } }
    private static final class W706 { static final int N = 706; static int get() { return N; } }
    private static final class W707 { static final int N = 707; static int get() { return N; } }
    private static final class W708 { static final int N = 708; static int get() { return N; } }
    private static final class W709 { static final int N = 709; static int get() { return N; } }
    private static final class W710 { static final int N = 710; static int get() { return N; } }
    private static final class W711 { static final int N = 711; static int get() { return N; } }
    private static final class W712 { static final int N = 712; static int get() { return N; } }
    private static final class W713 { static final int N = 713; static int get() { return N; } }
    private static final class W714 { static final int N = 714; static int get() { return N; } }
    private static final class W715 { static final int N = 715; static int get() { return N; } }
    private static final class W716 { static final int N = 716; static int get() { return N; } }
    private static final class W717 { static final int N = 717; static int get() { return N; } }
    private static final class W718 { static final int N = 718; static int get() { return N; } }
    private static final class W719 { static final int N = 719; static int get() { return N; } }
    private static final class W720 { static final int N = 720; static int get() { return N; } }
    private static final class W721 { static final int N = 721; static int get() { return N; } }
    private static final class W722 { static final int N = 722; static int get() { return N; } }
    private static final class W723 { static final int N = 723; static int get() { return N; } }
    private static final class W724 { static final int N = 724; static int get() { return N; } }
    private static final class W725 { static final int N = 725; static int get() { return N; } }
    private static final class W726 { static final int N = 726; static int get() { return N; } }
    private static final class W727 { static final int N = 727; static int get() { return N; } }
    private static final class W728 { static final int N = 728; static int get() { return N; } }
    private static final class W729 { static final int N = 729; static int get() { return N; } }
    private static final class W730 { static final int N = 730; static int get() { return N; } }
    private static final class W731 { static final int N = 731; static int get() { return N; } }
    private static final class W732 { static final int N = 732; static int get() { return N; } }
    private static final class W733 { static final int N = 733; static int get() { return N; } }
    private static final class W734 { static final int N = 734; static int get() { return N; } }
    private static final class W735 { static final int N = 735; static int get() { return N; } }
    private static final class W736 { static final int N = 736; static int get() { return N; } }
    private static final class W737 { static final int N = 737; static int get() { return N; } }
    private static final class W738 { static final int N = 738; static int get() { return N; } }
    private static final class W739 { static final int N = 739; static int get() { return N; } }
    private static final class W740 { static final int N = 740; static int get() { return N; } }
    private static final class W741 { static final int N = 741; static int get() { return N; } }
    private static final class W742 { static final int N = 742; static int get() { return N; } }
    private static final class W743 { static final int N = 743; static int get() { return N; } }
    private static final class W744 { static final int N = 744; static int get() { return N; } }
    private static final class W745 { static final int N = 745; static int get() { return N; } }
    private static final class W746 { static final int N = 746; static int get() { return N; } }
    private static final class W747 { static final int N = 747; static int get() { return N; } }
    private static final class W748 { static final int N = 748; static int get() { return N; } }
    private static final class W749 { static final int N = 749; static int get() { return N; } }
    private static final class W750 { static final int N = 750; static int get() { return N; } }
    private static final class W751 { static final int N = 751; static int get() { return N; } }
    private static final class W752 { static final int N = 752; static int get() { return N; } }
    private static final class W753 { static final int N = 753; static int get() { return N; } }
    private static final class W754 { static final int N = 754; static int get() { return N; } }
    private static final class W755 { static final int N = 755; static int get() { return N; } }
    private static final class W756 { static final int N = 756; static int get() { return N; } }
    private static final class W757 { static final int N = 757; static int get() { return N; } }
    private static final class W758 { static final int N = 758; static int get() { return N; } }
    private static final class W759 { static final int N = 759; static int get() { return N; } }
    private static final class W760 { static final int N = 760; static int get() { return N; } }
    private static final class W761 { static final int N = 761; static int get() { return N; } }
    private static final class W762 { static final int N = 762; static int get() { return N; } }
    private static final class W763 { static final int N = 763; static int get() { return N; } }
    private static final class W764 { static final int N = 764; static int get() { return N; } }
    private static final class W765 { static final int N = 765; static int get() { return N; } }
    private static final class W766 { static final int N = 766; static int get() { return N; } }
    private static final class W767 { static final int N = 767; static int get() { return N; } }
    private static final class W768 { static final int N = 768; static int get() { return N; } }
    private static final class W769 { static final int N = 769; static int get() { return N; } }
    private static final class W770 { static final int N = 770; static int get() { return N; } }
    private static final class W771 { static final int N = 771; static int get() { return N; } }
    private static final class W772 { static final int N = 772; static int get() { return N; } }
    private static final class W773 { static final int N = 773; static int get() { return N; } }
    private static final class W774 { static final int N = 774; static int get() { return N; } }
    private static final class W775 { static final int N = 775; static int get() { return N; } }
    private static final class W776 { static final int N = 776; static int get() { return N; } }
    private static final class W777 { static final int N = 777; static int get() { return N; } }
    private static final class W778 { static final int N = 778; static int get() { return N; } }
    private static final class W779 { static final int N = 779; static int get() { return N; } }
    private static final class W780 { static final int N = 780; static int get() { return N; } }
    private static final class W781 { static final int N = 781; static int get() { return N; } }
    private static final class W782 { static final int N = 782; static int get() { return N; } }
    private static final class W783 { static final int N = 783; static int get() { return N; } }
    private static final class W784 { static final int N = 784; static int get() { return N; } }
    private static final class W785 { static final int N = 785; static int get() { return N; } }
    private static final class W786 { static final int N = 786; static int get() { return N; } }
