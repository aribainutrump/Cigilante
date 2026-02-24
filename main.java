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
