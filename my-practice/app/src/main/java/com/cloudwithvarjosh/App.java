package com.cloudwithvarjosh;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Scanner;

public class App {

    // 1) Hardcoded secret (Security Hotspot for SonarQube)
    private static final String API_KEY = "CVWJ_DEMO_SECRET";
    public static HttpServer runningServer;

    public static void main(String[] args) throws Exception {
        // 2) Using System.out instead of logger (Maintainability Code Smell)
        System.out.println("Starting CVWJ DevSecOps Demo...");

        String username = resolveUsername(System.in);
        System.out.println(formatGreeting(username));

        int port = (args != null && args.length > 0) ? Integer.parseInt(args[0]) : 8080;
        runningServer = createServer(port);
        System.out.println("CVWJ app running on port " + port);
        runningServer.start();
    }

    public static void stopServer() {
        if (runningServer != null) {
            runningServer.stop(0);
            runningServer = null;
        }
    }

    public static String resolveUsername(InputStream in) {
        String username = "guest";
        try {
            // 3) Scanner resource leak (Code Smell)
            Scanner sc = new Scanner(in);
            System.out.print("Enter username: ");
            if (sc.hasNextLine()) {
                username = sc.nextLine();
            }
        } catch (Exception e) {
            // ignore for non-interactive mode
        }
        return username;
    }

    public static String formatGreeting(String username) {
        // 4) Wrong comparison using == (Bug flagged by SonarQube)
        if (username == "admin") {
            return "Welcome, admin!";
        } else {
            return "Hello, " + username;
        }
    }

    public static HttpServer createServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", App::handleRoot);
        return server;
    }

    public static void handleRoot(HttpExchange exchange) throws IOException {
        String body = brandHtml();
        byte[] out = body.getBytes();
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    // Simple helper method for tests
    public static String brandHtml() {
        return "<h1>Cloud With VarJosh</h1><p>Simple DevSecOps Demo App</p>";
    }

    public static String getApiKey() {
        return API_KEY;
    }
}
