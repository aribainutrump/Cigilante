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
