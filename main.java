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
